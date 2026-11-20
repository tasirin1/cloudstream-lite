package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.ui.settings.SettingsFragmentScreen.launchSafe
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.UIHelper.checkWrite
import com.lagradost.cloudstream3.utils.UIHelper.requestRW
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.setupStream
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager.QUEUE_KEY
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_DOWNLOAD_INFO
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import com.lagradost.safefile.MediaFileContentType
import com.lagradost.safefile.SafeFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.internal.closeQuietly
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object BackupUtils {

    // Audit-6 #23: generous ceiling for a settings backup file;
    // anything larger is not a backup this app wrote.
    private const val MAX_RESTORE_FILE_BYTES = 50_000_000L

    /**
     * No sensitive or breaking data in the backup
     */
    private val nonTransferableKeys = listOf(

        // The plugins themselves are not backed up
        PLUGINS_KEY,
        PLUGINS_KEY_LOCAL,

        AccountManager.ACCOUNT_TOKEN,
        AccountManager.ACCOUNT_IDS,

        // Audit-10 #6: the accounts array (DataStoreHelper's
        // "data_store_helper/account") embeds every profile's
        // lockPin. Restoring someone else's backup could plant
        // unknown PINs on all profiles — or strip the owner's
        // PINs on a shared device. Same reasoning as
        // biometric_key below.
        "data_store_helper/account",

        // TODO proper getter for string res keys to ensure that they are updated
        "biometric_key", // can lock down users if backup is shared on a incompatible device
        "nginx_user", // Nginx user key

        // No access rights after restore data from backup
        "download_path_key",
        "download_path_key_visual",
        "backup_path_key",
        "backup_dir_path_key",

        // When sharing backup we do not want to transfer what is essentially the password
        // Note that this is deprecated, and can be removed after all tokens have expired
        "anilist_token",
        "anilist_user",
        "mal_user",
        "mal_token",
        "mal_refresh_token",
        "mal_unixtime",
        "open_subtitles_user",
        "subdl_user",
        "simkl_token",


        // Downloads can not be restored from backups.
        // The download path URI can not be transferred.
        // In the future we may potentially write metadata to files in the download directory
        // and make it possible to restore download folders using that metadata.
        DOWNLOAD_EPISODE_CACHE_BACKUP,
        DOWNLOAD_EPISODE_CACHE,

        // Download headers are unintuitively used in the resume watching system.
        // We can therefore not prune download headers in backups.
        // DOWNLOAD_HEADER_CACHE_BACKUP,
        // DOWNLOAD_HEADER_CACHE,


        // This may overwrite valid local data with invalid data
        KEY_DOWNLOAD_INFO,

        // Prevent backups from automatically starting downloads
        KEY_RESUME_IN_QUEUE,
        KEY_RESUME_PACKAGES,
        QUEUE_KEY,

        // Prevent automatic plugin download after restoring backup
    )

    /** false if key should not be contained in backup */
    private fun String.isTransferable(): Boolean {
        // Audit-3 #36: substring matching used to exclude legitimate
        // keys that merely contain a blacklisted name (e.g. a key
        // containing "mal_token" inside a longer name). Match the
        // key itself, or the key as a folder prefix ("folder/path").
        return !nonTransferableKeys.any { this == it || this.startsWith("$it/") }
    }

    private var restoreFileSelector: ActivityResultLauncher<Array<String>>? = null

    // Kinda hack, but I couldn't think of a better way
    @Serializable
    data class BackupVars(
        @JsonProperty("_Bool") @SerialName("_Bool") val bool: Map<String, Boolean>?,
        @JsonProperty("_Int") @SerialName("_Int") val int: Map<String, Int>?,
        @JsonProperty("_String") @SerialName("_String") val string: Map<String, String>?,
        @JsonProperty("_Float") @SerialName("_Float") val float: Map<String, Float>?,
        @JsonProperty("_Long") @SerialName("_Long") val long: Map<String, Long>?,
        @JsonProperty("_StringSet") @SerialName("_StringSet") val stringSet: Map<String, Set<String>?>?,
    )

    @Serializable
    data class BackupFile(
        @JsonProperty("datastore") @SerialName("datastore") val datastore: BackupVars,
        @JsonProperty("settings") @SerialName("settings") val settings: BackupVars,
    )

    @Suppress("UNCHECKED_CAST")
    private fun getBackup(context: Context): BackupFile {
        val allData = context.getSharedPrefs().all.filter { it.key.isTransferable() }
        val allSettings = context.getDefaultSharedPrefs().all.filter { it.key.isTransferable() }

        val allDataSorted = BackupVars(
            allData.filter { it.value is Boolean } as? Map<String, Boolean>,
            allData.filter { it.value is Int } as? Map<String, Int>,
            allData.filter { it.value is String } as? Map<String, String>,
            allData.filter { it.value is Float } as? Map<String, Float>,
            allData.filter { it.value is Long } as? Map<String, Long>,
            allData.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>,
        )

        val allSettingsSorted = BackupVars(
            allSettings.filter { it.value is Boolean } as? Map<String, Boolean>,
            allSettings.filter { it.value is Int } as? Map<String, Int>,
            allSettings.filter { it.value is String } as? Map<String, String>,
            allSettings.filter { it.value is Float } as? Map<String, Float>,
            allSettings.filter { it.value is Long } as? Map<String, Long>,
            allSettings.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>,
        )

        return BackupFile(
            allDataSorted,
            allSettingsSorted,
        )
    }

    @WorkerThread
    fun restore(
        context: Context?,
        backupFile: BackupFile,
        restoreSettings: Boolean,
        restoreDataStore: Boolean,
    ) {
        if (context == null) return
        if (restoreSettings) {
            context.restoreMap(backupFile.settings.bool, true)
            context.restoreMap(backupFile.settings.int, true)
            context.restoreMap(backupFile.settings.string, true)
            context.restoreMap(backupFile.settings.float, true)
            context.restoreMap(backupFile.settings.long, true)
            context.restoreMap(backupFile.settings.stringSet, true)
        }

        if (restoreDataStore) {
            context.restoreMap(backupFile.datastore.bool)
            context.restoreMap(backupFile.datastore.int)
            context.restoreMap(backupFile.datastore.string)
            context.restoreMap(backupFile.datastore.float)
            context.restoreMap(backupFile.datastore.long)
            context.restoreMap(backupFile.datastore.stringSet)
        }

        // Audit-3 #9: the restores above wrote through a raw Editor,
        // bypassing PreferenceDelegate caches; invalidate them so the
        // restored accounts/settings apply without a force-stop.
        PreferenceDelegate.invalidateAllDelegateCaches()

        // Audit-14 #8: the account_key_index key is
        // transferable (Audit-10 #6 blocks "account" exactly /
        // "account/" prefix, and this key starts "account_"),
        // so a restore can switch the active profile — but
        // AccountManager's in-memory account caches were never
        // refreshed on this path (only setAccount does it), so
        // auth kept using the OLD profile's logins while local
        // data read the new profile's folder until a
        // force-stop. Refresh them like setAccount does.
        AccountManager.updateAccountIds()
        AccountManager.updateCachedAccounts()

        // Make sure the library is fresh
        for (api in AccountManager.syncApis) {
            api.requireLibraryRefresh = true
        }
    }

    fun backup(context: Context?) = ioSafe {
        if (context == null) return@ioSafe
        runBackup(context)
    }

    /**
     * Audit-4 #9: runs a backup and reports whether it actually
     * succeeded. backup() swallows every failure into a toast, so
     * BackupWorkManager — which joined backup()'s Job (Audit-3 #8)
     * and then unconditionally reported success — recorded failed
     * backups (revoked SAF permission, full storage, deleted
     * target folder) as successful indefinitely.
     */
    suspend fun backupAndAwait(context: Context?): Boolean {
        if (context == null) return false
        return withContext(Dispatchers.IO) { runBackup(context) }
    }

    // Audit-5 #28: backups are serialized. The filename has
    // one-second resolution (Audit-4 #40) and setupStream deletes
    // an existing file of the same name, so a manual backup and
    // the scheduled WorkManager backup starting in the same
    // second targeted the same file: interleaved writes, and a
    // "success" report for a file the other run then replaced.
    private val backupMutex = Mutex()

    private suspend fun runBackup(context: Context): Boolean =
        backupMutex.withLock { runBackupLocked(context) }

    private suspend fun runBackupLocked(context: Context): Boolean {
        var fileStream: OutputStream? = null
        var printStream: PrintWriter? = null
        // Audit-6 #25: tracked so a failed write can remove the
        // partial file (see the catch block).
        var streamData: DownloadObjects.StreamData? = null

        try {
            if (!context.checkWrite()) {
                showToast(R.string.backup_failed, Toast.LENGTH_LONG)
                context.getActivity()?.requestRW()
                return false
            }

            // Audit-4 #40: seconds added — minute resolution made
            // two backups within the same minute (e.g. a manual
            // backup right after the scheduled one) collide on
            // the same filename and silently overwrite each other.
            val date = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault()).format(
                Date(currentTimeMillis())
            )
            val displayName = "CS3_Backup_${date}"
            val backupFile = getBackup(context)
            val stream = setupBackupStream(context, displayName)
            streamData = stream

            fileStream = stream.openNew()
            printStream = PrintWriter(fileStream)
            printStream.print(backupFile.toJson())
            // Audit-3 #5: PrintWriter swallows IOExceptions (they
            // only surface via checkError()), so a failed write
            // (full storage, revoked SAF permission) still showed
            // the success toast over an empty/corrupt file.
            if (printStream.checkError()) {
                throw IOException("Backup write failed")
            }
            showToast(R.string.backup_success, Toast.LENGTH_LONG)
            return true
        } catch (e: Exception) {
            // Audit-6 #25: a write that failed midway left the
            // partial file under the proper backup name, where it
            // looked like a valid backup (and a later restore
            // picking the newest file would fail parsing it while
            // the user believed the backup existed). Remove it.
            //
            // Audit-7 #22: close the streams FIRST. The delete
            // used to run while the PrintWriter was still open
            // (it is closed in the finally below) — on SAF /
            // MediaStore targets deleting an open file can fail
            // silently, and the close afterwards flushed the
            // writer's remaining buffer into the file that was
            // supposed to be gone.
            printStream?.closeQuietly()
            fileStream?.closeQuietly()
            try {
                streamData?.delete()
            } catch (_: Exception) {
                // Best effort; the failure toast below still runs.
            }
            logError(e)
            try {
                showToast(
                    txt(R.string.backup_failed_error_format, e.toString()),
                    Toast.LENGTH_LONG,
                )
            } catch (e: Exception) {
                logError(e)
            }
            return false
        } finally {
            printStream?.closeQuietly()
            fileStream?.closeQuietly()
        }
    }

    @Throws(IOException::class)
    private fun setupBackupStream(
        context: Context,
        name: String,
        ext: String = "txt"
    ): DownloadObjects.StreamData {
        return setupStream(
            baseFile = getCurrentBackupDir(context).first ?: getDefaultBackupDir(context)
            ?: throw IOException("Bad config"),
            name,
            folder = null,
            extension = ext,
            tryResume = false,
        )
    }

    fun FragmentActivity.setUpBackup() {
        try {
            restoreFileSelector =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@registerForActivityResult
                    val activity = this
                    ioSafe {
                        try {
                            val input = activity.contentResolver.openInputStream(uri)
                                ?: return@ioSafe

                            // Audit-3 #33: the stream was never
                            // closed; read it inside use {}.
                            // Audit-6 #23: readText() had no size
                            // bound — picking a huge file (e.g. a
                            // video) as the "backup" OOM-crashed
                            // the STB instead of failing cleanly.
                            val text = input.use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(16 * 1024)
                                var total = 0L
                                while (true) {
                                    val read = stream.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > MAX_RESTORE_FILE_BYTES) {
                                        throw IOException(
                                            "Backup file exceeds $MAX_RESTORE_FILE_BYTES bytes"
                                        )
                                    }
                                    out.write(buffer, 0, read)
                                }
                                out.toString(Charsets.UTF_8.name())
                            }
                            val restoredValue = parseJson<BackupFile>(text)

                            restore(
                                activity,
                                restoredValue,
                                restoreSettings = true,
                                restoreDataStore = true,
                            )
                            activity.runOnUiThread { activity.recreate() }
                        } catch (e: Exception) {
                            logError(e)
                            main { // smth can fail in .format
                                showToast(
                                    getString(R.string.restore_failed_format).format(e.toString())
                                )
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun Activity.restorePrompt() {
        runOnUiThread {
            restoreFileSelector?.launchSafe(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "text/x-unknown",
                    "application/json",
                    "unknown/unknown",
                    "content/unknown",
                    "application/octet-stream",
                )
            )
        }
    }

    private fun <T> Context.restoreMap(
        map: Map<String, T>?,
        isEditingAppSettings: Boolean = false,
    ) {
        val editor = DataStore.editor(this, isEditingAppSettings)
        map?.forEach {
            if (it.key.isTransferable()) {
                editor.setKeyRaw(it.key, it.value)
            }
        }
        editor.apply()
    }

    /**
     * Copy of [com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getDefaultDir],
     * modified for backup-specific paths.
     */
    fun getDefaultBackupDir(context: Context): SafeFile? {
        return SafeFile.fromMedia(context, MediaFileContentType.Downloads)
    }

    /**
     * Copy of [com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath],
     * modified for backup-specific paths.
     */
    fun getCurrentBackupDir(context: Context): Pair<SafeFile?, String?> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
        val basePathSetting =
            settingsManager.getString(context.getString(R.string.backup_path_key), null)
        return baseBackupPathToFile(context, basePathSetting) to basePathSetting
    }

    /**
     * Copy of [com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.basePathToFile],
     * modified for backup-specific paths.
     */
    private fun baseBackupPathToFile(context: Context, path: String?): SafeFile? {
        return when {
            path.isNullOrBlank() -> getDefaultBackupDir(context)
            path.startsWith("content://") -> SafeFile.fromUri(context, path.toUri())
            else -> SafeFile.fromFilePath(context, path)
        }
    }
}
