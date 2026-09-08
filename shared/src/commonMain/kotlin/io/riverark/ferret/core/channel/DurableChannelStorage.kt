package io.riverark.ferret.core.channel

import io.riverark.ferret.core.backup.BackupCheckpointV1
import io.riverark.ferret.core.backup.WalletBackupCoordinator
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.core.security.SecureVault
import io.riverark.ferret.core.security.WalletOperationJournalV1
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
data class ChannelRecoveryV2(
    val schema: Int = 2,
    val channel: ChannelSnapshot,
    val payment: PaymentJournalV1 = PaymentJournalV1(),
) {
    init {
        require(schema == 2)
    }
}

@kotlinx.serialization.Serializable
private data class LegacyChannelSnapshot(
    val state: io.riverark.ferret.core.model.ChannelState,
    val pending: io.riverark.ferret.core.model.PendingOperation? = null,
    val action: ChannelAction? = null,
)

class VaultChannelJournal(
    private val vault: SecureVault,
    private val payments: PaymentStore? = null,
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
            journal.payment.fill(0)
            return ChannelSnapshot(vault.profiles().single { it.id == walletId }.channelState)
        }
        return try {
            decodeSnapshot(journal.channel.decodeToString())
        } finally {
            journal.l1.fill(0)
            journal.channel.fill(0)
            journal.payment.fill(0)
        }
    }
    suspend fun backupSnapshot(walletId: WalletId): ByteArray =
        json.encodeToString(
            ChannelRecoveryV2(
                channel = load(walletId),
                payment = payments?.recovery(walletId) ?: PaymentJournalV1(),
            ),
        ).encodeToByteArray()

    suspend fun restoreFromBackup(walletId: WalletId, bytes: ByteArray): ChannelSnapshot {
        require(bytes.size in 1..MAX_JOURNAL_BYTES)
        val text = bytes.decodeToString()
        val recovery = runCatching { json.decodeFromString<ChannelRecoveryV2>(text) }.getOrNull()
        val snapshot = recovery?.channel ?: decodeSnapshot(text)
        persist(walletId, snapshot)
        if (recovery != null) payments?.installRecovery(walletId, recovery.payment)
        return snapshot
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
            journal.payment.fill(0)
            channel.fill(0)
            encoded.fill(0)
        }
    }

    private fun decodeSnapshot(value: String): ChannelSnapshot =
        runCatching { json.decodeFromString<ChannelSnapshot>(value) }.getOrElse {
            val legacy = json.decodeFromString<LegacyChannelSnapshot>(value)
            ChannelSnapshot(
                legacy.state,
                legacy.pending?.let { pending ->
                    PreparedChannelOperation(
                        pending.id,
                        pending.intentHash,
                        requireNotNull(legacy.action) { "legacy pending channel action is unavailable" },
                        preparedAtEpochMillis = 0,
                        payload = ChannelPayload.Protocol(byteArrayOf()),
                        state = OperationState.PENDING_RECONCILIATION,
                    )
                },
            )
        }

    private companion object {
        const val MAX_JOURNAL_BYTES = 1_048_576
    }
}

class DriveChannelBackupProtocol(
    private val backups: WalletBackupCoordinator,
    private val requireWriter: suspend (WalletId, BackupCheckpointV1) -> WriterLease,
    private val payments: PaymentStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : ChannelBackupProtocol {
    override suspend fun requireVerifiedWriter(walletId: WalletId): WriterLease {
        val checkpoint = backups.verify(walletId)
        return try {
            requireWriter(walletId, checkpoint)
        } finally {
            checkpoint.ciphertextHash.fill(0)
            checkpoint.channelSnapshot.fill(0)
        }
    }

    override suspend fun writeAhead(walletId: WalletId, snapshot: ChannelSnapshot) = write(walletId, snapshot)

    override suspend fun commit(walletId: WalletId, snapshot: ChannelSnapshot) = write(walletId, snapshot)

    private suspend fun write(walletId: WalletId, snapshot: ChannelSnapshot) {
        val recovery = ChannelRecoveryV2(channel = snapshot, payment = payments?.recovery(walletId) ?: PaymentJournalV1())
        val bytes = json.encodeToString(recovery).encodeToByteArray()
        try {
            backups.writeNext(walletId, bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
