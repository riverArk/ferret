package io.riverark.ferret.core.backup

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGoogleOAuthTokenProvider(
    private val activity: ComponentActivity,
) : OAuthTokenProvider {
    private val preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE)
    private var accountResult: CompletableDeferred<String?>? = null
    private var authorizationResult: CompletableDeferred<Boolean>? = null
    val accountName: String? get() = preferences.getString(ACCOUNT_NAME, null)

    private val accountLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        accountResult?.complete(name.takeIf { result.resultCode == Activity.RESULT_OK })
    }
    private val authorizationLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authorizationResult?.complete(result.resultCode == Activity.RESULT_OK)
    }

    suspend fun connect(): String {
        check(accountResult == null) { "Google account selection is already active." }
        val pending = CompletableDeferred<String?>().also { accountResult = it }
        val current = accountName?.let { Account(it, GOOGLE_ACCOUNT_TYPE) }
        accountLauncher.launch(AccountManager.newChooseAccountIntent(
            current,
            null,
            arrayOf(GOOGLE_ACCOUNT_TYPE),
            null,
            null,
            null,
            null,
        ))
        return try {
            requireNotNull(pending.await()) { "Google Drive account selection was cancelled." }
                .also { preferences.edit().putString(ACCOUNT_NAME, it).apply() }
        } finally {
            accountResult = null
        }
    }

    override suspend fun accessToken(): String {
        val account = Account(requireNotNull(accountName) { "Google Drive account is not connected." }, GOOGLE_ACCOUNT_TYPE)
        var result = tokenResult(account)
        authorizationIntent(result)?.let { intent ->
            check(authorizationResult == null) { "Google Drive authorization is already active." }
            val pending = CompletableDeferred<Boolean>().also { authorizationResult = it }
            authorizationLauncher.launch(intent)
            try {
                require(pending.await()) { "Google Drive authorization was cancelled." }
            } finally {
                authorizationResult = null
            }
            result = tokenResult(account)
        }
        return requireNotNull(result.getString(AccountManager.KEY_AUTHTOKEN)) { "Google Drive authorization failed." }
            .also { require(it.length in 20..8_192) }
    }

    private suspend fun tokenResult(account: Account) = withContext(Dispatchers.IO) {
        AccountManager.get(activity).getAuthToken(account, DRIVE_SCOPE, null, false, null, null).result
    }

    private fun authorizationIntent(result: android.os.Bundle): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            result.getParcelable(AccountManager.KEY_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.getParcelable(AccountManager.KEY_INTENT) as? Intent
        }

    private companion object {
        const val PREFERENCES = "ferret-drive"
        const val ACCOUNT_NAME = "account-name"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.appdata"
    }
}
