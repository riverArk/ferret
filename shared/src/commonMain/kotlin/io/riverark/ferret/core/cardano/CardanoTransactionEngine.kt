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
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(transactionId))
        require(index >= 0 && address.isNotBlank())
        require(assets.all { (unit, quantity) -> unit.length in 56..120 && unit.length % 2 == 0 && unit.all { it in "0123456789abcdef" } && quantity >= 0 })
        require(listOfNotNull(datumHex, scriptRefHex).all { it.length % 2 == 0 && it.all { char -> char in "0123456789abcdef" } })
    }
}
@Serializable data class LedgerSnapshot(val network: CardanoNetwork, val utxos: List<LedgerUtxo>, val protocolParametersJson: String, val currentSlot: Long)
@Serializable data class UnsignedTransaction(val cbor: ByteArray, val operationId: String, val feeBound: Lovelace)
@Serializable data class SignedTransaction(val cbor: ByteArray)
@Serializable data class TransactionOutputSummary(val address: String, val lovelace: Lovelace, val assets: Map<String, Long>)
@Serializable data class TransactionSummary(val network: CardanoNetwork, val outputs: List<TransactionOutputSummary>, val fee: Lovelace, val requiredSigners: Set<String>, val validityStart: Long?, val validityEnd: Long?)

@Serializable
sealed interface CardanoIntent {
    val sourceAddress: String
    val amount: Lovelace
    val operationId: String
    val validFrom: Long
    val validUntil: Long

    @Serializable data class Transfer(override val sourceAddress: String, val destinationAddress: String, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class OpenChannel(override val sourceAddress: String, val deploymentAddress: String, val datumHex: String, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class AddChannelFunds(override val sourceAddress: String, val channelInput: LedgerUtxo, val referenceInput: LedgerUtxo, val datumHex: String, val redeemerHex: String, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class CloseChannel(override val sourceAddress: String, val channelInput: LedgerUtxo, val referenceInput: LedgerUtxo, val redeemerHex: String, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
    @Serializable data class SweepWallet(override val sourceAddress: String, val destinationAddress: String, override val amount: Lovelace, override val operationId: String, override val validFrom: Long, override val validUntil: Long) : CardanoIntent
}

interface CardanoTransactionEngine {
    suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet
    suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot): UnsignedTransaction
    fun sign(unsigned: UnsignedTransaction, seed: ByteArray): SignedTransaction
    fun inspect(signedCbor: ByteArray): TransactionSummary
    fun transactionId(signedCbor: ByteArray): String
}

fun TransactionSummary.requireMatches(intent: CardanoIntent, network: CardanoNetwork, feeBound: Lovelace) {
    require(this.network == network)
    require(fee.value <= feeBound.value)
    require(validityStart != null && validityStart == intent.validFrom)
    require(validityEnd != null && validityEnd == intent.validUntil)
    val (destination, expectedAmount, expectedAssets) = when (intent) {
        is CardanoIntent.Transfer -> Triple(intent.destinationAddress, intent.amount, emptyMap())
        is CardanoIntent.SweepWallet -> Triple(intent.destinationAddress, intent.amount, emptyMap())
        is CardanoIntent.OpenChannel -> Triple(intent.deploymentAddress, intent.amount, emptyMap())
        is CardanoIntent.AddChannelFunds -> Triple(
            intent.channelInput.address,
            intent.channelInput.lovelace + intent.amount,
            intent.channelInput.assets,
        )
        is CardanoIntent.CloseChannel -> Triple(intent.sourceAddress, intent.amount, intent.channelInput.assets)
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
