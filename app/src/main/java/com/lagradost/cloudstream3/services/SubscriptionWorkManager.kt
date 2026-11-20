package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.net.toUri
import androidx.work.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllSubscriptions
import com.lagradost.cloudstream3.utils.DataStoreHelper.getDub
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

const val SUBSCRIPTION_CHANNEL_ID = "cloudstream3.subscriptions"
const val SUBSCRIPTION_WORK_NAME = "work_subscription"
const val SUBSCRIPTION_CHANNEL_NAME = "Subscriptions"
const val SUBSCRIPTION_CHANNEL_DESCRIPTION = "Notifications for new episodes on subscribed shows"
const val SUBSCRIPTION_NOTIFICATION_ID = 938712897 // Random unique

class SubscriptionWorkManager(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    companion object {
        fun enqueuePeriodicWork(context: Context?) {
            if (context == null) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicSyncDataWork =
                PeriodicWorkRequest.Builder(SubscriptionWorkManager::class.java, 6, TimeUnit.HOURS)
                    .addTag(SUBSCRIPTION_WORK_NAME)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SUBSCRIPTION_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicSyncDataWork
            )

            // Uncomment below for testing

//            val oneTimeSyncDataWork =
//                OneTimeWorkRequest.Builder(SubscriptionWorkManager::class.java)
//                    .addTag(SUBSCRIPTION_WORK_NAME)
//                    .setConstraints(constraints)
//                    .build()
//
//            WorkManager.getInstance(context).enqueue(oneTimeSyncDataWork)
        }
    }

    private val progressNotificationBuilder =
        NotificationCompat.Builder(context, SUBSCRIPTION_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(context.getString(R.string.subscription_in_progress_notification))
            .setSmallIcon(R.drawable.baseline_sync_24)
            .setProgress(0, 0, true)

    // Audit-14 #15: this was a shared val mutated by the
    // parallel amap children below (setContentTitle/Text/
    // Intent/LargeIcon/build) — NotificationCompat.Builder is
    // not thread-safe, so one show's notification could ship
    // with another show's title, icon and intent. Build a
    // fresh builder per notification instead.
    private fun updateNotificationBuilder() =
        NotificationCompat.Builder(context, SUBSCRIPTION_CHANNEL_ID)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun updateProgress(max: Int, progress: Int, indeterminate: Boolean) {
        // Audit-14 #15: same shared-builder race as above —
        // updateProgress is called from the parallel children
        // too, so serialize the mutate+build+notify.
        synchronized(progressNotificationBuilder) {
            notificationManager.notify(
                SUBSCRIPTION_NOTIFICATION_ID, progressNotificationBuilder
                    .setProgress(max, progress, indeterminate)
                    .build()
            )
        }
    }

    override suspend fun doWork(): Result {
        try {
//        println("Update subscriptions!")
            context.createNotificationChannel(
                SUBSCRIPTION_CHANNEL_ID,
                SUBSCRIPTION_CHANNEL_NAME,
                SUBSCRIPTION_CHANNEL_DESCRIPTION
            )

            val foregroundInfo = if (SDK_INT >= 29)
                ForegroundInfo(
                    SUBSCRIPTION_NOTIFICATION_ID,
                    progressNotificationBuilder.build(),
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC
                ) else ForegroundInfo(SUBSCRIPTION_NOTIFICATION_ID, progressNotificationBuilder.build(),)
            setForeground(foregroundInfo)

            val subscriptions = getAllSubscriptions()
            // Audit-15 #3: remember which profile the snapshot
            // belongs to — the write-back keys are prefixed
            // with the LIVE currentAccount, so a mid-run
            // profile switch would land profile A's data in
            // profile B's namespace.
            val accountAtStart = DataStoreHelper.currentAccount

            if (subscriptions.isEmpty()) {
                WorkManager.getInstance(context).cancelWorkById(this.id)
                return Result.success()
            }

            val max = subscriptions.size
            // Audit-13 #14: atomic — incremented from parallel amap children.
            val progress = java.util.concurrent.atomic.AtomicInteger(0)

            updateProgress(max, 0, true)

            // We need all plugins loaded.
            PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context)
            PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context, false)

            subscriptions.amap { savedData ->
                try {
                    val id = savedData.id ?: return@amap null
                    val api = getApiFromNameNull(savedData.apiName) ?: return@amap null

                    // Reasonable timeout to prevent having this worker run forever.
                    val response = withTimeoutOrNull(60_000) {
                        api.load(savedData.url) as? EpisodeResponse
                    } ?: return@amap null

                    val dubPreference =
                        getDub(id) ?: if (
                            context.getApiDubstatusSettings().contains(DubStatus.Dubbed)
                        ) {
                            DubStatus.Dubbed
                        } else {
                            DubStatus.Subbed
                        }

                    val latestEpisodes = response.getLatestEpisodes()
                    val latestPreferredEpisode = latestEpisodes[dubPreference]

                    val (shouldUpdate, latestEpisode) = if (latestPreferredEpisode != null) {
                        val latestSeenEpisode =
                            savedData.lastSeenEpisodeCount[dubPreference] ?: Int.MIN_VALUE
                        val shouldUpdate = latestPreferredEpisode > latestSeenEpisode
                        shouldUpdate to latestPreferredEpisode
                    } else {
                        val latestEpisode = latestEpisodes[DubStatus.None] ?: Int.MIN_VALUE
                        val latestSeenEpisode =
                            savedData.lastSeenEpisodeCount[DubStatus.None] ?: Int.MIN_VALUE
                        val shouldUpdate = latestEpisode > latestSeenEpisode
                        shouldUpdate to latestEpisode
                    }

                    // Audit-15 #3: profile switched mid-run —
                    // do not write this profile's results into
                    // the new profile's namespace.
                    if (DataStoreHelper.currentAccount != accountAtStart) {
                        return@amap null
                    }

                    DataStoreHelper.updateSubscribedData(
                        id,
                        savedData,
                        response
                    )

                    if (shouldUpdate) {
                        val updateHeader = savedData.name
                        val updateDescription = txt(
                            R.string.subscription_episode_released,
                            latestEpisode,
                            savedData.name
                        ).asString(context)

                        val intent = Intent(context, MainActivity::class.java).apply {
                            data = savedData.url.toUri()
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }.putExtra(MainActivity.API_NAME_EXTRA_KEY, api.name)

                        val pendingIntent =
                            PendingIntentCompat.getActivity(context, 0, intent, 0, false)

                        val poster = ioWork {
                            savedData.posterUrl?.let { url ->
                                context.getImageBitmapFromUrl(
                                    url,
                                    savedData.posterHeaders
                                )
                            }
                        }

                        val updateNotification =
                            updateNotificationBuilder().setContentTitle(updateHeader)
                                .setContentText(updateDescription)
                                .setContentIntent(pendingIntent)
                                .setLargeIcon(poster)
                                .build()

                        notificationManager.notify(id, updateNotification)
                    }

                    // Audit-13 #14: atomic increment — the old
                    // ++progress raced between the parallel amap
                    // children and lost counts.
                    updateProgress(max, progress.incrementAndGet(), false)
                } catch (t: CancellationException) {
                    // Audit-13 #13: a stopped worker is not a
                    // per-subscription failure — rethrow so the
                    // amap scope actually cancels instead of
                    // logging a fake error per item and carrying
                    // on with the remaining subscriptions.
                    throw t
                } catch (t: Throwable) {
                    logError(t)
                }
            }

            return Result.success()
        } catch (t: CancellationException) {
            // Audit-13 #13: do not convert a WorkManager stop
            // into Result.success() + a fake error log.
            throw t
        } catch (t: Throwable) {
            logError(t)
            // ye, while this is not correct, but because gods know why android just crashes
            // and this causes major battery usage as it retries it inf times. This is better, just
            // in case android decides to be android and fuck us
            return Result.success()
        }
    }
}