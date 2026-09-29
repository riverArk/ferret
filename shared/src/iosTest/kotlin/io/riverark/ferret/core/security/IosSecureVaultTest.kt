package io.riverark.ferret.core.security

import io.riverark.ferret.core.backup.BackupCrypto
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosSecureVaultTest {
    @Test fun confirmationAndEncryptedStateSurviveLockButMissingIndexFailsClosed() = runBlocking {
        val directory = NSTemporaryDirectory() + "/ferret-vault-${NSUUID().UUIDString}"
        val manager = NSFileManager.defaultManager
        check(manager.createDirectoryAtPath(directory, true, null, null))
        try {
            val vault = IosSecureVault(RecordCrypto(), object : SecureRandomSource {
                override fun bytes(size: Int) = ByteArray(size) { 7 }
            }, directory)
            val key = ByteArray(32) { 42 }
            val seed = ByteArray(32) { it.toByte() }
            val profile = WalletProfile(WalletId("preprod-${"0".repeat(56)}"), "Test", CardanoNetwork.PREPROD,
                "test-address", "test-stake", recoveryPhraseConfirmed = false)
            vault.unlock(key)
            vault.createWallet(profile, WalletSecretV1(entropy = seed))
            vault.updateProfile(profile.copy(recoveryPhraseConfirmed = true))
            vault.updateWalletState(profile.id, WalletEncryptedStateV1(operationJournal = byteArrayOf(1, 2), backupGeneration = 3))
            vault.lock()
            assertFailsWith<IllegalStateException> { vault.profiles() }
            vault.unlock(key)
            assertTrue(vault.profiles().single().recoveryPhraseConfirmed)
            assertEquals(3, vault.walletState(profile.id).backupGeneration)
            assertTrue(vault.withWalletSeed(profile.id) { it.contentEquals(seed) })
            protectedAtomicWrite("$directory/wallet-${profile.id.value}.v1", ByteArray(28))
            assertFailsWith<IllegalArgumentException> { vault.walletState(profile.id) }
            assertTrue(vault.profiles().single().recoveryPhraseConfirmed)
            check(manager.removeItemAtPath("$directory/wallet-index.v1", null))
            assertFailsWith<IllegalStateException> { vault.profiles() }
            assertTrue(manager.fileExistsAtPath("$directory/wallet-${profile.id.value}.v1"))
            vault.lock()
            key.fill(0)
            seed.fill(0)
        } finally {
            check(manager.removeItemAtPath(directory, null))
        }
    }

    private class RecordCrypto : BackupCrypto {
        override fun random(size: Int) = ByteArray(size) { 7 }
        override fun sha256(input: ByteArray): ByteArray = error("Not used by vault")
        override fun hkdfSha256(input: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray = error("Not used by vault")
        override fun base64Url(input: ByteArray): String = error("Not used by vault")
        // Test-only record transform: real AES-GCM layout and tag handling are checked by CryptoKit XCTest.
        override fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
            val encoded = transform(plaintext, key, nonce)
            return try { encoded + ByteArray(16) { tag(encoded, nonce, it) } } finally { encoded.fill(0) }
        }
        override fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            require(ciphertext.size >= 16)
            val encoded = ciphertext.copyOfRange(0, ciphertext.size - 16)
            try {
                require(ciphertext.copyOfRange(encoded.size, ciphertext.size).contentEquals(ByteArray(16) { tag(encoded, nonce, it) }))
                return transform(encoded, key, nonce)
            } finally { encoded.fill(0) }
        }
        private fun transform(bytes: ByteArray, key: ByteArray, nonce: ByteArray) = ByteArray(bytes.size) {
            (bytes[it].toInt() xor key[it % key.size].toInt() xor nonce[it % nonce.size].toInt()).toByte()
        }
        private fun tag(bytes: ByteArray, nonce: ByteArray, position: Int) =
            (bytes.fold(0) { sum, byte -> sum + byte.toInt() } + nonce.sumOf { it.toInt() } + position).toByte()
    }
}
