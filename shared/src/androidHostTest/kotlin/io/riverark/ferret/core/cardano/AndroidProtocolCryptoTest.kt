package io.riverark.ferret.core.cardano

import com.bloxbean.cardano.client.crypto.KeyGenUtil
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider
import io.riverark.ferret.core.channel.ChequeBodyWire
import io.riverark.ferret.core.channel.Hex32
import io.riverark.ferret.core.channel.ProtocolCheque
import io.riverark.ferret.core.channel.ProtocolDurationWire
import io.riverark.ferret.core.channel.ProtocolReceipt
import io.riverark.ferret.core.channel.ProtocolTag
import io.riverark.ferret.core.channel.SignedChequeWire
import io.riverark.ferret.core.channel.SignedSquashWire
import io.riverark.ferret.core.channel.SquashBodyWire
import java.io.File
import io.riverark.ferret.core.model.loadEmbeddedAssetCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AndroidProtocolCryptoTest {
    @Test fun loadsVerifiedEmbeddedAssetCatalog() = runBlocking {
        val catalog = loadEmbeddedAssetCatalog(AndroidProtocolCrypto) { path ->
            File("src/commonMain/composeResources", path).readBytes()
        }

        assertEquals("09ce40fc9bfd7b600506400417b4c09ba0ca2bd5b58703aeb00699c084d298ae", catalog.digest)
        assertEquals(listOf("ada", "usda", "usdcx", "usdm"), catalog.assets.map { it.alias })
        assertEquals(setOf("usda", "usdcx", "usdm"), catalog.presentations.keys)
    }
    @Test fun embeddedCatalogRejectsChangedPackageBytes() = runBlocking {
        val root = File("src/commonMain/composeResources")
        assertFailsWith<IllegalArgumentException> {
            loadEmbeddedAssetCatalog(AndroidProtocolCrypto) { path ->
                root.resolve(path).readBytes().let { if (path.endsWith("catalog.json")) it + byteArrayOf(0x20) else it }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            loadEmbeddedAssetCatalog(AndroidProtocolCrypto) { path ->
                root.resolve(path).readBytes().let { if (path.contains("asset_")) it + byteArrayOf(0) else it }
            }
        }
        Unit
    }


    @Test fun verifiesPinnedKonduitReceiptSignaturesAndRejectsMutation() {
        val privateKey = ByteArray(32) { it.toByte() }
        val publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(privateKey).toHex()
        val tag = ProtocolTag("ab".repeat(20))
        val squashBody = SquashBodyWire(5, 2, listOf(1))
        val squash = SignedSquashWire(squashBody, sign(privateKey, squashBody.taggedCbor(tag)))
        val lockedBody = ChequeBodyWire(3, 7, ProtocolDurationWire.fromMillis(4_005), Hex32("cd".repeat(32)))
        val locked = SignedChequeWire(lockedBody, sign(privateKey, lockedBody.taggedCbor(tag)))
        val receipt = ProtocolReceipt(squash, listOf(ProtocolCheque.Locked(locked)))

        receipt.requireValidSignatures(publicKey, tag, AndroidProtocolCrypto)
        assertFailsWith<IllegalArgumentException> {
            receipt.copy(squash = squash.copy(body = squashBody.copy(amount = 6)))
                .requireValidSignatures(publicKey, tag, AndroidProtocolCrypto)
        }

        val secret = "ef".repeat(32)
        val unlockedBody = lockedBody.copy(latch = Hex32(secret))
        val signedUnlocked = SignedChequeWire(
            unlockedBody,
            sign(privateKey, lockedBody.copy(latch = Hex32(AndroidProtocolCrypto.sha256(secret.hexBytes()).toHex())).taggedCbor(tag)),
        )
        ProtocolReceipt(squash, listOf(ProtocolCheque.Unlocked(signedUnlocked)))
            .requireValidSignatures(publicKey, tag, AndroidProtocolCrypto)
        assertFailsWith<IllegalArgumentException> {
            ProtocolReceipt(
                squash,
                listOf(ProtocolCheque.Unlocked(signedUnlocked.copy(body = unlockedBody.copy(latch = Hex32("00".repeat(32)))))),
            ).requireValidSignatures(publicKey, tag, AndroidProtocolCrypto)
        }
    }

    private fun sign(privateKey: ByteArray, message: ByteArray) =
        EdDSASigningProvider().sign(message, privateKey).toHex()
}

private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.toHex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
