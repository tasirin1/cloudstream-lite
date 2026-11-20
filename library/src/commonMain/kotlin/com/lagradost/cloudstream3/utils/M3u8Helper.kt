package com.lagradost.cloudstream3.utils

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.pow

/** backwards api surface */
class M3u8Helper {
    companion object {
        suspend fun generateM3u8(
            source: String,
            streamUrl: String,
            referer: String,
            quality: Int? = null,
            headers: Map<String, String> = mapOf(),
            name: String = source
        ): List<ExtractorLink> {
            return M3u8Helper2.generateM3u8(source, streamUrl, referer, quality, headers, name)
        }
    }


    data class M3u8Stream(
        val streamUrl: String,
        val quality: Int? = null,
        val headers: Map<String, String> = mapOf()
    )

    suspend fun m3u8Generation(m3u8: M3u8Stream, returnThis: Boolean? = true): List<M3u8Stream> {
        return M3u8Helper2.m3u8Generation(m3u8, returnThis ?: true)
    }
}


object M3u8Helper2 {
    private val TAG = "M3u8Helper"

    private val aesCbc = CryptographyProvider.Default.get(AES.CBC)

    suspend fun generateM3u8(
        source: String,
        streamUrl: String,
        referer: String,
        quality: Int? = null,
        headers: Map<String, String> = mapOf(),
        name: String = source
    ): List<ExtractorLink> {
        return m3u8Generation(
            M3u8Helper.M3u8Stream(
                streamUrl = streamUrl,
                quality = quality,
                headers = headers,
            ), true
        )
            .map { stream ->
                newExtractorLink(
                    source,
                    name = name,
                    stream.streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = stream.quality ?: Qualities.Unknown.value
                    this.headers = stream.headers
                }
            }
    }

    private val ENCRYPTION_URL_IV_REGEX =
        Regex("#EXT-X-KEY:METHOD=([^,]+),URI=\"([^\"]+)\"(?:,IV=(.*))?")
    private val TS_EXTENSION_REGEX =
        Regex("""#EXTINF:(([0-9]*[.])?[0-9]+|).*\n(.+?\n)""") // fuck it we ball, who cares about the type anyways

    private fun toBytes16Big(n: Int): ByteArray {
        return ByteArray(16) {
            val fixed = n / 256.0.pow((15 - it))
            (maxOf(0, fixed.toInt()) % 256).toByte()
        }
    }

    private fun defaultIv(index: Int): ByteArray {
        return toBytes16Big(index + 1)
    }

    /**
     * Fork fix (audit #25): an HLS IV attribute is a hexadecimal
     * string (RFC 8216), not text. Decode it into bytes and
     * normalize to the 16-byte AES block size (left-pad short
     * values, keep the low 16 bytes of long ones). Returns an
     * empty array when there is no usable IV, so callers fall
     * back to the media-sequence IV.
     */
    private fun parseEncryptionIv(raw: String?): ByteArray {
        if (raw.isNullOrBlank()) return byteArrayOf()
        val hex = raw.trim().removePrefix("0x").removePrefix("0X")
            .takeWhile { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (hex.isEmpty()) return byteArrayOf()
        val padded = if (hex.length % 2 == 1) "0$hex" else hex
        val decoded = ByteArray(padded.length / 2) {
            padded.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        return when {
            decoded.size == 16 -> decoded
            decoded.size < 16 -> ByteArray(16 - decoded.size) + decoded
            else -> decoded.copyOfRange(decoded.size - 16, decoded.size)
        }
    }

    private val MEDIA_SEQUENCE_REGEX = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)")

    @OptIn(DelicateCryptographyApi::class)
    fun getDecrypted(
        secretKey: ByteArray,
        data: ByteArray,
        iv: ByteArray = byteArrayOf(),
        index: Int,
    ): ByteArray {
        val ivKey = if (iv.isEmpty()) defaultIv(index) else iv
        val aesKey = aesCbc.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, secretKey)
        return aesKey.cipher(padding = true).decryptWithIvBlocking(ivKey, data)
    }

    private fun getParentLink(url: String): String {
        val split = url.split("/").toMutableList()
        split.removeAt(split.lastIndex)
        return split.joinToString("/")
    }

    private fun isNotCompleteUrl(url: String): Boolean {
        return !url.startsWith("https://") && !url.startsWith("http://")
    }

    /** Resolves a playlist entry against the playlist url, as done for variants in [HlsPlaylistParser] */
    private fun resolveUrl(playlistUrl: String, url: String): String {
        return if (isNotCompleteUrl(url)) {
            HlsPlaylistParser.UrlUtil.resolveToUrl(playlistUrl, url).toString()
        } else {
            url
        }
    }

    @Throws
    suspend fun m3u8Generation(
        m3u8: M3u8Helper.M3u8Stream,
        returnThis: Boolean = true
    ): List<M3u8Helper.M3u8Stream> {
        val list = mutableListOf<M3u8Helper.M3u8Stream>()
        // Audit-3 #4: was verify = false -- the master playlist
        // (which decides every variant/segment URL, for playback
        // AND downloads) was fetched with no certificate check.
        val response = app.get(m3u8.streamUrl, headers = m3u8.headers).text
        val parsed = HlsPlaylistParser.parse(m3u8.streamUrl, response)

        var anyFound = false
        if (parsed != null) {
            for (video in parsed.variants) {
                // The m3u8 should not be split it that causes a loss of audio, however this can not be done reliably
                // Therefore that should be figured out on the extension level, so only "trick play" is checked for
                if (video.isTrickPlay()) {
                    //println("Denied m3u8Generation, isTrickPlay = ${video.isTrickPlay()} containsAudio = ${video.containsAudio()}, codec = ${video.format.codecs}, url = ${video.url}")
                    continue
                }

                anyFound = true
                val quality = video.format.height
                list.add(
                    M3u8Helper.M3u8Stream(
                        streamUrl = video.url.toString(),
                        quality = if (quality > 0) quality else null,
                        headers = m3u8.headers
                    )
                )
            }
        }

        // If it is not a "Master Playlist", or if it does not contain any playable "Media Playlist" or if it should return itself
        if (parsed == null || !anyFound || returnThis) {
            // Only include it if is a "Media Playlist" (any TS files are found), or if it is a "Master Playlist" (parsing is non null)
            if (parsed != null || TS_EXTENSION_REGEX.containsMatchIn(response)) {
                list += m3u8
            } else {
                Log.i(TAG, "M3u8 Playlist is not a \"Master Playlist\" nor a \"Media Playlist\". Removing this link as it is invalid and will not open in player: ${m3u8.streamUrl}")
            }
        }

        return list
    }

    data class TsLink(
        val url: String,
        val time: Double?,
    )

    data class LazyHlsDownloadData(
        private val encryptionData: ByteArray,
        private val encryptionIv: ByteArray,
        val isEncrypted: Boolean,
        val allTsLinks: List<TsLink>,
        val relativeUrl: String,
        val headers: Map<String, String>,
    ) {

        val size get() = allTsLinks.size

        /** Fork fix (audit #25): base for the default per-segment IV. */
        var mediaSequence: Int = 0

        /**
         * Audit-4 #11: one encryption key's reign — it applies to
         * segments from [fromIndex] until the next range starts.
         * An empty [key] marks a stretch that is not encrypted
         * (before the first key tag, or a METHOD=NONE range).
         */
        internal class KeyRange(
            val fromIndex: Int,
            val key: ByteArray,
            val iv: ByteArray
        )

        // Audit-4 #11: per-segment key ranges for playlists that
        // rotate #EXT-X-KEY. Empty = the single constructor key
        // applies to every segment (the historical model).
        internal var keyRanges: List<KeyRange> = emptyList()

        suspend fun resolveLinkWhileSafe(
            index: Int,
            tries: Int = 3,
            failDelay: Long = 3000,
            condition: (() -> Boolean)
        ): ByteArray? {
            for (i in 0 until tries) {
                if (!condition()) return null

                try {
                    val out = resolveLink(index)
                    return if (condition()) out else null
                } catch (e: IllegalArgumentException) {
                    return null
                } catch (e: CancellationException) {
                    return null
                } catch (t: Throwable) {
                    delay(failDelay)
                }
            }
            return null
        }

        suspend fun resolveLinkSafe(
            index: Int,
            tries: Int = 3,
            failDelay: Long = 3000
        ): ByteArray? {
            for (i in 0 until tries) {
                try {
                    return resolveLink(index)
                } catch (e: IllegalArgumentException) {
                    return null
                } catch (e: CancellationException) {
                    return null
                } catch (t: Throwable) {
                    delay(failDelay)
                }
            }
            return null
        }

        @Throws
        suspend fun resolveLink(index: Int): ByteArray {
            if (index < 0 || index >= size) throw IllegalArgumentException("index must be in the bounds of the ts")
            val ts = allTsLinks[index]

            val tsResponse = app.get(ts.url, headers = headers)
            val body = tsResponse.body
            // Audit-4 #12: close in a finally — when bytes() threw,
            // the body was never closed and its connection could
            // not return to the pool.
            val tsData = try {
                body.bytes()
            } finally {
                body.close()
            }
            if (tsData.isEmpty()) throw ErrorLoadingException("no data")

            // Some sources respond with "error 404" or similar, this checks for small responses that
            // looks like ASCII
            if (tsData.size < 128 && tsData.all { it >= 0 }) throw ErrorLoadingException("ASCII found instead of data")

            return if (isEncrypted) {
                // Audit-4 #11: with key rotation, the key whose
                // range covers this segment wins. A segment before
                // the first key tag, or inside a METHOD=NONE range,
                // has an empty key and is returned as-is.
                val range = keyRanges.lastOrNull { it.fromIndex <= index }
                val keyData = if (keyRanges.isNotEmpty()) {
                    range?.key ?: byteArrayOf()
                } else {
                    encryptionData
                }
                if (keyData.isEmpty()) {
                    return tsData
                }
                val explicitIv = if (keyRanges.isNotEmpty()) {
                    range?.iv ?: byteArrayOf()
                } else {
                    encryptionIv
                }
                // Fork fix (audit #25): with no explicit IV attribute,
                // the IV is the segment's media sequence number
                // (RFC 8216 section 5.2), not index + 1.
                val iv = if (explicitIv.isEmpty()) {
                    toBytes16Big(mediaSequence + index)
                } else {
                    explicitIv
                }
                getDecrypted(keyData, tsData, iv, index)
            } else {
                tsData
            }
        }
    }

    @Throws
    suspend fun hslLazy(
        playlistStream: M3u8Helper.M3u8Stream,
        selectBest: Boolean = true,
        requireAudio: Boolean,
        depth: Int = 3,
    ): LazyHlsDownloadData {
        // Allow nesting, but not too much:
        // Master Playlist (different videos)
        // -> Media Playlist (different qualities of the same video)
        // -> Media Segments (ts files of a single video)
        if (depth < 0) {
            throw IllegalArgumentException()
        }

        val playlistResponse =
            app.get(
                playlistStream.streamUrl,
                headers = playlistStream.headers,

            ).text

        val parsed = HlsPlaylistParser.parse(playlistStream.streamUrl, playlistResponse)
        if (parsed != null) {
            // find first with no audio group if audio is required, as otherwise muxing is required
            // as m3u8 files can include separate tracks for dubs/subs
            val variants = if (requireAudio) {
                parsed.variants.filter { it.isPlayableStandalone(parsed) }
            } else {
                parsed.variants.filter { !it.isTrickPlay() }
            }

            if (variants.isEmpty()) {
                throw IllegalStateException(
                    if (requireAudio) "M3u8 contains no video with audio"
                    else "M3u8 contains no video"
                )
            }

            // M3u8 can also include different camera angles (parsed.videos) for the same quality
            // but here the default is used
            val bestVideo = if (selectBest) {
                // Sort by pixels, while this normally is a bad idea if one is -1,
                // in the m3u8 parsing must contains both or neither, so NO_VALUE => 1
                // Tie break on averageBitrate
                variants.maxBy { (it.format.width * it.format.height).toLong() * 1000L + it.format.averageBitrate.toLong() }
            } else {
                variants.minBy { (it.format.width * it.format.height).toLong() * 1000L + it.format.averageBitrate.toLong() }
            }

            val quality = bestVideo.format.height
            return hslLazy(
                playlistStream = M3u8Helper.M3u8Stream(
                    bestVideo.url.toString(),
                    if (quality > 0) quality else null,
                    playlistStream.headers
                ),
                selectBest = selectBest,
                requireAudio = requireAudio,
                depth = depth - 1
            )
        }

        // This is already a "Media Segments" file

        // Encryption, this is because crunchy uses it
        var encryptionIv = byteArrayOf()
        var encryptionData = byteArrayOf()

        // Audit-4 #11: scan EVERY #EXT-X-KEY tag, not just the
        // first, and actually look at METHOD. The old code treated
        // any key tag as AES-128: a METHOD=NONE tag (segments are
        // plain) still flipped the playlist to "encrypted" and
        // "decrypted" plain segments into garbage, and when a
        // playlist rotated keys mid-stream, every segment after
        // the rotation was decrypted with the first key only.
        val keyRanges = mutableListOf<LazyHlsDownloadData.KeyRange>()
        var encryptionState = false
        var keySegmentIndex = 0
        val keyCache = mutableMapOf<String, ByteArray>()
        for (rawLine in playlistResponse.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-KEY:") -> {
                    val groups = ENCRYPTION_URL_IV_REGEX.find(line)?.groupValues
                    val method = groups?.getOrNull(1) ?: when {
                        line.contains("METHOD=NONE") -> "NONE"
                        else -> continue
                    }
                    when {
                        method.equals("NONE", ignoreCase = true) -> {
                            keyRanges += LazyHlsDownloadData.KeyRange(
                                fromIndex = keySegmentIndex,
                                key = byteArrayOf(),
                                iv = byteArrayOf()
                            )
                        }

                        method.equals("AES-128", ignoreCase = true) -> {
                            val keyUrl = groups?.getOrNull(2)?.takeIf { it.isNotEmpty() }
                                ?: throw IllegalStateException("EXT-X-KEY without a URI")
                            val resolvedKeyUrl =
                                resolveUrl(playlistStream.streamUrl, keyUrl)
                            val key = keyCache.getOrPut(resolvedKeyUrl) {
                                val keyResponse = app.get(
                                    resolvedKeyUrl,
                                    headers = playlistStream.headers
                                )
                                val keyBody = keyResponse.body
                                // Audit-4 #12: finally-close, as
                                // with the segment body.
                                try {
                                    keyBody.bytes()
                                } finally {
                                    keyBody.close()
                                }
                            }
                            val iv = parseEncryptionIv(groups.getOrNull(3))
                            keyRanges += LazyHlsDownloadData.KeyRange(
                                fromIndex = keySegmentIndex,
                                key = key,
                                iv = iv
                            )
                            encryptionState = true
                            if (encryptionData.isEmpty()) {
                                encryptionData = key
                                encryptionIv = iv
                            }
                        }

                        else -> throw IllegalStateException(
                            "Unsupported HLS encryption method: $method"
                        )
                    }
                }

                line.startsWith("#EXTINF:") -> keySegmentIndex++
            }
        }

        val relativeUrl = getParentLink(playlistStream.streamUrl)
        val allTsList = TS_EXTENSION_REGEX.findAll(playlistResponse + "\n").map { ts ->
            val time = ts.groupValues[1]
            val url = resolveUrl(playlistStream.streamUrl, ts.groupValues[3].trim())
            TsLink(url = url, time = time.toDoubleOrNull())
        }.toList()

        if (allTsList.isEmpty()) throw IllegalStateException("M3u8 must contains TS files")

        val mediaSequence = MEDIA_SEQUENCE_REGEX.find(playlistResponse)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        return LazyHlsDownloadData(
            encryptionData = encryptionData,
            encryptionIv = encryptionIv,
            isEncrypted = encryptionState,
            allTsLinks = allTsList,
            relativeUrl = relativeUrl,
            headers = playlistStream.headers
        ).apply {
            this.mediaSequence = mediaSequence
            this.keyRanges = keyRanges
        }
    }
}
