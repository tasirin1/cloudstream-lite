package com.lagradost.cloudstream3.subtitles

import androidx.core.net.toUri
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.util.zip.ZipInputStream

/**
 * A builder for subtitle files.
 * @see addUrl
 * @see addFile
 */
class SubtitleResource {
    companion object {
        // Audit-6 #6: subtitle archives are small. Neither the
        // download nor the extraction had any size cap (unlike
        // the repository JSON and plugin paths), so a hostile or
        // broken provider response — or a zip bomb — could fill
        // the STB's internal storage before anything noticed.
        private const val MAX_SUBTITLE_ARCHIVE_BYTES = 50_000_000L
        private const val MAX_SUBTITLE_EXTRACTED_BYTES = 100_000_000L
    }

    fun downloadFile(source: BufferedSource): File {
        val file = File.createTempFile("temp-subtitle", ".tmp").apply {
            deleteFileOnExit(this)
        }
        try {
            // Audit-6 #21: sink and source now close via use{} —
            // the old sequential close() calls were skipped by
            // any mid-write failure, leaking the descriptor and
            // leaving the partial temp file behind.
            file.sink().buffer().use { sink ->
                source.use { src ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SUBTITLE_ARCHIVE_BYTES) {
                            throw java.io.IOException(
                                "Subtitle archive exceeds $MAX_SUBTITLE_ARCHIVE_BYTES bytes"
                            )
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    private fun unzip(file: File): List<Pair<String, File>> {
        val entries = mutableListOf<Pair<String, File>>()
        var totalExtracted = 0L

        try {
            ZipInputStream(file.inputStream()).use { zipInputStream ->
                var zipEntry = zipInputStream.nextEntry

                while (zipEntry != null) {
                    val tempFile = File.createTempFile("unzipped-subtitle", ".tmp").apply {
                        deleteFileOnExit(this)
                    }
                    entries.add(zipEntry.name to tempFile)

                    tempFile.sink().buffer().use { buffer ->
                        val entrySource = zipInputStream.source()
                        while (true) {
                            val read = entrySource.read(buffer.buffer, 16 * 1024L)
                            if (read < 0) break
                            totalExtracted += read
                            if (totalExtracted > MAX_SUBTITLE_EXTRACTED_BYTES) {
                                throw java.io.IOException(
                                    "Subtitle archive extracts past $MAX_SUBTITLE_EXTRACTED_BYTES bytes"
                                )
                            }
                            buffer.emitCompleteSegments()
                        }
                    }

                    zipEntry = zipInputStream.nextEntry
                }
            }
        } catch (e: Exception) {
            entries.forEach { it.second.delete() }
            throw e
        }
        return entries
    }

    data class SingleSubtitleResource(
        val name: String?,
        val url: String,
        val origin: SubtitleOrigin
    )

    private var resources: MutableList<SingleSubtitleResource> = mutableListOf()

    fun getSubtitles(): List<SingleSubtitleResource> {
        return resources.toList()
    }

    fun addUrl(url: String?, name: String? = null) {
        if (url == null) return
        this.resources.add(
            SingleSubtitleResource(name, url, SubtitleOrigin.URL)
        )
    }

    fun addFile(file: File, name: String? = null) {
        this.resources.add(
            SingleSubtitleResource(name, file.toUri().toString(), SubtitleOrigin.DOWNLOADED_FILE)
        )
        deleteFileOnExit(file)
    }

    suspend fun addZipUrl(
        url: String,
        nameGenerator: (String, File) -> String? = { _, _ -> null }
    ) {
        val source = app.get(url).okhttpResponse.body.source()
        val zip = downloadFile(source)
        val realFiles = unzip(zip)
        zip.deleteRecursively()
        realFiles.forEach { (name, subtitleFile) ->
            addFile(subtitleFile, nameGenerator(name, subtitleFile))
        }
    }
}

