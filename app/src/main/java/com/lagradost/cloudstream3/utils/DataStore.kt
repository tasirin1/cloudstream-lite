package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeyClass
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKeyClass
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/** Used to display metadata about downloads and resume watching */
const val DOWNLOAD_HEADER_CACHE = "download_header_cache"
const val DOWNLOAD_HEADER_CACHE_BACKUP = "BACKUP_download_header_cache"

//const val WATCH_HEADER_CACHE = "watch_header_cache"
const val DOWNLOAD_EPISODE_CACHE = "download_episode_cache"
const val DOWNLOAD_EPISODE_CACHE_BACKUP = "BACKUP_download_episode_cache"
const val VIDEO_PLAYER_BRIGHTNESS = "video_player_alpha_key"
const val USER_SELECTED_HOMEPAGE_API = "home_api_used"
const val USER_PROVIDER_API = "user_custom_sites"
const val PREFERENCES_NAME = "rebuild_preference"

// TODO degelgate by value for get & set

class PreferenceDelegate<T : Any>(
    val key: String, val default: T //, private val klass: KClass<T>
) {
    private val klass: KClass<out T> = default::class

    // simple cache to make it not get the key every time it is accessed, however this requires
    // that ONLY this changes the key
    //
    // Audit-4 #36: the cache used to be unsynchronized plain
    // fields — a getValue read that started before a concurrent
    // setValue could write its stale result back over the new
    // value, after which every read served the stale cache while
    // the preferences held the new value. And an absent key was
    // never cached (null doubled as "not loaded"), so every
    // access re-read + re-parsed the preferences. All cache
    // access is now under cacheLock, and cacheLoaded separates
    // "not loaded yet" from "loaded, key absent" (cache == null
    // then means the default applies).
    private val cacheLock = Any()
    private var cache: T? = null
    private var cacheLoaded: Boolean = false
    private var cacheGeneration: Int = -1

    companion object {
        // Audit-3 #9: a backup restore writes keys directly through an
        // Editor, bypassing these delegates, so cached values (accounts,
        // selected profile) stayed stale until the process died. The
        // restore path bumps this generation; delegates re-read once
        // when it changes.
        @Volatile
        var restoreGeneration: Int = 0
            private set

        fun invalidateAllDelegateCaches() {
            restoreGeneration++
        }
    }

    operator fun getValue(self: Any?, property: KProperty<*>): T {
        // Audit-4 #36: see the cache fields.
        synchronized(cacheLock) {
            if (cacheLoaded && cacheGeneration == restoreGeneration) {
                return cache ?: default
            }
            // Audit-11 #12: sample the generation BEFORE the
            // load. It used to be re-read after, so a restore
            // landing between the load and the assignment tagged
            // a PRE-restore value as post-restore-fresh,
            // defeating the invalidation for this key for the
            // rest of the process.
            val generationAtLoad = restoreGeneration
            val stored = getKeyClass(key, klass.java)
            cache = stored
            cacheLoaded = true
            cacheGeneration = generationAtLoad
            return stored ?: default
        }
    }

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?
    ) {
        // Audit-4 #36: cache + preferences updated atomically
        // against a concurrent getValue.
        synchronized(cacheLock) {
            cache = t
            cacheLoaded = true
            cacheGeneration = restoreGeneration
            if (t == null) {
                removeKey(key)
            } else {
                setKeyClass(key, t)
            }
        }
    }
}

/** When inserting many keys use this function, this is because apply for every key is very expensive on memory */
data class Editor(
    val editor: SharedPreferences.Editor
) {
    /** Always remember to call apply after */
    fun <T> setKeyRaw(path: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        if (isStringSet(value)) {
            editor.putStringSet(path, value as Set<String>)
        } else {
            when (value) {
                is Boolean -> editor.putBoolean(path, value)
                is Int -> editor.putInt(path, value)
                is String -> editor.putString(path, value)
                is Float -> editor.putFloat(path, value)
                is Long -> editor.putLong(path, value)
            }
        }
    }

    private fun isStringSet(value: Any?): Boolean {
        if (value is Set<*>) {
            return value.filterIsInstance<String>().size == value.size
        }
        return false
    }

    fun apply() {
        // Fork fix (audit #41): no forced System.gc() on every write;
        // it stalled the calling thread for no benefit.
        editor.apply()
    }
}

object DataStore {
    // Extensions shouldn't have really been using this version of it, but it seems
    // some have. Since there has always been a very easy alternative, we won't
    // need to deprecate it that long, and should be able to fully remove it
    // once extensions at least use the other version.
    @Deprecated(
        "Please do not use the mapper version from DataStore. Preferably use methods from AppUtils " +
            "to parse JSON. However, you can use the stable-API version of the mapper at " +
            "com.lagradost.cloudstream3.mapper to access the mapper directly if necessary.",
        level = DeprecationLevel.ERROR,
        replaceWith = ReplaceWith("com.lagradost.cloudstream3.mapper"),
    )
    val mapper = com.lagradost.cloudstream3.mapper

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun Context.getSharedPrefs(): SharedPreferences {
        return getPreferences(this)
    }

    fun getFolderName(folder: String, path: String): String {
        return "${folder}/${path}"
    }

    fun editor(context: Context, isEditingAppSettings: Boolean = false): Editor {
        val editor: SharedPreferences.Editor =
            if (isEditingAppSettings) context.getDefaultSharedPrefs()
                .edit() else context.getSharedPrefs().edit()
        return Editor(editor)
    }

    fun Context.getDefaultSharedPrefs(): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(this)
    }

    fun Context.getKeys(folder: String): List<String> {
        // Ensure that the folder ends with "/" to prevent matching with other folders
        val fixedFolder = folder.trimEnd('/') + "/"
        return this.getSharedPrefs().all.keys.filter { it.startsWith(fixedFolder) }
    }

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun Context.containsKey(folder: String, path: String): Boolean {
        return containsKey(getFolderName(folder, path))
    }

    fun Context.containsKey(path: String): Boolean {
        val prefs = getSharedPrefs()
        return prefs.contains(path)
    }

    fun Context.removeKey(path: String) {
        try {
            val prefs = getSharedPrefs()
            if (prefs.contains(path)) {
                prefs.edit {
                    remove(path)
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys("$folder/")
        try {
            getSharedPrefs().edit {
                keys.forEach { value ->
                    remove(value)
                }
            }
            return keys.size
        } catch (e: Exception) {
            logError(e)
            return 0
        }
    }

    fun <T> Context.setKey(path: String, value: T) {
        try {
            getSharedPrefs().edit {
                putString(path, value?.toJsonLiteral())
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        return try {
            val json: String = getSharedPrefs().getString(path, null) ?: return null
            parseJson(json, valueType.kotlin)
        } catch (e: Exception) {
            // Fork fix (audit #39): a parse failure used to look exactly
            // like "no data stored", silently discarding user data
            // after any format change. At least leave a trace.
            logError(e)
            null
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    @Deprecated(
        message = "Use parseJson<T>(this) directly instead.",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith(
            expression = "parseJson<T>(this)",
            imports = ["com.lagradost.cloudstream3.utils.AppUtils.parseJson"],
        ),
    )
    inline fun <reified T : Any> String.toKotlinObject(): T {
        return parseJson(this)
    }

    @Deprecated(
        message = "Use parseJson<T>(this) directly instead.",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith(
            expression = "parseJson<T>(this)",
            imports = ["com.lagradost.cloudstream3.utils.AppUtils.parseJson"],
        ),
    )
    fun <T : Any> String.toKotlinObject(valueType: Class<T>): T {
        return parseJson(this, valueType.kotlin)
    }

    // GET KEY GIVEN PATH AND DEFAULT VALUE, NULL IF ERROR
    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        return try {
            val json: String = getSharedPrefs().getString(path, null) ?: return defVal
            parseJson<T>(json)
        } catch (e: Exception) {
            // Audit-3 #35: a parse failure used to return null even
            // when the caller supplied a default value.
            // Audit-11 #11: log it, like the valueType overload
            // does (audit #39) — this is the main read path, and
            // a corrupt value otherwise discarded the user's
            // data with no trace at all.
            logError(e)
            defVal
        }
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? {
        return getKey(path, null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? {
        return getKey(getFolderName(folder, path), null)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? {
        return getKey(getFolderName(folder, path), defVal) ?: defVal
    }
}
