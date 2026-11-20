package com.lagradost.cloudstream3.services
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Handle notification actions such as pause/resume downloads */
class VideoDownloadService : Service() {

    // Audit-6 #26: a parallelism-1 dispatcher — commands used
    // to launch onto the multi-threaded Default pool, so a fast
    // Pause-then-Resume (or the reverse) from the notification
    // could execute out of order and leave the download in the
    // opposite state of the user's last tap. Serial launches
    // deliver commands in arrival order.
    private val downloadScope =
        CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val id = intent.getIntExtra("id", -1)
            val type = intent.getStringExtra("type")
            if (id != -1 && type != null) {
                val state = when (type) {
                    "resume" -> VideoDownloadManager.DownloadActionType.Resume
                    "pause" -> VideoDownloadManager.DownloadActionType.Pause
                    "stop" -> VideoDownloadManager.DownloadActionType.Stop
                    else -> {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                downloadScope.launch {
                    VideoDownloadManager.downloadEvent.invoke(Pair(id, state))
                    // Audit-4 #3: stop only after the command has
                    // actually been delivered. Audit-3 #34 called
                    // stopSelf() synchronously below, and onDestroy
                    // cancels downloadScope — so on a busy device the
                    // command could be cancelled before it ever ran
                    // and the pause/resume/stop was lost silently.
                    // stopSelf(startId) also keeps a newer command
                    // from being killed by an older one's completion.
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
        }

        // No command to relay: do not linger. (Audit-3 #34's
        // stopSelf, kept for the no-command case only.)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        downloadScope.coroutineContext.cancel()
        super.onDestroy()
    }
}
