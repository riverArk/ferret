package io.riverark.ferret.core.cardano

import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import kotlinx.serialization.Serializable

@Serializable data class DerivedWallet(val paymentAddress: String, val stakeAddress: String, val paymentCredentialHex: String)
@Serializable
data class LedgerUtxo(
    val transactionId: String,
    val index: Int,
    val address: String,
    val lovelace: Lovelace,
    val assets: Map<String, Long> = emptyMap(),
    val datumHex: String? = null,
    val scriptRefHex: String? = null,
    val datumHashHex: String? = null,
    val scriptRefVersion: Int? = null,
    val scriptRefHashHex: String? = null,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(transactionId))
        require(index >= 0 && address.isNotBlank())
        require(assets.all { (unit, quantity) -> unit.length in 56..120 && unit.length % 2 == 0 && unit.all { it in "0123456789abcdef" } && quantity >= 0 })
        require(datumHashHex == null || Regex("[0-9a-f]{64}").matches(datumHashHex))
        require(scriptRefHashHex == null || Regex("[0-9a-f]{56}").matches(scriptRefHashHex))
        require(scriptRefVersion == null || scriptRefVersion in 0..3)
        require(scriptRefHex != null || scriptRefVersion == null)
        require(scriptRefHashHex != null || scriptRefVersion == null && scriptRefHex == null)
        require(listOfNotNull(datumHex, scriptRefHex).all { it.length % 2 == 0 && it.all { char -> char in "0123456789abcdef" } })
    }

    fun isSpendableBy(sourceAddress: String) =
        address == sourceAddress && assets.isEmpty() && datumHashHex == null && datumHex == null && scriptRefHashHex == null
}
@Serializable data class LedgerSnapshot(val network: CardanoNetwork, val utxos: List<LedgerUtxo>, val protocolParametersJson: String, val currentSlot: Long)
@Serializable data class UnsignedTransaction(val cbor: ByteArray, val operationId: String, val feeBound: Lovelace)
@Serializable data class SignedTransaction(val cbor: ByteArray)
@Serializable
data class SweepPreview(
    val destinationAddress: String,
    val amount: Lovelace,
    val fee: Lovelace,
    val intent: CardanoIntent.SweepWallet,
    val unsigned: UnsignedTransaction,
    val expectedTransactionId: String,
)

@Serializable
sealed interface TransactionDatum {
    @Serializable data object Absent : TransactionDatum
    @Serializable data class Hash(val hex: String) : TransactionDatum
    @Serializable data class Inline(val cborHex: String) : TransactionDatum
}

@Serializable
data class TransactionScriptReference(
    val language: Int,
    val cborHex: String,
    val hashHex: String,
)

@Serializable
data class TransactionOutputSummary(
    val address: String,
    val lovelace: Lovelace,
    val assets: Map<String, Long>,
    val datum: TransactionDatum = TransactionDatum.Absent,
    val scriptReference: TransactionScriptReference? = null,
)
@Serializable
data class TransactionInputReference(val transactionId: String, val index: Int) {
    init {
        require(Regex("[0-9a-f]{64}").matches(transactionId))
        require(index >= 0)
    }
}
@Serializable
data class TransactionKeyWitness(
    val verificationKeyHex: String,
    val keyHashHex: String,
    val signatureHex: String,
    val signatureValid: Boolean,
)

@Serializable
data class TransactionRedeemer(
    val purpose: String,
    val index: Long,
    val dataCborHex: String,
    val memory: Long,
    val steps: Long,
)

@Serializable
enum class ProhibitedBodyField {
    MINT, CERTIFICATES, WITHDRAWALS, UPDATE, GOVERNANCE, AUXILIARY_DATA, TREASURY, DONATION,
}

@Serializable
data class TransactionSummary(
    val network: CardanoNetwork,
    val outputs: List<TransactionOutputSummary>,
    val fee: Lovelace,
    val requiredSigners: Set<String>,
    val validityStart: Long?,
    val validityEnd: Long?,
    val inputs: List<TransactionInputReference>,
    val referenceInputs: List<TransactionInputReference> = emptyList(),
    val collateralInputs: List<TransactionInputReference> = emptyList(),
    val collateralReturn: TransactionOutputSummary? = null,
    val totalCollateral: Lovelace? = null,
    val keyWitnesses: List<TransactionKeyWitness> = emptyList(),
    val redeemers: List<TransactionRedeemer> = emptyList(),
    val scriptDataHashHex: String? = null,
    val prohibitedBodyFields: Set<ProhibitedBodyField> = emptySet(),
)

@Serializable
data class TransactionSemantics(
    val network: CardanoNetwork,
    val inputs: List<TransactionInputReference>,
    val referenceInputs: List<TransactionInputReference> = emptyList(),
    val collateralInputs: List<TransactionInputReference> = emptyList(),
    val outputs: List<TransactionOutputSummary>,
    val collateralReturn: TransactionOutputSummary? = null,
    val totalCollateral: Lovelace? = null,
    val requiredSignerHashes: Set<String> = emptySet(),
    val validityStart: Long?,
    val validityEnd: Long?,
    val redeemers: List<TransactionRedeemer> = emptyList(),
    val scriptDataHashHex: String? = null,
    val feeBound: Lovelace,
)

@Serializable
data class ChannelConstants(
    val tagHex: String,
    val addVerificationKeyHex: String,
    val adaptorVerificationKeyHex: String,
    val closePeriodMillis: Long,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(tagHex))
        require(Regex("[0-9a-f]{64}").matches(addVerificationKeyHex))
        require(Regex("[0-9a-f]{64}").matches(adaptorVerificationKeyHex))
        require(closePeriodMillis > 0)
    }
}

@Serializable
sealed interface ChannelDatumStage {
    val accountedAmount: Long
    val evidenceCborHex: List<String>

    @Serializable data class Opened(
        override val accountedAmount: Long,
        override val evidenceCborHex: List<String> = emptyList(),
    ) : ChannelDatumStage

    @Serializable data class Closed(
        override val accountedAmount: Long,
        override val evidenceCborHex: List<String> = emptyList(),
        val elapseAtEpochMillis: Long,
    ) : ChannelDatumStage

    @Serializable data class Responded(
        override val accountedAmount: Long,
        override val evidenceCborHex: List<String> = emptyList(),
    ) : ChannelDatumStage
}

@Serializable
data class ChannelDatum(
    val validatorHashHex: String,
    val constants: ChannelConstants,
    val stage: ChannelDatumStage,
) {
    init {
        require(Regex("[0-9a-f]{56}").matches(validatorHashHex))
        require(stage.accountedAmount >= 0)
        require(stage.evidenceCborHex.all { it.isNotEmpty() && it.length % 2 == 0 && it.all { char -> char in "0123456789abcdef" } })
    }
}

@Serializable enum class ChannelRedeemer { ADD, CLOSE, ELAPSE, END }
@Serializable enum class CloseChannelStep { CLOSE, ELAPSE, END }

@Serializable
sealed interface CardanoIntent {
    val sourceAddress: String
    val amount: Lovelace
    val operationId: String
    val validFrom: Long
    val validUntil: Long

    @Serializable data class Transfer(override val sourceAddress: String, val destinationAddress: String, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class OpenChannel(override val sourceAddress: String, val validatorAddress: String, val referenceInput: LedgerUtxo, val datum: ChannelDatum, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class AddChannelFunds(override val sourceAddress: String, val channelInput: LedgerUtxo, val referenceInput: LedgerUtxo, val currentDatum: ChannelDatum, val resultingDatum: ChannelDatum, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class CloseChannel(override val sourceAddress: String, val channelInput: LedgerUtxo, val referenceInput: LedgerUtxo, val currentDatum: ChannelDatum, val step: CloseChannelStep, val resultingDatum: ChannelDatum?, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class SweepWallet(override val sourceAddress: String, val destinationAddress: String, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
}

interface CardanoTransactionEngine {
    suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet
    suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot): UnsignedTransaction
    fun sign(unsigned: UnsignedTransaction, seed: ByteArray): SignedTransaction
    fun inspect(signedCbor: ByteArray): TransactionSummary
    fun transactionId(signedCbor: ByteArray): String
}

fun TransactionSummary.requireMatches(expected: TransactionSemantics) {
    require(network == expected.network)
    require(inputs == expected.inputs)
    require(referenceInputs == expected.referenceInputs)
    require(collateralInputs == expected.collateralInputs)
    require(outputs == expected.outputs)
    require(collateralReturn == expected.collateralReturn)
    require(totalCollateral == expected.totalCollateral)
    require(requiredSigners == expected.requiredSignerHashes)
    require(validityStart == expected.validityStart)
    require(validityEnd == expected.validityEnd)
    require(redeemers == expected.redeemers)
    require(scriptDataHashHex == expected.scriptDataHashHex)
    require(fee.value in 0..expected.feeBound.value)
    require(prohibitedBodyFields.isEmpty())
    require(keyWitnesses.all(TransactionKeyWitness::signatureValid))
    require(keyWitnesses.map(TransactionKeyWitness::keyHashHex).toSet().containsAll(expected.requiredSignerHashes))
}

fun TransactionSummary.requireMatches(intent: CardanoIntent, network: CardanoNetwork, feeBound: Lovelace) {
    require(this.network == network)
    require(fee.value <= feeBound.value)
    require(validityStart != null && validityStart == intent.validFrom)
    require(validityEnd != null && validityEnd == intent.validUntil)
    val (destination, expectedAmount, expectedAssets) = when (intent) {
        is CardanoIntent.Transfer -> Triple(intent.destinationAddress, intent.amount, emptyMap())
        is CardanoIntent.SweepWallet -> Triple(intent.destinationAddress, intent.amount, emptyMap())
        is CardanoIntent.OpenChannel -> Triple(intent.validatorAddress, intent.amount, emptyMap())
        is CardanoIntent.AddChannelFunds -> Triple(
            intent.channelInput.address,
            intent.channelInput.lovelace + intent.amount,
            intent.channelInput.assets,
        )
        is CardanoIntent.CloseChannel -> Triple(
            if (intent.step == CloseChannelStep.CLOSE) intent.channelInput.address else intent.sourceAddress,
            intent.amount,
            intent.channelInput.assets,
        )
    }
    require(outputs.count { it.address == destination && it.lovelace == expectedAmount && it.assets == expectedAssets } == 1)
    require(outputs.size in 1..2)
    val designated = outputs.indexOfFirst { it.address == destination && it.lovelace == expectedAmount && it.assets == expectedAssets }
    require(outputs.withIndex().all { (index, output) -> index == designated || output.address == intent.sourceAddress })
    if (intent is CardanoIntent.Transfer || intent is CardanoIntent.SweepWallet) {
        require(destination != intent.sourceAddress)
        require(outputs.all { it.assets.isEmpty() })
    }
}

fun TransactionSummary.requireL1Funding(intent: CardanoIntent, ledger: LedgerSnapshot) {
    require(intent is CardanoIntent.Transfer || intent is CardanoIntent.SweepWallet)
    require(intent.amount.value > 0)
    require(network == ledger.network)
    require(inputs.isNotEmpty() && inputs.distinct().size == inputs.size)

    val available = mutableMapOf<TransactionInputReference, LedgerUtxo>()
    ledger.utxos.forEach { utxo ->
        val reference = TransactionInputReference(utxo.transactionId, utxo.index)
        require(available.put(reference, utxo) == null) { "duplicate ledger input" }
    }
    val inputTotal = inputs.fold(Lovelace(0)) { total, reference ->
        val utxo = requireNotNull(available[reference]) { "unknown transaction input" }
        require(utxo.isSpendableBy(intent.sourceAddress))
        total + utxo.lovelace
    }
    val outputTotal = outputs.fold(fee) { total, output ->
        require(output.assets.isEmpty())
        total + output.lovelace
    }
    require(inputTotal == outputTotal)
}
