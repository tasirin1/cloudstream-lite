package com.lagradost.cloudstream4.viewmodels

import com.lagradost.cloudstream3.mvvm.safe

// Audit-5 #36: the AppUpdater interface that used to share this
// file was orphaned (no implementation, no usage anywhere) and
// was removed. DigestPair below is live — it carries the plugin
// digest from the repository manifest to ApkUpdater.

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

            // Audit-4 #5: tolerate sloppy whitespace around the
            // algorithm name or hex ("sha256 : ...") instead of
            // rejecting every plugin of that repository.
            val digestAlgorithm = split.getOrNull(0)?.trim() ?: return null
            val digestByteArray = safe { split.getOrNull(1)?.trim()?.hexToByteArray() } ?: return null

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
