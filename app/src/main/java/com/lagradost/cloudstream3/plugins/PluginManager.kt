package com.lagradost.cloudstream3.plugins

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainAPI.Companion.settingsForProvider
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainActivity.Companion.lastError
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.PROVIDER_STATUS_OK
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager.ONLINE_PLUGINS_FOLDER
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.downloadPluginToFile
import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins
import com.lagradost.cloudstream3.plugins.RepositoryManager.sha256
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.sanitizeFilename
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.txt
import dalvik.system.PathClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Different keys for local and not since local can be removed at any time without app knowing, hence the local are getting rebuilt on every app start
const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"
const val PLUGIN_FILE_HASHES_KEY = "PLUGIN_FILE_HASHES_KEY"

const val EXTENSIONS_CHANNEL_ID = "cloudstream3.extensions"
const val EXTENSIONS_CHANNEL_NAME = "Extensions"
const val EXTENSIONS_CHANNEL_DESCRIPT = "Extension notification channel"

// Data class for internal storage
@Serializable
data class PluginData(
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("url") @SerialName("url") val url: String?,
    @JsonProperty("isOnline") @SerialName("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") @SerialName("filePath") val filePath: String,
    @JsonProperty("version") @SerialName("version") val version: Int,
) {
    @WorkerThread
    fun toSitePlugin(): SitePlugin {
        return SitePlugin(
            this.filePath,
            PROVIDER_STATUS_OK,
            maxOf(1, version),
            1,
            internalName,
            internalName,
            emptyList(),
            File(this.filePath).name,
            null,
            null,
            null,
            null,
            File(this.filePath).length(),
            // No file hash for local plugins. Local plugins have no use for the hash, and it's expensive to compute.
            null
        )
    }
}

// This is used as a placeholder / not set version
const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE

// This always updates
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1

object PluginManager {
    // Prevent multiple writes at once
    val lock = Mutex()

    // Audit-11 #6: absolute paths of ".new" staging files a
    // plugin download is currently writing. The startup sweep
    // and deletePlugin (Audit-10 #10) must not delete a
    // staging file out from under an in-flight transfer —
    // the periodic SubscriptionWorkManager load could
    // otherwise kill a user-started update mid-download.
    private val inFlightStagingFiles =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    const val TAG = "PluginManager"

    private var hasCreatedNotChanel = false

    /**
     * Store data about the plugin for fetching later
     * */
    private suspend fun setPluginData(data: PluginData) {
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline()
                val newPlugins = plugins.filter { it.filePath != data.filePath } + data
                setKey(PLUGINS_KEY, newPlugins)
            } else {
                val plugins = getPluginsLocal()
                setKey(PLUGINS_KEY_LOCAL, plugins.filter { it.filePath != data.filePath } + data)
            }
        }
    }

    private suspend fun deletePluginData(data: PluginData?) {
        if (data == null) return
        lock.withLock {
            if (data.isOnline) {
                // Audit-5 #32: this filtered by url, so deleting
                // one plugin also unregistered its twin — the
                // same plugin file published by another
                // repository (same URL, different filePath).
                // setPluginData and the local branch both key by
                // filePath; do the same here.
                val plugins = getPluginsOnline().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY, plugins)
            } else {
                val plugins = getPluginsLocal().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY_LOCAL, plugins)
            }
        }
    }

    suspend fun deleteRepositoryData(repositoryPath: String) {
        lock.withLock {
            // Audit-7 #24: match on a path boundary, not a raw
            // substring — contains() also matched a DIFFERENT
            // repository whose folder path merely contains this
            // one (e.g. ".../repo" matching ".../repo2/..."), so
            // deleting one repo unregistered the other's plugins.
            val belongsToRepo = { path: String ->
                path == repositoryPath ||
                    path.startsWith(repositoryPath + File.separator)
            }
            // Audit-7 #6: the removed plugins' anchors go with
            // them (see deletePlugin).
            val removed = getPluginsOnline().filter {
                belongsToRepo(it.filePath)
            }
            val plugins = getPluginsOnline().filter {
                !belongsToRepo(it.filePath)
            }
            val file = File(repositoryPath)
            safe {
                if (file.exists()) file.deleteRecursively()
            }
            setKey(PLUGINS_KEY, plugins)
            removed.forEach { removePluginHash(it.filePath) }
        }
    }

    /**
     * Deletes all generated oat files which will force Android to recompile the dex extensions.
     * This might fix unrecoverable SIGSEGV exceptions when old oat files are loaded in a new app update.
     */
    fun deleteAllOatFiles(context: Context) {
        File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}").listFiles()?.forEach { repo ->
            repo.listFiles { file -> file.name == "oat" && file.isDirectory }?.forEach { file ->
                val success = file.deleteRecursively()
                Log.i(TAG, "Deleted oat directory: ${file.absolutePath} Success=$success")
            }
        }
    }


    // Fork fix (audit #9): load-time integrity anchor. Downloads are
    // hash-verified; remember the verified hash per file path and
    // re-check it on every load, so a plugin file swapped on disk
    // afterwards is refused instead of silently executed. Local
    // sideloaded plugins have no anchor by design.
    // Audit-3 #15: the anchor map used to be read + parsed from
    // SharedPreferences on every single plugin load and rewritten on
    // every store, making startup and mass downloads O(n^2) in the
    // number of plugins. It is now cached in memory; this object is
    // the only writer.
    @Volatile
    private var pluginHashCache: Map<String, String>? = null

    // Audit-6 #27: set when the stored anchor map exists but
    // cannot be parsed. pluginHashMap() then falls back to an
    // empty map — indistinguishable from "no anchors stored" —
    // which used to disable the load-time integrity check for
    // EVERY online plugin with only a log line. loadPlugin
    // refuses online plugins while this is set (fail-closed,
    // like the digest policy in Audit-4 #5): an unreadable
    // anchor store means nothing can be verified.
    @Volatile
    private var pluginHashStoreCorrupt = false

    private fun pluginHashMap(): Map<String, String> {
        pluginHashCache?.let { return it }
        // Audit-4 #4: initialize under the same lock storePluginHash
        // uses. The unsynchronized lazy init could finish after a
        // store had already written a newer cache, rolling the
        // in-memory map back to a stale snapshot.
        return synchronized(pluginHashLock) {
            pluginHashCache ?: run {
                // Audit sesi 15 #11: a parse failure used to be swallowed and
                // treated as "no anchor", silently disabling the integrity
                // check for every online plugin. At least leave evidence.
                val hashes = try {
                    getKey<Map<String, String>>(PLUGIN_FILE_HASHES_KEY)
                } catch (e: Exception) {
                    logError(e)
                    pluginHashStoreCorrupt = true
                    null
                } ?: emptyMap()
                pluginHashCache = hashes
                hashes
            }
        }
    }

    private fun getStoredPluginHash(filePath: String): String? {
        // Stored value: "sha256-<hex>". Values written before
        // Audit-4 #48 may carry a "|<length>|<lastModified>"
        // suffix (the removed unchanged-file stamp); the
        // substringBefore keeps those readable.
        return pluginHashMap()[filePath]?.substringBefore('|')
    }

    // Serializes the read-modify-write in storePluginHash: parallel
    // downloads (amap) used to race and silently drop anchors
    // (audit sesi 15 #10).
    private val pluginHashLock = Any()

    fun storePluginHash(filePath: String, hash: String) {
        synchronized(pluginHashLock) {
            // Audit-4 #48: the bare hash is stored; the
            // "|length|mtime" stamp Audit-3 #15 appended is gone
            // with the load-time shortcut that consumed it.
            val stamped = hash
            // Audit-4 #4: build on a fresh read of the stored map,
            // not on the read cache. A cache initialized from a
            // failed read (empty map) would otherwise be written
            // back over the real anchors on the next store, wiping
            // every other plugin's anchor. As before Audit-3 #15,
            // a read that throws aborts the store instead.
            val current = try {
                getKey<Map<String, String>>(PLUGIN_FILE_HASHES_KEY)
            } catch (e: Exception) {
                logError(e)
                return
            } ?: emptyMap()
            val updated = current + (filePath to stamped)
            pluginHashCache = updated
            setKey(PLUGIN_FILE_HASHES_KEY, updated)
            // Audit-7 #26: this store just read the stored map
            // successfully and wrote a fresh one back, so the
            // store is demonstrably healthy again — clear the
            // Audit-6 #27 corruption flag. It used to stick for
            // the process lifetime, refusing every anchorless
            // online plugin until an app restart even after the
            // transient read failure was long gone.
            pluginHashStoreCorrupt = false
        }
    }

    // Audit-6 #9: read/remove counterparts of storePluginHash,
    // used by the Compose update path to roll a failed update
    // back to the previously installed file and its anchor.
    fun storedPluginHash(filePath: String): String? {
        return getStoredPluginHash(filePath)
    }

    fun removePluginHash(filePath: String) {
        synchronized(pluginHashLock) {
            val current = try {
                getKey<Map<String, String>>(PLUGIN_FILE_HASHES_KEY)
            } catch (e: Exception) {
                logError(e)
                return
            } ?: emptyMap()
            val updated = current - filePath
            pluginHashCache = updated
            setKey(PLUGIN_FILE_HASHES_KEY, updated)
            // Audit-7 #26: same recovery as storePluginHash.
            pluginHashStoreCorrupt = false
        }
    }

    fun getPluginsOnline(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY) ?: emptyArray()
    }

    fun getPluginsLocal(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY_LOCAL) ?: emptyArray()
    }

    private val CLOUD_STREAM_FOLDER =
        Environment.getExternalStorageDirectory().absolutePath + "/Cloudstream3/"

    private val LOCAL_PLUGINS_PATH = CLOUD_STREAM_FOLDER + "plugins"

    @Volatile
    var currentlyLoading: String? = null

    // Maps filepath to plugin
    // Audit-15 #15: paths with a load currently in flight.
    // The duplicate guard below used to check-then-instantiate
    // non-atomically, so two concurrent loads of one file both
    // ran the plugin constructor; the claim makes the check
    // and the reservation one step (guarded by the plugins
    // lock, released in loadPlugin's finally).
    private val loadingPluginPaths: MutableSet<String> = mutableSetOf()

    val plugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    // Maps urls to plugin
    val urlPlugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    // Fork fix (audit #6): the maps are plain LinkedHashMaps whose
    // writes are guarded by synchronized(map) blocks. Route ALL reads
    // through the same lock; iterating or reading the raw maps from
    // other threads while a load writes can corrupt them.
    fun getPluginByPath(filePath: String): BasePlugin? =
        synchronized(plugins) { plugins[filePath] }

    fun getPluginByUrl(url: String): BasePlugin? =
        synchronized(urlPlugins) { urlPlugins[url] }

    fun hasPluginUrl(url: String): Boolean =
        synchronized(urlPlugins) { urlPlugins.containsKey(url) }

    private val classLoaders: MutableMap<PathClassLoader, BasePlugin> =
        HashMap<PathClassLoader, BasePlugin>()

    var loadedLocalPlugins = false
        private set

    var loadedOnlinePlugins = false
        private set

    private suspend fun maybeLoadPlugin(context: Context, file: File) {
        val name = file.name
        if (file.extension == "zip" || file.extension == "cs3") {
            loadPlugin(
                context,
                file,
                PluginData(name, null, false, file.absolutePath, PLUGIN_VERSION_NOT_SET)
            )
        } else {
            Log.i(TAG, "Skipping invalid plugin file: $file")
        }
    }


    // Helper class for updateAllOnlinePluginsAndLoadThem
    data class OnlinePluginData(
        val savedData: PluginData,
        val onlineData: PluginWrapper,
    ) {
        val isOutdated =
            onlineData.plugin.version > savedData.version || onlineData.plugin.version == PLUGIN_VERSION_ALWAYS_UPDATE
        val isDisabled = onlineData.plugin.status == PROVIDER_STATUS_DOWN

        fun validOnlineData(context: Context): Boolean {
            return getPluginPath(
                context,
                savedData.internalName,
                onlineData.repositoryData.url
            ).absolutePath == savedData.filePath
        }
    }

    // var allCurrentOutDatedPlugins: Set<OnlinePluginData> = emptySet()

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean {
        return (getPluginsOnline().firstOrNull {
            // Most of the time the provider ends with Provider which isn't part of the api name
            it.internalName.replace("provider", "", ignoreCase = true) == apiName
        }
            ?: getPluginsLocal().firstOrNull {
                it.internalName.replace("provider", "", ignoreCase = true) == apiName
            })?.let { savedData ->
            // OnlinePluginData(savedData, onlineData)
            loadPlugin(
                context,
                File(savedData.filePath),
                savedData
            )
        } ?: false
    }

    /**
     * Needs to be run before other plugin loading because plugin loading can not be overwritten
     * 1. Gets all online data about the downloaded plugins
     * 2. If disabled do nothing
     * 3. If outdated download and load the plugin
     * 4. Else load the plugin normally
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(activity: Activity) {
        assertNonRecursiveCallstack()

        // Load all plugins as fast as possible!
        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        // Iterates over all offline plugins, compares to remote repo and returns the plugins which are outdated
        val outdatedPlugins = getPluginsOnline().map { savedData ->
            onlinePlugins
                .filter { onlineData -> savedData.internalName == onlineData.plugin.internalName }
                .map { onlineData ->
                    OnlinePluginData(savedData, onlineData)
                }.filter {
                    it.validOnlineData(activity)
                }
        }.flatten().distinctBy { it.onlineData.plugin.url }

        debugPrint {
            "Outdated plugins: ${outdatedPlugins.filter { it.isOutdated }}"
        }

        // Audit-14 #33: filled from parallel amap children —
        // a plain ArrayList loses/corrupts entries under
        // concurrent add().
        val updatedPlugins =
            java.util.Collections.synchronizedList(mutableListOf<String>())

        outdatedPlugins.amap { pluginData ->
            if (pluginData.isDisabled) {
                //updatedPlugins.add(activity.getString(R.string.single_plugin_disabled, pluginData.onlineData.second.name))
                unloadPlugin(pluginData.savedData.filePath)
            } else if (pluginData.isOutdated) {
                downloadPlugin(
                    activity,
                    pluginData.onlineData.plugin.url,
                    pluginData.onlineData.plugin.fileHash,
                    pluginData.savedData.internalName,
                    File(pluginData.savedData.filePath),
                    true
                ).let { success ->
                    if (success)
                        updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }

        main {
            val uitext = txt(R.string.plugins_updated, updatedPlugins.size)
            createNotification(activity, uitext, updatedPlugins)
            /*val navBadge = (activity as MainActivity).binding?.navRailView?.getOrCreateBadge(R.id.navigation_settings)
            navBadge?.isVisible = true
            navBadge?.number = 5*/
        }

        // ioSafe {
        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)
        // }

        Log.i(TAG, "Plugin update done!")
    }

    /**
     * Automatically download plugins not yet existing on local
     * 1. Gets all online data from online plugins repo
     * 2. Fetch all not downloaded plugins
     * 3. Download them and reload plugins
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(
        activity: Activity,
        mode: AutoDownloadMode
    ) {
        assertNonRecursiveCallstack()

        // Audit-14 #33: see above.
        val newDownloadPlugins =
            java.util.Collections.synchronizedList(mutableListOf<String>())
        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it)?.toList() ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val providerLang = activity.getApiProviderLangSettings()
        //Log.i(TAG, "providerLang => ${providerLang.toJson()}")

        // Iterate online repos and returns not downloaded plugins
        val notDownloadedPlugins = onlinePlugins.mapNotNull { onlineData ->
            val sitePlugin = onlineData.plugin
            val tvtypes = sitePlugin.tvTypes ?: listOf()

            //Don't include empty urls
            if (sitePlugin.url.isBlank()) {
                return@mapNotNull null
            }
            if (sitePlugin.repositoryUrl.isNullOrBlank()) {
                return@mapNotNull null
            }

            //Omit already existing plugins
            if (getPluginPath(activity, sitePlugin.internalName, onlineData.repositoryData.url).exists()) {
                Log.i(TAG, "Skip > ${sitePlugin.internalName}")
                return@mapNotNull null
            }

            //Omit non-NSFW if mode is set to NSFW only
            if (mode == AutoDownloadMode.NsfwOnly) {
                if (!tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }
            //Omit NSFW, if disabled
            if (!settingsForProvider.enableAdult) {
                if (tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }

            //Omit lang not selected on language setting
            if (mode == AutoDownloadMode.FilterByLang) {
                val lang = sitePlugin.language ?: return@mapNotNull null
                //If set to 'universal', don't skip any language
                if (!providerLang.contains(AllLanguagesName) && !providerLang.contains(lang)) {
                    return@mapNotNull null
                }
                //Log.i(TAG, "sitePlugin lang => $lang")
            }

            val savedData = PluginData(
                url = sitePlugin.url,
                internalName = sitePlugin.internalName,
                isOnline = true,
                filePath = "",
                version = sitePlugin.version
            )
            OnlinePluginData(savedData, onlineData)
        }
        //Log.i(TAG, "notDownloadedPlugins => ${notDownloadedPlugins.toJson()}")

        notDownloadedPlugins.amap { pluginData ->
            downloadPlugin(
                activity,
                pluginData.onlineData.plugin.url,
                pluginData.onlineData.plugin.fileHash,
                pluginData.savedData.internalName,
                pluginData.onlineData.repositoryData.url,
                !pluginData.isDisabled
            ).let { success ->
                if (success)
                    newDownloadPlugins.add(pluginData.onlineData.plugin.name)
            }
        }

        main {
            val uitext = txt(R.string.plugins_downloaded, newDownloadPlugins.size)
            createNotification(activity, uitext, newDownloadPlugins)
        }

        // ioSafe {
        afterPluginsLoadedEvent.invoke(false)
        // }

        Log.i(TAG, "Plugin download done!")
    }

    @Throws
    private fun assertNonRecursiveCallstack() {
        if (Thread.currentThread().stackTrace.any { it.methodName == "loadPlugin" }) {
            throw Error("You tried to call a function that will recursively call loadPlugin, this will cause crashes or memory leaks. Do not do this, there is better ways to implement the feature than reloading plugins. Are you sure you read the compile error or docs?")
        }
    }

    /**
     * Use updateAllOnlinePluginsAndLoadThem
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()

        // Audit-9 #1: restore any plugin whose file is missing
        // but whose .bak survived — an update interrupted between
        // parking and swapping (process death, power loss) must
        // not lose the plugin.
        val onlinePlugins = (getPluginsOnline()).toList()
        for (pluginData in onlinePlugins) {
            val pluginFile = File(pluginData.filePath)
            // Audit-10 #10: a leftover ".new" staging file from
            // an interrupted download is never a loadable
            // plugin — delete it instead of letting it sit in
            // the plugin folder forever. Audit-11 #6: unless a
            // download is writing it right now.
            val stagingCandidate = File(pluginFile.parentFile, pluginFile.name + ".new")
            if (!inFlightStagingFiles.contains(stagingCandidate.absolutePath)) {
                stagingCandidate.delete()
            }
            if (!pluginFile.exists()) {
                val bakFile = File(pluginFile.parentFile, pluginFile.name + ".bak")
                if (bakFile.exists()) {
                    try {
                        Files.move(
                            bakFile.toPath(),
                            pluginFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
        }

        // Load all plugins as fast as possible!
        onlinePlugins.amap { pluginData ->
            loadPlugin(
                context,
                File(pluginData.filePath),
                pluginData
            )
        }
    }

    /**
     * Reloads all local plugins and forces a page update, used for hot reloading with deployWithAdb
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins(activity: FragmentActivity?) {
        assertNonRecursiveCallstack()

        Log.d(TAG, "Reloading all local plugins!")
        if (activity == null) return
        getPluginsLocal().forEach {
            unloadPlugin(it.filePath)
        }
        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(activity, true)
    }

    /**
     * @param forceReload see afterPluginsLoadedEvent, basically a way to load all local plugins
     * and reload all pages even if they are previously valid
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context: Context, forceReload: Boolean) {
        assertNonRecursiveCallstack()

        val dir = File(LOCAL_PLUGINS_PATH)

        if (!dir.exists()) {
            val res = dir.mkdirs()
            if (!res) {
                Log.w(TAG, "Failed to create local directories")
                // We have tried to load local plugins, but exit early.
                // This needs to be true to prevent the downloader waiting for plugins.
                loadedLocalPlugins = true
                return
            }
        }

        val sortedPlugins = dir.listFiles()

        // Audit-3 #13: local plugins are code from shared storage
        // executed with full app access and no integrity check, and
        // any app with storage permission can write into this
        // folder. They now load only when the user explicitly opted
        // in (Settings > General > Extensions, default off).
        if (!sortedPlugins.isNullOrEmpty()) {
            val localEnabled = try {
                androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getBoolean("local_plugins_enabled", false)
            } catch (e: Exception) {
                logError(e)
                false
            }
            if (!localEnabled) {
                Log.w(
                    TAG,
                    "Skipping ${sortedPlugins.size} local plugin(s): local plugins are disabled"
                )
                loadedLocalPlugins = true
                return
            }
        }
        // Always sort plugins alphabetically for reproducible results

        Log.d(TAG, "Files in '${LOCAL_PLUGINS_PATH}' folder: ${sortedPlugins?.size}")

        // Use app-specific external files directory and copy the file there.
        // We have to do this because on Android 14+, it otherwise gives SecurityException
        // due to dex files and setReadOnly seems to have no effect unless it it here.
        val pluginDirectory = File(context.getExternalFilesDir(null), "plugins")
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs() // Ensure the plugins directory exists
        }

        // Make sure all local plugins are fully refreshed.
        removeKey(PLUGINS_KEY_LOCAL)

        sortedPlugins?.sortedBy { it.name }?.amap { file ->
            try {
                val destinationFile = File(pluginDirectory, file.name)

                // Only copy the file if the destination file doesn't exist or if
                // its content differs. Audit-6 #30: length + mtime
                // used to decide "unchanged" — both are settable by
                // any app with storage access, so a same-size swap
                // of the copy in the shared external folder went
                // unnoticed and the tampered copy kept loading
                // (local plugins have no stored hash anchor).
                // Plugin files are a few MB; comparing SHA-256 is
                // cheap and definitive. A hash failure counts as
                // "changed" (re-copy from the source).
                val needsCopy = !destinationFile.exists() ||
                    runCatching { sha256(destinationFile) != sha256(file) }
                        .getOrDefault(true)
                if (needsCopy) {

                    // Copy the file to the app-specific plugin directory
                    file.copyTo(destinationFile, overwrite = true)

                    // After copying, set the destination file's modification time
                    // to match the source file.
                    destinationFile.setLastModified(file.lastModified())
                }

                // Load the plugin after it has been copied
                maybeLoadPlugin(context, destinationFile)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to copy the file")
                logError(t)
            }
        }

        loadedLocalPlugins = true
        afterPluginsLoadedEvent.invoke(forceReload)
    }

    /** @return true if safe mode is enabled in any possible way. */
    fun isSafeMode(): Boolean {
        return checkSafeModeFile() || lastError != null
    }

    /**
     * This can be used to override any extension loading to fix crashes!
     * @return true if safe mode file is present
     **/
    fun checkSafeModeFile(): Boolean {
        return safe {
            val folder = File(CLOUD_STREAM_FOLDER)
            if (!folder.exists()) return@safe false
            val files = folder.listFiles { _, name ->
                name.equals("safe", ignoreCase = true)
            }
            files?.any()
        } ?: false
    }

    /**
     * @return True if successful, false if not
     * */
    internal suspend fun loadPlugin(context: Context, file: File, data: PluginData): Boolean {
        val fileName = file.nameWithoutExtension
        val filePath = file.absolutePath
        currentlyLoading = fileName
        Log.i(TAG, "Loading plugin: $data")

        // Fork fix (audit #9): refuse a file whose bytes no longer
        // match the hash verified when it was downloaded.
        val storedHash = getStoredPluginHash(filePath)
        if (storedHash != null) {
            // Audit-4 #48: the Audit-3 #15 shortcut (same length +
            // mtime = skip the re-hash) is removed — length and
            // mtime are both attacker-controllable on a file that
            // was swapped with same-size content, which silently
            // defeated this integrity check. Plugin files are a
            // few MB, so a full SHA-256 on load is cheap; always
            // hash and compare.
            val actualHash = runCatching { sha256(file) }.getOrNull()
            if (actualHash != storedHash) {
                Log.e(
                    TAG,
                    "Refusing to load plugin $fileName: file hash mismatch"
                )
                currentlyLoading = null
                return false
            }
        } else if (!data.isOnline) {
            Log.w(TAG, "Loading local plugin $fileName without integrity verification")
        } else {
            // Audit-6 #27: no anchor because the anchor STORE is
            // unreadable is not the same as no anchor because the
            // repository never provided one — refuse the load
            // instead of running unverifiable code.
            if (pluginHashStoreCorrupt) {
                Log.e(
                    TAG,
                    "Refusing to load online plugin $fileName: stored integrity anchors could not be read"
                )
                currentlyLoading = null
                return false
            }
            // Audit sesi 15 #11: verified download paths always store
            // an anchor, so an online plugin without one is worth a
            // visible warning instead of silent trust.
            Log.w(TAG, "Loading online plugin $fileName without a stored integrity anchor")
        }

        // Audit-3 #14: refuse a duplicate load BEFORE instantiating
        // the plugin. The old flow instantiated + registered data
        // first and only then checked, outside the plugins lock, so
        // two parallel loads of the same file could both run the
        // plugin's constructor and load() side effects.
        // Audit-15 #15: claim the path atomically with the
        // duplicate check — a concurrent load of the same
        // file now skips here instead of instantiating the
        // plugin a second time.
        val claimed = synchronized(plugins) {
            if (plugins.containsKey(filePath)) false
            else loadingPluginPaths.add(filePath)
        }
        if (!claimed) {
            Log.i(TAG, "Plugin already loaded or loading, skipping: $fileName")
            currentlyLoading = null
            return true
        }

        var loadedInstance: BasePlugin? = null
        return try {
            // In case of Android 14+ then
            try {
                // Set the file as read-only and log if it fails
                if (!file.setReadOnly()) {
                    Log.e(TAG, "Failed to set read-only on plugin file: ${file.name}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set dex as read-only")
                logError(t)
            }

            val loader = PathClassLoader(filePath, context.classLoader)
            var manifest: BasePlugin.Manifest
            loader.getResourceAsStream("manifest.json").use { stream ->
                if (stream == null) {
                    Log.e(TAG, "Failed to load plugin  $fileName: No manifest found")
                    // Fork fix (audit #11): this early return bypassed the
                    // currentlyLoading reset, leaving a stale loading state.
                    currentlyLoading = null
                    return false
                }
                InputStreamReader(stream).use { reader ->
                    manifest = parseJson<BasePlugin.Manifest>(reader.readText())
                }
            }

            val name: String = manifest.name ?: "NO NAME".also {
                Log.d(TAG, "No manifest name for ${data.internalName}")
            }
            val version: Int = manifest.version ?: PLUGIN_VERSION_NOT_SET.also {
                Log.d(TAG, "No manifest version for ${data.internalName}")
            }

            @Suppress("UNCHECKED_CAST")
            val pluginClass: Class<*> =
                loader.loadClass(manifest.pluginClassName) as Class<out BasePlugin?>
            val pluginInstance: BasePlugin =
                pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
            loadedInstance = pluginInstance

            // Sets with the proper version
            setPluginData(data.copy(version = version))

            if (synchronized(plugins) { plugins.containsKey(filePath) }) {
                Log.i(TAG, "Plugin with name $name already exists")
                currentlyLoading = null
                return true
            }

            pluginInstance.filename = file.absolutePath
            if (manifest.requiresResources) {
                Log.d(TAG, "Loading resources for ${data.internalName}")
                // based on https://stackoverflow.com/questions/7483568/dynamic-resource-loading-from-other-apk
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPath =
                    AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                addAssetPath.invoke(assets, file.absolutePath)

                @Suppress("DEPRECATION")
                (pluginInstance as? Plugin)?.resources = Resources(
                    assets,
                    context.resources.displayMetrics,
                    context.resources.configuration
                )
            }
            synchronized(plugins) {
                plugins[filePath] = pluginInstance
            }
            synchronized(classLoaders) {
                classLoaders[loader] = pluginInstance
            }
            synchronized(urlPlugins) {
                urlPlugins[data.url ?: filePath] = pluginInstance
            }
            if (pluginInstance is Plugin) {
                pluginInstance.load(context)
            } else {
                pluginInstance.load()
            }
            Log.i(TAG, "Loaded plugin ${data.internalName} successfully")
            currentlyLoading = null
            true
        } catch (e: Throwable) {
            // Audit sesi 15 #12: roll back the registration. The
            // instance enters all three maps before load() runs, so a
            // plugin whose load() threw used to stay registered, look
            // loaded, and block every retry via the duplicate check.
            loadedInstance?.let { instance ->
                synchronized(plugins) {
                    if (plugins[filePath] === instance) plugins.remove(filePath)
                }
                synchronized(classLoaders) {
                    classLoaders.entries.removeAll { it.value === instance }
                }
                synchronized(urlPlugins) {
                    urlPlugins.entries.removeAll { it.value === instance }
                }
            }
            Log.e(TAG, "Failed to load $file: ${Log.getStackTraceString(e)}")
            showToast(
                // context.getActivity(), // we are not always on the main thread
                context.getString(R.string.plugin_load_fail).format(fileName),
                Toast.LENGTH_LONG
            )
            currentlyLoading = null
            false
        } finally {
            // Audit-15 #15: release the load claim on every
            // exit (success, early return, failure).
            synchronized(plugins) { loadingPluginPaths.remove(filePath) }
        }
    }

    fun unloadPlugin(absolutePath: String) {
        Log.i(TAG, "Unloading plugin: $absolutePath")
        val plugin = synchronized(plugins) { plugins[absolutePath] }
        if (plugin == null) {
            Log.w(TAG, "Couldn't find plugin $absolutePath")
            return
        }

        try {
            plugin.beforeUnload()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run beforeUnload $absolutePath: ${Log.getStackTraceString(e)}")
        }

        // remove all registered apis
        APIHolder.apis.filter { api -> api.sourcePlugin == plugin.filename }.forEach {
            removePluginMapping(it)
        }

        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { provider -> provider.sourcePlugin == plugin.filename }
        }

        extractorApis.withLock {
            extractorApis.removeAll { provider -> provider.sourcePlugin == plugin.filename }
        }

        VideoClickActionHolder.allVideoClickActions.withLock {
            VideoClickActionHolder.allVideoClickActions.removeAll { action -> action.sourcePlugin == plugin.filename }
        }

        synchronized(classLoaders) {
            classLoaders.values.removeIf { v -> v == plugin }
        }

        synchronized(plugins) {
            plugins.remove(absolutePath)
        }

        synchronized(urlPlugins) {
            urlPlugins.values.removeIf { v -> v == plugin }
        }
    }

    /**
     * Spits out a unique and safe filename based on name.
     * Used for repo folders (using repo url) and plugin file names (using internalName)
     * */
    fun getPluginSanitizedFileName(name: String): String {
        return sanitizeFilename(
            name,
            true
        ) + "." + name.hashCode()
    }

    /**
     * This should not be changed as it is used to also detect if a plugin is installed!
     **/
    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String
    ): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl) // Guaranteed unique
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}/${folderName}/$fileName.cs3")
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        repositoryUrl: String,
        loadPlugin: Boolean
    ): Boolean {
        val file = getPluginPath(activity, internalName, repositoryUrl)
        return downloadPlugin(activity, pluginUrl, pluginHash, internalName, file, loadPlugin)
    }

    // Audit-8 #2: restore the parked .bak + the previous anchor
    // and reload the old plugin — the legacy-path counterpart of
    // rollbackPluginUpdate on the Compose path (Audit-6 #9 /
    // Audit-7 #5), which this path never had.
    private suspend fun rollbackLegacyPluginUpdate(
        activity: Context,
        file: File,
        backupFile: File?,
        previousAnchor: String?,
        data: PluginData,
    ) {
        try {
            // Audit-10 #12: restore the anchor BEFORE the file.
            // File-first left a death window pairing the old
            // (working) file with the NEW anchor — a permanent
            // hash-mismatch refusal the startup sweep cannot
            // see because the file exists. Anchor-first leaves
            // at worst the already-failed new file refused.
            if (previousAnchor != null) {
                storePluginHash(file.absolutePath, previousAnchor)
            } else {
                removePluginHash(file.absolutePath)
            }
            file.delete()
            if (backupFile != null && backupFile.exists()) {
                Files.move(
                    backupFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            if (file.exists()) {
                loadPlugin(activity, file, data)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
    ): Boolean {
        // Audit-8 #2: give this path the same .bak rollback the
        // Compose path got in Audit-6 #9 — a verified-but-
        // unloadable update (newer plugin API, corrupt inner
        // manifest, missing class) used to destroy the working
        // plugin permanently here.
        //
        // Audit-9 #1: Audit-8 parked the installed file BEFORE
        // the download, leaving the plugin path EMPTY for the
        // whole transfer — a process death in that window lost
        // the plugin with no recovery. Mirror the Compose order
        // instead: download+verify into a staging file first,
        // park only for the swap, then move staging into place.
        val backupFile = file.parentFile?.let { File(it, file.name + ".bak") }
        val previousAnchor = storedPluginHash(file.absolutePath)
        val stagingFile = file.parentFile?.let { File(it, file.name + ".new") }
        var parked = false
        // Built up here (was: after the download) so the rollback
        // below can reload with it; newFile IS file on success.
        val data = PluginData(
            internalName,
            pluginUrl,
            true,
            file.absolutePath,
            PLUGIN_VERSION_NOT_SET
        )
        try {
            Log.d(TAG, "Downloading plugin: $pluginUrl to ${file.absolutePath}")
            // Audit-7 #25: normalize the manifest hash ONCE here
            // — the legacy path used to pass the raw spelling to
            // both the download check and the stored anchor, so
            // an uppercase hex or a "SHA-256:<hex>" spelling
            // (both fine on the Compose path since Audit-6 #7)
            // mismatched the computed lowercase sha256 forever.
            //
            // Audit-8 #5: the Audit-7 normalization stripped the
            // algorithm prefix and produced a BARE hex, but both
            // consumers compare against RepositoryManager.sha256()
            // which returns "sha256-<hex>" — so colon spellings
            // ("sha256:<hex>") could NEVER match and every plugin
            // from such a repository failed to install/update on
            // this path forever. Normalize to the canonical
            // "sha256-<hex>" form the consumers expect; anything
            // that is not a 64-char hex passes through unchanged
            // (and keeps failing closed, as before).
            val normalizedHash = pluginHash?.trim()?.lowercase()?.let { raw ->
                val hex = raw.substringAfterLast(':').substringAfterLast('-').trim()
                if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "sha256-$hex"
                } else {
                    raw.takeIf { it.isNotEmpty() }
                }
            }
            // The plugin file needs to be salted with the repository url hash as to allow multiple repositories with the same internal plugin names
            // Audit-9 #1: download+verify into the staging file;
            // the installed plugin stays untouched until the swap.
            // Audit-11 #6: mark the staging file in-flight until
            // this function exits (finally below), so background
            // plugin loads cannot sweep it mid-transfer.
            stagingFile?.let { inFlightStagingFiles.add(it.absolutePath) }
            downloadPluginToFile(activity, pluginUrl, stagingFile ?: file, normalizedHash)
                ?: run {
                    stagingFile?.delete()
                    return false
                }
            if (backupFile != null && file.exists()) {
                parked = try {
                    Files.move(
                        file.toPath(),
                        backupFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    true
                } catch (e: Exception) {
                    logError(e)
                    false
                }
                if (!parked) {
                    // Cannot swap safely — keep the old plugin.
                    stagingFile?.delete()
                    return false
                }
            }
            if (stagingFile != null) {
                try {
                    Files.move(
                        stagingFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: Exception) {
                    logError(e)
                    if (parked) {
                        rollbackLegacyPluginUpdate(
                            activity, file, backupFile, previousAnchor, data
                        )
                    }
                    return false
                }
            }
            val newFile = file

            // Fork fix (audit #9): anchor for the load-time check.
            if (normalizedHash != null) {
                storePluginHash(newFile.absolutePath, normalizedHash)
            } else {
                // Audit-7 #6: this download was accepted WITHOUT
                // a manifest hash, so any anchor stored for the
                // previous file at this path is stale — keeping
                // it made loadPlugin compare the new file against
                // the old file's hash and reject it forever (the
                // repo dropping fileHash from its manifest bricked
                // the plugin at every startup). Clear it.
                removePluginHash(newFile.absolutePath)
            }

            return if (loadPlugin) {
                unloadPlugin(file.absolutePath)
                val loaded = loadPlugin(
                    activity,
                    newFile,
                    data
                )
                if (loaded) {
                    backupFile?.delete()
                    true
                } else {
                    // Audit-8 #2: verified but unloadable — the
                    // working plugin comes back instead of being
                    // destroyed.
                    if (parked) {
                        rollbackLegacyPluginUpdate(
                            activity,
                            file,
                            backupFile,
                            previousAnchor,
                            data
                        )
                    }
                    false
                }
            } else {
                backupFile?.delete()
                setPluginData(data)
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Audit-14 #34: cancellation is not a download
            // failure — the outer catch turned it into a plain
            // "false", so a cancelled update run kept going
            // through the rest of the queue. Roll back the
            // parked file like the failure path, then rethrow.
            if (parked) {
                rollbackLegacyPluginUpdate(activity, file, backupFile, previousAnchor, data)
            }
            throw e
        } catch (e: Exception) {
            if (parked) {
                rollbackLegacyPluginUpdate(activity, file, backupFile, previousAnchor, data)
            }
            logError(e)
            return false
        } finally {
            // Audit-11 #6: transfer (and swap) finished one way
            // or another — the staging file is sweepable again.
            stagingFile?.let { inFlightStagingFiles.remove(it.absolutePath) }
        }
    }

    suspend fun deletePlugin(file: File): Boolean {
        val list =
            (getPluginsLocal() + getPluginsOnline()).filter { it.filePath == file.absolutePath }

        return try {
            val pluginFile = File(file.absolutePath)
            val deleted = pluginFile.delete()
            // Audit-10 #11: confirm the plugin file is really
            // gone BEFORE touching the .bak — deleting the
            // backup first and then failing left a registered
            // plugin with nothing to roll back to.
            if (!deleted && pluginFile.exists()) {
                return false
            }
            // Audit-9 #14: also remove a .bak left behind by an
            // interrupted update, and don't abandon the cleanup
            // when the plugin file was already gone (e.g. only
            // the .bak survived an interrupted update) — the old
            // early false left the data record and the anchor
            // registered forever. Audit-10 #10: the ".new"
            // staging sibling gets the same treatment.
            File(pluginFile.parentFile, pluginFile.name + ".bak").delete()
            // Audit-11 #6: not while an update download is
            // writing this plugin's staging file.
            val deleteStaging = File(pluginFile.parentFile, pluginFile.name + ".new")
            if (!inFlightStagingFiles.contains(deleteStaging.absolutePath)) {
                deleteStaging.delete()
            }
            unloadPlugin(file.absolutePath)
            list.forEach { deletePluginData(it) }
            // Audit-7 #6: drop the deleted plugin's anchor
            // too — orphaned anchors accumulated forever and
            // could later mismatch a different file installed
            // at the same path.
            removePluginHash(file.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(activity: Activity) {
        assertNonRecursiveCallstack()

        showToast(activity.getString(R.string.starting_plugin_update_manually), Toast.LENGTH_LONG)

        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val allPlugins = getPluginsOnline().flatMap { savedData ->
            onlinePlugins
                .filter { it.plugin.internalName == savedData.internalName }
                .mapNotNull { onlineData ->
                    OnlinePluginData(savedData, onlineData).takeIf { it.validOnlineData(activity) }
                }
        }.distinctBy { it.onlineData.plugin.url }

        // Audit-14 #33: filled from parallel amap children —
        // a plain ArrayList loses/corrupts entries under
        // concurrent add().
        val updatedPlugins =
            java.util.Collections.synchronizedList(mutableListOf<String>())

        allPlugins.amap { pluginData ->
            if (pluginData.isDisabled) {
                Log.e(
                    "PluginManager",
                    "Unloading disabled plugin: ${pluginData.onlineData.plugin.name}"
                )
                unloadPlugin(pluginData.savedData.filePath)
            } else {
                val existingFile = File(pluginData.savedData.filePath)
                // Fork fix (audit #8): do NOT delete the current plugin
                // first. downloadPluginToFile downloads to a temp file,
                // verifies the hash and only then atomically replaces
                // the target; deleting up front lost a working plugin
                // whenever the update download failed.
                if (downloadPlugin(
                        activity,
                        pluginData.onlineData.plugin.url,
                        pluginData.onlineData.plugin.fileHash,
                        pluginData.savedData.internalName,
                        existingFile,
                        true
                    )
                ) {
                    updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }.also {
            main {
                val message = if (updatedPlugins.isNotEmpty()) {
                    activity.getString(R.string.plugins_updated_manually, updatedPlugins.size)
                } else {
                    activity.getString(R.string.no_plugins_updated_manually)
                }
                showToast(message, Toast.LENGTH_LONG)

                val notificationText = UiText.StringResource(
                    R.string.plugins_updated_manually,
                    listOf(updatedPlugins.size)
                )
                createNotification(activity, notificationText, updatedPlugins)

            }
        }

        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)

        Log.i("PluginManager", "Plugin update done!")
    }

    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = EXTENSIONS_CHANNEL_NAME //getString(R.string.channel_name)
            val descriptionText =
                EXTENSIONS_CHANNEL_DESCRIPT//getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(EXTENSIONS_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        context: Context,
        uitext: UiText,
        extensions: List<String>
    ): Notification? {
        try {

            if (extensions.isEmpty()) return null

            val content = extensions.joinToString(", ")
//        main { // DON'T WANT TO SLOW IT DOWN
            val builder = NotificationCompat.Builder(context, EXTENSIONS_CHANNEL_ID)
                .setAutoCancel(false)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(uitext.asString(context))
                //.setContentTitle(context.getString(title, extensionNames.size))
                .setSmallIcon(R.drawable.ic_baseline_extension_24)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(content)
                )
                .setContentText(content)

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            // notificationId is a unique int for each notification that you must define
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context)
                    .notify((System.currentTimeMillis() / 1000).toInt(), notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }
}
