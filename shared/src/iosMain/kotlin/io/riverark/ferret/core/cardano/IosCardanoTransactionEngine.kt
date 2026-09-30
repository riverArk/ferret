@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.riverark.ferret.core.cardano

import io.riverark.ferret.core.channel.ProtocolCrypto
import io.riverark.ferret.core.channel.ProtocolSigner
import io.riverark.ferret.core.model.AssetCatalog
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.network.EvaluationResponse
import io.riverark.ferret.core.security.IosBackupCrypto
import io.riverark.ferret.core.security.IosCrypto
import io.riverark.ferret.core.security.SecureVault
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr as cPointer
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.cardano.FerretBuffer
import cnames.structs.FerretBuild
import platform.cardano.FerretBytes
import platform.cardano.ferret_authorize
import platform.cardano.ferret_buffer_free
import platform.cardano.ferret_build_begin
import platform.cardano.ferret_build_free
import platform.cardano.ferret_build_next
import platform.cardano.ferret_decode_datum
import platform.cardano.ferret_derive
import platform.cardano.ferret_inspect
import platform.cardano.ferret_minimum_ada
import platform.cardano.ferret_protocol_key
import platform.cardano.ferret_protocol_sign
import platform.cardano.ferret_protocol_verify
import platform.cardano.ferret_sign
import platform.cardano.ferret_transaction_id

private val bridgeJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
private const val BRIDGE_FAILURE = "Invalid Cardano transaction."

@Serializable
private data class BuildRequest(val intent: CardanoIntent, val ledger: LedgerSnapshot, val assets: List<io.riverark.ferret.core.model.ChannelAsset>)

@Serializable
private data class AuthorizationRequest(
    val intent: CardanoIntent,
    val ledger: LedgerSnapshot,
    val assets: List<io.riverark.ferret.core.model.ChannelAsset>,
    val operationId: String,
    val feeBound: Lovelace,
)

@Serializable
private data class BuildResult(
    val kind: String,
    val cborHex: String,
    val operationId: String? = null,
    val feeBound: Lovelace? = null,
)

/** The native bridge owns all Cardano-specific serialization and policy checks; this boundary never submits a transaction. */
class IosCardanoTransactionEngine(
    private val assets: AssetCatalog,
    private val evaluate: suspend (CardanoNetwork, ByteArray) -> EvaluationResponse,
) : CardanoTransactionEngine {
    override suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet {
        require(entropy.size == 32)
        val result = bridgeCall { out, error ->
            withBytes(entropy) { bytes -> ferret_derive(bytes, network.nativeId, out, error) }
        }
        return bridgeJson.decodeFromString(result.decodeToString())
    }

    override suspend fun buildSweep(intent: CardanoIntent.SweepWallet, ledger: LedgerSnapshot): UnsignedTransaction =
        build(intent, ledger)

    override suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot, evaluationSeed: ByteArray?): UnsignedTransaction {
        require(ledger.currentSlot <= intent.validFrom && intent.validFrom < intent.validUntil)
        require((intent is CardanoIntent.AddChannelFunds) == (evaluationSeed != null)) {
            "Evaluation entropy is required only for Add."
        }
        require(evaluationSeed == null || evaluationSeed.size == 32)
        val selected = when (intent) {
            is CardanoIntent.Transfer -> intent.amount.asset
            is CardanoIntent.OpenChannel -> intent.amount.asset
            is CardanoIntent.AddChannelFunds -> intent.amount.asset
            else -> null
        }
        selected?.let(assets::requireAsset)
        val request = bridgeJson.encodeToString(BuildRequest(intent, ledger, assets.assets)).encodeToByteArray()
        val handle = memScoped {
            val out = alloc<CPointerVar<FerretBuild>>()
            val error = alloc<FerretBuffer>()
            out.value = null
            error.ptr = null
            error.len = 0u
            try {
                val status = withBytes(request) { ferret_build_begin(it, out.cPointer, error.cPointer) }
                if (status != 0) throw bridgeError(status)
                requireNotNull(out.value) { BRIDGE_FAILURE }
            } catch (failure: Throwable) {
                out.value?.let(::ferret_build_free)
                throw failure
            } finally {
                ferret_buffer_free(error.readValue())
            }
        }
        try {
            val empty = ByteArray(0)
            var response = empty
            var rounds = 0
            while (true) {
                require(++rounds <= 16) { "Cardano fee evaluation did not converge." }
                val outcome = bridgeCall { out, error ->
                    withBytes(response) { evaluation ->
                        withBytes(evaluationSeed ?: empty) { seed ->
                            ferret_build_next(handle, evaluation, seed, out, error)
                        }
                    }
                }
                val next = bridgeJson.decodeFromString<BuildResult>(outcome.decodeToString())
                val candidate = next.cborHex.decodeHex()
                when (next.kind) {
                    "evaluate" -> {
                        require(next.operationId == null && next.feeBound == null && candidate.isNotEmpty())
                        val expectedId = transactionId(candidate)
                        val evaluated = evaluate(ledger.network, candidate)
                        require(evaluated.transactionId == expectedId) { "invalid channel evaluation" }
                        val declared = inspect(candidate).redeemers.map { it.purpose.lowercase() to it.index }.toSet()
                        require(evaluated.redeemers.map { it.purpose to it.index.toLong() }.toSet() == declared) {
                            "invalid channel evaluation"
                        }
                        response = bridgeJson.encodeToString(evaluated).encodeToByteArray()
                    }
                    "complete" -> {
                        val bound = requireNotNull(next.feeBound)
                        require(next.operationId == intent.operationId && candidate.isNotEmpty())
                        val unsigned = UnsignedTransaction(candidate, intent.operationId, bound)
                        val exactIntent = if (intent is CardanoIntent.SweepWallet) {
                            intent.copy(amount = inspect(candidate).outputs.single {
                                it.address == intent.destinationAddress
                            }.lovelace)
                        } else intent
                        requireAuthorized(unsigned, exactIntent, ledger)
                        return unsigned
                    }
                    else -> throw IllegalArgumentException(BRIDGE_FAILURE)
                }
            }
        } finally {
            ferret_build_free(handle)
        }
    }

    override fun requireMinimumAda(cbor: ByteArray, protocolParametersJson: String) {
        val summary = inspect(cbor)
        summary.outputs.forEachIndexed { index, output ->
            require(output.lovelace.value >= minimumAdaForOutput(cbor, protocolParametersJson, index).value) {
                "output below minimum ADA"
            }
        }
        summary.collateralReturn?.let { output ->
            require(output.lovelace.value >= minimumAdaForOutput(cbor, protocolParametersJson, -1).value) {
                "collateral return below minimum ADA"
            }
        }
    }

    override fun minimumAdaForOutput(cbor: ByteArray, protocolParametersJson: String, outputIndex: Int): Lovelace {
        require(outputIndex >= 0 || outputIndex == -1)
        val protocol = protocolParametersJson.encodeToByteArray()
        val result = bridgeCall { out, error ->
            withBytes(cbor) { transaction ->
                withBytes(protocol) { parameters ->
                    ferret_minimum_ada(transaction, parameters, outputIndex.toUInt(), out, error)
                }
            }
        }
        return Lovelace(result.decodeToString().toLong())
    }

    override fun decodeChannelDatum(cborHex: String): ChannelDatum {
        val encoded = cborHex.encodeToByteArray()
        val catalog = bridgeJson.encodeToString(assets.assets).encodeToByteArray()
        val result = bridgeCall { out, error ->
            withBytes(encoded) { datum ->
                withBytes(catalog) { entries -> ferret_decode_datum(datum, entries, out, error) }
            }
        }
        return bridgeJson.decodeFromString(result.decodeToString())
    }

    override fun requireAuthorized(unsigned: UnsignedTransaction, intent: CardanoIntent, ledger: LedgerSnapshot) {
        authorize(unsigned.cbor, unsigned.operationId, unsigned.feeBound, intent, ledger, signed = false)
    }

    override fun sign(unsigned: UnsignedTransaction, seed: ByteArray, intent: CardanoIntent, ledger: LedgerSnapshot): SignedTransaction {
        require(seed.size == 32)
        requireAuthorized(unsigned, intent, ledger)
        val request = authorizationRequest(intent, ledger, unsigned.operationId, unsigned.feeBound)
        val originalId = transactionId(unsigned.cbor)
        val cbor = bridgeCall { out, error ->
            withBytes(unsigned.cbor) { transaction ->
                withBytes(seed) { entropy ->
                    withBytes(request) { metadata -> ferret_sign(transaction, entropy, metadata, out, error) }
                }
            }
        }
        try {
            require(transactionId(cbor) == originalId) { "Signing changed transaction body." }
            authorize(cbor, unsigned.operationId, unsigned.feeBound, intent, ledger, signed = true)
            return SignedTransaction(cbor)
        } catch (failure: Throwable) {
            cbor.fill(0)
            throw failure
        }
    }

    private fun authorize(cbor: ByteArray, operationId: String, feeBound: Lovelace, intent: CardanoIntent, ledger: LedgerSnapshot, signed: Boolean) {
        require(intent.operationId == operationId)
        if (!signed) {
            val request = authorizationRequest(intent, ledger, operationId, feeBound)
            bridgeCall { out, error ->
                withBytes(cbor) { transaction ->
                    withBytes(request) { metadata -> ferret_authorize(transaction, metadata, out, error) }
                }
            }.also { require(it.isEmpty()) }
        }
        val summary = inspect(cbor)
        require(ledger.network == summary.network)
        require(summary.fee.value <= feeBound.value && summary.prohibitedBodyFields.isEmpty())
        require(summary.containsNonKeyWitnesses == (intent is CardanoIntent.AddChannelFunds || intent is CardanoIntent.CloseChannel))
        require(summary.validityStart == intent.validFrom && summary.validityEnd == intent.validUntil)
        require(summary.keyWitnesses.size == if (signed) 1 else 0)
        if (intent is CardanoIntent.Transfer || intent is CardanoIntent.SweepWallet) {
            summary.requireMatches(intent, ledger.network, feeBound)
            summary.requireL1Funding(intent, ledger)
        } else {
            summary.requireChannelFunding(intent, ledger)
        }
        requireMinimumAda(cbor, ledger.protocolParametersJson)
    }

    private fun authorizationRequest(intent: CardanoIntent, ledger: LedgerSnapshot, operationId: String, feeBound: Lovelace): ByteArray =
        bridgeJson.encodeToString(AuthorizationRequest(intent, ledger, assets.assets, operationId, feeBound)).encodeToByteArray()

    override fun inspect(signedCbor: ByteArray): TransactionSummary =
        bridgeJson.decodeFromString(bridgeCall { out, error ->
            withBytes(signedCbor) { ferret_inspect(it, out, error) }
        }.decodeToString())

    override fun transactionId(signedCbor: ByteArray): String =
        bridgeCall { out, error -> withBytes(signedCbor) { ferret_transaction_id(it, out, error) } }
            .decodeToString().also { require(Regex("[0-9a-f]{64}").matches(it)) }
}

class IosProtocolSigner(
    private val vault: SecureVault,
    private val walletId: WalletId,
    private val network: CardanoNetwork,
) : ProtocolSigner {
    override suspend fun verificationKeyHex(): String = vault.withWalletSeed(walletId) { entropy ->
        require(entropy.size == 32)
        val key = bridgeCall { out, error ->
            withBytes(entropy) { ferret_protocol_key(it, network.nativeId, out, error) }
        }
        try {
            require(key.size == 32)
            key.toHex()
        } finally {
            key.fill(0)
        }
    }

    override suspend fun sign(message: ByteArray): ByteArray = vault.withWalletSeed(walletId) { entropy ->
        require(entropy.size == 32)
        bridgeCall { out, error ->
            withBytes(entropy) { seed ->
                withBytes(message) { bytes -> ferret_protocol_sign(seed, network.nativeId, bytes, out, error) }
            }
        }.also { require(it.size == 64) }
    }
}

class IosProtocolCrypto(private val crypto: IosCrypto) : ProtocolCrypto {
    private val backupCrypto = IosBackupCrypto(crypto)
    override fun sha256(input: ByteArray): ByteArray = backupCrypto.sha256(input)

    override fun verify(verificationKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(verificationKey.size == 32 && signature.size == 64)
        return bridgeCall { out, error ->
            withBytes(verificationKey) { key ->
                withBytes(message) { bytes ->
                    withBytes(signature) { signed -> ferret_protocol_verify(key, bytes, signed, out, error) }
                }
            }
        }.let { require(it.size == 1 && (it[0] == 0.toByte() || it[0] == 1.toByte())); it[0] == 1.toByte() }
    }
}

private val CardanoNetwork.nativeId: UInt get() = if (this == CardanoNetwork.MAINNET) 1u else 0u

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
private fun String.decodeHex(): ByteArray {
    require(length % 2 == 0 && all { it in "0123456789abcdef" })
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private inline fun <T> withBytes(bytes: ByteArray, action: (CValue<FerretBytes>) -> T): T = bytes.usePinned { pinned ->
    action(memScoped {
        alloc<FerretBytes> {
            ptr = if (bytes.isEmpty()) null else pinned.addressOf(0).reinterpret()
            len = bytes.size.toULong()
        }.readValue()
    })
}

private fun bridgeError(status: Int): IllegalArgumentException = when (status) {
    2 -> InsufficientFundsException()
    3 -> InsufficientCollateralException()
    else -> IllegalArgumentException(if (status == 1) "Invalid Cardano transaction or authorization." else BRIDGE_FAILURE)
}

private inline fun bridgeCall(call: (CPointer<FerretBuffer>, CPointer<FerretBuffer>) -> Int): ByteArray = memScoped {
    val result = alloc<FerretBuffer>()
    val error = alloc<FerretBuffer>()
    result.ptr = null
    result.len = 0u
    error.ptr = null
    error.len = 0u
    try {
        val status = call(result.cPointer, error.cPointer)
        if (status != 0) throw bridgeError(status)
        require(error.len == 0uL && error.ptr == null && result.len <= Int.MAX_VALUE.toULong()) { BRIDGE_FAILURE }
        result.ptr?.reinterpret<ByteVar>()?.readBytes(result.len.toInt()) ?: run {
            require(result.len == 0uL) { BRIDGE_FAILURE }
            ByteArray(0)
        }
    } finally {
        ferret_buffer_free(result.readValue())
        ferret_buffer_free(error.readValue())
    }
}
