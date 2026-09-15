package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelCollectionV3
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.PaymentJournalV2
import io.riverark.ferret.core.channel.PaymentQuote
import io.riverark.ferret.core.channel.PendingPayment
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.RemovalReadiness
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.WalletRemovalManager
import io.riverark.ferret.core.model.WalletRepository
import io.riverark.ferret.core.model.WalletRemovalRepository
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.model.blockers
import io.riverark.ferret.core.security.WalletEncryptedStateV1
import io.riverark.ferret.core.security.WalletSecretV1
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WalletRemovalTest {
    @Test fun removalRequiresClosedSettledEmptyWalletAndDeletesBackupFirst() = runBlocking {
        val profile = WalletProfile(WalletId("preprod-" + "00".repeat(28)), "Wallet", CardanoNetwork.PREPROD, "addr_test1wallet", "stake_test1wallet")
        val events = mutableListOf<String>()
        var readiness = readiness(profile, channels = collection(profile, ChannelState.Open("channel")))
        val repository = object : WalletRemovalRepository {
            override suspend fun readiness(walletId: WalletId) = readiness
            override suspend fun previewSweep(walletId: WalletId, destinationAddress: String) = error("not used")
            override suspend fun submitSweep(walletId: WalletId, preview: io.riverark.ferret.core.cardano.SweepPreview) = error("not used")
            override suspend fun deleteDriveBackup(walletId: WalletId) { events += "drive" }
        }
        val vault = FakeVault(profile) { events += "vault" }
        val manager = WalletRemovalManager(repository, vault, WalletRepository())

        assertFailsWith<IllegalArgumentException> { manager.remove(profile.id) }
        readiness = readiness(profile, spendable = 1)
        assertFailsWith<IllegalArgumentException> { manager.previewSweep(profile.id, "addr1wrongnetwork") }
        assertTrue(events.isEmpty())
        readiness = readiness(profile, depth = 2_159)
        assertFailsWith<IllegalArgumentException> { manager.remove(profile.id) }

        readiness = readiness(profile)
        manager.remove(profile.id)
        assertEquals(listOf("drive", "vault"), events)
        assertTrue(vault.profiles().isEmpty())
    }
    @Test fun deletionResumesAfterBackupFailureWithoutReopeningSafetyWindow() = runBlocking {
        val profile = WalletProfile(WalletId("preprod-" + "11".repeat(28)), "Wallet", CardanoNetwork.PREPROD, "addr_test1wallet", "stake_test1wallet")
        var readiness = readiness(profile)
        var failBackup = true
        var backupCalls = 0
        val repository = object : WalletRemovalRepository {
            override suspend fun readiness(walletId: WalletId) = readiness
            override suspend fun previewSweep(walletId: WalletId, destinationAddress: String) = error("not used")
            override suspend fun submitSweep(walletId: WalletId, preview: io.riverark.ferret.core.cardano.SweepPreview) = error("not used")
            override suspend fun deleteDriveBackup(walletId: WalletId) {
                backupCalls++
                if (failBackup) error("Drive unavailable")
            }
        }
        val vault = FakeVault(profile) {}
        val manager = WalletRemovalManager(repository, vault, WalletRepository())

        assertFailsWith<IllegalStateException> { manager.remove(profile.id) }
        readiness = readiness.copy(channels = collection(profile, ChannelState.Open("changed")), driveResolved = false)
        failBackup = false
        manager.remove(profile.id)

        assertEquals(2, backupCalls)
        assertTrue(vault.profiles().isEmpty())
    }

    @Test fun blockersAggregateEveryChannelAndAsset() {
        val profile = WalletProfile(
            WalletId("preprod-" + "22".repeat(28)),
            "Wallet",
            CardanoNetwork.PREPROD,
            "addr_test1wallet",
            "stake_test1wallet",
        )
        val adaKeytag = ProtocolKeytag("00".repeat(33))
        val nativeKeytag = ProtocolKeytag("11".repeat(33))
        val pending = PendingPayment(
            "operation",
            "22".repeat(32),
            PaymentQuote(
                "quote",
                nativeKeytag,
                AssetAmount(USDM, 1),
                1,
                AssetAmount(USDM, 0),
                AssetAmount(USDM, 0),
                1,
                "22".repeat(32),
                bindingVersion = 2,
            ),
            0,
        )
        val channels = ChannelCollectionV3(
            walletId = profile.id,
            catalogDigest = DIGEST,
            channels = mapOf(
                adaKeytag.value to ChannelSnapshot(adaKeytag, ADA, ChannelState.Closed),
                nativeKeytag.value to ChannelSnapshot(
                    nativeKeytag,
                    USDM,
                    ChannelState.Open("native"),
                    spendableBalance = AssetAmount(USDM, 1),
                    payments = PaymentJournalV2(pending),
                ),
            ),
            unresolvedLegacy = byteArrayOf(1),
        )
        val blockers = readiness(profile, channels = channels, depth = 2_159).copy(
            l1Assets = listOf(AssetAmount(ADA, 0), AssetAmount(USDM, 1)),
            unsupportedAssets = mapOf("unknown" to 1),
        ).blockers()

        assertContains(blockers, "Legacy channel recovery requires verified identity.")
        assertContains(blockers, "Close every channel first.")
        assertContains(blockers, "Empty every channel balance.")
        assertContains(blockers, "Wait for every pending channel operation to reconcile.")
        assertContains(blockers, "Move native assets before removing this wallet.")
        assertContains(blockers, "Wait for every transaction to settle.")
        assertContains(
            readiness(profile).copy(mutationDepths = emptyList()).blockers(),
            "Transaction finality evidence is unavailable.",
        )
    }


    private fun readiness(
        profile: WalletProfile,
        spendable: Long = 0,
        channels: ChannelCollectionV3 = collection(profile),
        depth: Long = 2_160,
    ) = RemovalReadiness(
        profile = profile,
        spendable = AssetAmount(ADA, spendable),
        l1Assets = listOf(AssetAmount(ADA, spendable)),
        unsupportedAssets = emptyMap(),
        channels = channels,
        pendingL1Operation = false,
        driveResolved = true,
        mutationDepths = listOf(depth),
    )

    private fun collection(profile: WalletProfile, state: ChannelState? = null): ChannelCollectionV3 {
        val channels = state?.let {
            val keytag = ProtocolKeytag("00".repeat(33))
            mapOf(keytag.value to ChannelSnapshot(keytag, ADA, it))
        }.orEmpty()
        return ChannelCollectionV3(walletId = profile.id, catalogDigest = DIGEST, channels = channels)
    }

    private companion object {
        const val DIGEST = "09ce40fc9bfd7b600506400417b4c09ba0ca2bd5b58703aeb00699c084d298ae"
        val USDM = ChannelAsset("usdm", "11".repeat(28), "", 6, AssetPricing.USD_PEG, DIGEST)
        val ADA = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, DIGEST)
    }

    private class FakeVault(profile: WalletProfile, private val onDelete: () -> Unit) : SecureVault {
        private var state = WalletEncryptedStateV1()
        private val profiles = mutableListOf(profile)
        override val isUnlocked = true
        override suspend fun unlock(wrappedDataKey: ByteArray) = Unit
        override fun lock() = Unit
        override suspend fun profiles() = profiles.toList()
        override suspend fun createWallet(profile: WalletProfile, secret: WalletSecretV1) = error("not used")
        override suspend fun updateProfile(profile: WalletProfile) = error("not used")
        override suspend fun renameWallet(walletId: WalletId, name: String) = error("not used")
        override suspend fun deleteWallet(walletId: WalletId) { onDelete(); profiles.removeAll { it.id == walletId } }
        override suspend fun <T> withWalletSeed(walletId: WalletId, action: suspend (ByteArray) -> T): T = error("not used")
        override suspend fun walletState(walletId: WalletId) = state
        override suspend fun updateWalletState(walletId: WalletId, state: WalletEncryptedStateV1) { this.state = state }
    }
}
