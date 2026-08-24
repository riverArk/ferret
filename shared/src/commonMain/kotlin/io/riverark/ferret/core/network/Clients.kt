package io.riverark.ferret.core.network

import io.riverark.ferret.core.model.Lovelace
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

@Serializable data class HealthDto(val status: String)
@Serializable data class NetworkDto(val network: String)
@Serializable data class ProtocolParametersDto(val era: String, val epoch: Long, val slot: Long, val payload: JsonObject)
@Serializable data class SubmitRequest(@SerialName("operation_id") val operationId: String, @SerialName("transaction") val signedCborHex: String)
@Serializable data class SubmitResponse(@SerialName("transaction_id") val transactionId: String)
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
    suspend fun balance(address: String): Lovelace = getOnce<JsonArray>("/utxos_at/${path(address)}").lovelaceBalance()
    suspend fun utxos(address: String): String = getOnce("/utxos_at/${path(address)}")
    suspend fun transactions(address: String): String = getOnce("/transactions/${path(address)}")
    suspend fun claim(request: SessionClaimRequest): SessionClaimResponse = postOnce("/session/claim", request)
    suspend fun submit(request: SubmitRequest, lease: String): SubmitResponse = postOnce("/submit", request, lease)

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

private fun path(value: String): String {
    require(value.all { it.isLetterOrDigit() || it == '_' })
    return value
}
