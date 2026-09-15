package io.riverark.ferret.core.network

import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.cardano.LedgerSnapshot
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.channel.AdaptorPayRequest
import io.riverark.ferret.core.channel.Bolt11QuoteRequest
import io.riverark.ferret.core.channel.ProtocolCrypto
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.channel.ProtocolQuote
import io.riverark.ferret.core.channel.ProtocolReceipt
import io.riverark.ferret.core.channel.ProtocolSquashStatus
import io.riverark.ferret.core.channel.ProtocolTag
import io.riverark.ferret.core.channel.requireValidSignatures
import io.riverark.ferret.core.channel.SignedSquashWire
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.channel.WriterLease
import io.riverark.ferret.core.model.Realm
import io.riverark.ferret.core.model.TransactionRecord
import io.riverark.ferret.core.model.TransactionState
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.io.readByteArray

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
@Serializable
data class SubmitRequest(@SerialName("transaction") val signedCborHex: String) {
    init {
        require(signedCborHex.length in 2..1_048_576 && signedCborHex.length % 2 == 0)
        require(signedCborHex.all { it in "0123456789abcdef" })
    }
}
@Serializable data class SubmitResponse(@SerialName("transaction_id") val transactionId: String) {
    init { require(HEX_64.matches(transactionId)) }
}
@Serializable
data class L1SubmitRequest(
    @SerialName("operation_id") val operationId: String,
    @SerialName("expected_transaction_id") val expectedTransactionId: String,
    @SerialName("transaction") val signedCborHex: String,
) {
    init {
        require(UUID.matches(operationId))
        require(HEX_64.matches(expectedTransactionId))
        require(signedCborHex.length in 2..1_048_576 && signedCborHex.length % 2 == 0)
        require(signedCborHex.all { it in "0123456789abcdef" })
    }
}
@Serializable
data class EvaluationRedeemerDto(
    val purpose: String,
    val index: Int,
    val memory: Long,
    val steps: Long,
) {
    init {
        require(purpose in setOf("spend", "mint", "cert", "reward", "voting", "proposing"))
        require(index >= 0 && memory >= 0 && steps >= 0)
    }
}

@Serializable
data class EvaluationResponse(
    @SerialName("transaction_id") val transactionId: String,
    val redeemers: List<EvaluationRedeemerDto>,
) {
    init {
        require(HEX_64.matches(transactionId))
        require(redeemers.size <= 100)
        require(redeemers.map { it.purpose to it.index }.distinct().size == redeemers.size)
    }
}
@Serializable
data class L1OperationDto(
    @SerialName("operation_id") val operationId: String,
    @SerialName("expected_transaction_id") val expectedTransactionId: String,
    @SerialName("transaction_id") val transactionId: String? = null,
    val status: String,
    val depth: Long,
) {
    init {
        require(UUID.matches(operationId))
        require(HEX_64.matches(expectedTransactionId))
        require(transactionId == null || transactionId == expectedTransactionId)
        require(status in setOf("pending", "accepted", "confirmed", "settled", "rejected"))
        require(depth >= 0)
        require(when (status) {
            "pending", "rejected" -> depth == 0L
            "accepted" -> depth < 5
            "confirmed" -> depth in 5..2_159
            "settled" -> depth >= 2_160
            else -> false
        })
        require(status == "pending" || status == "rejected" || transactionId != null)
    }
}
@Serializable
data class ConnectorUtxoDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("output_index") val outputIndex: Int,
    val address: String,
    val value: List<ConnectorAssetDto>,
    @SerialName("consumed_by") val consumedBy: String? = null,
    @SerialName("datum_hash") val datumHash: String? = null,
    @SerialName("datum_inline") val datumInline: String? = null,
    @SerialName("reference_script_hash") val referenceScriptHash: String? = null,
    @SerialName("reference_script_version") val referenceScriptVersion: Int? = null,
    @SerialName("reference_script") val referenceScript: String? = null,
) {
    fun ledger(): LedgerUtxo {
        require(HEX_64.matches(transactionId))
        require(outputIndex >= 0 && address.length in 1..256)
        require(value.size in 1..100 && value.map(ConnectorAssetDto::unit).distinct().size == value.size)
        require(consumedBy == null || HEX_64.matches(consumedBy))
        require(datumHash == null || HEX_64.matches(datumHash))
        require(datumInline == null || datumInline.isBoundedHex(131_072))
        require(referenceScriptHash == null || Regex("[0-9a-f]{56}").matches(referenceScriptHash))
        require(referenceScriptVersion == null || referenceScriptVersion in 0..3)
        require(referenceScript == null || referenceScript.isBoundedHex(131_072))
        require(referenceScriptHash != null || referenceScriptVersion == null && referenceScript == null)
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
            referenceScript,
            datumHashHex = datumHash,
            scriptRefVersion = referenceScriptVersion,
            scriptRefHashHex = referenceScriptHash,
        )
    }
}
@Serializable
data class SessionClaimRequest(
    val walletVerificationKeyHex: String,
    val adaptorVerificationKeyHex: String,
    val generation: Long,
    val backupHashHex: String,
    val devicePublicKeyHex: String,
    val timestamp: Long,
    val signatureHex: String,
) {
    init {
        require(listOf(walletVerificationKeyHex, adaptorVerificationKeyHex, backupHashHex, devicePublicKeyHex).all(HEX_64::matches))
        require(generation >= 1 && timestamp >= 0)
        require(Regex("[0-9a-f]{128}").matches(signatureHex))
    }
}
@Serializable data class SessionClaimResponse(val lease: String, val expiresAtEpochMillis: Long) {
    init {
        require(Regex("[0-9a-f]{64}").matches(lease))
        require(expiresAtEpochMillis >= 0)
    }
}
@Serializable
data class AdaptorInfoDto(
    val tos: AdaptorTermsDto,
    @SerialName("channel_parameters") val channelParameters: AdaptorChannelParametersDto,
    @SerialName("tx_help") val transactionHelp: AdaptorTransactionHelpDto,
    @SerialName("asset_catalog_digest") val assetCatalogDigest: String? = null,
) {
    init { require(assetCatalogDigest == null || HEX_64.matches(assetCatalogDigest)) }
}
@Serializable data class AdaptorTermsDto(@SerialName("flat_fee") val flatFee: Long) {
    init { require(flatFee >= 0) }
}
@Serializable
data class AdaptorChannelParametersDto(
    @SerialName("adaptor_key") val adaptorKeyHex: String,
    @SerialName("close_period") val closePeriod: AdaptorClosePeriodDto,
    @SerialName("tag_length") val tagLength: Int,
) {
    init {
        require(HEX_64.matches(adaptorKeyHex))
        require(tagLength in 1..128)
    }
}
@Serializable data class AdaptorClosePeriodDto(val secs: Long, val nanos: Int) {
    init { require(secs > 0 && nanos in 0..999_999_999) }
}
@Serializable
data class AdaptorTransactionHelpDto(
    @SerialName("host_address") val hostAddress: String,
    val validator: String,
) {
    init {
        require(hostAddress.length in 1..256)
        require(Regex("[0-9a-f]{56}").matches(validator))
    }
}

class ConnectorClient(private val http: HttpClient, private val deployment: NetworkDeployment) {
    suspend fun health(): HealthDto = getOnce("/health")
    suspend fun network(): NetworkDto = getOnce("/network")
    suspend fun protocolParameters(): ProtocolParametersDto = getOnce("/protocol-parameters")
    suspend fun balance(address: String): Lovelace = utxos(address).filter { it.consumedBy == null }
        .fold(Lovelace(0)) { total, output -> total + output.ledger().lovelace }
    suspend fun utxos(address: String): List<ConnectorUtxoDto> = getOnce("/utxos_at/${path(address)}")
    suspend fun transactions(address: String, catalog: AssetCatalog): List<TransactionRecord> =
        getOnce<List<ConnectorTransactionDto>>("/transactions/${path(address)}").transactionRecords(address, catalog)
    suspend fun transaction(transactionId: String): ConnectorTransactionDto? {
        require(HEX_64.matches(transactionId))
        return getOnce<ConnectorTransactionDto?>("/transaction/$transactionId").validatedFor(transactionId)
    }
    suspend fun ledger(address: String, network: CardanoNetwork): LedgerSnapshot {
        val parameters = protocolParameters()
        return LedgerSnapshot(
            network,
            utxos(address).filter { it.consumedBy == null }.map(ConnectorUtxoDto::ledger),
            parameters.payload.toString(),
            parameters.slot,
        )
    }
    suspend fun evaluate(unsignedCborHex: String): EvaluationResponse =
        postOnce("/evaluate", SubmitRequest(unsignedCborHex))
    suspend fun submitL1(request: L1SubmitRequest): L1OperationDto = postOnce("/operations", request)
    suspend fun operation(operationId: String): L1OperationDto {
        require(UUID.matches(operationId))
        return getOnce("/operations/$operationId")
    }

    private suspend inline fun <reified T> getOnce(path: String): T = try {
        http.get(deployment.connector.value + path).boundedJsonBody()
    } catch (first: HttpRequestTimeoutException) {
        http.get(deployment.connector.value + path).boundedJsonBody()
    }

    private suspend inline fun <reified Request, reified Response> postOnce(path: String, request: Request, lease: String? = null): Response =
        http.post(deployment.connector.value + path) {
            contentType(ContentType.Application.Json)
            lease?.let { header("FERRET-SESSION", it) }
            setBody(request)
        }.boundedJsonBody()
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

internal fun List<ConnectorTransactionDto>.transactionRecords(
    address: String,
    catalog: AssetCatalog,
): List<TransactionRecord> =
    also { require(size <= 1_000) { "too many transactions" } }.
    map { transaction ->
        transaction.requireValid()
        val inputs = transaction.inputs.map { it.address to it.value.quantities() }
        val outputs = transaction.outputs.map { it.address to it.value.quantities() }
        val totalInput = inputs.map { it.second }.sumUnits()
        val totalOutput = outputs.map { it.second }.sumUnits()
        val walletInput = inputs.filter { it.first == address }.map { it.second }.sumUnits()
        val walletOutput = outputs.filter { it.first == address }.map { it.second }.sumUnits()
        require((walletInput.keys + walletOutput.keys).isNotEmpty()) { "transaction does not contain wallet address" }

        val ada = catalog.ada
        val incomingAda = walletOutput.units("lovelace") >= walletInput.units("lovelace")
        val feeUnits = if (incomingAda) 0 else {
            subtractUnits(totalInput.units("lovelace"), totalOutput.units("lovelace"))
        }
        val units = (walletInput.keys + walletOutput.keys + "lovelace").distinct().mapNotNull { unit ->
            val asset = catalog.assetForConnectorUnit(unit) ?: return@mapNotNull null
            val input = walletInput.units(unit)
            val output = walletOutput.units(unit)
            val delta = if (output >= input) output - input else subtractUnits(input, output)
            val amount = if (unit == "lovelace" && !incomingAda) subtractUnits(delta, feeUnits) else delta
            AssetAmount(asset, amount).takeIf { unit == "lovelace" || amount != 0L }
        }.sortedWith(compareBy<AssetAmount> { it.asset.policyId ?: "" }.thenBy { it.asset.assetName ?: "" })
        TransactionRecord(
            id = transaction.id,
            timestampEpochMillis = transaction.timestamp * 1_000,
            amounts = units,
            fee = AssetAmount(ada, feeUnits),
            realm = Realm.L1,
            state = when {
                transaction.depth < 5 -> TransactionState.PENDING
                transaction.depth < 2_160 -> TransactionState.CONFIRMED
                else -> TransactionState.SETTLED
            },
        )
    }.sortedWith(compareByDescending<TransactionRecord> { it.timestampEpochMillis }.thenByDescending { it.id })

internal fun ConnectorTransactionDto?.validatedFor(transactionId: String): ConnectorTransactionDto? = this?.also {
    it.requireValid()
    require(it.id == transactionId) { "transaction lookup returned a different transaction" }
}

internal fun ConnectorTransactionDto.requireValid() {
    require(HEX_64.matches(id)) { "invalid transaction id" }
    require(index >= 0 && depth >= 0) { "invalid transaction position" }
    require(timestamp in 0..Long.MAX_VALUE / 1_000) { "invalid transaction timestamp" }
    require(inputs.size <= 1_000 && outputs.size <= 1_000) { "transaction is too large" }
    require(listOfNotNull(invalidBefore, invalidAfter).all { it >= 0 }) { "invalid validity interval" }
    inputs.forEach {
        require(HEX_64.matches(it.transactionId) && it.outputIndex >= 0) { "invalid transaction input" }
    }
    (inputs.map { Triple(it.address, it.value, listOfNotNull(it.datumHash, it.datumInline, it.referenceScriptHash, it.consumedBy)) } +
        outputs.map { Triple(it.address, it.value, listOfNotNull(it.datumHash, it.datumInline, it.referenceScriptHash, it.consumedBy)) })
        .forEach { (outputAddress, value, hexFields) ->
            require(outputAddress.length in 1..256)
            require(value.size in 1..100 && value.map(ConnectorAssetDto::unit).distinct().size == value.size)
            require(hexFields.all { it.length in 2..2_097_152 && it.length % 2 == 0 && it.all { char -> char in "0123456789abcdef" } })
        }
}
private fun List<ConnectorAssetDto>.lovelace(): Lovelace {
    val quantity = singleOrNull { it.unit == "lovelace" }?.quantity
        ?: throw IllegalArgumentException("output must contain one lovelace value")
    require(Regex("(0|[1-9][0-9]*)").matches(quantity)) { "invalid lovelace quantity" }
    return Lovelace(quantity.toLong())
}

private fun List<ConnectorAssetDto>.quantities(): Map<String, Long> {
    require(map(ConnectorAssetDto::unit).distinct().size == size) { "duplicate asset unit" }
    require(any { it.unit == "lovelace" }) { "output must contain one lovelace value" }
    return associate { it.unit to it.quantity.toLong() }
}

private fun List<Map<String, Long>>.sumUnits(): Map<String, Long> {
    val result = mutableMapOf<String, Long>()
    forEach { values ->
        values.forEach { (unit, quantity) ->
            result[unit] = addUnits(result[unit] ?: 0L, quantity)
        }
    }
    return result
}

private fun Map<String, Long>.units(unit: String) = this[unit] ?: 0L

private fun addUnits(left: Long, right: Long): Long {
    require(left <= Long.MAX_VALUE - right) { "asset quantity overflow" }
    return left + right
}

private fun subtractUnits(left: Long, right: Long): Long {
    require(left >= right) { "negative asset quantity" }
    return left - right
}

class AdaptorClient(
    private val http: HttpClient,
    private val deployment: NetworkDeployment,
    private val crypto: ProtocolCrypto,
) {
    suspend fun info(): AdaptorInfoDto = http.get(deployment.adaptor.value + "/info").boundedJsonBody()
    suspend fun claim(request: SessionClaimRequest): SessionClaimResponse =
        http.post(deployment.adaptor.value + "/session/claim") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.boundedJsonBody()
    suspend fun receipt(keytag: ProtocolKeytag): ProtocolReceipt? =
        http.get(deployment.adaptor.value + "/ch/receipt") {
            header("KONDUIT", keytag.value)
        }.boundedJsonBody<ProtocolReceipt?>()?.also {
            val (verificationKey, tag) = keytag.signatureIdentity()
            it.requireValidSignatures(verificationKey, tag, crypto)
        }
    suspend fun quote(keytag: ProtocolKeytag, lease: String, invoice: String): ProtocolQuote =
        mutateJson("/ch/quote", keytag, lease, Bolt11QuoteRequest(invoice))
    suspend fun pay(keytag: ProtocolKeytag, lease: String, request: AdaptorPayRequest): ProtocolSquashStatus =
        mutateJson<AdaptorPayRequest, ProtocolSquashStatus>("/ch/pay", keytag, lease, request).verified(keytag)
    suspend fun squash(keytag: ProtocolKeytag, lease: String, request: SignedSquashWire): ProtocolSquashStatus =
        mutateJson<SignedSquashWire, ProtocolSquashStatus>("/ch/squash", keytag, lease, request).verified(keytag)
    suspend fun submitChannelOperation(
        keytag: ProtocolKeytag,
        writer: WriterLease,
        request: L1SubmitRequest,
    ): L1OperationDto = mutateJson("/ch/submit", keytag, writer.token, request)

    suspend fun channelOperation(
        keytag: ProtocolKeytag,
        writer: WriterLease,
        operationId: String,
    ): L1OperationDto? {
        require(UUID.matches(operationId) && HEX_64.matches(writer.token))
        val response = http.get(deployment.adaptor.value + "/ch/operations/$operationId") {
            expectSuccess = false
            header("KONDUIT", keytag.value)
            header("FERRET-SESSION", writer.token)
        }
        val bytes = response.boundedBody()
        return try {
            when (response.status.value) {
                200 -> decodeBoundedJson<L1OperationDto>(bytes)
                404 -> null
                else -> error("channel operation lookup failed")
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend inline fun <reified Request, reified Response> mutateJson(
        path: String,
        keytag: ProtocolKeytag,
        lease: String,
        request: Request,
    ): Response = post(path, keytag, lease, request).boundedJsonBody()

    private fun ProtocolSquashStatus.verified(keytag: ProtocolKeytag): ProtocolSquashStatus = apply {
        val (verificationKey, tag) = keytag.signatureIdentity()
        requireValidSignatures(verificationKey, tag, crypto)
    }

    private suspend inline fun <reified Request> post(
        path: String,
        keytag: ProtocolKeytag,
        lease: String,
        request: Request,
    ): HttpResponse {
        require(HEX_64.matches(lease))
        return http.post(deployment.adaptor.value + path) {
            contentType(ContentType.Application.Json)
            header("KONDUIT", keytag.value)
            header("FERRET-SESSION", lease)
            setBody(request)
        }
    }
}

private fun ProtocolKeytag.signatureIdentity() = value.take(64) to ProtocolTag(value.drop(64))

internal fun requireBoundedResponse(bytes: ByteArray) {
    require(bytes.size <= MAX_RESPONSE_BYTES) { "response too large" }
}

internal inline fun <reified T> decodeBoundedJson(bytes: ByteArray): T {
    requireBoundedResponse(bytes)
    return ferretJson.decodeFromString(bytes.decodeToString())
}

private suspend fun HttpResponse.boundedBody(): ByteArray =
    bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray().also(::requireBoundedResponse)

internal fun quoteRejectionMessage(status: Int, detail: String): String = when {
    "Bln:" in detail -> "The Lightning node could not find a payment route."
    "insufficient funds" in detail -> "The channel cannot cover this payment."
    "no receipt" in detail -> "The channel payment state is not initialized."
    "no retainer" in detail -> "The adaptor has not recognized the channel funding."
    "insufficient capacity" in detail -> "The channel has too many unresolved payments."
    "channel not active" in detail -> "The channel is not active."
    "verify failed" in detail -> "The adaptor rejected the channel authorization."
    "bad input" in detail -> "The adaptor rejected the channel payment state."
    "commitment:" in detail -> "The channel cannot cover this payment."
    "FX:" in detail -> "The adaptor could not price this payment."
    "unmediate:" in detail -> "The adaptor rejected the invoice format."
    else -> "Payment quote rejected by the adaptor (HTTP $status)."
}

internal suspend fun ResponseException.quoteRejectionMessage(): String {
    val bytes = response.boundedBody()
    return try {
        quoteRejectionMessage(response.status.value, bytes.decodeToString())
    } finally {
        bytes.fill(0)
    }
}

private suspend inline fun <reified T> HttpResponse.boundedJsonBody(): T {
    val bytes = boundedBody()
    return try {
        decodeBoundedJson(bytes)
    } finally {
        bytes.fill(0)
    }
}

internal suspend fun ResponseException.terminalPaymentFailureMessage(): String? {
    if (response.status.value != 400) return null
    val bytes = response.boundedBody()
    return try {
        val detail = bytes.decodeToString()
        when {
            "FAILURE_REASON_NO_ROUTE" in detail -> "No Lightning route was available."
            "FAILURE_REASON_TIMEOUT" in detail -> "The Lightning payment timed out."
            "FAILURE_REASON_INCORRECT_PAYMENT_DETAILS" in detail -> "The recipient rejected the payment details."
            "FAILURE_REASON_INSUFFICIENT_BALANCE" in detail -> "The Lightning node has insufficient outbound balance."
            "Payment failed:" in detail -> "The Lightning payment failed."
            else -> null
        }
    } finally {
        bytes.fill(0)
    }
}

private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val HEX_64 = Regex("[0-9a-f]{64}")

private fun String.isBoundedHex(maxLength: Int) =
    length <= maxLength && length % 2 == 0 && all { it in "0123456789abcdef" }

private fun path(value: String): String {
    require(value.all { it.isLetterOrDigit() || it == '_' })
    return value
}
