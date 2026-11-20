package com.lagradost.cloudstream3.utils

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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


// Audit-7 #16: size caps for the HLS fetch path. Segments,
// AES keys and playlists were all read fully into RAM with no
// bound anywhere (body.bytes() / .text), so one rogue URL in
// a playlist could OOM the STB; the rest of the app already
// caps its fetches (plugins 100 MB, manifests 10 MB, subtitle
// archives 50/100 MB). A real AES key is 16 bytes and a real
// playlist is far under a megabyte; the segment cap is sized
// generously for high-bitrate chunks.
private const val MAX_HLS_PLAYLIST_BYTES = 10_000_000L
private const val MAX_HLS_SEGMENT_BYTES = 256_000_000L
private const val MAX_HLS_KEY_BYTES = 65_536L

private fun okio.BufferedSource.readBytesBounded(maxBytes: Long, what: String): ByteArray {
    val buffer = okio.Buffer()
    var total = 0L
    while (true) {
        val read = read(buffer, 8192)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw ErrorLoadingException("$what exceeds $maxBytes bytes")
        }
    }
    return buffer.readByteArray()
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

    // Audit-5 #18: EXT-X-KEY attributes are parsed individually —
    // RFC 8216 allows any attribute order, and the old single
    // regex (METHOD,URI,IV in that exact order) silently skipped
    // legally ordered tags like URI=...,METHOD=AES-128, leaving
    // those segments to be "decrypted" with the wrong/no key.
    private val KEY_METHOD_REGEX = Regex("METHOD=([^,]+)")
    private val KEY_URI_REGEX = Regex("URI=\"([^\"]+)\"")
    private val KEY_IV_REGEX = Regex("IV=([^,]+)")
    // TS_EXTENSION_REGEX was removed in Audit-5 #4: segment
    // extraction is a line scan in hslLazy now (see there).

    private fun toBytes16Big(n: Int): ByteArray {
        // Audit-4 #19: this was 16 Double pow() calls, and it runs
        // once per segment for the default IV. Same 16-byte
        // big-endian encoding via shifts (n is a media sequence
        // number, never negative).
        val bytes = ByteArray(16)
        var value = n
        for (i in 15 downTo 0) {
            bytes[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return bytes
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

    // Audit-5 #20: single-entry cache for the decoded AES key —
    // decoding ran on every segment. Playlists overwhelmingly
    // use one key (rotation between two just re-decodes on the
    // switch, staying correct).
    //
    // Audit-6 #2: the Audit-5 cache kept the raw bytes and the
    // decoded key in TWO plain fields written in sequence (raw
    // first, decoded second), and its comment claimed a wrong
    // key was impossible — it was not: a parallel job could read
    // the NEW raw bytes paired with the OLD decoded key and
    // decrypt a segment with its neighbour's key. The pair now
    // lives in one immutable holder behind a single @Volatile
    // reference, so a reader always sees a consistent pair; two
    // jobs may still race a duplicate decode, but never a
    // mismatched one. The decoded type is AES.CBC.Key, not
    // AES.Key: the CBC subtype carries the cipher() used below.
    private class CachedAesKey(val raw: ByteArray, val decoded: AES.CBC.Key)

    // Audit-7 #35/#36: the holder now stores a COPY of the
    // caller's array (the "immutable" pair could previously be
    // mutated from outside via the retained reference), and the
    // cache keeps the last few keys instead of one — two
    // parallel downloads with different keys used to evict each
    // other's entry on nearly every segment, re-decoding
    // constantly. The list is immutable and swapped atomically
    // behind the same @Volatile, preserving the Audit-6 #2
    // guarantee that a reader never sees a mismatched pair.
    @kotlin.concurrent.Volatile
    private var cachedAesKeys: List<CachedAesKey> = emptyList()

    @OptIn(DelicateCryptographyApi::class)
    fun getDecrypted(
        secretKey: ByteArray,
        data: ByteArray,
        iv: ByteArray = byteArrayOf(),
        index: Int,
    ): ByteArray {
        val ivKey = if (iv.isEmpty()) defaultIv(index) else iv
        val aesKey = cachedAesKeys.firstOrNull {
            it.raw.contentEquals(secretKey)
        }?.decoded ?: aesCbc.keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, secretKey)
            .also { decoded ->
                val entry = CachedAesKey(secretKey.copyOf(), decoded)
                cachedAesKeys = (listOf(entry) + cachedAesKeys.filterNot {
                    it.raw.contentEquals(secretKey)
                }).take(4)
            }
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
        val responseBody = app.get(m3u8.streamUrl, headers = m3u8.headers).body
        val response = try {
            responseBody.source()
                .readBytesBounded(MAX_HLS_PLAYLIST_BYTES, "Playlist")
                .decodeToString()
        } finally {
            responseBody.close()
        }
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
            if (parsed != null || response.lineSequence().any { it.trim().startsWith("#EXTINF:") }) {
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
                } catch (e: ErrorLoadingException) {
                    // Audit-4 #20: permanent content failures (no
                    // data, ASCII error page) do not fix themselves
                    // within a few seconds — fail fast instead of
                    // burning the full retry budget per segment.
                    return null
                } catch (e: Error) {
                    // Audit-4 #20: never retry/swallow Errors such
                    // as OutOfMemoryError.
                    throw e
                } catch (t: Throwable) {
                    // Audit-5 #19: do not sleep after the final
                    // attempt — the delay only exists to space out
                    // retries, and sleeping before returning null
                    // added a pointless failDelay (3s) to every
                    // exhausted segment.
                    if (i < tries - 1) delay(failDelay)
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
                } catch (e: ErrorLoadingException) {
                    // Audit-4 #20: see resolveLinkWhileSafe.
                    return null
                } catch (e: Error) {
                    throw e
                } catch (t: Throwable) {
                    // Audit-5 #19: do not sleep after the final
                    // attempt — the delay only exists to space out
                    // retries, and sleeping before returning null
                    // added a pointless failDelay (3s) to every
                    // exhausted segment.
                    if (i < tries - 1) delay(failDelay)
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
                body.source().readBytesBounded(MAX_HLS_SEGMENT_BYTES, "Segment")
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

        val playlistBody = app.get(
            playlistStream.streamUrl,
            headers = playlistStream.headers
        ).body
        val playlistResponse = try {
            playlistBody.source()
                .readBytesBounded(MAX_HLS_PLAYLIST_BYTES, "Playlist")
                .decodeToString()
        } finally {
            playlistBody.close()
        }

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
                    val attributes = line.substringAfter("#EXT-X-KEY:")
                    val method = KEY_METHOD_REGEX.find(attributes)?.groupValues?.getOrNull(1)
                        ?: throw IllegalStateException("EXT-X-KEY without a METHOD: $line")
                    when {
                        method.equals("NONE", ignoreCase = true) -> {
                            keyRanges += LazyHlsDownloadData.KeyRange(
                                fromIndex = keySegmentIndex,
                                key = byteArrayOf(),
                                iv = byteArrayOf()
                            )
                        }

                        method.equals("AES-128", ignoreCase = true) -> {
                            val keyUrl = KEY_URI_REGEX.find(attributes)?.groupValues?.getOrNull(1)
                                ?.takeIf { it.isNotEmpty() }
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
                                    keyBody.source()
                                        .readBytesBounded(MAX_HLS_KEY_BYTES, "Encryption key")
                                } finally {
                                    keyBody.close()
                                }
                            }
                            val iv = parseEncryptionIv(
                                KEY_IV_REGEX.find(attributes)?.groupValues?.getOrNull(1)
                            )
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
        // Audit-5 #4: line-based segment scan. The old
        // TS_EXTENSION_REGEX demanded the segment URI on the very
        // next line after #EXTINF:, so a legal intervening tag
        // (#EXT-X-BYTERANGE in single-file VOD, #EXT-X-PROGRAM-
        // DATE-TIME) was stored AS the segment URL and the real
        // segment was dropped — every fetch for such playlists
        // failed. Now a tag line between #EXTINF: and its URI is
        // skipped, matching the #EXT-X-KEY scan above (segment
        // indices stay aligned with the key ranges).
        val allTsList = mutableListOf<TsLink>()
        var pendingDuration: Double? = null
        var awaitingSegment = false
        for (rawLine in playlistResponse.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.removePrefix("#EXTINF:")
                        .substringBefore(',').toDoubleOrNull()
                    awaitingSegment = true
                }

                awaitingSegment && line.isNotEmpty() && !line.startsWith("#") -> {
                    allTsList += TsLink(
                        url = resolveUrl(playlistStream.streamUrl, line),
                        time = pendingDuration
                    )
                    awaitingSegment = false
                }
            }
        }

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
