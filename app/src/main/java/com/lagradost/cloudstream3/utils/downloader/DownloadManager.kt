package com.lagradost.cloudstream3.utils.downloader


import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.util.Log
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.IDownloadableMinimum
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.VideoDownloadService
import com.lagradost.cloudstream3.sortUrls
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_NAVIGATE_TO
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP_DOWNLOAD
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getLinkPriority
import com.lagradost.cloudstream3.ui.result.ExtractorSubtitleLink
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper2
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToEnglishLanguageName
import com.lagradost.cloudstream3.utils.SubtitleUtils.deleteMatchingSubtitles
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getDefaultDir
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFileName
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFolder
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.sanitizeFilename
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.toFile
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.CreateNotificationMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadResumePackage
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadStatus
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfoResult
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.LazyStreamDownloadResponse
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.StreamData
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.appendAndDontOverride
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.cancel
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.downloadSubtitle
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getEstimatedTimeLeft
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.join
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.safefile.SafeFile
import com.lagradost.safefile.closeQuietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

const val DOWNLOAD_CHANNEL_ID = "cloudstream3.general"
const val DOWNLOAD_CHANNEL_NAME = "Downloads"
const val DOWNLOAD_CHANNEL_DESCRIPT = "The download notification channel"

object VideoDownloadManager {
    fun maxConcurrentDownloads(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            ?.getInt(context.getString(R.string.download_parallel_key), 3) ?: 3

    private fun maxConcurrentConnections(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            ?.getInt(context.getString(R.string.download_concurrent_key), 3) ?: 3

    private val _currentDownloads: MutableStateFlow<Set<Int>> = MutableStateFlow(emptySet())
    val currentDownloads: StateFlow<Set<Int>> = _currentDownloads

    const val TAG = "VDM"
    private const val DOWNLOAD_NOTIFICATION_TAG = "FROM_DOWNLOADER"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    @get:DrawableRes
    val imgDone get() = R.drawable.rddone

    @get:DrawableRes
    val imgDownloading get() = R.drawable.rdload

    @get:DrawableRes
    val imgPaused get() = R.drawable.rdpause

    @get:DrawableRes
    val imgStopped get() = R.drawable.rderror

    @get:DrawableRes
    val imgError get() = R.drawable.rderror

    @get:DrawableRes
    val pressToPauseIcon get() = R.drawable.ic_baseline_pause_24

    @get:DrawableRes
    val pressToResumeIcon get() = R.drawable.ic_baseline_play_arrow_24

    @get:DrawableRes
    val pressToStopIcon get() = R.drawable.baseline_stop_24

    enum class DownloadType {
        IsPaused,
        IsDownloading,
        IsDone,
        IsFailed,
        IsStopped,
        IsPending
    }

    enum class DownloadActionType {
        Pause,
        Resume,
        Stop,
    }


    /** Invalid input, just skip to the next one as the same args will give the same error */
    private val DOWNLOAD_INVALID_INPUT =
        DownloadStatus(retrySame = false, tryNext = true, success = false)

    /** no need to try any other mirror as we have downloaded the file */
    private val DOWNLOAD_SUCCESS =
        DownloadStatus(retrySame = false, tryNext = false, success = true)

    /** the user pressed stop, so no need to download anything else */
    private val DOWNLOAD_STOPPED =
        DownloadStatus(retrySame = false, tryNext = false, success = true)

    /** the process failed due to some reason, so we retry and also try the next mirror */
    private val DOWNLOAD_FAILED = DownloadStatus(retrySame = true, tryNext = true, success = false)

    /** The download only downloaded partial */
    private val DOWNLOAD_PARTIAL_SUCCESS =
        DownloadStatus(retrySame = true, tryNext = false, success = true)

    /** 50MB minimum size */
    const val DOWNLOAD_PARTIAL_MIN_SIZE = 1_048_576L * 50L

    /** bad config, skip all mirrors as every call to download will have the same bad config */
    private val DOWNLOAD_BAD_CONFIG =
        DownloadStatus(retrySame = false, tryNext = false, success = false)

    const val KEY_RESUME_PACKAGES = "download_resume_2"
    const val KEY_DOWNLOAD_INFO = "download_info"

    /** A key to save all the downloads which have not yet started and those currently running, using [DownloadQueueWrapper]
     * [KEY_RESUME_PACKAGES] can store keys which should not be automatically queued, unlike this key.
     */
    const val KEY_RESUME_IN_QUEUE = "download_resume_queue_key"
//    private const val KEY_RESUME_QUEUE_PACKAGES = "download_q_resume"

    // Fork fix (audit #14): mutated from download coroutines, the
    // service and the UI at once; a plain HashMap can corrupt under
    // concurrent writes (the notify() comment even asked "TODO MUTEX?").
    val downloadStatus = java.util.concurrent.ConcurrentHashMap<Int, DownloadType>()
    val downloadStatusEvent = Event<Pair<Int, DownloadType>>()
    val downloadDeleteEvent = Event<Int>()
    val downloadEvent = Event<Pair<Int, DownloadActionType>>()
    val downloadProgressEvent = Event<Triple<Int, Long, Long>>()
//    val downloadQueue = LinkedList<DownloadResumePackage>()

    private var hasCreatedNotChannel = false

    private fun Context.createNotificationChannel() {
        hasCreatedNotChannel = true

        this.createNotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            DOWNLOAD_CHANNEL_NAME,
            DOWNLOAD_CHANNEL_DESCRIPT
        )
    }

    fun cancelAllDownloadNotifications(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications.forEach { notification ->
            if (notification.tag == DOWNLOAD_NOTIFICATION_TAG) {
                manager.cancel(DOWNLOAD_NOTIFICATION_TAG, notification.id)
            }
        }
    }


    /**
     * @param hlsProgress will together with hlsTotal display another notification if used, to lessen the confusion about estimated size.
     * */
    @SuppressLint("StringFormatInvalid")
    private suspend fun createDownloadNotification(
        context: Context,
        source: String?,
        linkName: String?,
        ep: DownloadEpisodeMetadata,
        state: DownloadType,
        progress: Long,
        total: Long,
        notificationCallback: (Int, Notification) -> Unit,
        hlsProgress: Long? = null,
        hlsTotal: Long? = null,
        bytesPerSecond: Long
    ): Notification? {
        try {
            if (total <= 0) return null// crash, invalid data

            val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setAutoCancel(true)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(ep.mainName)
                .setSmallIcon(
                    when (state) {
                        DownloadType.IsDone -> imgDone
                        DownloadType.IsDownloading -> imgDownloading
                        DownloadType.IsPaused -> imgPaused
                        DownloadType.IsFailed -> imgError
                        DownloadType.IsStopped -> imgStopped
                        DownloadType.IsPending -> imgDownloading
                    }
                )

            if (ep.sourceApiName != null) {
                builder.setSubText(ep.sourceApiName)
            }

            if (source != null) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    data = source.toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent =
                    PendingIntentCompat.getActivity(context, 0, intent, 0, false)
                builder.setContentIntent(pendingIntent)
            }

            if (state == DownloadType.IsDownloading || state == DownloadType.IsPaused) {
                builder.setProgress((total / 1000).toInt(), (progress / 1000).toInt(), false)
            } else if (state == DownloadType.IsPending) {
                builder.setProgress(0, 0, true)
            }

            val rowTwoExtra = if (ep.name != null) " - ${ep.name}\n" else ""
            val rowTwo = if (ep.season != null && ep.episode != null) {
                "${context.getString(R.string.season_short)}${ep.season}:${context.getString(R.string.episode_short)}${ep.episode}" + rowTwoExtra
            } else if (ep.episode != null) {
                "${context.getString(R.string.episode)} ${ep.episode}" + rowTwoExtra
            } else {
                (ep.name ?: "") + ""
            }
            val downloadFormat = context.getString(R.string.download_format)

            if (SDK_INT >= Build.VERSION_CODES.O) {
                if (ep.poster != null) {
                    val poster = withContext(Dispatchers.IO) {
                        context.getImageBitmapFromUrl(ep.poster)
                    }
                    if (poster != null)
                        builder.setLargeIcon(poster)
                }

                val progressPercentage: Long
                val progressMbString: String
                val totalMbString: String
                val suffix: String

                val mbFormat = "%.1f MB"

                if (hlsProgress != null && hlsTotal != null) {
                    progressPercentage = hlsProgress * 100 / hlsTotal
                    progressMbString = hlsProgress.toString()
                    totalMbString = hlsTotal.toString()
                    suffix = " - $mbFormat".format(progress / 1000000f)
                } else {
                    progressPercentage = progress * 100 / total
                    progressMbString = mbFormat.format(progress / 1000000f)
                    totalMbString = mbFormat.format(total / 1000000f)
                    suffix = ""
                }

                val mbPerSecondString =
                    if (state == DownloadType.IsDownloading) {
                        " ($mbFormat/s)".format(bytesPerSecond.toFloat() / 1000000f)
                    } else ""

                val remainingTime =
                    if (state == DownloadType.IsDownloading) {
                        getEstimatedTimeLeft(context, bytesPerSecond, progress, total)
                    } else ""

                val bigText =
                    when (state) {
                        DownloadType.IsDownloading, DownloadType.IsPaused -> {
                            (if (linkName == null) "" else "$linkName\n") + "$rowTwo\n$progressPercentage % ($progressMbString/$totalMbString)$suffix$mbPerSecondString $remainingTime"
                        }

                        DownloadType.IsPending -> {
                            (if (linkName == null) "" else "$linkName\n") + rowTwo
                        }

                        DownloadType.IsFailed -> {
                            downloadFormat.format(
                                context.getString(R.string.download_failed),
                                rowTwo
                            )
                        }

                        DownloadType.IsDone -> {
                            downloadFormat.format(context.getString(R.string.download_done), rowTwo)
                        }

                        DownloadType.IsStopped -> {
                            downloadFormat.format(
                                context.getString(R.string.download_canceled),
                                rowTwo
                            )
                        }
                    }

                val bodyStyle = NotificationCompat.BigTextStyle()
                bodyStyle.bigText(bigText)
                builder.setStyle(bodyStyle)
            } else {
                val txt =
                    when (state) {
                        DownloadType.IsDownloading, DownloadType.IsPaused, DownloadType.IsPending -> {
                            rowTwo
                        }

                        DownloadType.IsFailed -> {
                            downloadFormat.format(
                                context.getString(R.string.download_failed),
                                rowTwo
                            )
                        }

                        DownloadType.IsDone -> {
                            downloadFormat.format(context.getString(R.string.download_done), rowTwo)
                        }

                        DownloadType.IsStopped -> {
                            downloadFormat.format(
                                context.getString(R.string.download_canceled),
                                rowTwo
                            )
                        }
                    }

                builder.setContentText(txt)
            }

            if ((state == DownloadType.IsDownloading || state == DownloadType.IsPaused || state == DownloadType.IsPending) && SDK_INT >= Build.VERSION_CODES.O) {
                val actionTypes: MutableList<DownloadActionType> = ArrayList()
                // INIT
                if (state == DownloadType.IsDownloading) {
                    actionTypes.add(DownloadActionType.Pause)
                    actionTypes.add(DownloadActionType.Stop)
                }

                if (state == DownloadType.IsPaused) {
                    actionTypes.add(DownloadActionType.Resume)
                    actionTypes.add(DownloadActionType.Stop)
                }
                if (state == DownloadType.IsPending) {
                    actionTypes.add(DownloadActionType.Stop)
                }

                // ADD ACTIONS
                for ((index, i) in actionTypes.withIndex()) {
                    val actionResultIntent = Intent(context, VideoDownloadService::class.java)

                    actionResultIntent.putExtra(
                        "type", when (i) {
                            DownloadActionType.Resume -> "resume"
                            DownloadActionType.Pause -> "pause"
                            DownloadActionType.Stop -> "stop"
                        }
                    )

                    actionResultIntent.putExtra("id", ep.id)

                    val pending: PendingIntent = PendingIntent.getService(
                        // BECAUSE episodes lying near will have the same id +1, index will give the same requested as the previous episode, *100000 fixes this
                        context, (4337 + index * 1000000 + ep.id),
                        actionResultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    builder.addAction(
                        NotificationCompat.Action(
                            when (i) {
                                DownloadActionType.Resume -> pressToResumeIcon
                                DownloadActionType.Pause -> pressToPauseIcon
                                DownloadActionType.Stop -> pressToStopIcon
                            }, when (i) {
                                DownloadActionType.Resume -> context.getString(R.string.resume)
                                DownloadActionType.Pause -> context.getString(R.string.pause)
                                DownloadActionType.Stop -> context.getString(R.string.cancel)
                            }, pending
                        )
                    )
                }
            }

            if (!hasCreatedNotChannel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            notificationCallback(ep.id, notification)
            with(NotificationManagerCompat.from(context)) {
                // notificationId is a unique int for each notification that you must define
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return null
                }
                notify(DOWNLOAD_NOTIFICATION_TAG, ep.id, notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }


    @Throws(IOException::class)
    fun setupStream(
        context: Context,
        name: String,
        folder: String?,
        extension: String,
        tryResume: Boolean,
    ): StreamData {
        return setupStream(
            context.getBasePath().first ?: getDefaultDir(context)
            ?: throw IOException("Bad config"),
            name,
            folder,
            extension,
            tryResume
        )
    }

    /**
     * Sets up the appropriate file and creates a data stream from the file.
     * Used for initializing downloads and backups.
     * */
    @Throws(IOException::class)
    fun setupStream(
        baseFile: SafeFile,
        name: String,
        folder: String?,
        extension: String,
        tryResume: Boolean,
    ): StreamData {
        val displayName = getDisplayName(name, extension)

        val subDir = baseFile.gotoDirectory(folder, createMissingDirectories = true)
            ?: throw IOException("Cant create directory")
        val foundFile = subDir.findFile(displayName)

        val (file, fileLength) = if (foundFile == null || foundFile.exists() != true) {
            subDir.createFileOrThrow(displayName) to 0L
        } else {
            if (tryResume) {
                foundFile to foundFile.lengthOrThrow()
            } else {
                foundFile.deleteOrThrow()
                subDir.createFileOrThrow(displayName) to 0L
            }
        }

        return StreamData(fileLength, file)
    }

    /** This class handles the notifications, as well as the relevant key */
    data class DownloadMetaData(
        private val id: Int?,
        private val linkHash : Int,
        // Audit-4 #10: these counters are mutated by the parallel
        // download jobs. @Volatile prevents torn 64-bit reads on
        // 32-bit devices, and every compound mutation (+=, the
        // notify() rate pair) is guarded by counterLock below —
        // previously a lost update could make the final
        // completeness check judge a whole download as failed.
        @Volatile
        var bytesDownloaded: Long = 0,
        @Volatile
        var bytesWritten: Long = 0,

        var totalBytes: Long? = null,

        // notification metadata
        @Volatile
        private var lastUpdatedMs: Long = 0,
        @Volatile
        private var lastDownloadedBytes: Long = 0,
        private val createNotificationCallback: (CreateNotificationMetadata) -> Unit,

        // Audit-13 #6: @Volatile like every neighbouring flag
        // (Audit-4 #10 missed this one) — the pause-wait loops
        // read ONLY this flag, written from the service thread
        // via the event listener with no lock, so without a
        // happens-before edge a Resume could formally never be
        // seen and the download would sit "paused" forever.
        @Volatile
        private var internalType: DownloadType = DownloadType.IsPending,
        val isHLS : Boolean,
        // how many segments that we have downloaded
        @Volatile
        var hlsProgress: Int = 0,
        // how many segments that exist
        var hlsTotal: Int? = null,
        // this is how many segments that has been written to the file
        // will always be <= hlsProgress as we may keep some in a buffer
        @Volatile
        var hlsWrittenProgress: Int = 0,

        // this is used for copy with metadata on how much we have downloaded for setKey
        private var downloadFileInfoTemplate: DownloadedFileInfo? = null
    ) : Closeable {
        // Audit-4 #1: the HLS resume checkpoint pairs the written
        // segment count with the exact file length at that count.
        // The pair is only ever replaced as a whole (from inside the
        // download write lock, or when a download starts), so a
        // persisted pair always describes a real prefix of the file.
        // The old count-only extraInfo could not detect the extra
        // segments an abrupt kill leaves in the file tail, and resume
        // appended duplicates of them.
        @Volatile
        private var hlsCheckpoint: Pair<Int, Long> = Pair(0, 0L)

        // Audit-5 #9: fingerprint of the playlist the checkpoint
        // belongs to (segment URL paths + durations), persisted as
        // the third extraInfo component. The (count, bytes) pair
        // alone cannot tell that the content behind a stable m3u8
        // URL changed between sessions.
        @Volatile
        var hlsPlaylistFingerprint: String? = null

        // Audit-4 #37: segment index of the checkpoint as of the
        // last updateFileInfo() persist (any path). notify() uses
        // it to avoid re-persisting every second.
        @Volatile
        private var lastPersistedHlsIndex: Int = 0

        fun setHlsCheckpoint(index: Int, bytes: Long) {
            hlsCheckpoint = Pair(index, bytes)
        }

        // Audit-4 #10: guards every compound counter mutation.
        private val counterLock = Any()

        fun setResumeLength(length: Long) {
            synchronized(counterLock) {
                bytesDownloaded = length
                bytesWritten = length
                lastDownloadedBytes = length
            }
        }

        /** Returns the appropriate failed status based on download progress */
        fun failedStatus() = if (this.bytesWritten > DOWNLOAD_PARTIAL_MIN_SIZE)
            DOWNLOAD_PARTIAL_SUCCESS
        else
            DOWNLOAD_FAILED

        val approxTotalBytes: Long
            // Fork fix (audit #18): hlsProgress is 0 before the first
            // segment; dividing by it produced NaN/Infinity estimates.
            get() = totalBytes ?: hlsTotal?.let { total ->
                if (hlsProgress <= 0) bytesDownloaded
                else (bytesDownloaded * (total / hlsProgress.toFloat())).toLong()
            } ?: bytesDownloaded

        private var stopListener: (() -> Unit)? = null

        /** on cancel button pressed or failed invoke this once and only once */
        fun setOnStop(callback: (() -> Unit)) {
            stopListener = callback
        }

        fun removeStopListener() {
            stopListener = null
        }

        private val downloadEventListener = { event: Pair<Int, DownloadActionType> ->
            if (event.first == id) {
                when (event.second) {
                    DownloadActionType.Pause -> {
                        type = DownloadType.IsPaused
                    }

                    DownloadActionType.Stop -> {
                        type = DownloadType.IsStopped
                        stopListener?.invoke()
                        stopListener = null
                    }

                    DownloadActionType.Resume -> {
                        type = DownloadType.IsDownloading
                    }
                }
            }
        }

        private fun updateFileInfo() {
            if (id == null) return
            downloadFileInfoTemplate?.let { template ->
                /** This looks strange, but fixes an issue where we do an instant retry, and it fails immediately,
                 * eg. by turning off wifi */
                val totalBytesValue = if (approxTotalBytes <= bytesDownloaded) {
                    val prevInfo = getKey<DownloadedFileInfo>(
                        KEY_DOWNLOAD_INFO,
                        id.toString()
                    )

                    /** If this link is the same as the last cached video link metadata */
                    if (prevInfo != null && prevInfo.linkHash == linkHash) {
                        /** Try to use totalBytes if it exists, otherwise the max of the prev data,
                         * and download size to ensure total >= downloaded */
                        totalBytes ?: maxOf(prevInfo.totalBytes, bytesDownloaded)
                    } else {
                        approxTotalBytes
                    }
                } else {
                    approxTotalBytes
                }

                setKey(
                    KEY_DOWNLOAD_INFO,
                    id.toString(),
                    template.copy(
                        linkHash = linkHash,
                        totalBytes = totalBytesValue,
                        extraInfo = if (isHLS) {
                            // Audit-4 #1: persist the (segment count,
                            // file byte length) checkpoint pair.
                            // Audit-5 #9: plus the playlist
                            // fingerprint as a third component.
                            val checkpoint = hlsCheckpoint
                            val base = "${checkpoint.first}:${checkpoint.second}"
                            val fingerprint = hlsPlaylistFingerprint
                            if (fingerprint != null) "$base:$fingerprint" else base
                        } else template.extraInfo
                        // Audit-14 #1: the plain-file branch used
                        // to write null here, discarding the
                        // "v1:" content validator the template
                        // carries (Audit-7 #3) — the stored
                        // validator was therefore always null and
                        // the resume check never fired.
                    )
                )
                // Audit-4 #37: every persist path funnels through
                // here, so this keeps the notify() gate accurate.
                if (isHLS) {
                    lastPersistedHlsIndex = hlsCheckpoint.first
                }
            }
        }

        fun setDownloadFileInfoTemplate(template: DownloadedFileInfo) {
            downloadFileInfoTemplate = template
            updateFileInfo()
        }

        init {
            if (id != null) {
                downloadEvent += downloadEventListener
            }
        }

        override fun close() {
            // as we may need to resume hls downloads, we save the current written index
            if (isHLS || totalBytes == null) {
                updateFileInfo()
            }
            if (id != null) {
                downloadEvent -= downloadEventListener
                downloadStatus -= id
                // Audit-13 #18: evict the file-info cache too —
                // an entry cached just before this download ran
                // (partial size) became cacheable again the
                // moment downloadStatus dropped the id, and was
                // served as current for the rest of its TTL.
                fileInfoCache.remove(id)
            }
            stopListener = null
        }

        var type
            get() = internalType
            set(value) {
                internalType = value
                notify()
            }

        fun onDelete() {
            bytesDownloaded = 0
            hlsWrittenProgress = 0
            hlsProgress = 0
            if (id != null)
                downloadDeleteEvent(id)

            //internalType = DownloadType.IsStopped
            notify()
        }

        companion object {
            const val UPDATE_RATE_MS: Long = 1000L
        }

        @JvmName("DownloadMetaDataNotify")
        private fun notify() {
            // Audit-4 #10: snapshot the rate pair and the progress
            // values under counterLock (this answers the old
            // "TODO MUTEX?" below). The callbacks run outside the
            // lock so a listener can never deadlock against a
            // counter update.
            val snapshot = synchronized(counterLock) {
                // max 10 sec between notifications, min 0.1s, this is to stop div by zero
                val dt = (System.currentTimeMillis() - lastUpdatedMs).coerceIn(100, 10000)

                val bytesPerSecond =
                    ((bytesDownloaded - lastDownloadedBytes) * 1000L) / dt

                lastDownloadedBytes = bytesDownloaded
                lastUpdatedMs = System.currentTimeMillis()

                NotifySnapshot(
                    bytesPerSecond = bytesPerSecond,
                    bytesDownloaded = bytesDownloaded,
                    approxTotalBytes = approxTotalBytes,
                    hlsProgress = hlsProgress,
                    type = internalType
                )
            }
            try {
                val bytes = snapshot.approxTotalBytes

                // notification creation
                if (isHLS) {
                    createNotificationCallback(
                        CreateNotificationMetadata(
                            snapshot.type,
                            snapshot.bytesDownloaded,
                            bytes,
                            hlsTotal = hlsTotal?.toLong(),
                            hlsProgress = snapshot.hlsProgress.toLong(),
                            bytesPerSecond = snapshot.bytesPerSecond
                        )
                    )
                } else {
                    createNotificationCallback(
                        CreateNotificationMetadata(
                            snapshot.type,
                            snapshot.bytesDownloaded,
                            bytes,
                            bytesPerSecond = snapshot.bytesPerSecond
                        )
                    )
                }

                // as hls has an approx file size we want to update this metadata
                if (isHLS) {
                    // Audit-4 #37: notify() runs ~1x/sec, and
                    // persisting here unconditionally rewrote the
                    // whole preferences file every second for the
                    // entire download, defeating the Audit-3 #6
                    // every-10-segments batching in
                    // setWrittenSegment. Persist from here only
                    // when the checkpoint advanced >= 10 segments
                    // since the last persist (from any path) or on
                    // a terminal state; close() and the type setter
                    // still persist unconditionally, so the
                    // Audit-4 #1 resume guarantee is unchanged.
                    val advanced = hlsCheckpoint.first - lastPersistedHlsIndex
                    if (advanced >= 10 ||
                        snapshot.type == DownloadType.IsStopped ||
                        snapshot.type == DownloadType.IsFailed
                    ) {
                        updateFileInfo()
                    }
                }

                if (snapshot.type == DownloadType.IsStopped || snapshot.type == DownloadType.IsFailed) {
                    stopListener?.invoke()
                    stopListener = null
                }

                // push all events, this *should* not crash
                if (id != null) {
                    downloadStatus[id] = type
                    downloadProgressEvent(Triple(id, snapshot.bytesDownloaded, bytes))
                    downloadStatusEvent(id to type)
                }
            } catch (t: Throwable) {
                // Audit-4 #42: this used to rethrow on DEBUG
                // builds, crashing the download loop mid-download
                // for any notification-path failure — the exact
                // thing the "should not crash" comment above says
                // must not happen. Log only, on every build type.
                logError(t)
            }
        }

        // Audit-4 #10: one consistent set of progress values for a
        // single notify() pass, captured under counterLock.
        private data class NotifySnapshot(
            val bytesPerSecond: Long,
            val bytesDownloaded: Long,
            val approxTotalBytes: Long,
            val hlsProgress: Int,
            val type: DownloadType
        )

        private fun checkNotification() {
            if (lastUpdatedMs + UPDATE_RATE_MS > System.currentTimeMillis()) return
            notify()
        }


        /** adds the length and pushes a notification if necessary */
        fun addBytes(length: Long) {
            // Audit-4 #10: += on a plain var lost updates when the
            // parallel download jobs raced.
            synchronized(counterLock) {
                bytesDownloaded += length
            }
            // we don't want to update the notification after it is paused,
            // download progress may not stop directly when we "pause" it
            if (type == DownloadType.IsDownloading) checkNotification()
        }

        fun addBytesWritten(length: Long) {
            synchronized(counterLock) {
                bytesWritten += length
            }
        }

        /** adds the length + hsl progress and pushes a notification if necessary */
        fun addSegment(length: Long) {
            // Audit-4 #10: both counters move as one locked step.
            synchronized(counterLock) {
                hlsProgress += 1
                bytesDownloaded += length
            }
            if (type == DownloadType.IsDownloading) checkNotification()
        }

        fun setWrittenSegment(segmentIndex: Int) {
            // Audit-4 #10: the progress + checkpoint update is one
            // locked step; the prefs write itself stays outside.
            val shouldPersist = synchronized(counterLock) {
                hlsWrittenProgress = segmentIndex + 1
                // Audit-4 #1: bytesWritten was already updated by
                // addBytesWritten just before this call (both write paths),
                // so this pair always describes the file's current prefix.
                hlsCheckpoint = Pair(hlsWrittenProgress, bytesWritten)
                hlsWrittenProgress % 10 == 0
            }
            // Audit-3 #6: persisting rewrites the whole prefs file,
            // so doing it for every segment made HLS downloads write
            // the same XML hundreds of times. Every 10 segments is
            // enough: close() and every state transition still call
            // updateFileInfo(), so an abrupt kill loses at most the
            // last few segments of resume progress — and since
            // Audit-4 #1 the resume side verifies the file length
            // against the persisted checkpoint before appending, so
            // the segments past it can no longer be duplicated.
            if (shouldPersist) {
                updateFileInfo()
            }
        }
    }


    data class LazyStreamDownloadData(
        private val url: String,
        private val headers: Map<String, String>,
        private val referer: String,
        /** This specifies where chunk i starts and ends,
         * bytes=${chuckStartByte[ i ]}-${chuckStartByte[ i+1 ] -1}
         * where out of bounds => bytes=${chuckStartByte[ i ]}- */
        private val chuckStartByte: LongArray,
        val totalLength: Long?,
        val downloadLength: Long?,
        val chuckSize: Long,
        val bufferSize: Int,
        val isResumed: Boolean,
        // Audit-7 #3: content validator (ETag, falling back to
        // Last-Modified) seen by the probe, so a resume can tell
        // whether the remote file is still the same content the
        // partial bytes came from.
        val contentValidator: String? = null,
    ) {
        val size get() = chuckStartByte.size

        /** returns what byte it has downloaded,
         * so start at 10 and download 4 bytes = return 14
         *
         * the range is [startByte, endByte) to be able to do [a, b) [b, c) ect
         *
         * [a, null) will return inclusive to eof = [a, eof]
         *
         * throws an error if initial get request fails, can be specified as return startByte
         * */
        @Throws
        private suspend fun resolve(
            startByte: Long,
            endByte: Long?,
            buffer: ByteArray,
            callback: (suspend CoroutineScope.(LazyStreamDownloadResponse) -> Unit)
        ): Long = withContext(Dispatchers.IO) {
            var currentByte: Long = startByte
            val stopAt = endByte ?: Long.MAX_VALUE
            if (currentByte >= stopAt) return@withContext currentByte

            val request = app.get(
                url,
                headers = headers + mapOf(
                    // range header is inclusive so [startByte, endByte-1] = [startByte, endByte)
                    // if nothing at end the server will continue until eof
                    "Range" to "bytes=$startByte-" // ${endByte?.minus(1)?.toString() ?: "" }
                ),
                referer = referer,
            )
            // Audit-6 #3: the body was trusted to start at
            // startByte without ever looking at the status code
            // (only the one-time probe in streamLazy checks for
            // 206). A parallel chunk answered 200 — a CDN node
            // ignoring Range — had the start of the file written
            // at this chunk's offset; the total length still
            // matched, so the final size check declared the
            // corrupt file done. An error status would likewise
            // write the error page into the file. Fail the chunk
            // instead (IllegalStateException = no retries in
            // resolveSafe, partial file kept for resume).
            if (request.code !in 200..299 ||
                (startByte > 0 && request.code != 206)
            ) {
                val code = request.code
                request.body.close()
                throw IllegalStateException(
                    "Chunk at byte $startByte answered with HTTP $code"
                )
            }
            val requestStream = request.body.byteStream()

            var read: Int
            var writeFailure: Throwable? = null

            try {
                while (requestStream.read(buffer, 0, bufferSize).also { read = it } >= 0) {
                    val start = currentByte
                    currentByte += read.toLong()

                    // this stops overflow
                    val response = if (currentByte >= stopAt) {
                        LazyStreamDownloadResponse(buffer, start, stopAt)
                    } else {
                        LazyStreamDownloadResponse(buffer, start, currentByte)
                    }
                    try {
                        callback(response)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // Audit-5 #1: the callback writes to the
                        // file (and drains the pending queue). A
                        // failure there — disk full, SAF provider
                        // error — is NOT a network EOF, but the
                        // catch-all below used to swallow it as
                        // one: currentByte had already advanced
                        // past the unwritten bytes, so resolveSafe
                        // reported the chunk done, later data piled
                        // into pendingData behind the permanent
                        // gap, and the download either hung on the
                        // 16 MB throttle forever or was declared
                        // IsDone over a corrupt file (the final
                        // check compares bytesDownloaded, which the
                        // callback increments before writing, never
                        // bytesWritten). Fail the chunk instead:
                        // resolveSafe maps IllegalStateException to
                        // a failed chunk with no retries, and the
                        // partial file is kept for resume.
                        writeFailure = t
                        break
                    }
                    if (currentByte >= stopAt) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError(t)
            } finally {
                requestStream.closeQuietly()
            }

            writeFailure?.let {
                throw IllegalStateException("Download write callback failed", it)
            }

            return@withContext currentByte
        }

        /** retries the resolve n times and returns true if successful */
        suspend fun resolveSafe(
            index: Int,
            retries: Int = 3,
            buffer: ByteArray,
            callback: (suspend CoroutineScope.(LazyStreamDownloadResponse) -> Unit)
        ): Boolean {
            var start = chuckStartByte.getOrNull(index) ?: return false
            val end = chuckStartByte.getOrNull(index + 1)

            for (i in 0 until retries) {
                try {
                    // in case
                    start = resolve(start, end, buffer, callback)
                    // no end defined, so we don't care exactly where it ended
                    if (end == null) return true
                    // we have download more or exactly what we needed
                    if (start >= end) return true
                } catch (_: IllegalStateException) {
                    return false
                } catch (_: CancellationException) {
                    return false
                } catch (_: Throwable) {
                    continue
                }
            }
            return false
        }
    }

    @Throws
    suspend fun streamLazy(
        url: String,
        headers: Map<String, String>,
        referer: String,
        startByte: Long,
        /** how many bytes every connection should be, by default it is 10 MiB */
        chuckSize: Long = (1 shl 20) * 10,
        /** maximum bytes in the buffer that responds */
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        /** how many bytes bytes it should require to use the parallel downloader instead,
         * if we download a very small file we don't want it parallel */
        maximumSmallSize: Long = chuckSize * 2
    ): LazyStreamDownloadData {
        // we don't want to make a separate connection for every 1kb
        require(chuckSize > 1000)

        val headRequest = app.head(url = url, headers = headers, referer = referer)
        var contentLength = headRequest.size
        if (contentLength != null && contentLength <= 0) contentLength = null

        val hasRangeSupport = when (headRequest.headers["Accept-Ranges"]?.lowercase()?.trim()) {
            // server has stated it has no support
            "none" -> false
            // server has stated it has support
            "bytes" -> true
            // if null or undefined (as bytes is the only range unit formally defined)
            // If the get request returns partial content we support range
            else -> {
                headRequest.headers["Accept-Ranges"]?.let { range ->
                    Log.v(TAG, "Unknown Accept-Ranges tag: $range")
                }
                // as we don't poll the body this should be fine
                val getRequest = app.get(
                    url,
                    headers = headers + mapOf(
                        "Range" to "bytes=0-${
                            // we don't want to request more than the actual file
                            // but also more than 0 bytes
                            contentLength?.let { max ->
                                minOf(maxOf(max - 1L, 3L), 1023L)
                            } ?: 1023L
                        }"
                    ),
                    referer = referer,
                )
                // if head request did not work then we can just look for the size here too
                // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Range
                if (contentLength == null) {
                    contentLength =
                        getRequest.headers["Content-Range"]?.trim()?.lowercase()?.let { range ->
                            // we only support "bytes" unit
                            if (range.startsWith("bytes")) {
                                // may be '*' if unknown
                                range.substringAfter("/").toLongOrNull()
                            } else {
                                Log.v(TAG, "Unknown Content-Range unit: $range")
                                null
                            }
                        }
                }

                // supports range if status is partial content https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/206
                (getRequest.code == 206).also {
                    // Audit-3 #30: the probe body was never consumed
                    // or closed, so its pooled connection stayed held
                    // until the socket timed out.
                    getRequest.body.close()
                }
            }
        }

        Log.d(
            TAG,
            "Starting stream with url=$url, startByte=$startByte, contentLength=$contentLength, hasRangeSupport=$hasRangeSupport"
        )

        var downloadLength: Long? = null

        val ranges = if (!hasRangeSupport) {
            // is the equivalent of [0..EOF] as we cant resume, nor can parallelize it
            downloadLength = contentLength
            LongArray(1) { 0 }
        } else if (contentLength == null || contentLength < maximumSmallSize) {
            if (contentLength != null) {
                downloadLength = contentLength - startByte
            }
            // is the equivalent of [startByte..EOF] as we don't know the size we can only do one
            // connection
            LongArray(1) { startByte }
        } else {
            downloadLength = contentLength - startByte
            // div with ceiling as
            // this makes the last part "unknown ending" and it will break at EOF
            // so eg startByte = 0, downloadLength = 13, chuckSize = 10
            // = LongArray(2) { 0, 10 } = [0,10) + [10..EOF]
            LongArray(((downloadLength + chuckSize - 1) / chuckSize).toInt()) { idx ->
                startByte + idx * chuckSize
            }
        }

        return LazyStreamDownloadData(
            url = url,
            headers = headers,
            referer = referer,
            chuckStartByte = ranges,
            downloadLength = downloadLength,
            totalLength = contentLength,
            chuckSize = chuckSize,
            bufferSize = bufferSize,
            // we have only resumed if we had a downloaded file and we can resume
            isResumed = startByte > 0 && hasRangeSupport,
            // Audit-7 #3: ETag is the strong validator; fall back
            // to Last-Modified when the server sends no ETag.
            contentValidator = headRequest.headers["ETag"]
                ?: headRequest.headers["Last-Modified"]
        )
    }


    /** download a file that consist of a single stream of data*/
    suspend fun downloadThing(
        context: Context,
        link: IDownloadableMinimum,
        name: String,
        folder: String,
        extension: String,
        tryResume: Boolean,
        parentId: Int?,
        createNotificationCallback: (CreateNotificationMetadata) -> Unit,
        parallelConnections: Int = 3,
        /** how many bytes a valid file must be in bytes,
         * this should be different for subtitles and video */
        minimumSize: Long = 100
    ): DownloadStatus = withContext(Dispatchers.IO) {
        if (parallelConnections < 1) {
            return@withContext DOWNLOAD_INVALID_INPUT
        }

        var fileStream: OutputStream? = null
        //var requestStream: InputStream? = null
        val metadata = DownloadMetaData(
            totalBytes = 0,
            bytesDownloaded = 0,
            createNotificationCallback = createNotificationCallback,
            id = parentId,
            linkHash = link.url.hashCode(),
            isHLS = false
        )
        try {
            // get the file path
            val (baseFile, basePath) = context.getBasePath()
            val displayName = getDisplayName(name, extension)
            if (baseFile == null) return@withContext DOWNLOAD_BAD_CONFIG

            // set up the download file
            var stream = setupStream(baseFile, name, folder, extension, tryResume)

            fileStream = stream.open()

            metadata.setResumeLength(stream.startAt)
            metadata.type = DownloadType.IsPending

            var items = streamLazy(
                url = link.url.replace(" ", "%20"),
                referer = link.referer,
                startByte = stream.startAt,
                headers = link.headers.appendAndDontOverride(
                    mapOf(
                        "user-agent" to USER_AGENT,
                    )
                )
            )

            // too short file, treat it as a invalid link
            if (items.totalLength != null && items.totalLength < minimumSize) {
                fileStream.closeQuietly()
                // Audit-5 #8: on a RESUME, the partial file belongs
                // to the download, not to this mirror — a mirror
                // answering HEAD with a tiny Content-Length (error
                // or geo-block page) must not destroy it. Skip the
                // delete/onDelete so the next mirror can resume
                // the same partial (DOWNLOAD_INVALID_INPUT has
                // tryNext = true). Fresh downloads keep the
                // cleanup: their just-created stub should not
                // linger.
                if (stream.startAt <= 0) {
                    metadata.onDelete()
                    stream.delete()
                }
                return@withContext DOWNLOAD_INVALID_INPUT
            }

            // Audit-7 #3: the validator stored by the previous
            // run of this download (in the info's extraInfo as
            // "v1:<etag-or-last-modified>"). A changed remote
            // file at the same URL — re-upload, re-encode — used
            // to be resumed blindly on byte offset alone, gluing
            // the new tail onto the old head; the final byte
            // count still matched, so the corrupt hybrid was
            // marked done. The HLS side got its playlist
            // fingerprint in Audit-5 #9; this is the plain-file
            // counterpart.
            val storedValidator = if (tryResume && stream.startAt > 0) {
                parentId?.let { id ->
                    context.getKey<DownloadedFileInfo>(
                        KEY_DOWNLOAD_INFO, id.toString(), null
                    )?.extraInfo?.removePrefix("v1:")?.takeIf { it.isNotEmpty() }
                }
            } else null
            val validatorChanged = storedValidator != null &&
                items.contentValidator != null &&
                storedValidator != items.contentValidator

            // if we have an output stream that cant be resumed then we delete the entire file
            // and set up the stream again; the same applies when the
            // partial file is longer than the remote file is now —
            // the remote content changed since the partial download
            // and appending to it would produce a corrupt hybrid
            if (stream.startAt > 0 && (!items.isResumed || validatorChanged ||
                        (items.totalLength != null && stream.startAt > items.totalLength))
            ) {
                fileStream.closeQuietly()
                stream.delete()
                metadata.setResumeLength(0)
                stream = setupStream(baseFile, name, folder, extension, false)
                fileStream = stream.open()
                // Audit-8 #1: the chunk table was built from the
                // OLD resume offset — after this restart every
                // chunk's startByte sat above the write cursor
                // (now 0), so chunks only ever buffered: past
                // 16 MB buffered the jobs spun in the throttle
                // loop forever, below it the download failed
                // having written nothing. Rebuild the table for
                // the fresh stream.
                items = streamLazy(
                    url = link.url.replace(" ", "%20"),
                    referer = link.referer,
                    startByte = stream.startAt,
                    headers = link.headers.appendAndDontOverride(
                        mapOf(
                            "user-agent" to USER_AGENT,
                        )
                    )
                )
            }

            metadata.totalBytes = items.totalLength
            metadata.type = DownloadType.IsDownloading
            metadata.setDownloadFileInfoTemplate(
                DownloadedFileInfo(
                    totalBytes = metadata.approxTotalBytes,
                    relativePath = folder,
                    displayName = displayName,
                    basePath = basePath,
                    // Audit-7 #3: persist the content validator so
                    // the next resume can detect a replaced file.
                    extraInfo = items.contentValidator?.let { "v1:$it" }
                )
            )

            val currentMutex = Mutex()
            val current = (0 until items.size).iterator()

            val fileMutex = Mutex()
            // start to data
            val pendingData: HashMap<Long, LazyStreamDownloadResponse> =
                hashMapOf()

            val fileChecker = launch(Dispatchers.IO) {
                while (isActive) {
                    if (stream.exists) {
                        delay(5000)
                        continue
                    }
                    fileMutex.withLock {
                        metadata.type = DownloadType.IsStopped
                    }
                    break
                }
            }

            val jobs = (0 until parallelConnections).map {
                launch(Dispatchers.IO) {

                    // @downloadexplanation
                    // this may seem a bit complex but it more or less acts as a queue system
                    // imagine we do the downloading [0,3] and it response in the order 0,2,3,1
                    // file: [_,_,_,_] queue: [_,_,_,_] Initial condition
                    // file: [X,_,_,_] queue: [_,_,_,_] + added 0 directly to file
                    // file: [X,_,_,_] queue: [_,_,X,_] + added 2 to queue
                    // file: [X,_,_,_] queue: [_,_,X,X] + added 3 to queue
                    // file: [X,X,_,_] queue: [_,_,X,X] + added 1 directly to file
                    // file: [X,X,X,X] queue: [_,_,_,_] write the queue and remove from it

                    // note that this is a bit more complex compared to hsl as ever segment
                    // will return several bytearrays, and is therefore chained by the byte
                    // so every request has a front and back byte instead of an index
                    // this *requires* that no gap exist due because of resolve
                    val callback: (suspend CoroutineScope.(LazyStreamDownloadResponse) -> Unit) =
                        callback@{ response ->
                            if (!isActive) return@callback
                            fileMutex.withLock {
                                // wait until not paused
                                while (metadata.type == DownloadType.IsPaused) delay(100)
                                // if stopped then throw
                                if (metadata.type == DownloadType.IsStopped || metadata.type == DownloadType.IsFailed) {
                                    this.cancel()
                                    return@callback
                                }

                                val responseSize = response.size
                                metadata.addBytes(response.size)

                                if (response.startByte == metadata.bytesWritten) {
                                    // if we are first in the queue then write it directly
                                    fileStream.write(
                                        response.bytes,
                                        0,
                                        responseSize.toInt()
                                    )
                                    metadata.addBytesWritten(responseSize)
                                } else {
                                    // otherwise append to queue, we need to clone the bytes as they will be overridden otherwise
                                    pendingData[response.startByte] =
                                        response.copy(bytes = response.bytes.clone())
                                }

                                while (true) {
                                    // remove the current queue start, so no possibility of
                                    // while(true) { continue } in case size = 0, and removed extra
                                    // garbage
                                    val pending = pendingData.remove(metadata.bytesWritten) ?: break

                                    val size = pending.size

                                    fileStream.write(
                                        pending.bytes,
                                        0,
                                        size.toInt()
                                    )
                                    metadata.addBytesWritten(size)
                                }
                            }
                        }

                    // Reuse a download buffer to decrease unnecessary alloc
                    val buffer = ByteArray(items.bufferSize)

                    // This will take up the first available job and resolve
                    while (true) {
                        if (!isActive) return@launch

                        var isTooFarAhead = false
                        fileMutex.withLock {
                            if (metadata.type == DownloadType.IsStopped
                                || metadata.type == DownloadType.IsFailed
                            ) return@launch

                            // Limit RAM usage by throttling if too much data is downloaded but not yet written to disk
                            // 16MB limit (Audit-3 #7: 50MB of buffered segments risks OOM on low-RAM STBs)
                            if (metadata.bytesDownloaded - metadata.bytesWritten > 16_000_000) {
                                isTooFarAhead = true
                            }
                        }

                        if (isTooFarAhead) {
                            delay(500)
                            continue
                        }

                        // mutex just in case, we never want this to fail due to multithreading
                        val index = currentMutex.withLock {
                            if (!current.hasNext()) return@launch
                            current.nextInt()
                        }

                        // in case something has gone wrong set to failed if the fail is not caused by
                        // user cancellation
                        if (!items.resolveSafe(index, buffer = buffer, callback = callback)) {
                            fileMutex.withLock {
                                if (metadata.type != DownloadType.IsStopped) {
                                    metadata.type = DownloadType.IsFailed
                                }
                            }
                            return@launch
                        }
                    }
                }
            }

            // fast stop as the jobs may be in a slow request
            metadata.setOnStop {
                jobs.cancel()
            }

            jobs.join()
            // Audit-13 #5: cancel() alone does not wait — a
            // checker already past its exists() check and
            // queued on fileMutex could still write IsStopped
            // AFTER this download completed and closed,
            // resurrecting a ghost status entry + a "canceled"
            // notification over the "done" one.
            fileChecker.cancelAndJoin()

            // jobs are finished so we don't want to stop them anymore
            metadata.removeStopListener()
            if (!stream.exists) metadata.type = DownloadType.IsStopped

            if (metadata.type == DownloadType.IsFailed) {
                return@withContext metadata.failedStatus()
            }

            if (metadata.type == DownloadType.IsStopped) {
                // we need to close before delete
                fileStream.closeQuietly()
                metadata.onDelete()
                stream.delete()
                return@withContext DOWNLOAD_STOPPED
            }

            // in case the head request lies about content-size,
            // then we don't want shit output
            if (metadata.bytesDownloaded < minimumSize) {
                // we need to close before delete
                fileStream.closeQuietly()
                metadata.onDelete()
                stream.delete()
                return@withContext DOWNLOAD_INVALID_INPUT
            }

            // a connection dying mid-stream is swallowed by resolve()
            // as if it were EOF, so when the total size is known a
            // short byte count means the file is truncated: fail it
            // (the partial file is kept for resume, like other
            // failures) instead of reporting success
            val knownTotal = metadata.totalBytes
            if (knownTotal != null && metadata.bytesDownloaded < knownTotal) {
                metadata.type = DownloadType.IsFailed
                return@withContext metadata.failedStatus()
            }

            metadata.type = DownloadType.IsDone
            return@withContext DOWNLOAD_SUCCESS
        } catch (e: IOException) {
            // some sort of IO error, this should not happened
            // we just rethrow it
            logError(e)
            throw e
        } catch (t: Throwable) {
            // some sort of network error, will error
            logError(t)
            // note that when failing we don't want to delete the file,
            // only user interaction has that power
            metadata.type = DownloadType.IsFailed
            return@withContext metadata.failedStatus()
        } finally {
            fileStream?.closeQuietly()
            //requestStream?.closeQuietly()
            metadata.close()
        }
    }

    // Audit-5 #9: stable identity of a playlist's segment list —
    // SHA-256 over each segment's URL path (query stripped, since
    // signed tokens rotate between fetches of identical content)
    // and duration, truncated to 16 hex chars.
    private fun hlsPlaylistFingerprint(links: List<M3u8Helper2.TsLink>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (link in links) {
            digest.update(link.url.substringBefore('?').toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update((link.time?.toString() ?: "").toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private suspend fun downloadHLS(
        context: Context,
        link: ExtractorLink,
        name: String,
        folder: String,
        parentId: Int?,
        startIndex: Int?,
        startBytes: Long?,
        startFingerprint: String?,
        createNotificationCallback: (CreateNotificationMetadata) -> Unit,
        parallelConnections: Int = 3
    ): DownloadStatus = withContext(Dispatchers.IO) {
        if (parallelConnections < 1) return@withContext DOWNLOAD_INVALID_INPUT

        val metadata = DownloadMetaData(
            createNotificationCallback = createNotificationCallback,
            id = parentId,
            linkHash = link.url.hashCode(),
            isHLS = true
        )
        var fileStream: OutputStream? = null
        try {
            val extension = "mp4"

            // the start .ts index
            var startAt = startIndex ?: 0

            // set up the file data
            val (baseFile, basePath) = context.getBasePath()
            if (baseFile == null) return@withContext DOWNLOAD_BAD_CONFIG

            val displayName = getDisplayName(name, extension)
            var stream =
                setupStream(baseFile, name, folder, extension, startAt > 0)

            if (!stream.resume) {
                startAt = 0
            } else if (startBytes == null || stream.startAt != startBytes) {
                // Audit-4 #1: the file length does not match the
                // persisted (segment count, byte length) checkpoint —
                // an abrupt kill left extra segments in the tail, or
                // this is a legacy count-only entry. The excess tail
                // cannot be located or trusted, so restart this file
                // from zero instead of appending duplicate segments
                // after it. Clean pauses/stops persist the exact final
                // checkpoint in close(), so they still resume fully.
                stream = setupStream(baseFile, name, folder, extension, false)
                startAt = 0
            }
            fileStream = stream.open()

            // push the metadata
            metadata.setResumeLength(stream.startAt)
            metadata.setHlsCheckpoint(startAt, stream.startAt)
            metadata.hlsProgress = startAt
            metadata.hlsWrittenProgress = startAt
            metadata.type = DownloadType.IsPending
            metadata.setDownloadFileInfoTemplate(
                DownloadedFileInfo(
                    totalBytes = 0,
                    relativePath = folder,
                    displayName = displayName,
                    basePath = basePath
                )
            )

            // do the initial get request to fetch the segments
            val m3u8 = M3u8Helper.M3u8Stream(
                link.url, link.quality, link.headers.appendAndDontOverride(
                    mapOf(
                        "user-agent" to USER_AGENT,
                    ) + if (link.referer.isNotBlank()) mapOf("referer" to link.referer) else emptyMap()
                )
            )

            val items = M3u8Helper2.hslLazy(m3u8, selectBest = true, requireAudio = true)

            // Audit-7 #20: a valid-but-EMPTY playlist (no
            // segments at all) used to sail through: the segment
            // loop ran zero times and the final completeness
            // check compared 0 < 0, so a 0-byte file was marked
            // done. An empty playlist is an invalid link — fail
            // it so the next mirror is tried.
            if (items.size == 0) {
                fileStream?.closeQuietly()
                if (stream.startAt <= 0) {
                    metadata.onDelete()
                    stream.delete()
                }
                return@withContext DOWNLOAD_INVALID_INPUT
            }

            // Audit-5 #9: verify the playlist is still the one the
            // checkpoint was written for. The m3u8 URL is stable
            // and unsigned; if the provider swapped the encode or
            // trimmed segments between sessions, resuming would
            // append the new playlist's segments onto the old
            // prefix (a hybrid, unplayable file) — or, when the
            // new playlist is shorter than startAt, declare the
            // stale file done. The fingerprint covers segment URL
            // paths (query stripped: tokens rotate) and durations.
            val playlistFingerprint = hlsPlaylistFingerprint(items.allTsLinks)
            metadata.hlsPlaylistFingerprint = playlistFingerprint
            if (startAt > 0 && startFingerprint != null &&
                startFingerprint != playlistFingerprint
            ) {
                fileStream?.closeQuietly()
                stream = setupStream(baseFile, name, folder, extension, false)
                fileStream = stream.open()
                startAt = 0
                metadata.setResumeLength(0)
                metadata.setHlsCheckpoint(0, 0)
                metadata.hlsProgress = 0
                metadata.hlsWrittenProgress = 0
            }

            // Audit-6 #4: entries WITHOUT a fingerprint (written
            // by <= v4.9.14, or the process died before the first
            // persist) skip the check above, and nothing else
            // validated the checkpoint against this playlist: a
            // startAt beyond the segment count made the segment
            // iterator empty and hlsWrittenProgress == startAt
            // pass the final completeness check, declaring a
            // stale file from a longer, different playlist done.
            // A checkpoint that cannot belong to this playlist
            // restarts the download, fingerprint or not.
            if (startAt > items.size) {
                fileStream?.closeQuietly()
                stream = setupStream(baseFile, name, folder, extension, false)
                fileStream = stream.open()
                startAt = 0
                metadata.setResumeLength(0)
                metadata.setHlsCheckpoint(0, 0)
                metadata.hlsProgress = 0
                metadata.hlsWrittenProgress = 0
            }

            metadata.hlsTotal = items.size
            metadata.type = DownloadType.IsDownloading

            val currentMutex = Mutex()
            val current = (startAt until items.size).iterator()

            val fileMutex = Mutex()
            val pendingData: HashMap<Int, ByteArray> = hashMapOf()

            val fileChecker = launch(Dispatchers.IO) {
                while (isActive) {
                    if (stream.exists) {
                        delay(5000)
                        continue
                    }
                    fileMutex.withLock {
                        metadata.type = DownloadType.IsStopped
                    }
                    break
                }
            }

            // see @downloadexplanation for explanation of this download strategy,
            // this keeps all jobs working at all times,
            // does several connections in parallel instead of a regular for loop to improve
            // download speed
            val jobs = (0 until parallelConnections).map {
                launch(Dispatchers.IO) {
                    while (true) {
                        if (!isActive) return@launch

                        var isTooFarAhead = false
                        fileMutex.withLock {
                            if (metadata.type == DownloadType.IsStopped
                                || metadata.type == DownloadType.IsFailed
                            ) return@launch

                            // Limit RAM usage by throttling if too much data is downloaded but not yet written to disk
                            // 16MB limit (Audit-3 #7: 50MB of buffered segments risks OOM on low-RAM STBs)
                            if (metadata.bytesDownloaded - metadata.bytesWritten > 16_000_000) {
                                isTooFarAhead = true
                            }
                        }

                        if (isTooFarAhead) {
                            delay(500)
                            continue
                        }

                        // mutex just in case, we never want this to fail due to multithreading
                        val index = currentMutex.withLock {
                            if (!current.hasNext()) return@launch
                            current.nextInt()
                        }

                        // in case something has gone wrong set to failed if the fail is not caused by
                        // user cancellation
                        val bytes = items.resolveLinkSafe(index) ?: run {
                            fileMutex.withLock {
                                if (metadata.type != DownloadType.IsStopped) {
                                    metadata.type = DownloadType.IsFailed
                                }
                            }
                            return@launch
                        }

                        fileMutex.withLock {
                            try {
                                // user pause
                                while (metadata.type == DownloadType.IsPaused) delay(100)
                                // if stopped then break to delete
                                if (metadata.type == DownloadType.IsStopped || metadata.type == DownloadType.IsFailed || !isActive) return@launch

                                val segmentLength = bytes.size.toLong()
                                // send notification, no matter the actual write order
                                metadata.addSegment(segmentLength)

                                // directly write the bytes if you are first
                                if (metadata.hlsWrittenProgress == index) {
                                    fileStream.write(bytes)

                                    metadata.addBytesWritten(segmentLength)
                                    metadata.setWrittenSegment(index)
                                } else {
                                    // no need to clone as there will be no modification of this bytearray
                                    pendingData[index] = bytes
                                }

                                // write the cached bytes submitted by other threads
                                while (true) {
                                    val cache =
                                        pendingData.remove(metadata.hlsWrittenProgress) ?: break
                                    val cacheLength = cache.size.toLong()

                                    fileStream.write(cache)

                                    metadata.addBytesWritten(cacheLength)
                                    metadata.setWrittenSegment(metadata.hlsWrittenProgress)
                                }
                            } catch (t: Throwable) {
                                // this is in case of write fail
                                logError(t)
                                if (metadata.type != DownloadType.IsStopped) {
                                    metadata.type = DownloadType.IsFailed
                                }
                            }
                        }
                    }
                }
            }

            // fast stop as the jobs may be in a slow request
            metadata.setOnStop {
                jobs.cancel()
            }

            jobs.join()
            // Audit-13 #5: cancel() alone does not wait — a
            // checker already past its exists() check and
            // queued on fileMutex could still write IsStopped
            // AFTER this download completed and closed,
            // resurrecting a ghost status entry + a "canceled"
            // notification over the "done" one.
            fileChecker.cancelAndJoin()

            metadata.removeStopListener()

            if (!stream.exists) metadata.type = DownloadType.IsStopped

            if (metadata.type == DownloadType.IsFailed) {
                return@withContext metadata.failedStatus()
            }

            if (metadata.type == DownloadType.IsStopped) {
                // we need to close before delete
                fileStream.closeQuietly()
                metadata.onDelete()
                stream.delete()
                return@withContext DOWNLOAD_STOPPED
            }

            // Fork fix (audit #13, moved to the right function in
            // audit sesi 15 #2): never declare an HLS download done
            // while segments are missing. A segment dropped on a
            // cancellation race used to leave a hole in the file yet
            // report success, and the file was never resumed.
            if (metadata.hlsWrittenProgress < items.size) {
                metadata.type = DownloadType.IsFailed
                return@withContext metadata.failedStatus()
            }

            metadata.type = DownloadType.IsDone
            return@withContext DOWNLOAD_SUCCESS
        } catch (t: Throwable) {
            logError(t)
            metadata.type = DownloadType.IsFailed
            return@withContext metadata.failedStatus()
        } finally {
            fileStream?.closeQuietly()
            metadata.close()
        }
    }

    private fun getDisplayName(name: String, extension: String): String {
        return "$name.$extension"
    }

    private suspend fun downloadSingleEpisode(
        context: Context,
        source: String?,
        folder: String?,
        ep: DownloadEpisodeMetadata,
        link: ExtractorLink,
        notificationCallback: (Int, Notification) -> Unit,
        tryResume: Boolean = false,
    ): DownloadStatus {
        // no support for these file formats
        if (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.DASH) {
            return DOWNLOAD_INVALID_INPUT
        }

        val name = getFileName(context, ep)

        // Make sure this is cancelled when download is done or cancelled.
        val extractorJob = ioSafe {
            if (link.extractorData != null) {
                getApiFromNameNull(link.source)?.extractorVerifierJob(link.extractorData)
            }
        }

        val callback: (CreateNotificationMetadata) -> Unit = { meta ->
            main {
                createDownloadNotification(
                    context,
                    source,
                    link.name,
                    ep,
                    meta.type,
                    meta.bytesDownloaded,
                    meta.bytesTotal,
                    notificationCallback,
                    meta.hlsProgress,
                    meta.hlsTotal,
                    meta.bytesPerSecond
                )
            }
        }

        try {
            when (link.type) {
                ExtractorLinkType.M3U8 -> {
                    // Audit-4 #1: extraInfo stores the resume checkpoint
                    // as "<segment count>:<file byte length>". Legacy
                    // entries hold only the count; their byte length is
                    // null, which makes downloadHLS restart that one
                    // partial file instead of trusting its tail.
                    val savedInfo = if (tryResume) {
                        context.getKey<DownloadedFileInfo>(
                            KEY_DOWNLOAD_INFO,
                            ep.id.toString(),
                            null
                        )?.extraInfo
                    } else null
                    // Audit-5 #9: split-based parse — extraInfo is
                    // "<count>:<bytes>[:<playlist fingerprint>]".
                    val infoParts = savedInfo?.split(':')
                    val startIndex = infoParts?.getOrNull(0)?.toIntOrNull()
                    val startBytes = infoParts?.getOrNull(1)?.toLongOrNull()
                    val startFingerprint = infoParts?.getOrNull(2)

                    return downloadHLS(
                        context,
                        link,
                        name,
                        folder ?: "",
                        ep.id,
                        startIndex,
                        startBytes,
                        startFingerprint,
                        callback, parallelConnections = maxConcurrentConnections(context)
                    )
                }

                ExtractorLinkType.VIDEO -> {
                    return downloadThing(
                        context,
                        link,
                        name,
                        folder ?: "",
                        "mp4",
                        tryResume,
                        ep.id,
                        callback,
                        parallelConnections = maxConcurrentConnections(context),
                        /** We require at least 10 MB video files */
                        minimumSize = (1 shl 20) * 10
                    )
                }

                else -> throw IllegalArgumentException("Unsupported download type")
            }
        } catch (e: CancellationException) {
            // Audit-13 #17: a cancelled scope (service
            // teardown) is not a failed download — converting
            // it to DOWNLOAD_FAILED gave it retry semantics,
            // so the mirror loop kept spinning during shutdown
            // and the download ended up marked failed.
            throw e
        } catch (_: Throwable) {
            return DOWNLOAD_FAILED
        } finally {
            extractorJob.cancel()
        }
    }


    // Audit-4 #39: getDownloadFileInfo pays a SAF provider query
    // (lengthOrThrow) per call, and the downloads screen calls it
    // once per episode on every refresh — N Binder round-trips to
    // the storage provider for data that cannot change while no
    // download is running. Successful results for ids with no
    // live download session are cached for a short TTL; active
    // downloads (present in downloadStatus) always query fresh
    // because their file length is still growing, misses are
    // never cached so deletions show up immediately, and the
    // app's own delete path evicts explicitly.
    private data class CachedFileInfo(
        val result: DownloadedFileInfoResult,
        val atMs: Long
    )

    private val fileInfoCache =
        java.util.concurrent.ConcurrentHashMap<Int, CachedFileInfo>()

    private const val FILE_INFO_CACHE_TTL_MS = 15_000L

    fun getDownloadFileInfo(
        context: Context,
        id: Int,
    ): DownloadedFileInfoResult? {
        // Audit-4 #39: see fileInfoCache. (containsKey, not `in`:
        // downloadStatus is a ConcurrentHashMap, whose legacy
        // contains() means containsValue — Kotlin rejects it.)
        val cacheable = !downloadStatus.containsKey(id)
        if (cacheable) {
            val cached = fileInfoCache[id]
            if (cached != null &&
                System.currentTimeMillis() - cached.atMs < FILE_INFO_CACHE_TTL_MS
            ) {
                return cached.result
            }
            // Audit-8 #10: an expired entry used to stay in the
            // map forever (only the app's own delete path ever
            // removed one), so the cache grew by one entry per
            // downloaded episode ever displayed, for the whole
            // process lifetime. Evict on expiry; the re-fetch
            // below re-inserts on success.
            if (cached != null) fileInfoCache.remove(id)
        }
        try {
            val info =
                context.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString())
                    ?: run {
                        fileInfoCache.remove(id)
                        return null
                    }
            val file = info.toFile(context) ?: run {
                fileInfoCache.remove(id)
                return null
            }

            // Audit-3 #38: the old code paid a separate SAF exists()
            // provider query per episode on every stats refresh, on
            // top of the length query below. lengthOrThrow() already
            // throws for a missing file, which the catch turns into
            // the same null result.
            val result = DownloadedFileInfoResult(
                file.lengthOrThrow(),
                info.totalBytes,
                file.uriOrThrow()
            )
            // Audit-4 #39: see fileInfoCache.
            if (cacheable) {
                fileInfoCache[id] = CachedFileInfo(result, System.currentTimeMillis())
            }
            return result
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    fun deleteFilesAndUpdateSettings(
        context: Context,
        ids: Set<Int>,
        scope: CoroutineScope,
        onComplete: (Set<Int>) -> Unit = {}
    ) {
        scope.launchSafe(Dispatchers.IO) {
            val deleteJobs = ids.map { id ->
                async {
                    id to deleteFileAndUpdateSettings(context, id)
                }
            }
            val results = deleteJobs.awaitAll()

            val (successfulResults, failedResults) = results.partition { it.second }
            val successfulIds = successfulResults.map { it.first }.toSet()

            if (failedResults.isNotEmpty()) {
                failedResults.forEach { (id, _) ->
                    // TODO show a toast if some failed?
                    Log.e("FileDeletion", "Failed to delete file with ID: $id")
                }
            } else {
                Log.i("FileDeletion", "All files deleted successfully")
            }

            onComplete.invoke(successfulIds)
        }
    }

    private fun deleteFileAndUpdateSettings(context: Context, id: Int): Boolean {
        val success = deleteFile(context, id)
        if (success) context.removeKey(KEY_DOWNLOAD_INFO, id.toString())
        // Audit-4 #39: never serve a deleted file from the cache.
        fileInfoCache.remove(id)
        return success
    }

    private fun deleteFile(context: Context, id: Int): Boolean {
        val info =
            context.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString()) ?: return false
        val file = info.toFile(context)

        // Audit-6 #24: a null handle (the series folder was
        // deleted outside the app, or the SAF tree was revoked)
        // made both comparisons false forever, so the info key
        // could never be removed from inside the app — an orphan
        // entry the user could not delete. An unreachable file is
        // gone as far as the app is concerned.
        val isFileDeleted = file == null || file.delete() == true || file.exists() == false

        if (isFileDeleted) {
            deleteMatchingSubtitles(context, info)
            downloadEvent.invoke(id to DownloadActionType.Stop)
            downloadProgressEvent.invoke(Triple(id, 0, 0))
            downloadStatusEvent.invoke(id to DownloadType.IsStopped)
            downloadDeleteEvent.invoke(id)
        }

        return isFileDeleted
    }

    fun getDownloadResumePackage(context: Context, id: Int): DownloadResumePackage? {
        return context.getKey<DownloadResumePackage>(KEY_RESUME_PACKAGES, id.toString())
    }

    fun getDownloadQueuePackage(context: Context, id: Int): DownloadQueueWrapper? {
        return context.getKey<DownloadQueueWrapper>(KEY_RESUME_IN_QUEUE, id.toString())
    }

    fun getDownloadEpisodeMetadata(
        episode: ResultEpisode,
        titleName: String,
        apiName: String,
        currentPoster: String?,
        currentIsMovie: Boolean,
        tvType: TvType,
    ): DownloadEpisodeMetadata {
        return DownloadEpisodeMetadata(
            episode.id,
            episode.parentId,
            sanitizeFilename(titleName),
            apiName,
            episode.poster ?: currentPoster,
            episode.name,
            if (currentIsMovie) null else episode.season,
            if (currentIsMovie) null else episode.episode,
            tvType,
        )
    }

    class EpisodeDownloadInstance(
        val context: Context,
        val downloadQueueWrapper: DownloadQueueWrapper
    ) {
        private val TAG = "EpisodeDownloadInstance"
        private var subtitleDownloadJob: Job? = null
        private var downloadJob: Job? = null
        private var linkLoadingJob: Job? = null

        /** isCompleted just means the download should not be retried.
         * It includes stopped by user AND completion of file download.
         * */
        var isCompleted = false
            set(value) {
                field = value
                if (value) {
                    removeKey(KEY_RESUME_IN_QUEUE, downloadQueueWrapper.id.toString())
                    // Do not emit events when completed as it may also trigger on cancellation.


                    // Force refresh the queue when completed.
                    // May lead to some redundant calls, but ensures that the queue is always up to date.
                    DownloadQueueManager.forceRefreshQueue()
                }
            }

        /** Cancels all active jobs and sets instance to failed. */
        fun cancelDownload() {
            val cause = "Cancel call from cancelDownload"
            this.subtitleDownloadJob?.cancel(cause)
            this.linkLoadingJob?.cancel(cause)

            // Should not cancel the download job, it may need to clean up itself.
            // Better to send a status event using isStopped and let it cancel itself.
            isCancelled = true
        }

        // Run to cancel ongoing work, delete partial work and refresh queue
        private fun cleanup(status: DownloadType) {
            removeKey(KEY_RESUME_IN_QUEUE, downloadQueueWrapper.id.toString())
            val id = downloadQueueWrapper.id

            // Delete subtitles on cancel — Audit-7 #4: ONLY on
            // cancel. This cleanup also runs for failures (via
            // the isFailed setter), and a failed video used to
            // take its already-downloaded subtitles with it; the
            // resume path has no subtitle job, so they were never
            // fetched again and the finished download silently
            // had no subtitles.
            if (status == DownloadType.IsStopped) {
                safe {
                    val info = context.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString())
                    if (info != null) {
                        deleteMatchingSubtitles(context, info)
                    }
                }
            }

            downloadStatusEvent.invoke(Pair(id, status))
            downloadStatus[id] = status
            // Audit-8 #3: the Stop event means "the user stopped
            // this" to its only listener (DownloadQueueService),
            // which reacts by cancelling the instance and
            // clearing its resume keys. Firing it for FAILURES
            // made the service convert every failure into a user
            // stop: a second cleanup ran as IsStopped, deleting
            // the subtitles Audit-7 #4 preserves for failures,
            // overwriting the shown status Failed -> Stopped, and
            // discarding the resume package (mirror progress)
            // that the failure path deliberately keeps.
            if (status == DownloadType.IsStopped) {
                downloadEvent.invoke(Pair(id, DownloadActionType.Stop))
            }

            // Force refresh the queue when failed.
            // May lead to some redundant calls, but ensures that the queue is always up to date.
            DownloadQueueManager.forceRefreshQueue()
        }

        var isCancelled = false
            set(value) {
                val oldField = field
                field = value

                // Clean up cancelled work, but only once
                if (value && !oldField) {
                    cleanup(DownloadType.IsStopped)
                }
            }


        /** This failure can be both downloader and user initiated.
         * Do not automatically retry in case of failure. */
        var isFailed = false
            set(value) {
                val oldField = field
                field = value

                // Clean up failed work, but only once
                if (value && !oldField) {
                    cleanup(DownloadType.IsFailed)
                }
            }

        companion object {
            private fun displayNotification(context: Context, id: Int, notification: Notification) {
                safe {
                    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) return@safe

                    NotificationManagerCompat.from(context)
                        .notify(DOWNLOAD_NOTIFICATION_TAG, id, notification)
                }
            }
        }

        private suspend fun downloadFromResume(
            downloadResumePackage: DownloadResumePackage,
            notificationCallback: (Int, Notification) -> Unit,
        ) {
            val item = downloadResumePackage.item
            val id = item.ep.id
            // Audit-13 #1: claim the id atomically — the old
            // contains-check and the add were two separate
            // operations, so two starts of the same episode
            // racing each other (queue relaunch + manual
            // resume) both passed the check and downloaded
            // the same file concurrently, corrupting it.
            // StateFlow.update retries its lambda under
            // contention with the newest value, so seeing the
            // id inside the lambda means another start owns
            // it. (Same claim pattern as popQueue, Audit-4 #2.)
            var alreadyDownloading = false
            _currentDownloads.update { downloads ->
                // Audit-14 #5: reassign the flag on EVERY
                // invocation of the transform. update() retries
                // under contention, and the previous shape only
                // ever set the flag to true — a failed attempt
                // that saw the id (owner still downloading)
                // followed by a winning retry on the emptied set
                // (owner just finished) added the id but left
                // the flag true, so the caller returned via the
                // Resume path without ever entering the
                // try/finally that removes the id: the episode
                // stayed claimed forever with nothing running.
                alreadyDownloading = downloads.contains(id)
                if (alreadyDownloading) {
                    downloads
                } else {
                    downloads + id
                }
            }
            if (alreadyDownloading) { // IF IT IS ALREADY DOWNLOADING, RESUME IT
                downloadEvent.invoke(id to DownloadActionType.Resume)
                return
            }

            try {
                for (index in (downloadResumePackage.linkIndex ?: 0) until item.links.size) {
                    // Audit-7 #2: a user stop is delivered as an
                    // event that only a live DownloadMetaData hears.
                    // A stop landing before the first metadata
                    // exists, or between mirrors (previous metadata
                    // closed, next not yet created), was lost and
                    // the loop downloaded the episode anyway. The
                    // persisted isCancelled flag is checked here so
                    // the stop survives those gaps.
                    if (isCancelled) break
                    val link = item.links[index]
                    val resume = downloadResumePackage.linkIndex == index

                    setKey(
                        KEY_RESUME_PACKAGES,
                        id.toString(),
                        DownloadResumePackage(item, index)
                    )

                    var connectionResult =
                        downloadSingleEpisode(
                            context,
                            item.source,
                            item.folder,
                            item.ep,
                            link,
                            notificationCallback,
                            resume
                        )

                    if (connectionResult.retrySame) {
                        connectionResult = downloadSingleEpisode(
                            context,
                            item.source,
                            item.folder,
                            item.ep,
                            link,
                            notificationCallback,
                            true
                        )
                    }

                    // Audit-7 #2: a stop that landed while this
                    // mirror was being attempted ends the loop
                    // quietly — it is neither success nor failure.
                    if (isCancelled) break

                    if (connectionResult.success) { // SUCCESS
                        isCompleted = true
                        break
                    } else if (!connectionResult.tryNext || index >= item.links.lastIndex) {
                        isFailed = true
                        break
                    }
                }
            } catch (e: Exception) {
                isFailed = true
                logError(e)
            } finally {
                // Audit-7 #2: a user stop already ran its own
                // cleanup via the isCancelled setter; do not
                // re-report it as a failure here.
                isFailed = !isCompleted && !isCancelled
                // Audit-3 #32: a completed download no longer needs
                // its resume package; leaving it behind accumulated
                // one stale entry per finished download forever.
                if (isCompleted) {
                    context.removeKey(KEY_RESUME_PACKAGES, id.toString())
                }
                _currentDownloads.update { downloads ->
                    downloads - id
                }
            }
        }

        private suspend fun startDownload(
            info: DownloadItem?,
            pkg: DownloadResumePackage?
        ) {
            try {
                if (info != null) {
                    getDownloadResumePackage(context, info.ep.id)?.let { dpkg ->
                        downloadFromResume(dpkg) { id, notification ->
                            displayNotification(context, id, notification)
                        }
                    } ?: run {
                        if (info.links.isEmpty()) return
                        downloadFromResume(
                            DownloadResumePackage(info, null)
                        ) { id, notification ->
                            displayNotification(context, id, notification)
                        }
                    }
                } else if (pkg != null) {
                    downloadFromResume(pkg) { id, notification ->
                        displayNotification(context, id, notification)
                    }
                }
                return
            } catch (e: Exception) {
                isFailed = true
                logError(e)
                return
            }
        }

        private suspend fun downloadFromResume() {
            val resumePackage = downloadQueueWrapper.resumePackage ?: return
            downloadFromResume(resumePackage) { id, notification ->
                displayNotification(context, id, notification)
            }
        }

        fun startDownload() {
            Log.d(TAG, "Starting download ${downloadQueueWrapper.id}")
            setKey(KEY_RESUME_IN_QUEUE, downloadQueueWrapper.id.toString(), downloadQueueWrapper)

            // Audit-7 #2: keep the job handle for every entry
            // path — the resume path used to launch anonymously,
            // leaving downloadJob null here.
            downloadJob = ioSafe {
                if (downloadQueueWrapper.resumePackage != null) {
                    downloadFromResume()
                    // Load links if they are not already loaded
                } else if (downloadQueueWrapper.downloadItem != null && downloadQueueWrapper.downloadItem.links.isNullOrEmpty()) {
                    downloadEpisodeWithoutLinks()
                } else if (downloadQueueWrapper.downloadItem?.links != null) {
                    downloadEpisodeWithLinks(
                        sortUrls(downloadQueueWrapper.downloadItem.links.toSet()),
                        downloadQueueWrapper.downloadItem.subs
                    )
                }
            }
        }

        private fun downloadEpisodeWithLinks(
            links: List<ExtractorLink>,
            subs: List<SubtitleData>?
        ) {
            val downloadItem = downloadQueueWrapper.downloadItem ?: return
            try {
                // Prepare visual keys
                setKey(
                    DOWNLOAD_HEADER_CACHE,
                    downloadItem.resultId.toString(),
                    DownloadObjects.DownloadHeaderCached(
                        apiName = downloadItem.apiName,
                        url = downloadItem.resultUrl,
                        type = downloadItem.resultType,
                        name = downloadItem.resultName,
                        poster = downloadItem.resultPoster,
                        id = downloadItem.resultId,
                        cacheTime = System.currentTimeMillis(),
                    )
                )
                setKey(
                    getFolderName(
                        DOWNLOAD_EPISODE_CACHE,
                        downloadItem.resultId.toString()
                    ), // 3 deep folder for faster access
                    downloadItem.episode.id.toString(),
                    DownloadObjects.DownloadEpisodeCached(
                        name = downloadItem.episode.name,
                        poster = downloadItem.episode.poster,
                        episode = downloadItem.episode.episode,
                        season = downloadItem.episode.season,
                        id = downloadItem.episode.id,
                        parentId = downloadItem.resultId,
                        score = downloadItem.episode.score,
                        description = downloadItem.episode.description,
                        cacheTime = System.currentTimeMillis(),
                    )
                )

                val meta =
                    getDownloadEpisodeMetadata(
                        downloadItem.episode,
                        downloadItem.resultName,
                        downloadItem.apiName,
                        downloadItem.resultPoster,
                        downloadItem.isMovie,
                        downloadItem.resultType
                    )

                val folder =
                    getFolder(downloadItem.resultType, downloadItem.resultName)
                val src = "$DOWNLOAD_NAVIGATE_TO/${downloadItem.resultId}"

                // DOWNLOAD VIDEO
                val info = DownloadItem(src, folder, meta, links)

                this.downloadJob = ioSafe {
                    startDownload(info, null)
                }

                // 1. Checks if the lang should be downloaded
                // 2. Makes it into the download format
                // 3. Downloads it as a .vtt file
                this.subtitleDownloadJob = ioSafe {
                    try {
                        val downloadList = SubtitlesFragment.getDownloadSubsLanguageTagIETF()

                        subs?.filter { subtitle ->
                            downloadList.any { langTagIETF ->
                                subtitle.languageCode == langTagIETF ||
                                        subtitle.originalName.contains(
                                            fromTagToEnglishLanguageName(
                                                langTagIETF
                                            ) ?: langTagIETF
                                        )
                            }
                        }
                            ?.map { ExtractorSubtitleLink(it.name, it.url, "", it.headers) }
                            ?.take(3) // max subtitles download hardcoded (?_?)
                            ?.forEach { link ->
                                val fileName = getFileName(context, meta)
                                downloadSubtitle(context, link, fileName, folder)
                            }

                    } catch (_: CancellationException) {
                        val fileName = getFileName(context, meta)

                        val info = DownloadedFileInfo(
                            totalBytes = 0,
                            relativePath = folder,
                            displayName = fileName,
                            basePath = context.getBasePath().second
                        )

                        deleteMatchingSubtitles(context, info)
                    }
                }
            } catch (e: Exception) {
                // The work is only failed if the job did not get started
                if (this.downloadJob == null) {
                    isFailed = true
                }
                logError(e)
            }
        }

        private suspend fun downloadEpisodeWithoutLinks() {
            val downloadItem = downloadQueueWrapper.downloadItem ?: return

            val generator = RepoLinkGenerator(listOf(downloadItem.episode))
            // Audit-13 #16: synchronized sets — generateLinks
            // fans its callbacks out in parallel, and a plain
            // HashSet can silently lose entries when concurrent
            // adds collide during a resize (a mirror or a
            // subtitle vanishing, or a false "no links").
            val currentLinks =
                java.util.Collections.synchronizedSet(mutableSetOf<ExtractorLink>())
            val currentSubs =
                java.util.Collections.synchronizedSet(mutableSetOf<SubtitleData>())
            val meta =
                getDownloadEpisodeMetadata(
                    downloadItem.episode,
                    downloadItem.resultName,
                    downloadItem.apiName,
                    downloadItem.resultPoster,
                    downloadItem.isMovie,
                    downloadItem.resultType
                )

            createDownloadNotification(
                context,
                downloadItem.apiName,
                txt(R.string.loading).asString(context),
                meta,
                DownloadType.IsPending,
                0,
                1,
                { _, _ -> },
                null,
                null,
                0
            )?.let { linkLoadingNotification ->
                displayNotification(context, downloadItem.episode.id, linkLoadingNotification)
            }

            linkLoadingJob = ioSafe {
                generator.generateLinks(
                    offset = 0,
                    isCasting = false,
                    clearCache = false,
                    sourceTypes = LOADTYPE_INAPP_DOWNLOAD,
                    callback = {
                        it.first?.let { link ->
                            currentLinks.add(link)
                        }
                    },
                    subtitleCallback = { sub ->
                        currentSubs.add(sub)
                    })
            }

            // Wait for link loading completion
            linkLoadingJob?.join()

            // Remove link loading notification
            NotificationManagerCompat.from(context)
                .cancel(DOWNLOAD_NOTIFICATION_TAG, downloadItem.episode.id)

            // Audit-13 #7: also honour THIS instance's flag —
            // a stop landing before linkLoadingJob was assigned
            // cancelled a still-null field and only set
            // isCancelled; checking just the job let the flow
            // continue into downloadEpisodeWithLinks, writing
            // subtitle files and toasting "download started"
            // for a download the user had already stopped.
            if (isCancelled || linkLoadingJob?.isCancelled == true) {
                // Same as if no links, but no toast.
                // Cancelled link loading is presumed to be user initiated
                isCancelled = true
                return
            } else if (currentLinks.isEmpty()) {
                main {
                    showToast(
                        R.string.no_links_found_toast,
                        Toast.LENGTH_SHORT
                    )
                }
                isFailed = true
                return
            } else {
                main {
                    showToast(
                        R.string.download_started,
                        Toast.LENGTH_SHORT
                    )
                }
            }

            // Profiles should always contain a download type
            // Audit-3 #10: first{} threw NoSuchElementException when the
            // user had edited profiles so none is a Download profile,
            // leaving a zombie instance that kept the queue service
            // alive forever. Fail the download cleanly instead.
            val profile = QualityDataHelper.getProfiles().firstOrNull {
                it.types.contains(
                    QualityDataHelper.QualityProfileType.Download
                )
            } ?: run {
                isFailed = true
                return
            }

            val sortedLinks = currentLinks.sortedBy { link ->
                // Negative, because the highest priority should be first
                -getLinkPriority(profile.id, link)
            }

            downloadEpisodeWithLinks(
                sortedLinks,
                sortSubs(currentSubs),
            )
        }
    }
}
