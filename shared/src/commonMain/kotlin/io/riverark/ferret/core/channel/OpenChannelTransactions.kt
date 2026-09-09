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
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.network.AdaptorInfoDto
import io.riverark.ferret.core.network.MAINNET
import io.riverark.ferret.core.security.SecureVault

class OpenChannelTransactions(
    private val vault: SecureVault,
    private val engine: CardanoTransactionEngine,
    private val loadLedger: suspend (WalletProfile) -> LedgerSnapshot,
    private val loadInfo: suspend (WalletProfile) -> AdaptorInfoDto,
    private val verificationKey: suspend (WalletProfile) -> String,
    private val availability: suspend (WalletId) -> Unit,
    private val newTag: () -> ByteArray,
    private val nowEpochMillis: () -> Long,
) {
    suspend fun requireAvailable(walletId: WalletId) = availability(walletId)

    suspend fun preview(walletId: WalletId, amount: Lovelace, operationId: String): ChannelPreview {
        require(amount.value > KONDUIT_MIN_ADA_BUFFER)
        require(UUID.matches(operationId))
        val profile = profile(walletId)
        availability(walletId)
        val walletKey = verificationKey(profile)
        require(KEY.matches(walletKey))
        val context = context(profile)
        requireNoOwnedChannel(context.ledger, walletKey)
        val tagBytes = newTag()
        val tag = try {
            require(tagBytes.size == TAG_BYTES)
            tagBytes.hex()
        } finally {
            tagBytes.fill(0)
        }
        val datum = ChannelDatum(
            MAINNET.validatorHashHex,
            ChannelConstants(tag, walletKey, context.adaptorKey, context.closePeriodMillis),
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
        val change = summary.outputs.singleOrNull { it.address == profile.paymentAddress }?.lovelace ?: Lovelace(0)
        val reserve = Lovelace(KONDUIT_MIN_ADA_BUFFER)
        val capacity = amount - reserve
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
                ChannelAction.Open(amount.value),
                preparedAtEpochMillis = nowEpochMillis(),
                payload = payload,
                keytag = ProtocolKeytag.from(walletKey, ProtocolTag(tag), TAG_BYTES).value,
                resultingSpendableBalance = capacity,
            ),
            amount,
            summary.fee,
            unsigned.feeBound,
            change,
            engine.minimumAdaForOutput(unsigned.cbor, context.ledger.protocolParametersJson, channelIndex),
            reserve,
            capacity,
            profile.network,
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
        return Context(ledger, info.channelParameters.adaptorKeyHex, period, references.first())
    }

    private fun validate(preview: ChannelPreview, profile: WalletProfile, context: Context) {
        val operation = preview.operation
        val intent = requireIntent(operation)
        val payload = operation.payload as ChannelPayload.Transaction
        require(preview.network == CardanoNetwork.MAINNET && profile.network == preview.network)
        require(operation.action == ChannelAction.Open(preview.amount.value))
        require(operation.state == io.riverark.ferret.core.model.OperationState.PROPOSED)
        require(operation.priorChannelIdentity == null)
        require(operation.intentHash == payload.expectedTransactionId)
        require(operation.resultingSpendableBalance == preview.resultingSpendableBalance)
        require(preview.protocolReserve == Lovelace(KONDUIT_MIN_ADA_BUFFER))
        require(preview.amount.value > KONDUIT_MIN_ADA_BUFFER)
        require(preview.resultingSpendableBalance == preview.amount - preview.protocolReserve)
        require(payload.feeBound == preview.feeBound)
        val unsigned = validateOperation(operation, profile, context)
        val summary = engine.inspect(unsigned.cbor)
        require(summary.fee == preview.actualFee)
        val channelIndex = channelOutputIndex(summary.outputs, intent)
        require(engine.minimumAdaForOutput(unsigned.cbor, context.ledger.protocolParametersJson, channelIndex) == preview.ledgerMinAda)
        require(intent.amount.value >= preview.ledgerMinAda.value)
        require((summary.outputs.singleOrNull { it.address == profile.paymentAddress }?.lovelace ?: Lovelace(0)) == preview.sourceChange)
    }

    private fun validateOperation(
        operation: PreparedChannelOperation,
        profile: WalletProfile,
        context: Context,
    ): UnsignedTransaction {
        val payload = operation.payload as? ChannelPayload.Transaction ?: error("channel transaction payload is required")
        val intent = requireIntent(operation)
        require(UUID.matches(operation.operationId) && intent.operationId == operation.operationId)
        require(operation.action == ChannelAction.Open(intent.amount.value))
        require(intent.amount.value > KONDUIT_MIN_ADA_BUFFER)
        require(intent.sourceAddress == profile.paymentAddress)
        require(intent.validatorAddress == MAINNET.validatorAddress)
        require(intent.referenceInput == context.reference)
        require(intent.datum.validatorHashHex == MAINNET.validatorHashHex)
        require(intent.datum.constants.adaptorVerificationKeyHex == context.adaptorKey)
        require(intent.datum.constants.closePeriodMillis == context.closePeriodMillis)
        require(intent.datum.stage == ChannelDatumStage.Opened(0))
        require(intent.validFrom < intent.validUntil && context.ledger.currentSlot < intent.validUntil)
        require(operation.keytag == ProtocolKeytag.from(
            intent.datum.constants.addVerificationKeyHex,
            ProtocolTag(intent.datum.constants.tagHex),
            TAG_BYTES,
        ).value)
        requireNoOwnedChannel(context.ledger, intent.datum.constants.addVerificationKeyHex)
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
        intent: CardanoIntent.OpenChannel,
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

    private fun requireIntent(operation: PreparedChannelOperation): CardanoIntent.OpenChannel =
        ((operation.payload as? ChannelPayload.Transaction)?.intent as? CardanoIntent.OpenChannel)
            ?: error("Open intent is required")

    private fun requireNoOwnedChannel(ledger: LedgerSnapshot, addVerificationKeyHex: String) {
        require(ledger.utxos.none { utxo ->
            utxo.address == MAINNET.validatorAddress && utxo.datumHex?.let { datum ->
                runCatching { engine.decodeChannelDatum(datum) }.getOrNull()
                    ?.constants?.addVerificationKeyHex == addVerificationKeyHex
            } == true
        }) { "wallet already has a channel" }
    }

    private fun channelOutputIndex(
        outputs: List<io.riverark.ferret.core.cardano.TransactionOutputSummary>,
        intent: CardanoIntent.OpenChannel,
    ): Int = outputs.indexOfFirst {
        it.address == intent.validatorAddress && it.lovelace == intent.amount &&
            it.assets.isEmpty() && it.datum is TransactionDatum.Inline && it.scriptReference == null
    }.also { require(it >= 0) }

    private data class Context(
        val ledger: LedgerSnapshot,
        val adaptorKey: String,
        val closePeriodMillis: Long,
        val reference: io.riverark.ferret.core.cardano.LedgerUtxo,
    )

    private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    private companion object {
        const val TAG_BYTES = 32
        const val VALIDITY_SLOTS = 3_600L
        val KEY = Regex("[0-9a-f]{64}")
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

