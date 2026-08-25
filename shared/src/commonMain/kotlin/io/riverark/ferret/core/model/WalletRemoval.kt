package io.riverark.ferret.core.model

import io.riverark.ferret.core.security.SecureVault

data class RemovalReadiness(
    val profile: WalletProfile,
    val spendable: Lovelace,
    val pendingOperation: Boolean,
    val driveResolved: Boolean,
    val lastMutationDepth: Long,
)

interface WalletRemovalRepository {
    suspend fun readiness(walletId: WalletId): RemovalReadiness
    suspend fun sweep(walletId: WalletId, destinationAddress: String): String
    suspend fun deleteDriveBackup(walletId: WalletId)
}

class DefaultWalletRemovalRepository(
    private val loadReadiness: suspend (WalletId) -> RemovalReadiness,
    private val sweepWallet: suspend (WalletId, String) -> String,
    private val deleteBackup: suspend (WalletId) -> Unit,
) : WalletRemovalRepository {
    override suspend fun readiness(walletId: WalletId) = loadReadiness(walletId)
    override suspend fun sweep(walletId: WalletId, destinationAddress: String) = sweepWallet(walletId, destinationAddress)
    override suspend fun deleteDriveBackup(walletId: WalletId) = deleteBackup(walletId)
}

class WalletRemovalManager(
    private val repository: WalletRemovalRepository,
    private val vault: SecureVault,
) {
    suspend fun readiness(walletId: WalletId) = repository.readiness(walletId)

    suspend fun sweep(walletId: WalletId, destinationAddress: String): String {
        val readiness = repository.readiness(walletId)
        require(canStartRemoval(readiness))
        require(readiness.spendable.value > 0)
        val expectedPrefix = if (readiness.profile.network == CardanoNetwork.MAINNET) "addr1" else "addr_test1"
        require(destinationAddress.startsWith(expectedPrefix)) { "cross-network sweep" }
        return repository.sweep(walletId, destinationAddress)
    }

    suspend fun remove(walletId: WalletId) {
        val readiness = repository.readiness(walletId)
        require(canStartRemoval(readiness))
        require(readiness.spendable.value == 0L) { "wallet must be swept" }
        require(readiness.lastMutationDepth >= 2160) { "wallet finality pending" }
        repository.deleteDriveBackup(walletId)
        vault.deleteWallet(walletId)
    }


    private fun canStartRemoval(readiness: RemovalReadiness): Boolean =
        (readiness.profile.channelState == ChannelState.Absent || readiness.profile.channelState == ChannelState.Closed) &&
            !readiness.pendingOperation && readiness.driveResolved
}
fun RemovalReadiness.blockers(): List<String> = buildList {
    if (profile.channelState != ChannelState.Absent && profile.channelState != ChannelState.Closed) add("Close the channel first.")
    if (pendingOperation) add("Wait for the pending operation to reconcile.")
    if (!driveResolved) add("Resolve or verify the encrypted Drive backup.")
    if (spendable.value > 0) add("Sweep the remaining balance.")
    if (spendable.value == 0L && lastMutationDepth < 2_160) add("Wait for the final transaction to settle.")
}
