package io.riverark.ferret.core.network

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
import kotlinx.serialization.json.JsonObject

@Serializable data class HealthDto(val status: String)
@Serializable data class NetworkDto(val network: String)
@Serializable data class ProtocolParametersDto(val era: String, val epoch: Long, val slot: Long, val payload: JsonObject)
@Serializable data class SubmitRequest(@SerialName("operation_id") val operationId: String, @SerialName("transaction") val signedCborHex: String)
@Serializable data class SubmitResponse(@SerialName("transaction_id") val transactionId: String)
@Serializable data class SessionClaimRequest(val walletVerificationKeyHex: String, val generation: Long, val backupHashHex: String, val devicePublicKeyHex: String, val timestamp: Long, val signatureHex: String)
@Serializable data class SessionClaimResponse(val lease: String, val expiresAtEpochMillis: Long)
@Serializable data class AdaptorInfoDto(val network: String, val identityHex: String, val lightningChain: String)

class ConnectorClient(private val http: HttpClient, private val deployment: NetworkDeployment) {
    suspend fun health(): HealthDto = getOnce("/health")
    suspend fun network(): NetworkDto = getOnce("/network")
    suspend fun protocolParameters(): ProtocolParametersDto = getOnce("/protocol-parameters")
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
