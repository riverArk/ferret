package io.riverark.ferret.core.channel

import io.riverark.ferret.core.backup.BackupCheckpointV1
import io.riverark.ferret.core.backup.WalletBackupCoordinator
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletOperationJournalV1
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VaultChannelJournal(
    private val vault: SecureVault,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : ChannelJournal {
    override suspend fun load(walletId: WalletId): ChannelSnapshot {
        val state = vault.walletState(walletId)
        if (state.operationJournal.isEmpty()) {
            return ChannelSnapshot(vault.profiles().single { it.id == walletId }.channelState)
        }
        val journal = try {
            json.decodeFromString<WalletOperationJournalV1>(state.operationJournal.decodeToString())
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
        }
        if (journal.channel.isEmpty()) {
            journal.l1.fill(0)
            return ChannelSnapshot(vault.profiles().single { it.id == walletId }.channelState)
        }
        return try {
            json.decodeFromString<ChannelSnapshot>(journal.channel.decodeToString())
        } finally {
            journal.l1.fill(0)
            journal.channel.fill(0)
        }
    }

    override suspend fun persist(walletId: WalletId, snapshot: ChannelSnapshot) {
        val state = vault.walletState(walletId)
        val journal = if (state.operationJournal.isEmpty()) WalletOperationJournalV1() else
            json.decodeFromString<WalletOperationJournalV1>(state.operationJournal.decodeToString())
        val channel = json.encodeToString(snapshot).encodeToByteArray()
        val encoded = json.encodeToString(journal.copy(channel = channel)).encodeToByteArray()
        require(encoded.size <= MAX_JOURNAL_BYTES)
        try {
            vault.updateWalletState(walletId, state.copy(operationJournal = encoded))
        } finally {
            state.channelRecovery.fill(0)
            state.operationJournal.fill(0)
            journal.l1.fill(0)
            journal.channel.fill(0)
            channel.fill(0)
            encoded.fill(0)
        }
    }

    private companion object {
        const val MAX_JOURNAL_BYTES = 1_048_576
    }
}

class DriveChannelBackupProtocol(
    private val backups: WalletBackupCoordinator,
    private val requireWriter: suspend (WalletId, BackupCheckpointV1) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : ChannelBackupProtocol {
    override suspend fun requireVerifiedWriter(walletId: WalletId) {
        requireWriter(walletId, requireNotNull(backups.checkpoint(walletId)) { "verified Drive backup is required" })
    }

    override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = write(walletId, snapshot)

    override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = write(walletId, snapshot)

    private suspend fun write(walletId: WalletId, snapshot: ChannelSnapshot) {
        val bytes = json.encodeToString(snapshot).encodeToByteArray()
        try {
            backups.writeNext(walletId, bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
