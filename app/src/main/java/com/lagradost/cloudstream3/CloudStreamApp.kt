package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeyRaw
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ImageLoader.buildImageLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ExceptionHandler(
    val errorFile: File,
    // Fork addition: extra copies of the crash log in folders that can
    // be read without root/adb (e.g. on TV boxes). Declared before
    // onError so the trailing lambda at the call site still binds to
    // onError.
    val extraErrorFiles: List<File> = emptyList(),
    val onError: (() -> Unit)
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val threadId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                thread.threadId()
            } else {
                @Suppress("DEPRECATION")
                thread.id
            }

            PrintStream(errorFile).use { ps ->
                ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
                ps.println("Fatal exception on thread ${thread.name} ($threadId)")
                error.printStackTrace(ps)
            }
        } catch (_: FileNotFoundException) {
        }
        // Fork addition: mirror the log to the extra locations, best
        // effort only. The app-specific external folder needs no
        // permission; the public Downloads copy fails silently when
        // storage permission is not granted.
        for (extra in extraErrorFiles) {
            try {
                extra.parentFile?.mkdirs()
                errorFile.copyTo(extra, overwrite = true)
            } catch (_: Exception) {
            }
        }
        // Fork addition: boot-loop guard. If the previous crash happened
        // less than 30s ago, do not restart the app again, otherwise a
        // deterministic startup crash loops forever (crash -> restart ->
        // crash) and looks like endless force closes on TV boxes.
        val allowRestart = try {
            val guardFile = File(errorFile.parentFile, "last_crash_time")
            val lastCrash = guardFile.takeIf { it.exists() }
                ?.readText()?.trim()?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()
            guardFile.writeText(now.toString())
            now - lastCrash > 30_000
        } catch (_: Exception) {
            true
        }
        if (allowRestart) {
            try {
                onError()
            } catch (_: Exception) {
            }
        }
        exitProcess(1)
    }
}

class CloudStreamApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // If we want to initialize Coil as early as possible, maybe when
        // loading an image or GIF in a splash screen activity.
        // buildImageLoader(applicationContext)

        AppDebug.isDebug = BuildConfig.DEBUG
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
        // Fork change: install the crash handler here instead of
        // onCreate. attachBaseContext runs before ContentProviders and
        // Application.onCreate, so even a crash during provider/startup
        // initialization gets logged. The log is mirrored to the
        // app-specific external folder (no storage permission needed,
        // readable with any file manager) and to public Downloads
        // (best effort, needs the storage permission on Android 6).
        if (base != null) {
            try {
                val extras = mutableListOf<File>()
                try {
                    base.getExternalFilesDir(null)?.let {
                        extras += File(it, "cloudstream_last_error.txt")
                    }
                } catch (_: Exception) {
                }
                @Suppress("DEPRECATION")
                extras += File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "cloudstream_last_error.txt"
                )
                ExceptionHandler(
                    File(base.filesDir, "last_error"),
                    extraErrorFiles = extras
                ) {
                    val intent =
                        context!!.packageManager.getLaunchIntentForPackage(context!!.packageName)
                    startActivity(Intent.makeRestartActivityTask(intent!!.component))
                }.also {
                    exceptionHandler = it
                    Thread.setDefaultUncaughtExceptionHandler(it)
                }
            } catch (_: Exception) {
                // Never let the crash handler setup crash the app itself.
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Coil module will be initialized globally when first loadImage() is invoked.
        return buildImageLoader(applicationContext)
    }

    companion object {
        var exceptionHandler: ExceptionHandler? = null

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }

        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            private set(value) {
                _context = WeakReference(value)
                setContext(value)
            }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        fun getKeyRaw(path: String): String? {
            return context?.getKeyRaw(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /** If fallbackWebView is true and a fragment is supplied then it will open a WebView with the URL if the browser fails. */
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            context?.openBrowser(url, fallbackWebView, fragment)
        }

        /** Will fall back to WebView if in TV or emulator layout. */
        fun openBrowser(url: String, activity: Activity?) {
            openBrowser(
                url,
                isLayout(TV or EMULATOR),
                (activity as? FragmentActivity)?.supportFragmentManager?.fragments?.lastOrNull()
            )
        }
    }
}
