package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil3.Extras
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ExtractorSubtitleLink
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFileName
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFolder
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/** Separate object with helper functions for the downloader */
object DownloadUtils {
    // Fork fix (audit #16): this cache used to grow without bound for
    // the whole process lifetime; on the low-RAM STB target that is a
    // slow memory leak. Cap it as a small access-ordered LRU instead.
    private val cachedBitmaps = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Bitmap>?
        ): Boolean = size > 32
    }
    internal fun Context.getImageBitmapFromUrl(
        url: String,
        headers: Map<String, String>? = null
    ): Bitmap? = safe {
        synchronized(cachedBitmaps) { cachedBitmaps[url] }?.let {
            return@safe it
        }

        val imageLoader = SingletonImageLoader.get(this)

        val request = ImageRequest.Builder(this)
            .data(url)
            .apply {
                headers?.forEach { (key, value) ->
                    extras[Extras.Key<String>(key)] = value
                }
            }
            .build()

        val bitmap = runBlocking {
            val result = imageLoader.execute(request)
            (result as? SuccessResult)?.image?.asDrawable(applicationContext.resources)
                ?.toBitmap()
        }

        bitmap?.let {
            synchronized(cachedBitmaps) {
                if (!cachedBitmaps.containsKey(url)) cachedBitmaps[url] = it
            }
        }

        return@safe bitmap
    }

    //calculate the time
    internal fun getEstimatedTimeLeft(
        context: Context,
        bytesPerSecond: Long,
        progress: Long,
        total: Long
    ): String {
        if (bytesPerSecond <= 0) return ""
        val timeInSec = (total - progress) / bytesPerSecond
        val hrs = timeInSec / 3600
        val mins = (timeInSec % 3600) / 60
        val secs = timeInSec % 60
        val timeFormated: UiText? = when {
            hrs > 0 -> txt(
                R.string.download_time_left_hour_min_sec_format,
                hrs,
                mins,
                secs
            )

            mins > 0 -> txt(
                R.string.download_time_left_min_sec_format,
                mins,
                secs
            )

            secs > 0 -> txt(
                R.string.download_time_left_sec_format,
                secs
            )

            else -> null
        }
        return timeFormated?.asString(context) ?: ""
    }

    // Audit-8 #4: this used to launch the download in a fresh
    // detached ioSafe scope and return immediately, so the
    // caller's job handle (EpisodeDownloadInstance.
    // subtitleDownloadJob, cancelled on stop/delete) controlled
    // nothing — an in-flight subtitle download ran to
    // completion and re-created the subtitle file AFTER
    // cleanup had deleted it. As a suspend function the
    // download is a child of the caller's job and dies with it.
    internal suspend fun downloadSubtitle(
        context: Context?,
        link: ExtractorSubtitleLink,
        fileName: String,
        folder: String
    ) {
            VideoDownloadManager.downloadThing(
                context ?: return,
                link,
                // Fork fix (audit #21): link.name comes from the
                // provider; sanitize it before it becomes a file name.
                "$fileName ${DownloadFileManagement.sanitizeFilename(link.name)}",
                folder,
                // Fork fix (audit #20): keep the real subtitle format
                // from the URL; ASS/TTML used to be saved as .vtt and
                // then failed to parse in the player.
                link.url.substringBefore('?').substringAfterLast('.', "")
                    .lowercase()
                    .takeIf { it in setOf("srt", "vtt", "ass", "ssa", "ttml") } ?: "vtt",
                false,
                null, createNotificationCallback = {},
                // Audit-5 #29: subtitles used the default
                // minimumSize of 100 bytes, so a legitimately
                // tiny subtitle (a one-cue forced track can be
                // ~50 bytes) was deleted and reported invalid.
                // Any non-empty file is a valid subtitle.
                minimumSize = 1
            )
    }

    fun downloadSubtitle(
        context: Context?,
        link: SubtitleData,
        meta: DownloadObjects.DownloadEpisodeMetadata,
    ) {
        // Fire-and-forget entry point (single-subtitle action in
        // the result UI): the launch lives HERE now that the
        // worker above is a suspend function (Audit-8 #4).
        context?.let { ctx ->
            val fileName = getFileName(ctx, meta)
            val folder = getFolder(meta.type ?: return, meta.mainName)
            ioSafe {
                downloadSubtitle(
                    ctx,
                    ExtractorSubtitleLink(link.name, link.url, "", link.headers),
                    fileName,
                    folder
                )
            }
        }
    }


    /** Helper function to make sure duplicate attributes don't get overridden or inserted without lowercase cmp
     * example: map("a" to 1) appendAndDontOverride map("A" to 2, "a" to 3, "c" to 4) = map("a" to 1, "c" to 4)
     * */
    internal fun <V> Map<String, V>.appendAndDontOverride(rhs: Map<String, V>): Map<String, V> {
        val out = this.toMutableMap()
        val current = this.keys.map { it.lowercase() }
        for ((key, value) in rhs) {
            if (current.contains(key.lowercase())) continue
            out[key] = value
        }
        return out
    }

    internal fun List<Job>.cancel() {
        forEach { job ->
            try {
                job.cancel()
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    internal suspend fun List<Job>.join() {
        forEach { job ->
            try {
                job.join()
            } catch (t: CancellationException) {
                // Audit-13 #15: join() never throws the CHILD's
                // exception — a CancellationException here is
                // the CALLER's own cancellation, and swallowing
                // it let cancelled download scopes run their
                // completion logic anyway.
                throw t
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }
}