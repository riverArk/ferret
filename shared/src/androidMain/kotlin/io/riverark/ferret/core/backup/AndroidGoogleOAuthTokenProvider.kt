package io.riverark.ferret.core.backup

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGoogleOAuthTokenProvider(
    private val activity: Activity,
    private val accountName: () -> String?,
) : OAuthTokenProvider {
    override suspend fun accessToken(): String {
        val name = requireNotNull(accountName()) { "Google Drive account is not connected." }
        val result = withContext(Dispatchers.IO) {
            AccountManager.get(activity).getAuthToken(
                Account(name, GOOGLE_ACCOUNT_TYPE),
                DRIVE_SCOPE,
                null,
                false,
                null,
                null,
            ).result
        }
        val authorization = if (Build.VERSION.SDK_INT >= 33) {
            result.getParcelable(AccountManager.KEY_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.getParcelable(AccountManager.KEY_INTENT) as? Intent
        }
        if (authorization != null) {
            withContext(Dispatchers.Main) { activity.startActivity(authorization) }
            error("Google Drive authorization is required.")
        }
        return requireNotNull(result.getString(AccountManager.KEY_AUTHTOKEN)) { "Google Drive authorization failed." }
            .also { require(it.length in 20..8_192) }
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.appdata"
    }
}
