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
) {
    suspend fun requireAvailable(walletId: WalletId) = availability(walletId)

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
        val profile = profile(walletId)
        availability(walletId)
        validate(preview, profile, context(profile))
    }

    suspend fun sign(walletId: WalletId, operation: PreparedChannelOperation): PreparedChannelOperation {
        val profile = profile(walletId)
        availability(walletId)
        val context = context(profile)
        val intent = requireIntent(operation)
        val unsigned = validateOperation(operation, profile, context)
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
        val profile = profile(walletId)
        availability(walletId)
        val context = context(profile)
        val intent = requireIntent(operation)
        val unsigned = validateOperation(operation, profile, context)
        val signed = (operation.payload as ChannelPayload.Transaction).signedTransaction
        require(signed.isNotEmpty())
        requireSignedMatches(profile, unsigned, intent, signed, context.ledger)
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
                operation.action is ChannelAction.Add && intent is CardanoIntent.AddChannelFunds,
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
        val KEY = Regex("[0-9a-f]{64}")
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

