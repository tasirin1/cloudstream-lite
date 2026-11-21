package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.syncproviders.providers.Addic7ed
import com.lagradost.cloudstream3.syncproviders.providers.LocalList
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.cloudstream3.syncproviders.providers.SubSourceApi
import androidx.core.content.edit
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.videoskip.AnimeSkipAuth
import java.util.concurrent.TimeUnit

abstract class AccountManager {
    companion object {
        const val NONE_ID: Int = -1
        val localListApi = LocalList()

        val openSubtitlesApi = OpenSubtitlesApi()
        val addic7ed = Addic7ed()
        val subDlApi = SubDlApi()
        val subSourceApi = SubSourceApi()
        val animeSkipApi = AnimeSkipAuth()

        // Fork fix (audit #37): these maps double as lock objects for
        // synchronized() blocks here and in AuthRepo. They must never
        // be reassigned (updateAccountIds used to swap the ids map
        // inside its own synchronized block, defeating the lock), so
        // they are vals that are only ever mutated in place.
        val cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()
        val cachedAccountIds: MutableMap<String, Int> = mutableMapOf()

        // Audit-14 #32: the selected account's NAME, stored
        // alongside its id. Several providers derive user.id
        // from String.hashCode(), so an id alone cannot tell
        // two colliding accounts apart — the second one could
        // never be selected. Guarded by the cachedAccountIds
        // lock, like the ids map.
        val cachedAccountNames: MutableMap<String, String?> = mutableMapOf()

        // Audit-10 #4: serializes whole read-modify-write cycles
        // of the account arrays in AuthRepo (logout / refreshUser
        // / setupLogin). Without it, a token refresh racing a
        // logout wrote its stale array back and resurrected the
        // logged-out account (and a fresh login could be dropped
        // the same way).
        val accountsWriteLock = Any()

        const val ACCOUNT_TOKEN = "auth_tokens"
        const val ACCOUNT_IDS = "auth_ids"
        const val ACCOUNT_ID_NAMES = "auth_id_names"
        private const val ENCRYPTED_PREFIX = "enc:v1:"
        private const val TOKEN_KEY_ALIAS = "cs3_auth_token_key"

        fun accounts(prefix: String): Array<AuthData> {
            require(prefix != "NONE")
            // Fork fix (audit #40): serve from the in-memory cache
            // instead of re-parsing the stored JSON on every call.
            // The cache is kept in sync by updateAccounts and is
            // refreshed wholesale on profile switches.
            synchronized(cachedAccounts) { cachedAccounts[prefix] }?.let { return it }
            return loadAccountsFromDisk(prefix)
        }

        private fun loadAccountsFromDisk(prefix: String): Array<AuthData> {
            val context = CloudStreamApp.context ?: return arrayOf()
            val raw = context.getSharedPrefs()
                .getString(tokenPrefsKey(prefix), null) ?: return arrayOf()
            val json = decryptTokenJson(raw) ?: return arrayOf()
            val parsed = runCatching { parseJson<Array<AuthData>>(json) }
                .getOrNull() ?: return arrayOf()
            if (!raw.startsWith(ENCRYPTED_PREFIX)) {
                // Migrate legacy plaintext tokens to encrypted storage.
                writeAccountsToDisk(prefix, parsed)
            }
            return parsed
        }

        private fun writeAccountsToDisk(prefix: String, array: Array<AuthData>) {
            val context = CloudStreamApp.context ?: return
            val json = array.toJsonLiteral()
            context.getSharedPrefs().edit {
                putString(tokenPrefsKey(prefix), encryptTokenJson(json))
            }
        }

        private fun tokenPrefsKey(prefix: String): String =
            "${ACCOUNT_TOKEN}/${prefix}/${DataStoreHelper.currentAccount}"

        // Fork fix (audit #36): OAuth tokens used to sit in shared
        // preferences as plaintext JSON; on a rooted device (this
        // fork's STB target) any root-capable app could read every
        // account token. Values are now AES-GCM encrypted with a key
        // held in the Android KeyStore. If the keystore is somehow
        // unavailable, storage falls back to plaintext instead of
        // breaking logins entirely.
        // Audit-21 #10: guards the check-then-generate in
        // tokenKey() — two concurrent first-time callers both
        // passed the existence check and both generated, and
        // the second generateKey() overwrote the first key,
        // orphaning every ciphertext made with it.
        private val tokenKeyLock = Any()

        private fun tokenKey(): javax.crypto.SecretKey? = runCatching {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
            (keyStore.getEntry(TOKEN_KEY_ALIAS, null) as?
                java.security.KeyStore.SecretKeyEntry)?.secretKey ?: synchronized(tokenKeyLock) {
                // Re-check inside the lock: another caller may
                // have created the key while this one waited.
                (keyStore.getEntry(TOKEN_KEY_ALIAS, null) as?
                    java.security.KeyStore.SecretKeyEntry)?.secretKey ?: run {
                val generator = javax.crypto.KeyGenerator
                    .getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        TOKEN_KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                            android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
                        )
                        .build()
                )
                generator.generateKey()
                }
            }
        }.getOrNull()

        private fun encryptTokenJson(json: String): String {
            val key = tokenKey() ?: return json
            return runCatching {
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
                val body = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
                ENCRYPTED_PREFIX + android.util.Base64.encodeToString(
                    cipher.iv + body, android.util.Base64.NO_WRAP
                )
            }.getOrElse { json }
        }

        private fun decryptTokenJson(stored: String): String? {
            if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored
            val key = tokenKey() ?: return null
            return runCatching {
                val raw = android.util.Base64.decode(
                    stored.removePrefix(ENCRYPTED_PREFIX), android.util.Base64.NO_WRAP
                )
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE, key,
                    javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0, 12))
                )
                String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
            }.getOrNull()
        }

        // Fork fix (audit #38): a profile switch used to refresh only
        // the cached ids, leaving the previous profile's tokens in
        // cachedAccounts until a restart (sync looked logged out, or
        // worse, used the wrong profile's token). Reload both.
        fun updateCachedAccounts() {
            // Audit-16 #16: read + swap under the write lock
            // — unlocked, a refresh/logout landing between
            // the disk read and the cache swap was overwritten
            // by the older disk snapshot (cache served a stale
            // token while disk held the new one).
            synchronized(accountsWriteLock) {
                val data = mutableMapOf<String, Array<AuthData>>()
                for (api in allApis) {
                    data[api.idPrefix] = loadAccountsFromDisk(api.idPrefix)
                }
                synchronized(cachedAccounts) {
                    cachedAccounts.clear()
                    cachedAccounts.putAll(data)
                }
            }
        }

        fun updateAccounts(prefix: String, array: Array<AuthData>) {
            require(prefix != "NONE")
            writeAccountsToDisk(prefix, array)
            synchronized(cachedAccounts) {
                cachedAccounts[prefix] = array
            }
        }

        fun updateAccountsId(prefix: String, id: Int) {
            updateAccountsId(prefix, id, null)
        }

        // Audit-14 #32: name-aware selection (see
        // cachedAccountNames). A null name clears the stored
        // name, reverting selection to id-only matching.
        fun updateAccountsId(prefix: String, id: Int, name: String?) {
            require(prefix != "NONE")
            setKey(ACCOUNT_IDS, "${prefix}/${DataStoreHelper.currentAccount}", id)
            if (name == null) {
                removeKey(ACCOUNT_ID_NAMES, "${prefix}/${DataStoreHelper.currentAccount}")
            } else {
                setKey(ACCOUNT_ID_NAMES, "${prefix}/${DataStoreHelper.currentAccount}", name)
            }
            synchronized(cachedAccountIds) {
                cachedAccountIds[prefix] = id
                cachedAccountNames[prefix] = name
            }
        }

        /**
         * Audit-10 #2: purge one profile's stored provider
         * credentials. Profile deletion only removed the
         * "<index>/" DataStore namespace, leaving the tokens and
         * selected ids under auth_tokens/auth_ids behind — and
         * since a new profile reuses a freed index (max+1), it
         * silently inherited the deleted profile's logins.
         */
        fun removeAccountData(accountIndex: Int) {
            val context = CloudStreamApp.context ?: return
            for (api in allApis) {
                val prefix = api.idPrefix
                context.getSharedPrefs().edit {
                    remove("${ACCOUNT_TOKEN}/${prefix}/${accountIndex}")
                }
                removeKey(ACCOUNT_IDS, "${prefix}/${accountIndex}")
                // Audit-15 #12: the names store (Audit-14
                // #32) was added after this purge and never
                // joined it — a profile reusing the index
                // inherited the deleted profile's provider
                // account names.
                removeKey(ACCOUNT_ID_NAMES, "${prefix}/${accountIndex}")
            }
        }

        val allApis = arrayOf(
            SyncRepo(localListApi),
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(addic7ed),
            SubtitleRepo(subDlApi),
            PlainAuthRepo(animeSkipApi),
            SubtitleRepo(subSourceApi)
        )

        fun updateAccountIds() {
            val ids = mutableMapOf<String, Int>()
            val names = mutableMapOf<String, String?>()
            for (api in allApis) {
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
                names.put(
                    api.idPrefix,
                    getKey<String>(
                        ACCOUNT_ID_NAMES,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        null
                    )
                )
            }
            synchronized(cachedAccountIds) {
                cachedAccountIds.clear()
                cachedAccountIds.putAll(ids)
                cachedAccountNames.clear()
                cachedAccountNames.putAll(names)
            }
        }

        init {
            val data = mutableMapOf<String, Array<AuthData>>()
            val ids = mutableMapOf<String, Int>()
            val names = mutableMapOf<String, String?>()
            for (api in allApis) {
                data.put(api.idPrefix, accounts(api.idPrefix))
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
                names.put(
                    api.idPrefix,
                    getKey<String>(
                        ACCOUNT_ID_NAMES,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        null
                    )
                )
            }
            cachedAccounts.putAll(data)
            cachedAccountIds.putAll(ids)
            cachedAccountNames.putAll(names)
        }

        // I do not want to place this in the init block as JVM initialization order is weird, and it may cause exceptions
        // accessing other classes
        fun initMainAPI() {
            // Fork change: the external sync providers (MAL, Kitsu,
            // AniList, Simkl) were removed on 2026-11-20 (owner slimming
            // pick 3). The id prefixes stay as literals because
            // extensions still send these ids in LoadResponse.syncData
            // and local features (duplicate detection, filler check,
            // Kitsu episode lookup) key off them.
            LoadResponse.malIdPrefix = "mal"
            LoadResponse.kitsuIdPrefix = "kitsu"
            LoadResponse.aniListIdPrefix = "anilist"
            LoadResponse.simklIdPrefix = "simkl"
        }

        val subtitleProviders = arrayOf(
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(addic7ed),
            SubtitleRepo(subDlApi),
            SubtitleRepo(subSourceApi)
        )
        val syncApis = arrayOf(
            SyncRepo(localListApi)
        )

        const val APP_STRING = "cloudstreamapp"
        const val APP_STRING_REPO = "cloudstreamrepo"
        const val APP_STRING_PLAYER = "cloudstreamplayer"

        // Instantly start the search given a query
        const val APP_STRING_SEARCH = "cloudstreamsearch"

        // Instantly resume watching a show
        const val APP_STRING_RESUME_WATCHING = "cloudstreamcontinuewatching"

        const val APP_STRING_SHARE = "csshare"

        fun secondsToReadable(seconds: Int, completedValue: String): String {
            var secondsLong = seconds.toLong()
            val days = TimeUnit.SECONDS
                .toDays(secondsLong)
            secondsLong -= TimeUnit.DAYS.toSeconds(days)

            val hours = TimeUnit.SECONDS
                .toHours(secondsLong)
            secondsLong -= TimeUnit.HOURS.toSeconds(hours)

            val minutes = TimeUnit.SECONDS
                .toMinutes(secondsLong)
            secondsLong -= TimeUnit.MINUTES.toSeconds(minutes)
            if (minutes < 0) {
                return completedValue
            }
            //println("$days $hours $minutes")
            return "${if (days != 0L) "$days" + "d " else ""}${if (hours != 0L) "$hours" + "h " else ""}${minutes}m"
        }
    }
}