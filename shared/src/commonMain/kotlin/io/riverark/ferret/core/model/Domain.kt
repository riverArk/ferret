package io.riverark.ferret.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
enum class CardanoNetwork { PREPROD, MAINNET }

@JvmInline
@Serializable
value class WalletId(val value: String) {
    init { require(Regex("(preprod|mainnet)-[0-9a-f]{56}").matches(value)) }
}

@JvmInline
@Serializable
value class Lovelace(val value: Long) {
    init { require(value >= 0) }
    operator fun plus(other: Lovelace): Lovelace {
        require(value <= Long.MAX_VALUE - other.value) { "lovelace overflow" }
        return Lovelace(value + other.value)
    }
    operator fun minus(other: Lovelace): Lovelace {
        require(value >= other.value) { "negative lovelace" }
        return Lovelace(value - other.value)
    }
}

@Serializable
sealed interface ChannelState {
    @Serializable data object Absent : ChannelState
    @Serializable data class Opening(val txId: String) : ChannelState
    @Serializable data class Open(val channelId: String) : ChannelState
    @Serializable data class Closing(val txId: String) : ChannelState
    @Serializable data object Closed : ChannelState
    @Serializable data object Responded : ChannelState
    @Serializable data object Ending : ChannelState
}

@Serializable
enum class BackupStatus { DISCONNECTED, VERIFYING, VERIFIED, REQUIRED, CONFLICT }

@Serializable
data class WalletProfile(
    val id: WalletId,
    val name: String,
    val network: CardanoNetwork,
    val paymentAddress: String,
    val stakeAddress: String,
    val channelState: ChannelState = ChannelState.Absent,
    val backupStatus: BackupStatus = BackupStatus.DISCONNECTED,
)

@Serializable
enum class OperationState { PROPOSED, WRITE_AHEAD_VERIFIED, SUBMITTED, PENDING_RECONCILIATION, COMPLETED, FAILED }

@Serializable
data class PendingOperation(
    val id: String,
    val intentHash: String,
    val state: OperationState,
    val remoteId: String? = null,
    val lastReconciledAtEpochMillis: Long? = null,
)

@Serializable
data class TransactionRecord(
    val id: String,
    val timestampEpochMillis: Long,
    val amount: Lovelace,
    val fee: Lovelace,
    val realm: Realm,
    val state: TransactionState,
)

@Serializable enum class Realm { L1, L2 }
@Serializable enum class TransactionState { PENDING, CONFIRMED, SETTLED, FAILED }

@Serializable
data class Quote(val id: String, val amount: Lovelace, val routingFee: Lovelace, val adaptorFee: Lovelace, val expiresAtEpochMillis: Long)

@Serializable
data class Receipt(val operationId: String, val paymentHash: String, val amount: Lovelace, val fee: Lovelace, val verified: Boolean)

sealed interface AppState {
    data object Locked : AppState
    data object CheckingConnectivity : AppState
    data object Offline : AppState
    data object NoWallets : AppState
    data class Ready(val activeWalletId: WalletId, val wallets: List<WalletProfile>) : AppState
}

enum class FerretError(val userMessage: String) {
    AUTHENTICATION_REQUIRED("Unlock Ferret to continue."),
    OFFLINE("Ferret must be online to access a wallet."),
    INVALID_NETWORK("The wallet and service network do not match."),
    BACKUP_REQUIRED("Verify the encrypted backup before continuing."),
    BACKUP_CONFLICT("Restore the latest backup before continuing."),
    INVALID_TRANSACTION("The transaction did not match the confirmed intent."),
    OPERATION_PENDING("Reconcile the pending operation before continuing."),
}
