package io.riverark.ferret.core.backup

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IosGoogleOAuthTokenProviderTest {
    @Test fun cancelledSelectionIgnoresLateSuccessAndDoesNotAuthorizeToken() = runBlocking {
        val google = PendingGoogle()
        val provider = IosGoogleOAuthTokenProvider(google)
        val selection = async { provider.connect() }
        yield()
        selection.cancel()
        google.account = "unexpected@example.org"
        google.completeConnect?.invoke(google.account, null)
        selection.join()
        assertNull(provider.accountName)
        assertFailsWith<IllegalArgumentException> { provider.accessToken() }
        assertNull(google.completeToken)
        val second = async { provider.connect() }
        yield()
        google.account = "chosen@example.org"
        google.completeConnect?.invoke(google.account, null)
        assertEquals("chosen@example.org", second.await())
        val token = async { provider.accessToken() }
        yield()
        google.completeToken?.invoke("x".repeat(20), null)
        assertEquals("x".repeat(20), token.await())
    }

    private class PendingGoogle : IosGoogleSignIn {
        var account: String? = null
        override val accountName: String? get() = account
        var completeConnect: ((String?, String?) -> Unit)? = null
        var completeToken: ((String?, String?) -> Unit)? = null
        override fun connect(completion: (String?, String?) -> Unit) { completeConnect = completion }
        override fun accessToken(completion: (String?, String?) -> Unit) { completeToken = completion }
    }
}
