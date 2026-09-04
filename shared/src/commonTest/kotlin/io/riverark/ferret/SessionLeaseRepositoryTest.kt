package io.riverark.ferret

import io.riverark.ferret.core.backup.BackupCheckpointV1
import io.riverark.ferret.core.channel.ProtocolSigner
import io.riverark.ferret.core.channel.SessionLeaseRepository
import io.riverark.ferret.core.network.SessionClaimRequest
import io.riverark.ferret.core.network.SessionClaimResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SessionLeaseRepositoryTest {
    @Test fun claimsTheVerifiedCheckpointForOneStableDevice() = runBlocking {
        var now = 100L
        val requests = mutableListOf<SessionClaimRequest>()
        val signedMessages = mutableListOf<String>()
        val repository = SessionLeaseRepository(
            claimSession = { request ->
                requests += request
                SessionClaimResponse("ee".repeat(32), now + 10)
            },
            adaptorVerificationKeyHex = "aa".repeat(32),
            signer = object : ProtocolSigner {
                override suspend fun verificationKeyHex() = "bb".repeat(32)
                override suspend fun sign(message: ByteArray) = ByteArray(64) { 0xcc.toByte() }.also {
                    signedMessages += message.decodeToString()
                }
            },
            devicePublicKeyHex = "dd".repeat(32),
            nowEpochMillis = { now },
        )
        val checkpoint = BackupCheckpointV1(
            generation = 2,
            sequence = 3,
            ciphertextHash = ByteArray(32) { 1 },
            channelSnapshot = byteArrayOf(1),
        )

        val first = repository.claim(checkpoint)
        assertSame(first, repository.claim(checkpoint))
        assertEquals(1, requests.size)
        assertEquals("bb".repeat(32), requests.single().walletVerificationKeyHex)
        assertEquals(2, requests.single().generation)
        assertEquals("01".repeat(32), requests.single().backupHashHex)
        assertEquals("dd".repeat(32), requests.single().devicePublicKeyHex)
        assertEquals("aa".repeat(32) + "\n2\n" + "01".repeat(32) + "\n" + "dd".repeat(32) + "\n100", signedMessages.single())

        now = 110
        repository.claim(checkpoint)
        assertEquals(listOf(100L, 110L), requests.map(SessionClaimRequest::timestamp))
    }
}
