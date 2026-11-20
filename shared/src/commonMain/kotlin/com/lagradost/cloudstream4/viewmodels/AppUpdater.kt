package com.lagradost.cloudstream4.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.state.ActionHandler
import com.lagradost.cloudstream4.state.DefaultStateContainer
import com.lagradost.cloudstream4.state.SingleActiveQuery
import com.lagradost.cloudstream4.state.StateContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable

interface AppUpdater {
    @Throws
    suspend fun update(
        settings: AppSettings,
        url: String,
        digest: DigestPair?,
        downloadProgress: (Long, Long?) -> Unit,
    )
}


data class DigestPair(
    val algorithm: String,
    val digest: ByteArray,
) {
    companion object {
        // "sha256:XXXX" or "sha256-XXXX" -> "sha256", bytearray(XXXX)
        fun parse(digestPair: String?): DigestPair? {
            if (digestPair == null) return null

            var split = digestPair.split(":", limit = 2)
            if (split.size != 2) {
                split = digestPair.split("-", limit = 2)
            }
            if (split.size != 2) {
                return null
            }

            val digestAlgorithm = split.getOrNull(0) ?: return null
            val digestByteArray = safe { split.getOrNull(1)?.hexToByteArray() } ?: return null

            return DigestPair(digestAlgorithm, digestByteArray)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val other = other as? DigestPair ?: return false

        if (algorithm != other.algorithm) return false
        if (!digest.contentEquals(other.digest)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + digest.contentHashCode()
        return result
    }
}
