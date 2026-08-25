package io.riverark.ferret.core.backup

import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.security.AndroidBackupCrypto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DriveBackupRepositoryTest {
    @Test fun roundTripsAndVerifiesGenerationsBeforeDeletion() = runBlocking {
        val drive = FakeDrive()
        val crypto = AndroidBackupCrypto()
        val repository = DriveBackupRepository(drive, crypto)
        val walletId = WalletId("preprod-" + "00".repeat(28))
        val seed = ByteArray(32) { it.toByte() }
        val first = repository.write(walletId, seed, 1, 1, ByteArray(32), 1, "write-ahead".encodeToByteArray())
        val second = repository.write(walletId, seed, 1, 2, crypto.sha256(first.ciphertext), 2, "terminal".encodeToByteArray())
        val takeover = repository.write(walletId, seed, 2, 1, ByteArray(32), 3, "takeover".encodeToByteArray())

        assertContentEquals("takeover".encodeToByteArray(), repository.decrypt(seed, repository.verifyChain(repository.discover(walletId, seed))))
        assertTrue(repository.verifyChain(listOf(first, second, takeover)).ciphertext.contentEquals(takeover.ciphertext))
        assertFails { repository.verifyChain(listOf(first, second, second.copy(ciphertext = second.ciphertext + 1))) }
        assertFails { repository.decrypt(seed, takeover.copy(ciphertext = takeover.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })) }

        repository.deleteAll(walletId, seed)
        assertTrue(drive.list("ferret-").isEmpty())
        seed.fill(0)
    }

    private class FakeDrive : DriveAppDataClient {
        private val files = mutableMapOf<String, ByteArray>()
        override suspend fun list(prefix: String) = files.keys.filter { it.startsWith(prefix) }.map { DriveObject(it, 0) }
        override suspend fun put(name: String, bytes: ByteArray) { files[name] = bytes.copyOf() }
        override suspend fun get(name: String) = files.getValue(name).copyOf()
        override suspend fun delete(name: String) { files.remove(name)?.fill(0) }
    }
}
