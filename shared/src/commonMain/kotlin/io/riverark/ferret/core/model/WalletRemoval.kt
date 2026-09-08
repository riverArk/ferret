package io.riverark.ferret.core.model

import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.cardano.SweepPreview
import io.riverark.ferret.core.security.WalletRemovalState

data class RemovalReadiness(
    val profile: WalletProfile,
    val spendable: Lovelace,
    val pendingOperation: Boolean,
    val driveResolved: Boolean,
    val lastMutationDepth: Long,
    val hasNativeAssets: Boolean = false,
)

interface WalletRemovalRepository {
    suspend fun readiness(walletId: WalletId): RemovalReadiness
    suspend fun previewSweep(walletId: WalletId, destinationAddress: String): SweepPreview
    suspend fun submitSweep(walletId: WalletId, preview: SweepPreview): String
    suspend fun deleteDriveBackup(walletId: WalletId)
}

class DefaultWalletRemovalRepository(
    private val loadReadiness: suspend (WalletId) -> RemovalReadiness,
    private val previewer: suspend (WalletId, String) -> SweepPreview,
    private val submitter: suspend (WalletId, SweepPreview) -> String,
    private val deleteBackup: suspend (WalletId) -> Unit,
) : WalletRemovalRepository {
    override suspend fun readiness(walletId: WalletId) = loadReadiness(walletId)
    override suspend fun previewSweep(walletId: WalletId, destinationAddress: String): SweepPreview = previewer(walletId, destinationAddress)
    override suspend fun submitSweep(walletId: WalletId, preview: SweepPreview): String = submitter(walletId, preview)
    override suspend fun deleteDriveBackup(walletId: WalletId) = deleteBackup(walletId)
}

class WalletRemovalManager(
    private val repository: WalletRemovalRepository,
    private val vault: SecureVault,
    private val wallets: WalletRepository,
) {
    suspend fun readiness(walletId: WalletId) = repository.readiness(walletId)


    suspend fun previewSweep(walletId: WalletId, destinationAddress: String): SweepPreview {
        val readiness = repository.readiness(walletId)
        require(canStartRemoval(readiness))
        require(readiness.spendable.value > 0 && !readiness.hasNativeAssets)
        val expectedPrefix = if (readiness.profile.network == CardanoNetwork.MAINNET) "addr1" else "addr_test1"
        require(destinationAddress.startsWith(expectedPrefix)) { "cross-network sweep" }
        return repository.previewSweep(walletId, destinationAddress)
    }

    suspend fun submitSweep(walletId: WalletId, preview: SweepPreview): String {
        val readiness = repository.readiness(walletId)
        require(canStartRemoval(readiness) && !readiness.hasNativeAssets)
        return repository.submitSweep(walletId, preview)
    }

    suspend fun remove(walletId: WalletId) = wallets.withWalletLock(walletId) {
        var state = vault.walletState(walletId)
        try {
            if (state.removalState == WalletRemovalState.ACTIVE) {
                val readiness = repository.readiness(walletId)
                require(canStartRemoval(readiness))
                require(readiness.spendable.value == 0L) { "wallet must be swept" }
                require(!readiness.hasNativeAssets) { "wallet contains native assets" }
                require(readiness.lastMutationDepth >= 2160) { "wallet finality pending" }
                state = state.copy(removalState = WalletRemovalState.DELETING_BACKUP)
                vault.updateWalletState(walletId, state)
            }
            if (state.removalState == WalletRemovalState.DELETING_BACKUP) {
                repository.deleteDriveBackup(walletId)
                state = state.copy(removalState = WalletRemovalState.DELETING_VAULT)
                vault.updateWalletState(walletId, state)
            }
            vault.deleteWallet(walletId)
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
        }
    }


    private fun canStartRemoval(readiness: RemovalReadiness): Boolean =
        (readiness.profile.channelState == ChannelState.Absent || readiness.profile.channelState == ChannelState.Closed) &&
            !readiness.pendingOperation && readiness.driveResolved
}
fun RemovalReadiness.blockers(): List<String> = buildList {
    if (profile.channelState != ChannelState.Absent && profile.channelState != ChannelState.Closed) add("Close the channel first.")
    if (pendingOperation) add("Wait for the pending operation to reconcile.")
    if (hasNativeAssets) add("Move native assets before removing this wallet.")
    if (!driveResolved) add("Resolve or verify the encrypted Drive backup.")
    if (spendable.value > 0) add("Sweep the remaining balance.")
    if (spendable.value == 0L && lastMutationDepth < 2_160) add("Wait for the final transaction to settle.")
}
