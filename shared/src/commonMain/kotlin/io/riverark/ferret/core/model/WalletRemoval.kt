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
