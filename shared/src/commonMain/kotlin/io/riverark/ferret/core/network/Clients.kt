package io.riverark.ferret.core.network

import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.TransactionState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable data class HealthDto(val status: String) {
    init { require(status.length in 1..32) }
}
@Serializable data class NetworkDto(val network: String) {
    init { require(network in setOf("preprod", "mainnet")) }
}
@Serializable data class ProtocolParametersDto(val era: String, val epoch: Long, val slot: Long, val payload: JsonObject) {
    init {
        require(era.length in 1..32 && epoch >= 0 && slot >= 0)
        require(payload.toString().length <= MAX_RESPONSE_BYTES)
    }
}
@Serializable
data class ConnectorTransactionDto(
    val id: String,
    val index: Long,
    val depth: Long,
    val timestamp: Long,
    @SerialName("invalid_before") val invalidBefore: Long? = null,
    @SerialName("invalid_after") val invalidAfter: Long? = null,
    val inputs: List<ConnectorInputDto>,
    val outputs: List<ConnectorOutputDto>,
)
@Serializable
data class ConnectorInputDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("output_index") val outputIndex: Long,
    val address: String,
    val value: List<ConnectorAssetDto>,
    @SerialName("datum_hash") val datumHash: String? = null,
    @SerialName("datum_inline") val datumInline: String? = null,
    @SerialName("reference_script_hash") val referenceScriptHash: String? = null,
    @SerialName("consumed_by") val consumedBy: String? = null,
)
@Serializable
data class ConnectorOutputDto(
    val address: String,
    val value: List<ConnectorAssetDto>,
    @SerialName("datum_hash") val datumHash: String? = null,
    @SerialName("datum_inline") val datumInline: String? = null,
    @SerialName("reference_script_hash") val referenceScriptHash: String? = null,
    @SerialName("consumed_by") val consumedBy: String? = null,
)
@Serializable data class ConnectorAssetDto(val unit: String, val quantity: String) {
    init {
        require(unit == "lovelace" || unit.length in 56..120 && unit.length % 2 == 0 && unit.all { it in "0123456789abcdef" })
        require(quantity.length in 1..20 && Regex("(0|[1-9][0-9]*)").matches(quantity))
    }
}
@Serializable data class SubmitRequest(@SerialName("operation_id") val operationId: String, @SerialName("transaction") val signedCborHex: String)
@Serializable data class SubmitResponse(@SerialName("transaction_id") val transactionId: String)
@Serializable
data class L1SubmitRequest(
    @SerialName("operation_id") val operationId: String,
    @SerialName("expected_transaction_id") val expectedTransactionId: String,
    @SerialName("transaction") val signedCborHex: String,
) {
    init {
        require(UUID.matches(operationId))
        require(HEX_64.matches(expectedTransactionId))
        require(signedCborHex.length in 2..2_097_152 && signedCborHex.length % 2 == 0)
        require(signedCborHex.all { it in "0123456789abcdef" })
    }
}
@Serializable
data class L1OperationDto(
    @SerialName("operation_id") val operationId: String,
    @SerialName("expected_transaction_id") val expectedTransactionId: String,
    @SerialName("transaction_id") val transactionId: String? = null,
    val state: String,
    val depth: Long? = null,
) {
    init {
        require(UUID.matches(operationId))
        require(HEX_64.matches(expectedTransactionId))
        require(transactionId == null || transactionId == expectedTransactionId)
        require(state in setOf("pending", "accepted", "confirmed", "settled", "rejected"))
        require(depth == null || depth >= 0)
    }
}
@Serializable
data class ConnectorUtxoDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("output_index") val outputIndex: Int,
    val address: String,
    val value: List<ConnectorAssetDto>,
    @SerialName("datum_inline") val datumInline: String? = null,
    @SerialName("reference_script_hash") val referenceScriptHash: String? = null,
) {
    fun ledger(): LedgerUtxo {
        require(HEX_64.matches(transactionId))
        require(outputIndex >= 0 && address.length in 1..256)
        require(value.size in 1..100 && value.map(ConnectorAssetDto::unit).distinct().size == value.size)
        val quantities = value.associate { asset ->
            require(asset.unit == "lovelace" || asset.unit.length in 56..120 && asset.unit.length % 2 == 0 && asset.unit.all { it in "0123456789abcdef" })
            require(Regex("(0|[1-9][0-9]*)").matches(asset.quantity))
            asset.unit to asset.quantity.toLong()
        }
        return LedgerUtxo(
            transactionId,
            outputIndex,
            address,
            Lovelace(quantities["lovelace"] ?: error("missing lovelace")),
            quantities - "lovelace",
            datumInline,
            referenceScriptHash,
        )
    }
}
@Serializable data class SessionClaimRequest(val walletVerificationKeyHex: String, val generation: Long, val backupHashHex: String, val devicePublicKeyHex: String, val timestamp: Long, val signatureHex: String)
@Serializable data class SessionClaimResponse(val lease: String, val expiresAtEpochMillis: Long)
@Serializable
data class AdaptorInfoDto(
    val tos: AdaptorTermsDto,
    @SerialName("channel_parameters") val channelParameters: AdaptorChannelParametersDto,
    @SerialName("tx_help") val transactionHelp: AdaptorTransactionHelpDto,
)
@Serializable data class AdaptorTermsDto(@SerialName("flat_fee") val flatFee: Long)
@Serializable
data class AdaptorChannelParametersDto(
    @SerialName("adaptor_key") val adaptorKeyHex: String,
    @SerialName("close_period") val closePeriod: AdaptorClosePeriodDto,
    @SerialName("tag_length") val tagLength: Int,
)
@Serializable data class AdaptorClosePeriodDto(val secs: Long, val nanos: Int)
@Serializable
data class AdaptorTransactionHelpDto(
    @SerialName("host_address") val hostAddress: String,
    val validator: String,
)

class ConnectorClient(private val http: HttpClient, private val deployment: NetworkDeployment) {
    suspend fun health(): HealthDto = getOnce("/health")
    suspend fun network(): NetworkDto = getOnce("/network")
    suspend fun protocolParameters(): ProtocolParametersDto = getOnce("/protocol-parameters")
    suspend fun balance(address: String): Lovelace = utxos(address).fold(Lovelace(0)) { total, output -> total + output.ledger().lovelace }
    suspend fun utxos(address: String): List<ConnectorUtxoDto> = getOnce("/utxos_at/${path(address)}")
    suspend fun transactions(address: String): List<TransactionRecord> =
        getOnce<List<ConnectorTransactionDto>>("/transactions/${path(address)}").transactionRecords(address)
    suspend fun claim(request: SessionClaimRequest): SessionClaimResponse = postOnce("/session/claim", request)
    suspend fun submit(request: SubmitRequest, lease: String): SubmitResponse = postOnce("/submit", request, lease)
    suspend fun ledger(address: String, network: CardanoNetwork): LedgerSnapshot {
        val parameters = protocolParameters()
        return LedgerSnapshot(network, utxos(address).map(ConnectorUtxoDto::ledger), parameters.payload.toString(), parameters.slot)
    }
    suspend fun submitL1(request: L1SubmitRequest): L1OperationDto = postOnce("/operations", request)
    suspend fun operation(operationId: String): L1OperationDto {
        require(UUID.matches(operationId))
        return getOnce("/operations/$operationId")
    }

    private suspend inline fun <reified T> getOnce(path: String): T = try {
        http.get(deployment.connector.value + path).body()
    } catch (first: HttpRequestTimeoutException) {
        http.get(deployment.connector.value + path).body()
    }

    private suspend inline fun <reified Request, reified Response> postOnce(path: String, request: Request, lease: String? = null): Response =
        http.post(deployment.connector.value + path) {
            contentType(ContentType.Application.Json)
            lease?.let { header("FERRET-SESSION", it) }
            setBody(request)
        }.body()
}
internal fun JsonArray.lovelaceBalance(): Lovelace =
    fold(Lovelace(0)) { total, output ->
        output.jsonObject.getValue("value").jsonArray
            .filter { it.jsonObject.getValue("unit").jsonPrimitive.content == "lovelace" }
            .fold(total) { balance, value ->
                val quantity = value.jsonObject.getValue("quantity").jsonPrimitive
                require(quantity.isString) { "lovelace quantity must be a string" }
                balance + Lovelace(quantity.content.toLong())
            }
    }

internal fun List<ConnectorTransactionDto>.transactionRecords(address: String): List<TransactionRecord> =
    also { require(size <= 1_000) { "too many transactions" } }.
    map { transaction ->
        require(Regex("[0-9a-f]{64}").matches(transaction.id)) { "invalid transaction id" }
        require(transaction.index >= 0 && transaction.depth >= 0) { "invalid transaction position" }
        require(transaction.timestamp in 0..Long.MAX_VALUE / 1_000) { "invalid transaction timestamp" }
        require(transaction.inputs.size <= 1_000 && transaction.outputs.size <= 1_000) { "transaction is too large" }
        require(listOfNotNull(transaction.invalidBefore, transaction.invalidAfter).all { it >= 0 }) { "invalid validity interval" }
        transaction.inputs.forEach {
            require(Regex("[0-9a-f]{64}").matches(it.transactionId) && it.outputIndex >= 0) { "invalid transaction input" }
        }
        (transaction.inputs.map { Triple(it.address, it.value, listOfNotNull(it.datumHash, it.datumInline, it.referenceScriptHash, it.consumedBy)) } +
            transaction.outputs.map { Triple(it.address, it.value, listOfNotNull(it.datumHash, it.datumInline, it.referenceScriptHash, it.consumedBy)) })
            .forEach { (outputAddress, value, hexFields) ->
                require(outputAddress.length in 1..256)
                require(value.size in 1..100 && value.map(ConnectorAssetDto::unit).distinct().size == value.size)
                require(hexFields.all { it.length in 2..2_097_152 && it.length % 2 == 0 && it.all { char -> char in "0123456789abcdef" } })
            }
        val inputs = transaction.inputs.map { it.address to it.value.lovelace() }
        val outputs = transaction.outputs.map { it.address to it.value.lovelace() }
        val totalInput = inputs.fold(Lovelace(0)) { total, (_, amount) -> total + amount }
        val totalOutput = outputs.fold(Lovelace(0)) { total, (_, amount) -> total + amount }
        val walletInput = inputs.filter { it.first == address }.fold(Lovelace(0)) { total, (_, amount) -> total + amount }
        val walletOutput = outputs.filter { it.first == address }.fold(Lovelace(0)) { total, (_, amount) -> total + amount }
        require(walletInput.value > 0 || walletOutput.value > 0) { "transaction does not contain wallet address" }
        val fee = totalInput - totalOutput
        val amount = if (walletOutput.value >= walletInput.value) {
            walletOutput - walletInput
        } else {
            (walletInput - walletOutput) - fee
        }
        TransactionRecord(
            id = transaction.id,
            timestampEpochMillis = transaction.timestamp * 1_000,
            amount = amount,
            fee = fee,
            realm = Realm.L1,
            state = when {
                transaction.depth < 5 -> TransactionState.PENDING
                transaction.depth < 2_160 -> TransactionState.CONFIRMED
                else -> TransactionState.SETTLED
            },
        )
    }.sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })

private fun List<ConnectorAssetDto>.lovelace(): Lovelace {
    val quantity = singleOrNull { it.unit == "lovelace" }?.quantity
        ?: throw IllegalArgumentException("output must contain one lovelace value")
    require(Regex("(0|[1-9][0-9]*)").matches(quantity)) { "invalid lovelace quantity" }
    return Lovelace(quantity.toLong())
}

class AdaptorClient(private val http: HttpClient, private val deployment: NetworkDeployment) {
    suspend fun info(): AdaptorInfoDto = http.get(deployment.adaptor.value + "/info").body()
    suspend fun receipt(keytag: String): String = http.get(deployment.adaptor.value + "/ch/receipt") { header("KONDUIT", keytag) }.body()
    suspend fun quote(keytag: String, lease: String, request: String): String = mutate("/ch/quote", keytag, lease, request)
    suspend fun pay(keytag: String, lease: String, request: String): String = mutate("/ch/pay", keytag, lease, request)
    suspend fun squash(keytag: String, lease: String, request: String): String = mutate("/ch/squash", keytag, lease, request)

    private suspend fun mutate(path: String, keytag: String, lease: String, request: String): String =
        http.post(deployment.adaptor.value + path) {
            contentType(ContentType.Application.Json)
            header("KONDUIT", keytag)
            header("FERRET-SESSION", lease)
            setBody(request)
        }.body<String>().also { require(it.length <= 1_048_576) { "response too large" } }
}

private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val HEX_64 = Regex("[0-9a-f]{64}")

private fun path(value: String): String {
    require(value.all { it.isLetterOrDigit() || it == '_' })
    return value
}
