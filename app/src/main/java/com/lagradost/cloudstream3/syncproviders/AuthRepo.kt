package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.CloudStreamApp.Companion.openBrowser
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.NONE_ID
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.sync.withLock

/** General-purpose repo */
class PlainAuthRepo(api: AuthAPI) : AuthRepo(api)

/** Safe abstraction for AuthAPI that provides both a catching interface, and automatic token management. */
abstract class AuthRepo(open val api: AuthAPI) {
    fun isValidRedirectUrl(url: String) = safe { api.isValidRedirectUrl(url) } ?: false
    val idPrefix get() = api.idPrefix
    val name get() = api.name
    val icon get() = api.icon
    val requiresLogin get() = api.requiresLogin
    val createAccountUrl get() = api.createAccountUrl
    val hasOAuth2 get() = api.hasOAuth2
    val hasPin get() = api.hasPin
    val hasInApp get() = api.hasInApp
    val inAppLoginRequirement get() = api.inAppLoginRequirement
    val isAvailable get() = !api.requiresLogin || authUser() != null

    companion object {
        private val oauthPayload: MutableMap<String, String?> = mutableMapOf()
    }

    // Fork fix (audit #37): check-refresh-store was unsynchronized;
    // two concurrent calls could both refresh, and providers that
    // rotate refresh tokens then invalidate the first result,
    // silently logging the user out. Serialize per repo instance.
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    @Throws
    protected suspend fun freshAuth(): AuthData? =
        refreshMutex.withLock {
            val data = authData() ?: return@withLock null
            if (data.token.isAccessTokenExpired()) {
                val newToken = api.refreshToken(data.token) ?: return@withLock null
                val newAuth = AuthData(user = data.user, token = newToken)
                refreshUser(newAuth)
                return@withLock newAuth
            }
            return@withLock data
        }

    @Throws
    fun openOAuth2Page(): Boolean {
        val page = api.loginRequest() ?: return false
        synchronized(oauthPayload) {
            oauthPayload.put(idPrefix, page.payload)
        }
        openBrowser(page.url)
        return true
    }

    fun openOAuth2PageWithToast() {
        try {
            if (!openOAuth2Page()) {
                showToast(txt(R.string.authenticated_user_fail, api.name))
            }
        } catch (t: Throwable) {
            logError(t)
            if (t is ErrorLoadingException && t.message != null) {
                showToast(t.message)
                return
            }
            showToast(txt(R.string.authenticated_user_fail, api.name))
        }
    }

    suspend fun logout(from: AuthUser) {
        // Audit-10 #4: the read-modify-write runs under the
        // shared write lock so a concurrent refresh/login cannot
        // resurrect the removed account from a stale array. The
        // token invalidation (network) stays outside the lock.
        val oldAccounts = synchronized(AccountManager.accountsWriteLock) {
            val currentAccounts = AccountManager.accounts(idPrefix)
            // Audit-13 #26: match on (id, name) — several
            // providers derive user.id from String.hashCode()
            // (OpenSubtitles, Subdl, AnimeSkip), whose 32-bit
            // collisions made logout remove the OTHER account
            // too. Same account = same id AND same name.
            val (newAccounts, removed) = currentAccounts.partition {
                it.user.id != from.id || it.user.name != from.name
            }
            if (newAccounts.size < currentAccounts.size) {
                AccountManager.updateAccounts(idPrefix, newAccounts.toTypedArray())
                AccountManager.updateAccountsId(idPrefix, 0)
            }
            removed
        }

        for (oldAccount in oldAccounts) {
            try {
                api.invalidateToken(oldAccount.token)
            } catch (_: NotImplementedError) {
                // no-op
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    fun refreshUser(newAuth: AuthData) {
        // Audit-10 #4: same write lock as logout/setupLogin.
        synchronized(AccountManager.accountsWriteLock) {
            val currentAccounts = AccountManager.accounts(idPrefix)
            val newAccounts = currentAccounts.map {
                // Audit-13 #26: (id, name), see logout above —
                // an id-only match let a token refresh overwrite
                // a different account whose hashCode collided.
                if (it.user.id == newAuth.user.id && it.user.name == newAuth.user.name) {
                    newAuth
                } else {
                    it
                }
            }.toTypedArray()
            AccountManager.updateAccounts(idPrefix, newAccounts)
        }
    }

    // Audit-10 #5: this used to synchronize on cachedAccountIds
    // while READING cachedAccounts, whose writers lock
    // cachedAccounts itself — no mutual exclusion with them.
    // Lock both maps, in the same order everywhere (accounts
    // first); no other site nests the two locks.
    fun authData(): AuthData? = synchronized(AccountManager.cachedAccounts) {
        synchronized(AccountManager.cachedAccountIds) {
            AccountManager.cachedAccountIds[idPrefix]?.let { id ->
                AccountManager.cachedAccounts[idPrefix]?.firstOrNull { data -> data.user.id == id }
            }
        }
    }

    fun authToken(): AuthToken? = authData()?.token

    fun authUser(): AuthUser? = authData()?.user

    val accounts
        get() = synchronized(AccountManager.cachedAccounts) {
            AccountManager.cachedAccounts[idPrefix] ?: emptyArray()
        }
    var accountId
        get() = synchronized(AccountManager.cachedAccountIds) {
            AccountManager.cachedAccountIds[idPrefix] ?: NONE_ID
        }
        set(value) {
            AccountManager.updateAccountsId(idPrefix, value)
        }

    @Throws
    suspend fun pinRequest() =
        api.pinRequest()

    @Throws
    private suspend fun setupLogin(token: AuthToken): Boolean {
        val user = api.user(token) ?: return false

        val newAccount = AuthData(
            token = token,
            user = user,
        )

        // Audit-10 #4: the duplicate check and the append must
        // be atomic against logout/refresh writes.
        synchronized(AccountManager.accountsWriteLock) {
            val currentAccounts = AccountManager.accounts(idPrefix)
            // Audit-13 #26: (id, name), see logout above — an
            // id-only check refused a second, DIFFERENT account
            // as a "duplicate" whenever the hashCodes collided.
            if (currentAccounts.any {
                    it.user.id == newAccount.user.id && it.user.name == newAccount.user.name
                }) {
                throw ErrorLoadingException("Already logged into this account")
            }

            val newAccounts = currentAccounts + newAccount
            AccountManager.updateAccounts(idPrefix, newAccounts)
            AccountManager.updateAccountsId(idPrefix, user.id)
        }
        if (this is SyncRepo) {
            requireLibraryRefresh = true
        }
        return true
    }

    @Throws
    suspend fun login(form: AuthLoginResponse): Boolean {
        return setupLogin(api.login(form) ?: return false)
    }

    @Throws
    suspend fun login(payload: AuthPinData): Boolean {
        return setupLogin(api.login(payload) ?: return false)
    }

    @Throws
    suspend fun login(redirectUrl: String): Boolean {
        return setupLogin(
            api.login(
                redirectUrl,
                synchronized(oauthPayload) { oauthPayload[api.idPrefix] }) ?: return false
        )
    }
}