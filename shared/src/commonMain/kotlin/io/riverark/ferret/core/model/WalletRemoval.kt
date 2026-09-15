package io.riverark.ferret.core.model
import io.riverark.ferret.core.channel.ChannelCollectionV3

import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.cardano.SweepPreview
import io.riverark.ferret.core.security.WalletRemovalState

data class RemovalReadiness(
    val profile: WalletProfile,
    val spendable: AssetAmount,
    val l1Assets: List<AssetAmount>,
    val unsupportedAssets: Map<String, Long>,
    val channels: ChannelCollectionV3,
    val pendingL1Operation: Boolean,
    val driveResolved: Boolean,
    val mutationDepths: List<Long>,
) {
    init {
        require(spendable.asset.alias == "ada" && spendable.asset.policyId == null)
        require(l1Assets.map { it.asset.connectorUnit }.distinct().size == l1Assets.size)
        require(unsupportedAssets.values.all { it >= 0 })
        require(channels.walletId == profile.id)
        require(mutationDepths.all { it >= 0 })
    }

    val hasNativeAssets: Boolean
        get() = l1Assets.any { it.asset.policyId != null && it.baseUnits > 0 } ||
            unsupportedAssets.values.any { it > 0 }
}

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
        require(readiness.spendable.baseUnits > 0 && !readiness.hasNativeAssets)
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
                require(readiness.l1Assets.all { it.baseUnits == 0L } && readiness.unsupportedAssets.values.all { it == 0L }) {
                    "wallet must be swept"
                }
                require(readiness.mutationDepths.all { it >= 2_160 }) { "wallet finality pending" }
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
        readiness.channelBlockers().isEmpty() && !readiness.pendingL1Operation && readiness.driveResolved &&
            !readiness.hasNativeAssets && readiness.mutationDepths.isNotEmpty() && readiness.mutationDepths.all { it >= 2_160 }
}
fun RemovalReadiness.blockers(): List<String> = buildList {
    addAll(channelBlockers())
    if (pendingL1Operation) add("Wait for the pending wallet operation to reconcile.")
    if (hasNativeAssets) add("Move native assets before removing this wallet.")
    if (!driveResolved) add("Resolve or verify the encrypted Drive backup.")
    if (spendable.baseUnits > 0) add("Sweep the remaining ADA balance.")
    if (l1Assets.any { it.baseUnits > 0 } && spendable.baseUnits == 0L && !hasNativeAssets) {
        add("Resolve the remaining L1 holdings.")
    }
    if (mutationDepths.isEmpty()) add("Transaction finality evidence is unavailable.")
    else if (mutationDepths.any { it < 2_160 }) add("Wait for every transaction to settle.")
}

private fun RemovalReadiness.channelBlockers(): List<String> = buildList {
    if (channels.unresolvedLegacy.isNotEmpty()) add("Legacy channel recovery requires verified identity.")
    if (channels.channels.values.any { it.state != ChannelState.Closed }) add("Close every channel first.")
    if (channels.channels.values.any { it.spendableBalance.baseUnits > 0 }) add("Empty every channel balance.")
    if (channels.channels.values.any { it.pending != null || it.payments.pending != null }) {
        add("Wait for every pending channel operation to reconcile.")
    }
}
