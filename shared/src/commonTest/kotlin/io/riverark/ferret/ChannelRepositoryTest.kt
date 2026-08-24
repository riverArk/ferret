package io.riverark.ferret

import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelBackupProtocol
import io.riverark.ferret.core.channel.ChannelJournal
import io.riverark.ferret.core.channel.ChannelRemote
import io.riverark.ferret.core.channel.ChannelRepository
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.MutationResult
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.PendingOperation
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.model.WalletRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ChannelRepositoryTest {
    private val walletId = WalletId("preprod-${"0".repeat(56)}")

    @Test fun repositoryRejectsIllegalPaymentWithoutUi() {
        runBlocking {
        var snapshot = ChannelSnapshot(ChannelState.Absent)
        val repository = ChannelRepository(
            WalletRepository(),
            object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = snapshot
                override suspend fun persist(walletId: WalletId, snapshotValue: ChannelSnapshot) { snapshot = snapshotValue }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId) = Unit
                override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
                override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = Unit
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operationId: String, action: ChannelAction) = MutationResult(operationId, ChannelState.Absent)
                override suspend fun reconcile(walletId: WalletId, operation: PendingOperation) = null
            },
        )
        repository.load(walletId)
        assertFailsWith<IllegalArgumentException> {
            repository.mutate(walletId, "operation", "intent", ChannelAction.Pay("quote", "invoice"))
        }
        }
    }
}
