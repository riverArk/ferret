package io.riverark.ferret.core.channel

import io.riverark.ferret.core.cardano.CardanoIntent
import io.riverark.ferret.core.cardano.CardanoTransactionEngine
import io.riverark.ferret.core.cardano.ChannelConstants
import io.riverark.ferret.core.cardano.ChannelDatum
import io.riverark.ferret.core.cardano.ChannelDatumStage
import io.riverark.ferret.core.cardano.KONDUIT_MIN_ADA_BUFFER
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.TransactionDatum
import io.riverark.ferret.core.cardano.UnsignedTransaction
import io.riverark.ferret.core.cardano.requireChannelFunding
import io.riverark.ferret.core.cardano.requireL1Witnesses
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.network.AdaptorInfoDto
import io.riverark.ferret.core.network.MAINNET
import io.riverark.ferret.core.security.SecureVault

import io.riverark.ferret.core.cardano.CloseChannelStep
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.cardano.TransactionInputReference
import io.riverark.ferret.core.cardano.TransactionOutputSummary
import io.riverark.ferret.core.cardano.cardanoSlotEpochMillis
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.network.ConnectorTransactionDto
import io.riverark.ferret.core.network.ConnectorAssetDto

class ChannelStateChangedException : IllegalStateException("This channel changed. Review the updated transaction before confirming.")
class ChannelReturnNotReadyException(val afterEpochMillis: Long) : IllegalStateException("Channel funds are not ready to return.")

class ChannelTransactions(
    private val vault: SecureVault,
    private val engine: CardanoTransactionEngine,
    private val assets: AssetCatalog,
    private val loadLedger: suspend (WalletProfile) -> LedgerSnapshot,
    private val loadInfo: suspend (WalletProfile) -> AdaptorInfoDto,
    private val verificationKey: suspend (WalletProfile) -> String,
    private val availability: suspend (WalletId) -> Unit,
    private val newTag: () -> ByteArray,
    private val nowEpochMillis: () -> Long,
    private val loadTransaction: (suspend (WalletProfile, String) -> ConnectorTransactionDto?)? = null,
    private val closeReturnAfterEpochMillis: ((String) -> Long?)? = null,
) {
    val closeAvailable: Boolean get() = loadTransaction != null && closeReturnAfterEpochMillis != null

    private fun requireCloseAvailable() {
        check(closeAvailable) { "Channel closing is unavailable on this platform." }
    }

    private fun requireCloseAvailable(operation: PreparedChannelOperation) {
        if (operation.action == ChannelAction.Close || operation.action is ChannelAction.ReturnFunds) requireCloseAvailable()
    }

    suspend fun requireAvailable(walletId: WalletId) = availability(walletId)
    suspend fun previewClose(walletId: WalletId, channel: ChannelSnapshot, operationId: String): ChannelPreview =
        previewExit(walletId, channel, operationId, closing = true)

    suspend fun previewReturnFunds(walletId: WalletId, channel: ChannelSnapshot, operationId: String): ChannelPreview =
        previewExit(walletId, channel, operationId, closing = false)

    suspend fun observe(walletId: WalletId, channels: Collection<ChannelSnapshot>): Map<ProtocolKeytag, ChannelChainObservation> {
        requireCloseAvailable()
        val profile = profile(walletId)
        val ledger = loadLedger(profile)
        require(ledger.network == profile.network)
        val walletKey = verificationKey(profile)
        val matched = matchChannels(ledger, channels, walletKey)
        val depths = mutableMapOf<String, Long>()
        return matched.mapValues { (_, pair) ->
            val (output, datum) = pair
            val depth = depths.getOrPut(output.transactionId) {
                val tx = loadTransaction!!(profile, output.transactionId)
                if (tx == null) 0 else {
                    require(tx.id == output.transactionId && tx.depth >= 0)
                    tx.depth
                }
            }
            val after = closeReturnAfterEpochMillis!!(requireNotNull(output.datumHex))
            require(after == null || after >= 0)
            require((datum.stage is ChannelDatumStage.Opened) == (after == null))
            ChannelChainObservation(output, datum, depth, after,
                after != null && depth >= 5 && cardanoSlotEpochMillis(ledger.network, ledger.currentSlot) >= after)
        }
    }

    private fun matchChannels(
        ledger: LedgerSnapshot,
        channels: Collection<ChannelSnapshot>,
        walletKey: String,
    ): Map<ProtocolKeytag, Pair<LedgerUtxo, ChannelDatum>> {
        require(KEY.matches(walletKey))
        val tracked = channels.associateBy { it.keytag }
        require(tracked.size == channels.size)
        val matched = mutableMapOf<ProtocolKeytag, Pair<LedgerUtxo, ChannelDatum>>()
        for (output in ledger.utxos) {
            if (output.address != MAINNET.validatorAddress || output.datumHex == null) continue
            val datum = runCatching { engine.decodeChannelDatum(output.datumHex) }.getOrNull() ?: continue
            val keytag = ProtocolKeytag.from(datum.constants.addVerificationKeyHex, ProtocolTag(datum.constants.tagHex), TAG_BYTES)
            val channel = tracked[keytag] ?: continue
            requireDatumBinding(channel.keytag, channel.asset, output, datum)
            require(datum.constants.addVerificationKeyHex == walletKey)
            channel.chainObservation?.let { require(it.datum.constants == datum.constants) }
            require(matched.put(keytag, output to datum) == null) { "A unique channel output is unavailable." }
        }
        return matched
    }

    private fun requireDatumBinding(
        keytag: ProtocolKeytag,
        asset: io.riverark.ferret.core.model.ChannelAsset,
        output: LedgerUtxo,
        datum: ChannelDatum,
    ) {
        require(output.address == MAINNET.validatorAddress && output.scriptRefHashHex == null)
        require(datum.validatorHashHex == MAINNET.validatorHashHex)
        require(datum.constants.asset == assets.requireAsset(asset))
        require(datum.constants.adaptorVerificationKeyHex == MAINNET.adaptorIdentityHex)
        require(ProtocolKeytag.from(datum.constants.addVerificationKeyHex, ProtocolTag(datum.constants.tagHex), TAG_BYTES) == keytag)
        require(output.assets.isEmpty() || asset.policyId != null &&
            output.assets.keys == setOf(asset.connectorUnit))
        require(asset.policyId != null || output.assets.isEmpty())
        closeReturnAfterEpochMillis!!(requireNotNull(output.datumHex))
    }

    private suspend fun previewExit(walletId: WalletId, channel: ChannelSnapshot, operationId: String, closing: Boolean): ChannelPreview {
        requireCloseAvailable()
        require(UUID.matches(operationId))
        require(channel.pending == null && channel.payments.pending == null)
        require(channel.asset == assets.requireAsset(channel.asset) && channel.spendableBalance.asset == channel.asset)
        val profile = profile(walletId)
        availability(walletId)
        val context = context(profile)
        val (input, datum) = matchChannels(context.ledger, listOf(channel), verificationKey(profile))[channel.keytag]
            ?: throw ChannelStateChangedException()
        channel.chainObservation?.let { if (it.output != input || it.datum != datum) throw ChannelStateChangedException() }
        val step = if (closing) {
            if (channel.state !is ChannelState.Open || datum.stage !is ChannelDatumStage.Opened) throw ChannelStateChangedException()
            CloseChannelStep.CLOSE
        } else {
            when {
                channel.state == ChannelState.Closed && datum.stage is ChannelDatumStage.Closed -> CloseChannelStep.ELAPSE
                channel.state == ChannelState.Responded && datum.stage is ChannelDatumStage.Responded -> CloseChannelStep.END
                else -> throw ChannelStateChangedException()
            }
        }
        require(context.ledger.currentSlot <= Long.MAX_VALUE - CLOSE_VALIDITY_SLOTS)
        val until = context.ledger.currentSlot + CLOSE_VALIDITY_SLOTS
        val resulting = if (closing) {
            val upper = cardanoSlotEpochMillis(profile.network, until)
            require(upper <= Long.MAX_VALUE - datum.constants.closePeriodMillis)
            datum.copy(stage = ChannelDatumStage.Closed(datum.stage.accountedAmount, datum.stage.evidenceCborHex,
                upper + datum.constants.closePeriodMillis))
        } else {
            val after = requireNotNull(closeReturnAfterEpochMillis!!(requireNotNull(input.datumHex)))
            val tx = loadTransaction!!(profile, input.transactionId)
            require(tx != null && tx.id == input.transactionId && tx.depth >= 5) { "Channel confirmation evidence is unavailable." }
            if (cardanoSlotEpochMillis(profile.network, context.ledger.currentSlot) < after) throw ChannelReturnNotReadyException(after)
            null
        }
        val intent = CardanoIntent.CloseChannel(profile.paymentAddress, input, context.reference, datum, step, resulting,
            input.lovelace, operationId, context.ledger.currentSlot, until)
        val unsigned = engine.build(intent, context.ledger)
        engine.requireAuthorized(unsigned, intent, context.ledger)
        val summary = engine.inspect(unsigned.cbor)
        val index = if (closing) summary.outputs.indexOfFirst { it.address == MAINNET.validatorAddress }
            else summary.outputs.indexOfFirst { it.address == profile.paymentAddress }
        require(index >= 0)
        val capacity = if (closing) channel.spendableBalance else AssetAmount(channel.asset, 0)
        val payload = ChannelPayload.Transaction(unsigned.cbor.copyOf(), engine.transactionId(unsigned.cbor),
            intent = intent, feeBound = unsigned.feeBound)
        val operation = PreparedChannelOperation(operationId, payload.expectedTransactionId, channel.keytag, channel.asset,
            if (closing) ChannelAction.Close else ChannelAction.ReturnFunds(step),
            preparedAtEpochMillis = nowEpochMillis(), payload = payload, resultingSpendableBalance = capacity,
            priorChannelState = channel.state, priorSpendableBalance = channel.spendableBalance)
        return ChannelPreview(operation, exitAmount(intent), AssetAmount(assets.ada, summary.fee.value),
            AssetAmount(assets.ada, unsigned.feeBound.value),
            summary.outputs.lastOrNull { it.address == profile.paymentAddress },
            AssetAmount(assets.ada, engine.minimumAdaForOutput(unsigned.cbor, context.ledger.protocolParametersJson, index).value),
            AssetAmount(assets.ada, KONDUIT_MIN_ADA_BUFFER), capacity, profile.network,
            AssetAmount(assets.ada, if (closing) summary.outputs[index].lovelace.value else input.lovelace.value),
            AssetAmount(assets.ada, requireNotNull(summary.totalCollateral).value)).also { validate(it, profile, context) }
    }

    private fun exitAmount(intent: CardanoIntent.CloseChannel): AssetAmount {
        val asset = intent.currentDatum.constants.asset
        return AssetAmount(asset, if (asset == assets.ada) {
            if (intent.step == CloseChannelStep.CLOSE) {
                require(intent.channelInput.lovelace.value >= KONDUIT_MIN_ADA_BUFFER)
                intent.channelInput.lovelace.value - KONDUIT_MIN_ADA_BUFFER
            } else intent.channelInput.lovelace.value
        } else intent.channelInput.assets[asset.connectorUnit] ?: 0)
    }

    private fun validateCloseOperation(operation: PreparedChannelOperation, profile: WalletProfile, context: Context): UnsignedTransaction {
        requireCloseAvailable()
        val intent = requireIntent(operation) as CardanoIntent.CloseChannel
        val payload = operation.payload as ChannelPayload.Transaction
        require(UUID.matches(operation.operationId) && intent.operationId == operation.operationId)
        require(intent.sourceAddress == profile.paymentAddress && intent.referenceInput == context.reference)
        requireDatumBinding(operation.keytag, operation.asset, intent.channelInput, intent.currentDatum)
        require(engine.decodeChannelDatum(requireNotNull(intent.channelInput.datumHex)) == intent.currentDatum)
        require(intent.amount == intent.channelInput.lovelace)
        require(operation.priorSpendableBalance?.asset == operation.asset)
        require(context.ledger.currentSlot < intent.validUntil)
        val candidates = context.ledger.utxos.filter { it.address == MAINNET.validatorAddress }.mapNotNull { output ->
            val datum = output.datumHex?.let { runCatching { engine.decodeChannelDatum(it) }.getOrNull() }
            if (datum?.constants?.let { it.addVerificationKeyHex + it.tagHex } == operation.keytag.value) output else null
        }
        if (candidates != listOf(intent.channelInput)) throw ChannelStateChangedException()
        requireSavedCloseStep(operation, profile, intent)
        require(payload.expectedTransactionId == engine.transactionId(payload.unsignedBody) && operation.intentHash == payload.expectedTransactionId)
        val unsigned = UnsignedTransaction(payload.unsignedBody, operation.operationId, requireNotNull(payload.feeBound))
        engine.requireAuthorized(unsigned, intent, context.ledger)
        return unsigned
    }

    private fun requireSavedCloseStep(operation: PreparedChannelOperation, profile: WalletProfile, intent: CardanoIntent.CloseChannel) {
        require(operation.priorSpendableBalance?.asset == operation.asset)
        // Saved bytes keep their reviewed window; signing and recovery never extend it.
        require(intent.validFrom >= 0 && intent.validUntil > intent.validFrom &&
            intent.validUntil - intent.validFrom <= CLOSE_VALIDITY_SLOTS)
        require(intent.referenceInput.address == MAINNET.scriptDeploymentAddress &&
            intent.referenceInput.scriptRefVersion == 3 && intent.referenceInput.scriptRefHashHex == MAINNET.validatorHashHex)
        when (intent.step) {
            CloseChannelStep.CLOSE -> {
                require(operation.priorChannelState is ChannelState.Open && intent.currentDatum.stage is ChannelDatumStage.Opened)
                val upper = cardanoSlotEpochMillis(profile.network, intent.validUntil)
                require(upper <= Long.MAX_VALUE - intent.currentDatum.constants.closePeriodMillis)
                require(intent.resultingDatum == intent.currentDatum.copy(stage = ChannelDatumStage.Closed(
                    intent.currentDatum.stage.accountedAmount, intent.currentDatum.stage.evidenceCborHex,
                    upper + intent.currentDatum.constants.closePeriodMillis)))
                require(operation.resultingSpendableBalance == operation.priorSpendableBalance)
            }
            CloseChannelStep.ELAPSE, CloseChannelStep.END -> {
                require(intent.resultingDatum == null)
                require(if (intent.step == CloseChannelStep.ELAPSE)
                    operation.priorChannelState == ChannelState.Closed && intent.currentDatum.stage is ChannelDatumStage.Closed
                else operation.priorChannelState == ChannelState.Responded && intent.currentDatum.stage is ChannelDatumStage.Responded)
                val after = requireNotNull(closeReturnAfterEpochMillis!!(requireNotNull(intent.channelInput.datumHex)))
                require(cardanoSlotEpochMillis(profile.network, intent.validFrom) >= after)
                require(operation.resultingSpendableBalance == AssetAmount(operation.asset, 0))
            }
        }
    }

    private fun validateClose(preview: ChannelPreview, profile: WalletProfile, context: Context) {
        val operation = preview.operation
        val intent = requireIntent(operation) as CardanoIntent.CloseChannel
        val unsigned = validateCloseOperation(operation, profile, context)
        val summary = engine.inspect(unsigned.cbor)
        require(operation.state == OperationState.PROPOSED && preview.network == profile.network)
        require(preview.amount == exitAmount(intent) && preview.resultingSpendableBalance == operation.resultingSpendableBalance)
        require(preview.actualFee == AssetAmount(assets.ada, summary.fee.value))
        require(preview.feeBound == AssetAmount(assets.ada, unsigned.feeBound.value))
        require(preview.collateral == AssetAmount(assets.ada, requireNotNull(summary.totalCollateral).value))
        require(preview.protocolReserve == AssetAmount(assets.ada, KONDUIT_MIN_ADA_BUFFER))
        val index = summary.outputs.indexOfFirst { it.address == if (intent.step == CloseChannelStep.CLOSE) MAINNET.validatorAddress else profile.paymentAddress }
        require(index >= 0)
        require(preview.ledgerMinAda == AssetAmount(assets.ada,
            engine.minimumAdaForOutput(unsigned.cbor, context.ledger.protocolParametersJson, index).value))
        require(preview.outputAda == AssetAmount(assets.ada,
            if (intent.step == CloseChannelStep.CLOSE) summary.outputs[index].lovelace.value else intent.channelInput.lovelace.value))
        require(preview.sourceChange == summary.outputs.lastOrNull { it.address == profile.paymentAddress })
    }

    internal suspend fun expiredAndUnspent(walletId: WalletId, operation: PreparedChannelOperation): Boolean {
        requireCloseAvailable()
        val profile = profile(walletId)
        val intent = requireIntent(operation) as CardanoIntent.CloseChannel
        val payload = operation.payload as ChannelPayload.Transaction
        require(intent.sourceAddress == profile.paymentAddress)
        requireDatumBinding(operation.keytag, operation.asset, intent.channelInput, intent.currentDatum)
        requireSavedCloseStep(operation, profile, intent)
        require(payload.expectedTransactionId == engine.transactionId(payload.unsignedBody) &&
            operation.intentHash == payload.expectedTransactionId)
        val ledger = loadLedger(profile)
        require(ledger.network == profile.network)
        if (ledger.currentSlot < intent.validUntil || ledger.utxos.none { it == intent.channelInput }) return false
        return loadTransaction!!(profile, payload.expectedTransactionId) == null
    }

    suspend fun confirmOperation(walletId: WalletId, operation: PreparedChannelOperation): Pair<ChannelRemoteResult, ChannelReturnProof?>? {
        requireCloseAvailable()
        val profile = profile(walletId)
        val intent = requireIntent(operation) as CardanoIntent.CloseChannel
        val payload = operation.payload as ChannelPayload.Transaction
        require(intent.operationId == operation.operationId && UUID.matches(operation.operationId))
        require(intent.sourceAddress == profile.paymentAddress && intent.amount == intent.channelInput.lovelace)
        requireDatumBinding(operation.keytag, operation.asset, intent.channelInput, intent.currentDatum)
        require(engine.decodeChannelDatum(requireNotNull(intent.channelInput.datumHex)) == intent.currentDatum)
        requireSavedCloseStep(operation, profile, intent)
        require(intent.currentDatum.constants.addVerificationKeyHex == verificationKey(profile))
        require(payload.signedTransaction.isNotEmpty())
        require(payload.expectedTransactionId == engine.transactionId(payload.signedTransaction) &&
            payload.expectedTransactionId == engine.transactionId(payload.unsignedBody) && operation.intentHash == payload.expectedTransactionId)
        val summary = engine.inspect(payload.signedTransaction)
        summary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = true)
        require(summary.copy(keyWitnesses = emptyList()) == engine.inspect(payload.unsignedBody))
        require(summary.network == profile.network && summary.validityStart == intent.validFrom && summary.validityEnd == intent.validUntil)
        require(summary.inputs.contains(TransactionInputReference(intent.channelInput.transactionId, intent.channelInput.index)))
        require(summary.prohibitedBodyFields.isEmpty() && summary.fee.value <= requireNotNull(payload.feeBound).value)
        require(summary.collateralInputs.distinct().size == summary.collateralInputs.size &&
            summary.collateralInputs.none { it in summary.inputs || it in summary.referenceInputs })
        val tx = loadTransaction!!(profile, payload.expectedTransactionId) ?: return null
        if (tx.id != payload.expectedTransactionId || tx.depth < 5 ||
            tx.invalidBefore != summary.validityStart || tx.invalidAfter != summary.validityEnd) return null
        val inputRefs = tx.inputs.map {
            if (it.outputIndex !in 0..Int.MAX_VALUE.toLong()) return null
            TransactionInputReference(it.transactionId, it.outputIndex.toInt())
        }
        if (inputRefs == summary.inputs) {
            if (tx.outputs.size != summary.outputs.size ||
                !tx.outputs.zip(summary.outputs).all { (actual, expected) ->
                    projectionMatches(actual.address, actual.value, actual.datumHash, actual.datumInline, actual.referenceScriptHash, expected)
                }) return null
            val actualInput = tx.inputs.singleOrNull {
                it.transactionId == intent.channelInput.transactionId && it.outputIndex == intent.channelInput.index.toLong()
            } ?: return null
            if (!projectionMatches(actualInput.address, actualInput.value, actualInput.datumHash, actualInput.datumInline,
                    actualInput.referenceScriptHash, intent.channelInput.asOutput())) return null
            val consumed = runCatching {
                tx.inputs.map { input ->
                    if (input === actualInput) intent.channelInput else {
                        require(input.address == profile.paymentAddress && input.datumHash == null && input.datumInline == null &&
                            input.referenceScriptHash == null)
                        require(input.value.map { it.unit }.distinct().size == input.value.size)
                        val values = input.value.associate { it.unit to it.quantity.toLong() }.toMutableMap()
                        val ada = requireNotNull(values.remove("lovelace"))
                        LedgerUtxo(input.transactionId, input.outputIndex.toInt(), input.address, Lovelace(ada), values)
                    }
                }
            }.getOrNull() ?: return null
            if (runCatching {
                    summary.requireChannelFunding(intent, LedgerSnapshot(profile.network, consumed, "", intent.validFrom))
                }.isFailure) return null
            if (intent.step == CloseChannelStep.CLOSE) {
                val continuing = summary.outputs.singleOrNull { it.address == MAINNET.validatorAddress } ?: return null
                val inline = continuing.datum as? TransactionDatum.Inline ?: return null
                if (engine.decodeChannelDatum(inline.cborHex) != intent.resultingDatum ||
                    continuing.assets != intent.channelInput.assets || continuing.lovelace.value < intent.channelInput.lovelace.value ||
                    continuing.scriptReference != null) return null
                if (summary.outputs.any { it !== continuing &&
                    (it.address != profile.paymentAddress || it.datum != TransactionDatum.Absent || it.scriptReference != null)
                }) return null
                return confirmedResult(operation, tx.depth, ChannelState.Closed, OperationState.COMPLETED) to null
            }
            if (summary.outputs.any { it.address != profile.paymentAddress || it.datum != TransactionDatum.Absent || it.scriptReference != null }) return null
            val proof = ChannelReturnProof(tx.id, intent.channelInput, intent.currentDatum, profile.paymentAddress,
                exitAmount(intent), intent.channelInput.lovelace, summary.fee, tx.depth)
            return confirmedResult(operation, tx.depth, ChannelState.FundsReturned(tx.id), OperationState.COMPLETED) to proof
        }
        if (inputRefs != summary.collateralInputs || inputRefs.isEmpty()) return null
        val collateralReturn = listOfNotNull(summary.collateralReturn)
        if (tx.outputs.size != collateralReturn.size ||
            !tx.outputs.zip(collateralReturn).all { (actual, expected) ->
                projectionMatches(actual.address, actual.value, actual.datumHash, actual.datumInline, actual.referenceScriptHash, expected)
            }) return null
        var collateral = 0L
        for (input in tx.inputs) {
            if (input.address != profile.paymentAddress || input.datumHash != null || input.datumInline != null ||
                input.referenceScriptHash != null || input.value.size != 1 || input.value.single().unit != "lovelace") return null
            val value = input.value.single().quantity.toLongOrNull() ?: return null
            if (value <= 0 || collateral > Long.MAX_VALUE - value) return null
            collateral += value
        }
        val returned = summary.collateralReturn?.let {
            if (it.address != profile.paymentAddress || it.assets.isNotEmpty() || it.datum != TransactionDatum.Absent ||
                it.scriptReference != null) return null
            it.lovelace.value
        } ?: 0L
        val loss = summary.totalCollateral?.value ?: return null
        if (collateral < returned || collateral - returned != loss || loss <= 0) return null
        val ledger = loadLedger(profile)
        require(ledger.network == profile.network)
        if (ledger.utxos.none { it == intent.channelInput }) return null
        return confirmedResult(operation, tx.depth, requireNotNull(operation.priorChannelState), OperationState.FAILED).copy(
            failureMessage = "Transaction failed during on-chain validation. Channel funds remain locked. Collateral charged: $loss lovelace."
        ) to null
    }

    private fun confirmedResult(operation: PreparedChannelOperation, depth: Long, state: ChannelState, status: OperationState) =
        ChannelRemoteResult(operation.operationId, operation.intentHash, operation.keytag, operation.asset,
            (operation.payload as ChannelPayload.Transaction).expectedTransactionId, state, status,
            closeStep = (requireIntent(operation) as CardanoIntent.CloseChannel).step, confirmationDepth = depth)

    private fun LedgerUtxo.asOutput() = TransactionOutputSummary(address, lovelace, assets,
        datumHex?.let { TransactionDatum.Inline(it) } ?: datumHashHex?.let { TransactionDatum.Hash(it) } ?: TransactionDatum.Absent,
        scriptRefHashHex?.let { io.riverark.ferret.core.cardano.TransactionScriptReference(
            requireNotNull(scriptRefVersion), requireNotNull(scriptRefHex), it) })

    private fun projectionMatches(address: String, values: List<ConnectorAssetDto>, hash: String?, inline: String?, script: String?,
        expected: TransactionOutputSummary): Boolean {
        if (address != expected.address || script != expected.scriptReference?.hashHex) return false
        if (values.map { it.unit }.toSet().size != values.size) return false
        val amounts = mutableMapOf<String, Long>()
        for (value in values) {
            val quantity = value.quantity.toLongOrNull() ?: return false
            if (quantity < 0) return false
            amounts[value.unit] = quantity
        }
        if (amounts.remove("lovelace") != expected.lovelace.value || amounts != expected.assets) return false
        return when (val datum = expected.datum) {
            TransactionDatum.Absent -> hash == null && inline == null
            is TransactionDatum.Hash -> hash == datum.hex && inline == null
            // Inline CBOR is authoritative; the connector's optional derived hash is redundant.
            is TransactionDatum.Inline -> inline == datum.cborHex && (hash == null || KEY.matches(hash))
        }
    }


    suspend fun previewOpen(walletId: WalletId, amount: AssetAmount, operationId: String): ChannelPreview {
        val selected = assets.requireAsset(amount.asset)
        require(amount.baseUnits > if (selected == assets.ada) KONDUIT_MIN_ADA_BUFFER else 0)
        require(UUID.matches(operationId))
        val profile = profile(walletId)
        availability(walletId)
        val walletKey = verificationKey(profile)
        require(KEY.matches(walletKey))
        val context = context(profile)
        assets.requireDiscoveryDigest(context.assetCatalogDigest)
        val tagBytes = newTag()
        val tag = try {
            require(tagBytes.size == TAG_BYTES)
            tagBytes.hex()
        } finally {
            tagBytes.fill(0)
        }
        val keytag = ProtocolKeytag.from(walletKey, ProtocolTag(tag), TAG_BYTES)
        requireNoDuplicateChannel(context.ledger, keytag)
        val datum = ChannelDatum(
            MAINNET.validatorHashHex,
            ChannelConstants(tag, walletKey, context.adaptorKey, context.closePeriodMillis, selected),
            ChannelDatumStage.Opened(0),
        )
        require(context.ledger.currentSlot <= Long.MAX_VALUE - VALIDITY_SLOTS)
        val validUntil = context.ledger.currentSlot + VALIDITY_SLOTS
        val intent = CardanoIntent.OpenChannel(
            profile.paymentAddress,
            MAINNET.validatorAddress,
            context.reference,
            datum,
            amount,
            operationId,
            context.ledger.currentSlot,
            validUntil,
        )
        val unsigned = engine.build(intent, context.ledger)
        engine.requireAuthorized(unsigned, intent, context.ledger)
        val summary = engine.inspect(unsigned.cbor)
        val channelIndex = channelOutputIndex(summary.outputs, intent)
        val change = summary.outputs.singleOrNull { it.address == profile.paymentAddress }
        val reserve = Lovelace(KONDUIT_MIN_ADA_BUFFER)
        val channelOutput = summary.outputs[channelIndex]
        val capacity = if (selected == assets.ada) {
            AssetAmount(selected, amount.baseUnits - reserve.value)
        } else {
            amount
        }
        val payload = ChannelPayload.Transaction(
            unsigned.cbor.copyOf(),
            engine.transactionId(unsigned.cbor),
            intent = intent,
            feeBound = unsigned.feeBound,
        )
        return ChannelPreview(
            PreparedChannelOperation(
                operationId,
                payload.expectedTransactionId,
                keytag,
                selected,
                ChannelAction.Open(amount),
                preparedAtEpochMillis = nowEpochMillis(),
                payload = payload,
                resultingSpendableBalance = capacity,
            ),
            amount,
            AssetAmount(assets.ada, summary.fee.value),
            AssetAmount(assets.ada, unsigned.feeBound.value),
            change,
            AssetAmount(assets.ada, engine.minimumAdaForOutput(unsigned.cbor, context.ledger.protocolParametersJson, channelIndex).value),
            AssetAmount(assets.ada, reserve.value),
            capacity,
            profile.network,
            AssetAmount(assets.ada, channelOutput.lovelace.value),
        ).also { validate(it, profile, context) }
    }

    suspend fun previewAdd(
        walletId: WalletId,
        channel: ChannelSnapshot,
        amount: AssetAmount,
        operationId: String,
    ): ChannelPreview {
        val selected = assets.requireAsset(amount.asset)
        require(amount.baseUnits > 0 && channel.asset == selected && channel.spendableBalance.asset == selected)
        require(channel.state is io.riverark.ferret.core.model.ChannelState.Open)
        require(channel.pending == null && channel.payments.pending == null)
        require(UUID.matches(operationId))
        val profile = profile(walletId)
        availability(walletId)
        val context = context(profile)
        assets.requireDiscoveryDigest(context.assetCatalogDigest)
        val matches = context.ledger.utxos.filter { utxo ->
            utxo.address == MAINNET.validatorAddress && utxo.datumHex?.let { encoded ->
                val datum = engine.decodeChannelDatum(encoded)
                datum.constants.asset == selected &&
                    ProtocolKeytag.from(
                        datum.constants.addVerificationKeyHex,
                        ProtocolTag(datum.constants.tagHex),
                        TAG_BYTES,
                    ) == channel.keytag
            } == true
        }
        require(matches.size == 1) { "A unique open channel output is unavailable." }
        val channelInput = matches.single()
        val datum = engine.decodeChannelDatum(requireNotNull(channelInput.datumHex))
        require(datum.stage is ChannelDatumStage.Opened)
        require(context.ledger.currentSlot <= Long.MAX_VALUE - VALIDITY_SLOTS)
        val intent = CardanoIntent.AddChannelFunds(
            profile.paymentAddress,
            channelInput,
            context.reference,
            datum,
            datum,
            amount,
            operationId,
            context.ledger.currentSlot,
            context.ledger.currentSlot + VALIDITY_SLOTS,
        )
        val unsigned = vault.withWalletSeed(walletId) { engine.build(intent, context.ledger, it) }
        engine.requireAuthorized(unsigned, intent, context.ledger)
        val summary = engine.inspect(unsigned.cbor)
        val channelIndex = addOutputIndex(summary.outputs, intent)
        val channelOutput = summary.outputs[channelIndex]
        val capacity = channel.spendableBalance + amount
        val payload = ChannelPayload.Transaction(
            unsigned.cbor.copyOf(),
            engine.transactionId(unsigned.cbor),
            intent = intent,
            feeBound = unsigned.feeBound,
        )
        return ChannelPreview(
            PreparedChannelOperation(
                operationId,
                payload.expectedTransactionId,
                channel.keytag,
                selected,
                ChannelAction.Add(amount),
                priorChannelIdentity = channel.state.channelId,
                preparedAtEpochMillis = nowEpochMillis(),
                payload = payload,
                resultingSpendableBalance = capacity,
            ),
            amount,
            AssetAmount(assets.ada, summary.fee.value),
            AssetAmount(assets.ada, unsigned.feeBound.value),
            summary.outputs.singleOrNull { it.address == profile.paymentAddress },
            AssetAmount(
                assets.ada,
                engine.minimumAdaForOutput(unsigned.cbor, context.ledger.protocolParametersJson, channelIndex).value,
            ),
            AssetAmount(assets.ada, KONDUIT_MIN_ADA_BUFFER),
            capacity,
            profile.network,
            AssetAmount(assets.ada, channelOutput.lovelace.value),
            AssetAmount(assets.ada, requireNotNull(summary.totalCollateral).value),
        ).also { validate(it, profile, context) }
    }

    suspend fun validatePreview(walletId: WalletId, preview: ChannelPreview) {
        requireCloseAvailable(preview.operation)
        val profile = profile(walletId)
        availability(walletId)
        requireLiveCloseIdentity(preview.operation, profile)
        validate(preview, profile, context(profile))
    }

    suspend fun sign(walletId: WalletId, operation: PreparedChannelOperation): PreparedChannelOperation {
        requireCloseAvailable(operation)
        val profile = profile(walletId)
        availability(walletId)
        val context = context(profile)
        val intent = requireIntent(operation)
        val unsigned = validateOperation(operation, profile, context)
        requireLiveCloseIdentity(operation, profile)
        val signed = vault.withWalletSeed(walletId) { engine.sign(unsigned, it, intent, context.ledger) }
        try {
            requireSignedMatches(profile, unsigned, intent, signed.cbor, context.ledger)
            val payload = operation.payload as ChannelPayload.Transaction
            return operation.copy(payload = payload.copy(signedTransaction = signed.cbor.copyOf()))
        } finally {
            signed.cbor.fill(0)
        }
    }

    suspend fun validateReplay(walletId: WalletId, operation: PreparedChannelOperation) {
        requireCloseAvailable(operation)
        val profile = profile(walletId)
        availability(walletId)
        val context = context(profile)
        val intent = requireIntent(operation)
        val unsigned = validateOperation(operation, profile, context)
        requireLiveCloseIdentity(operation, profile)
        val signed = (operation.payload as ChannelPayload.Transaction).signedTransaction
        require(signed.isNotEmpty())
        requireSignedMatches(profile, unsigned, intent, signed, context.ledger)
    }

    private suspend fun requireLiveCloseIdentity(operation: PreparedChannelOperation, profile: WalletProfile) {
        val intent = requireIntent(operation) as? CardanoIntent.CloseChannel ?: return
        require(intent.currentDatum.constants.addVerificationKeyHex == verificationKey(profile))
        if (intent.step != CloseChannelStep.CLOSE) {
            val tx = loadTransaction!!(profile, intent.channelInput.transactionId)
            require(tx != null && tx.id == intent.channelInput.transactionId && tx.depth >= 5) {
                "Channel confirmation evidence is unavailable."
            }
        }
    }

    private suspend fun profile(walletId: WalletId): WalletProfile =
        vault.profiles().single { it.id == walletId }.also { require(it.network == CardanoNetwork.MAINNET) }

    private suspend fun context(profile: WalletProfile): Context {
        val ledger = loadLedger(profile)
        require(ledger.network == CardanoNetwork.MAINNET)
        val info = loadInfo(profile)
        require(info.channelParameters.adaptorKeyHex == MAINNET.adaptorIdentityHex)
        require(info.transactionHelp.hostAddress == MAINNET.scriptDeploymentAddress)
        require(info.transactionHelp.validator == MAINNET.validatorHashHex)
        assets.requireDiscoveryDigest(info.assetCatalogDigest)
        require(info.channelParameters.tagLength == TAG_BYTES)
        require(info.channelParameters.closePeriod.nanos % 1_000_000 == 0)
        val period = ProtocolDurationWire(
            info.channelParameters.closePeriod.secs,
            info.channelParameters.closePeriod.nanos,
        ).millis()
        require(period > 0)
        val references = ledger.utxos.filter {
            it.address == MAINNET.scriptDeploymentAddress &&
                it.scriptRefVersion == 3 && it.scriptRefHashHex == MAINNET.validatorHashHex
        }.sortedWith(compareBy({ it.transactionId }, { it.index }))
        return Context(ledger, info.channelParameters.adaptorKeyHex, period, references.first(), info.assetCatalogDigest)
    }

    private fun validateOpen(preview: ChannelPreview, profile: WalletProfile, context: Context) {
        val operation = preview.operation
        val intent = requireIntent(operation) as? CardanoIntent.OpenChannel ?: error("Open intent is required")
        val payload = operation.payload as ChannelPayload.Transaction
        require(preview.network == CardanoNetwork.MAINNET && profile.network == preview.network)
        require(operation.asset == assets.requireAsset(preview.amount.asset))
        require(operation.action == ChannelAction.Open(preview.amount))
        require(operation.state == io.riverark.ferret.core.model.OperationState.PROPOSED)
        require(operation.priorChannelIdentity == null)
        require(operation.intentHash == payload.expectedTransactionId)
        require(operation.resultingSpendableBalance == preview.resultingSpendableBalance)
        require(preview.protocolReserve == AssetAmount(assets.ada, KONDUIT_MIN_ADA_BUFFER))
        require(preview.collateral == null)
        require(preview.amount.baseUnits > if (preview.amount.asset == assets.ada) KONDUIT_MIN_ADA_BUFFER else 0)
        require(preview.resultingSpendableBalance == if (preview.amount.asset == assets.ada) {
            preview.amount - AssetAmount(assets.ada, KONDUIT_MIN_ADA_BUFFER)
        } else {
            preview.amount
        })
        require(payload.feeBound?.value == preview.feeBound.baseUnits)
        val unsigned = validateOperation(operation, profile, context)
        val summary = engine.inspect(unsigned.cbor)
        require(summary.fee.value == preview.actualFee.baseUnits)
        val channelIndex = channelOutputIndex(summary.outputs, intent)
        require(engine.minimumAdaForOutput(unsigned.cbor, context.ledger.protocolParametersJson, channelIndex).value == preview.ledgerMinAda.baseUnits)
        require(preview.outputAda.baseUnits >= preview.ledgerMinAda.baseUnits)
        require(summary.outputs.singleOrNull { it.address == profile.paymentAddress } == preview.sourceChange)
        require(summary.outputs[channelIndex].lovelace.value == preview.outputAda.baseUnits)
    }

    private fun validate(preview: ChannelPreview, profile: WalletProfile, context: Context) {
        when (preview.operation.action) {
            is ChannelAction.Open -> validateOpen(preview, profile, context)
            is ChannelAction.Add -> validateAdd(preview, profile, context)
            ChannelAction.Close, is ChannelAction.ReturnFunds -> validateClose(preview, profile, context)
            else -> error("Channel funding action is required")
        }
    }

    private fun validateAdd(preview: ChannelPreview, profile: WalletProfile, context: Context) {
        val operation = preview.operation
        val intent = requireIntent(operation) as? CardanoIntent.AddChannelFunds
            ?: error("Add intent is required")
        val amount = (operation.action as? ChannelAction.Add)?.amount ?: error("Add action is required")
        require(preview.network == CardanoNetwork.MAINNET && profile.network == preview.network)
        require(amount == preview.amount && amount == intent.amount)
        require(operation.asset == assets.requireAsset(amount.asset))
        require(operation.state == io.riverark.ferret.core.model.OperationState.PROPOSED)
        require(operation.priorChannelIdentity != null)
        require(operation.resultingSpendableBalance == preview.resultingSpendableBalance)
        require(preview.resultingSpendableBalance.asset == amount.asset)
        require(preview.resultingSpendableBalance.baseUnits >= amount.baseUnits)
        require(preview.protocolReserve == AssetAmount(assets.ada, KONDUIT_MIN_ADA_BUFFER))
        require(preview.collateral != null && preview.collateral.asset == assets.ada && preview.collateral.baseUnits > 0)
        require(preview.amount.baseUnits > 0)
        require((operation.payload as ChannelPayload.Transaction).feeBound?.value == preview.feeBound.baseUnits)
        val unsigned = validateAddOperation(operation, profile, context)
        val summary = engine.inspect(unsigned.cbor)
        require(summary.fee.value == preview.actualFee.baseUnits)
        require(summary.totalCollateral?.value == preview.collateral.baseUnits)
        val channelIndex = addOutputIndex(summary.outputs, intent)
        require(engine.minimumAdaForOutput(
            unsigned.cbor,
            context.ledger.protocolParametersJson,
            channelIndex,
        ).value == preview.ledgerMinAda.baseUnits)
        require(preview.outputAda.baseUnits >= preview.ledgerMinAda.baseUnits)
        require(summary.outputs.singleOrNull { it.address == profile.paymentAddress } == preview.sourceChange)
        require(summary.outputs[channelIndex].lovelace.value == preview.outputAda.baseUnits)
    }

    private fun validateOperation(
        operation: PreparedChannelOperation,
        profile: WalletProfile,
        context: Context,
    ): UnsignedTransaction = when (operation.action) {
        is ChannelAction.Open -> validateOpenOperation(operation, profile, context)
        is ChannelAction.Add -> validateAddOperation(operation, profile, context)
        ChannelAction.Close, is ChannelAction.ReturnFunds -> validateCloseOperation(operation, profile, context)
        else -> error("Channel funding action is required")
    }

    private fun validateOpenOperation(
        operation: PreparedChannelOperation,
        profile: WalletProfile,
        context: Context,
    ): UnsignedTransaction {
        val payload = operation.payload as? ChannelPayload.Transaction ?: error("channel transaction payload is required")
        val intent = requireIntent(operation) as? CardanoIntent.OpenChannel ?: error("Open intent is required")
        require(UUID.matches(operation.operationId) && intent.operationId == operation.operationId)
        require(operation.asset == assets.requireAsset(intent.amount.asset))
        require(operation.action == ChannelAction.Open(intent.amount))
        require(intent.amount.baseUnits > if (intent.amount.asset == assets.ada) KONDUIT_MIN_ADA_BUFFER else 0)
        require(intent.sourceAddress == profile.paymentAddress)
        require(intent.validatorAddress == MAINNET.validatorAddress)
        require(intent.referenceInput == context.reference)
        require(intent.datum.validatorHashHex == MAINNET.validatorHashHex)
        require(intent.datum.constants.adaptorVerificationKeyHex == context.adaptorKey)
        require(intent.datum.constants.closePeriodMillis == context.closePeriodMillis)
        require(intent.datum.constants.asset == operation.asset)
        require(intent.datum.stage == ChannelDatumStage.Opened(0))
        require(intent.validFrom < intent.validUntil && context.ledger.currentSlot < intent.validUntil)
        require(operation.keytag == ProtocolKeytag.from(
            intent.datum.constants.addVerificationKeyHex,
            ProtocolTag(intent.datum.constants.tagHex),
            TAG_BYTES,
        ))
        requireNoDuplicateChannel(context.ledger, operation.keytag)
        val feeBound = requireNotNull(payload.feeBound)
        val unsigned = UnsignedTransaction(payload.unsignedBody, operation.operationId, feeBound)
        require(payload.expectedTransactionId == engine.transactionId(payload.unsignedBody))
        require(operation.intentHash == payload.expectedTransactionId)
        engine.requireAuthorized(unsigned, intent, context.ledger)
        return unsigned
    }

    private fun validateAddOperation(
        operation: PreparedChannelOperation,
        profile: WalletProfile,
        context: Context,
    ): UnsignedTransaction {
        val payload = operation.payload as? ChannelPayload.Transaction
            ?: error("channel transaction payload is required")
        val intent = requireIntent(operation) as? CardanoIntent.AddChannelFunds
            ?: error("Add intent is required")
        val action = operation.action as? ChannelAction.Add ?: error("Add action is required")
        require(UUID.matches(operation.operationId) && intent.operationId == operation.operationId)
        require(action.amount == intent.amount && action.amount.baseUnits > 0)
        require(operation.asset == assets.requireAsset(intent.amount.asset))
        require(operation.priorChannelIdentity != null)
        require(operation.resultingSpendableBalance?.asset == operation.asset)
        require(intent.sourceAddress == profile.paymentAddress)
        require(intent.referenceInput == context.reference)
        require(intent.currentDatum == intent.resultingDatum)
        require(intent.currentDatum.validatorHashHex == MAINNET.validatorHashHex)
        require(intent.currentDatum.constants.adaptorVerificationKeyHex == context.adaptorKey)
        require(intent.currentDatum.constants.closePeriodMillis == context.closePeriodMillis)
        require(intent.currentDatum.constants.asset == operation.asset)
        require(intent.currentDatum.stage is ChannelDatumStage.Opened)
        require(intent.channelInput.address == MAINNET.validatorAddress)
        require(engine.decodeChannelDatum(requireNotNull(intent.channelInput.datumHex)) == intent.currentDatum)
        require(intent.validFrom < intent.validUntil && context.ledger.currentSlot < intent.validUntil)
        require(operation.keytag == ProtocolKeytag.from(
            intent.currentDatum.constants.addVerificationKeyHex,
            ProtocolTag(intent.currentDatum.constants.tagHex),
            TAG_BYTES,
        ))
        val feeBound = requireNotNull(payload.feeBound)
        val unsigned = UnsignedTransaction(payload.unsignedBody, operation.operationId, feeBound)
        require(payload.expectedTransactionId == engine.transactionId(payload.unsignedBody))
        require(operation.intentHash == payload.expectedTransactionId)
        engine.requireAuthorized(unsigned, intent, context.ledger)
        return unsigned
    }

    private fun requireSignedMatches(
        profile: WalletProfile,
        unsigned: UnsignedTransaction,
        intent: CardanoIntent,
        signed: ByteArray,
        ledger: LedgerSnapshot,
    ) {
        require(engine.transactionId(signed) == engine.transactionId(unsigned.cbor))
        val unsignedSummary = engine.inspect(unsigned.cbor)
        val signedSummary = engine.inspect(signed)
        signedSummary.requireL1Witnesses(profile.id.value.substringAfter('-'), signed = true)
        require(signedSummary.copy(keyWitnesses = emptyList()) == unsignedSummary)
        signedSummary.requireChannelFunding(intent, ledger)
        engine.requireMinimumAda(signed, ledger.protocolParametersJson)
    }

    private fun requireIntent(operation: PreparedChannelOperation): CardanoIntent {
        val intent = (operation.payload as? ChannelPayload.Transaction)?.intent
            ?: error("Channel transaction intent is required")
        require(
            operation.action is ChannelAction.Open && intent is CardanoIntent.OpenChannel ||
                operation.action is ChannelAction.Add && intent is CardanoIntent.AddChannelFunds ||
                operation.action == ChannelAction.Close && intent is CardanoIntent.CloseChannel && intent.step == CloseChannelStep.CLOSE ||
                operation.action is ChannelAction.ReturnFunds && intent is CardanoIntent.CloseChannel &&
                operation.action.step == intent.step && intent.step != CloseChannelStep.CLOSE,
        )
        return intent
    }

    private fun requireNoDuplicateChannel(ledger: LedgerSnapshot, keytag: ProtocolKeytag) {
        require(ledger.utxos.none { utxo ->
            utxo.address == MAINNET.validatorAddress && utxo.datumHex?.let { datum ->
                runCatching { engine.decodeChannelDatum(datum) }.getOrNull()?.constants?.let {
                    it.addVerificationKeyHex + it.tagHex == keytag.value
                }
            } == true
        }) { "channel keytag already exists" }
    }

    private fun channelOutputIndex(
        outputs: List<io.riverark.ferret.core.cardano.TransactionOutputSummary>,
        intent: CardanoIntent.OpenChannel,
    ): Int = outputs.indexOfFirst {
        val expectedAssets = intent.amount.asset.policyId?.let {
            mapOf(intent.amount.asset.connectorUnit to intent.amount.baseUnits)
        }.orEmpty()
        it.address == intent.validatorAddress &&
            it.assets == expectedAssets &&
            (intent.amount.asset.policyId != null || it.lovelace == Lovelace(intent.amount.baseUnits)) &&
            it.datum is TransactionDatum.Inline && it.scriptReference == null
    }.also { require(it >= 0) }

    private fun addOutputIndex(
        outputs: List<io.riverark.ferret.core.cardano.TransactionOutputSummary>,
        intent: CardanoIntent.AddChannelFunds,
    ): Int {
        val selected = assets.requireAsset(intent.amount.asset)
        val expectedAssets = if (selected.policyId == null) {
            emptyMap()
        } else {
            mapOf(
                selected.connectorUnit to (
                    AssetAmount(
                        intent.amount.asset,
                        requireNotNull(intent.channelInput.assets[selected.connectorUnit]),
                    ) + intent.amount
                    ).baseUnits,
            )
        }
        return outputs.indexOfFirst {
            it.address == intent.channelInput.address &&
                it.assets == expectedAssets &&
                it.datum == TransactionDatum.Inline(requireNotNull(intent.channelInput.datumHex)) &&
                it.scriptReference == null &&
                (selected.policyId != null ||
                    it.lovelace == intent.channelInput.lovelace + Lovelace(intent.amount.baseUnits))
        }.also { require(it >= 0) }
    }

    private data class Context(
        val ledger: LedgerSnapshot,
        val adaptorKey: String,
        val closePeriodMillis: Long,
        val reference: io.riverark.ferret.core.cardano.LedgerUtxo,
        val assetCatalogDigest: String?,
    )

    private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private companion object {
        const val TAG_BYTES = 32
        const val VALIDITY_SLOTS = 3_600L
        const val CLOSE_VALIDITY_SLOTS = 600L
        val KEY = Regex("[0-9a-f]{64}")
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

