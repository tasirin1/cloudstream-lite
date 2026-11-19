package com.lagradost.cloudstream3.utils

import android.app.Activity

/**
 * Fork change: app self-update is removed in this fork.
 *
 * Upstream CloudStream checks the upstream GitHub releases, downloads
 * an upstream APK and offers to install it (including the upstream
 * prerelease). That must never happen here: this fork ships its own
 * builds under its own versioning, and an upstream APK would silently
 * replace the fork's fixes (Conscrypt gating, lite edition, ...).
 *
 * Both public entry points keep their original signatures so the
 * settings UI compiles unchanged, but they are inert:
 * [runAutoUpdate] always reports "no update" and
 * [installPreReleaseIfNeeded] does nothing. The extension updater
 * (ApkUpdater, used for extension repositories) is a separate
 * mechanism and is not affected by this change.
 */
object InAppUpdater {

    fun Activity.installPreReleaseIfNeeded() = Unit

    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     * @return always false in this fork: no update is ever available
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean = false
}
