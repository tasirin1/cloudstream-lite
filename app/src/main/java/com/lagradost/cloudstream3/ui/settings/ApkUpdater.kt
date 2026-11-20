package com.lagradost.cloudstream3.ui.settings

import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager.MAX_PLUGIN_FILE_SIZE_BYTES
import com.lagradost.cloudstream4.viewmodels.DigestPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestException
import java.security.MessageDigest

// Audit-4 #31: this object used to also be the app self-update
// installer (update() implementing the shared AppUpdater
// interface, packageInstallerDownloader, legacyDownloader,
// openApk, clearOldFiles). That path has had no caller since
// the Compose updater was removed in Audit-3 #24 — the fork
// never installs APKs of itself; only the plugin-download
// helpers below (transfer/isFileDigest/calculateDigest, used by
// AndroidRepositoryManager) are alive, so the dead install path
// (~150 lines) is deleted and the AppUpdater supertype dropped.
object ApkUpdater {
    /**
     * Write the "readStream" to the "writeStream", while notifying the "downloadProgress" and
     * calculating the digest
     *
     * ------------------------------------------------------------------------------------------
     *
     * throws a DigestException if a digest was provided but cannot be verified: the algorithm
     * is unknown to MessageDigest, computing it fails, or the computed digest mismatches
     *
     * throws a CancellationException if canceled, but may not be "instant" if the read is blocking
     *
     * throws IOException and similar for reading
     *
     * ------------------------------------------------------------------------------------------
     *
     * Policy (Audit-3 #2; this KDoc reconciled with the code in Audit-4 #5): a provided digest
     * that cannot be verified FAILS the transfer. This deliberately retires the old "ignore
     * digest failures" policy — the algorithm name comes from the repository itself, so
     * ignoring an unknown one would let any repository (or a MITM on a cleartext URL) bypass
     * verification entirely just by naming a bogus algorithm. A repository that provides no
     * digest at all is still accepted for backwards compatibility; then nothing is verified,
     * which is the repository's explicit choice, not a silent fallback.
     * */
    @Throws
    suspend fun transfer(
        writeStream: OutputStream,
        readStream: InputStream,
        length: Long?,
        downloadProgress: (Long, Long?) -> Unit,
        digest: DigestPair?,
    ) = withContext(Dispatchers.IO) {
        val md = digest?.algorithm?.let { digestAlgorithm ->
            safe {
                MessageDigest.getInstance(digestAlgorithm)
            }
        }

        // Audit-3 #2: a digest was provided but its algorithm is
        // unknown to MessageDigest -> fail loudly instead of
        // transferring the file with no verification at all.
        if (digest != null && md == null) {
            throw DigestException("Unsupported digest algorithm: ${digest.algorithm}")
        }

        val context = currentCoroutineContext()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        var transferred: Long = 0
        while ((readStream.read(buffer, 0, DEFAULT_BUFFER_SIZE)
                .also { read = it }) >= 0
        ) {
            writeStream.write(buffer, 0, read)

            // Audit-4 #5: not wrapped in safe{} — if a provided
            // digest cannot be computed, the transfer must fail
            // (see the KDoc policy above), not pass unverified.
            md?.update(buffer, 0, read)

            transferred += read.toLong()
            // Audit-4 #8: enforce the manifest's declared size and
            // an absolute ceiling while copying. Previously the
            // declared length only drove the progress display, so a
            // repository could stream unbounded data into the cache
            // dir and fill the device storage.
            if (transferred > MAX_PLUGIN_FILE_SIZE_BYTES ||
                (length != null && transferred > length)
            ) {
                throw IOException(
                    "Plugin download exceeded its size limit ($transferred bytes)"
                )
            }
            downloadProgress(transferred, length)
            context.ensureActive()
        }
        writeStream.flush()

        // Audit-4 #5: same fail-closed policy as the KDoc above;
        // a throwing digest() aborts the transfer.
        val check = md?.digest()
        if (check != null && !check.contentEquals(digest?.digest)) {
            throw DigestException("Mismatched digest on transfer ${digest?.algorithm} : ${check.toHexString()} / ${digest?.digest?.toHexString()}")
        }
    }

    /**
     * Returns true if we have a non-null digest that matches a digest on the specified existing file
     * */
    suspend fun isFileDigest(file: File, digest: DigestPair?): Boolean {
        if (digest == null) {
            return false
        }
        try {
            val fileDigest = calculateDigest(
                digestAlgorithm = digest.algorithm,
                readStream = file.inputStream()
            )
            return fileDigest != null && digest.digest.contentEquals(fileDigest)
        } catch (_: Throwable) {
            // File not found/security/read issues, not very useful errors
        }
        return false
    }

    /**
     * Calculates the digest of a stream with the specified algorithm, use transfer instead if
     * you already download something.
     *
     * Will throw read errors
     * Will return null if the algorithm is null or does not exist/has some error
     * */
    @Throws
    suspend fun calculateDigest(digestAlgorithm: String?, readStream: InputStream) =
        withContext(Dispatchers.IO) {
            if (digestAlgorithm == null) return@withContext null

            val md =
                safe {
                    MessageDigest.getInstance(digestAlgorithm)
                } ?: return@withContext null

            val context = currentCoroutineContext()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while ((readStream.read(buffer, 0, DEFAULT_BUFFER_SIZE)
                    .also { read = it }) >= 0
            ) {
                try {
                    md.update(buffer, 0, read)
                } catch (_: Throwable) {
                    return@withContext null
                }
                context.ensureActive()
            }
            return@withContext safe { md.digest() }
        }
}
