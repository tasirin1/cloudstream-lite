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

        // Audit-14 #31: ONE refresh mutex per provider, shared
        // by every repo wrapper around the same API.
        // AccountManager builds separate SubtitleRepo/SyncRepo
        // instances for one API (allApis vs subtitleProviders
        // vs syncApis), so the old per-instance mutex (audit
        // #37) never actually serialized the player path
        // against the account-screen path — both could refresh
        // (for OpenSubtitles: full re-login) at once.
        private val refreshMutexes = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()

        fun refreshMutexFor(idPrefix: String): kotlinx.coroutines.sync.Mutex =
            synchronized(refreshMutexes) {
                refreshMutexes.getOrPut(idPrefix) { kotlinx.coroutines.sync.Mutex() }
            }
    }

    @Throws
    protected suspend fun freshAuth(): AuthData? =
        refreshMutexFor(idPrefix).withLock {
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
                // Audit-14 #7: reset the selection ONLY when the
                // removed account was the selected one. This used
                // to reset unconditionally, so logging out an
                // INACTIVE account silently deselected the active
                // one (id 0 matches no user, so the provider
                // looked fully logged out).
                val selectedId = synchronized(AccountManager.cachedAccountIds) {
                    AccountManager.cachedAccountIds[idPrefix]
                }
                // Audit-15 #13: match the selection by
                // (id, name) like the removal above — with
                // hashCode-derived ids, logging out the
                // INACTIVE colliding account still reset the
                // active one's selection on the id alone.
                val selectedName = synchronized(AccountManager.cachedAccountNames) {
                    AccountManager.cachedAccountNames[idPrefix]
                }
                // Audit-16 #18: a missing cached name (legacy
                // entries) falls back to id-only matching —
                // otherwise logging out the SELECTED account
                // left the selection pointing at the removed
                // account when its name was never cached.
                if (removed.any {
                        it.user.id == selectedId &&
                            (selectedName == null || it.user.name == selectedName)
                    }) {
                    AccountManager.updateAccountsId(idPrefix, 0)
                }
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
                val accounts = AccountManager.cachedAccounts[idPrefix]
                // Audit-14 #32: when a selection name is stored,
                // prefer the (id, name) match — with hashCode
                // ids, an id-only firstOrNull could pin the
                // WRONG colliding account forever.
                val name = AccountManager.cachedAccountNames[idPrefix]
                accounts?.firstOrNull { data -> data.user.id == id && (name == null || data.user.name == name) }
                    ?: accounts?.firstOrNull { data -> data.user.id == id }
            }
        }
    }

    // Audit-14 #32: select an account by its full identity so
    // the name is stored with the id (see AccountManager).
    fun selectAccount(user: AuthUser) {
        AccountManager.updateAccountsId(idPrefix, user.id, user.name)
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
            // Audit-14 #32: store the name with the id.
            AccountManager.updateAccountsId(idPrefix, user.id, user.name)
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
                // Audit-16 #17: consume the payload — it used
                // to stay in the map for the process lifetime,
                // so a stray/repeated redirect for the same
                // provider replayed setupLogin with stale data.
                synchronized(oauthPayload) { oauthPayload.remove(api.idPrefix) }) ?: return false
        )
    }
}