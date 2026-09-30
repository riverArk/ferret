package io.riverark.ferret.core.backup

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Swift's Google Sign-In adapter. Errors are fixed, redacted messages, never SDK error text. */
interface IosGoogleSignIn {
    val accountName: String?
    fun connect(completion: (String?, String?) -> Unit)
    fun accessToken(completion: (String?, String?) -> Unit)
}

class IosGoogleOAuthTokenProvider(private val google: IosGoogleSignIn) : OAuthTokenProvider {
    private val requests = Mutex()
    @Volatile private var selectionBlocked = false
    val accountName: String? get() = if (selectionBlocked) null else google.accountName

    suspend fun connect(): String = requests.withLock {
        // The SDK cannot abort its account picker through this callback interface. Keep any
        // late SDK success unusable until a subsequent explicit, completed selection.
        selectionBlocked = true
        await(google::connect).also {
            require(it.isNotBlank() && google.accountName == it) { "Google Drive account selection failed." }
            selectionBlocked = false
        }
    }

    override suspend fun accessToken(): String = requests.withLock {
        require(accountName != null) { "Google Drive account is not connected." }
        await(google::accessToken).also {
            require(it.length in 20..8_192) { "Google Drive authorization failed." }
        }
    }

    private suspend fun await(start: (completion: (String?, String?) -> Unit) -> Unit): String =
        suspendCancellableCoroutine { continuation ->
            try {
                start { value, error ->
                    if (continuation.isActive) {
                        if (value != null && error == null) continuation.resume(value)
                        else continuation.resumeWithException(IllegalStateException(error ?: "Google Drive authorization failed."))
                    }
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Google Drive authorization failed."))
            }
        }
}
