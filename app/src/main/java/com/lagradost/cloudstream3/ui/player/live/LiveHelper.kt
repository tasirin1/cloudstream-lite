package com.lagradost.cloudstream3.ui.player.live

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.mvvm.debugWarning
import java.util.WeakHashMap

object LiveHelper {
    private val liveManagers = WeakHashMap<Player, Pair<LiveManager, Player.Listener>>()

    @OptIn(UnstableApi::class)
    fun registerPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper registerPlayer called with null player!" }
            return
        }

        // Audit-15 #19: the duplicate check used to sit
        // OUTSIDE the synchronized block below — two
        // concurrent registrations for one player both
        // passed it, both added listeners, and the second
        // put orphaned the first listener forever
        // (WeakHashMap is not thread-safe). The check now
        // happens inside the lock at registration time.

        val liveManager = LiveManager(player)
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Audit-14 #25: an empty/failed source reports
                // timeline changes with no windows — indexing
                // getWindow with the current index (or -1) threw
                // IndexOutOfBounds on the player thread.
                val index = player.currentMediaItemIndex
                if (timeline.isEmpty || index < 0 || index >= timeline.windowCount) {
                    return
                }
                val window = Timeline.Window()
                timeline.getWindow(index, window)
                if (window.isDynamic) {
                    liveManager.submitLivestreamChunk(LivestreamChunk(window.durationMs))
                }
                super.onTimelineChanged(timeline, reason)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                val timeAheadOfLive = liveManager.getTimeAheadOfLive(newPosition.positionMs)

                // Seek back to the optimal live spot
                if (timeAheadOfLive > 100) {
                    // Audit-15 #20: a discontinuity near
                    // position 0 with a large ahead-estimate
                    // produced a negative target — some
                    // sources throw IllegalSeekPositionException
                    // instead of clamping.
                    player.seekTo(
                        (newPosition.positionMs - timeAheadOfLive).coerceAtLeast(0)
                    )
                }
            }
        }

        synchronized(liveManagers) {
            // Prevent duplicates (inside the lock, see above)
            if (liveManagers.contains(player)) {
                return
            }
            player.addListener(listener)
            liveManagers[player] = liveManager to listener
        }
    }

    fun unregisterPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper unregisterPlayer called with null player!" }
            return
        }

        // Audit-15 #19: check-and-remove under the same lock.
        synchronized(liveManagers) {
            liveManagers[player]?.let { (_, listener) ->
                player.removeListener(listener)
            }
            liveManagers.remove(player)
        }
    }

    fun getLiveManager(player: Player?) =
        synchronized(liveManagers) { liveManagers[player]?.first }
}
