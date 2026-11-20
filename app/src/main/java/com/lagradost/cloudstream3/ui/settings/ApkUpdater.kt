package com.lagradost.cloudstream3.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.FileProvider
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager.MAX_PLUGIN_FILE_SIZE_BYTES
import com.lagradost.cloudstream3.receivers.PackageInstallerStatusReceiver
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.viewmodels.AppUpdater
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

object ApkUpdater : AppUpdater {
    private const val APP_UPDATE_NAME = "CloudStream"
    private const val APP_UPDATE_SUFFIX = "apk"

    @Throws
    override suspend fun update(
        settings: AppSettings,
        url: String,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit
    ) {
        val activity = CommonActivity.activity ?: throw ErrorLoadingException("No activity found")
        clearOldFiles(activity)

        val request = app.get(url)
        val length = request.size
        val body = request.body
        body.use { body ->
            val length = length ?: body.contentLength()
            val readStream = body.byteStream()

            when (settings.updates.apkInstaller.get()) {
                0 -> {
                    packageInstallerDownloader(
                        activity,
                        readStream,
                        length,
                        digest,
                        downloadProgress
                    )
                }

                else -> {
                    legacyDownloader(activity, readStream, length, digest, downloadProgress)
                }
            }
        }
    }

    fun clearOldFiles(activity: Activity) {
        // Delete old files
        activity.cacheDir.listFiles()?.filter {
            it.name.startsWith(APP_UPDATE_NAME) && it.extension == APP_UPDATE_SUFFIX
        }?.forEach {
            deleteFileOnExit(it)
        }
    }

    /** https://medium.com/@solrudev/painless-building-of-an-android-package-installer-app-d5a09b5df432 */
    @SuppressLint("RequestInstallPackagesPolicy")
    @Throws
    suspend fun packageInstallerDownloader(
        activity: Activity,
        readStream: InputStream,
        length: Long?,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var sessionId: Int? = null
        val packageInstaller = activity.packageManager.packageInstaller
        try {
            val installParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (length != null) {
                installParams.setSize(length)
            }

            sessionId = packageInstaller.createSession(installParams)
            val session = packageInstaller.openSession(sessionId)

            // We do not need to buffer this because transfer has large writes
            session.openWrite(activity.packageName, 0, length ?: -1L)
                .use { writeStream ->
                    transfer(writeStream, readStream, length, downloadProgress, digest)
                    session.fsync(writeStream)
                }

            val receiverIntent = Intent(activity, PackageInstallerStatusReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val receiverPendingIntent =
                PendingIntent.getBroadcast(activity, 0, receiverIntent, flags)

            // Avoid delayed updates, and just commit instantly
            session.commit(receiverPendingIntent.intentSender)
            session.close()
        } catch (t: Throwable) {
            sessionId?.let { sessionId ->
                packageInstaller.abandonSession(sessionId)
            }
            throw t
        }
    }

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

        // Audit-4 #5: same fail-closed policy as update() above;
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

    @Throws
    suspend fun legacyDownloader(
        activity: Activity,
        readStream: InputStream,
        length: Long?,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val downloadedFile = File.createTempFile(APP_UPDATE_NAME, ".$APP_UPDATE_SUFFIX")

        // We do not need to buffer this because transfer has large writes
        downloadedFile.outputStream().use { writeStream ->
            transfer(writeStream, readStream, length, downloadProgress, digest)
        }

        openApk(activity, downloadedFile)
    }

    fun openApk(context: Context, file: File) {
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }
}