// Adapted for CloudStream - taken from https://github.com/vargalex/ResolveURL/blob/fix/videa-resolver-add-cookie/script.module.resolveurl/lib/resolveurl/plugins/videa.py
package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.base64DecodeArray

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("""<error.*?"noembed".*>(.*)</error>""")
private val hoistedRegex2 = Regex("""sl=([^;]+)""")
private val hoistedRegex3 = Regex("""<iframe.*?src="(/player\?[^\"]+)""")
private val hoistedRegex4 = Regex("""sl=([^;]+)""")
private val hoistedRegex5 = Regex("""_xt\s*=\s*"([^"]+)""")
private val hoistedRegex6 = Regex("""video_source\s*name="([^"]+)".*exp="([^"]+)"[^>]*>([^<]+)""")


/**
 * Extractor for Videa.hu video hosting service
 * Handles encrypted XML responses and redirect chains
 */
class Videa : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    private val videaSecret = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = url
        var key = ""
        // Handle redirect loop until we get valid XML
        val visitedUrls = mutableSetOf<String>()
        var count = 10
        while (!visitedUrls.contains(currentUrl) && count > 0) {
            visitedUrls += currentUrl
            count -= 1

            val webUrl = getXmlUrl(currentUrl) { cookie -> /* no-op, cookie not used */ } ?: return
            val response = app.get(webUrl)
            val body = response.body
            val rawBytes = body.bytes()
            body.close()

            // Check if response starts with XML declaration
            val isXml = rawBytes.size >= 5 &&
                    rawBytes[0] == 0x3C.toByte() &&  // '<'
                    rawBytes[1] == 0x3F.toByte() &&  // '?'
                    rawBytes[2] == 0x78.toByte() &&  // 'x'
                    rawBytes[3] == 0x6D.toByte() &&  // 'm'
                    rawBytes[4] == 0x6C.toByte()     // 'l'

            val videaXml = if (isXml) {
                rawBytes.decodeToString()
            } else {
                // Handle encrypted XML response
                val xsHeader = response.headers["X-Videa-Xs"] ?: return
                key += xsHeader
                rc4DecryptBytes(rawBytes, key)
            }

            // Check for redirect in XML error
            val redirectMatch = hoistedRegex1.find(videaXml)

            if (redirectMatch != null && redirectMatch.groupValues[1] != currentUrl) {
                currentUrl = redirectMatch.groupValues[1]
            } else {
                parseVideoSources(videaXml, callback)
                break
            }
        }
    }

    private suspend fun getXmlUrl(url: String, cookieCallback: (String) -> Unit = {}): String? {
        val response = app.get(url)
        val size = response.size
        /* OOM Protection */
        if(size != null && size > 5_000_000) {
            // You tried to use a video here
            return null
        }
        val html = response.text

        // Extract sl cookie if present
        response.headers["Set-Cookie"]?.let { cookieHeader ->
            hoistedRegex2.find(cookieHeader)?.let {
                cookieCallback(it.value)
            }
        }

        // Determine if this is a player URL or needs iframe extraction
        val playerUrl = if ("/player" in url) {
            url
        } else {
            val iframeMatch = hoistedRegex3.find(html)
            iframeMatch?.let { "$mainUrl${it.groupValues[1]}" } ?: return null
        }

        // Get player page to extract tokens
        val playerResponse = app.get(playerUrl)
        val playerHtml = playerResponse.text

        // Update cookie from player response
        playerResponse.headers["Set-Cookie"]?.let { cookieHeader ->
            hoistedRegex4.find(cookieHeader)?.let {
                cookieCallback(it.value)
            }
        }

        // Extract nonce and generate tokens
        val nonceMatch = hoistedRegex5.find(playerHtml) ?: return null
        val (s, t, key) = generateTokens(nonceMatch.groupValues[1]) ?: return null

        // Extract video parameter
        val videoParam = when {
            "f=" in playerUrl -> "f=" + playerUrl.substringAfter("f=").substringBefore("&")
            "v=" in playerUrl -> "v=" + playerUrl.substringAfter("v=").substringBefore("&")
            else -> return null
        }

        return "$mainUrl/player/xml?platform=desktop&$videoParam&_s=$s&_t=$t"
    }

    private fun generateTokens(nonce: String): Triple<String, String, String>? {
        // Audit-4 #17: the nonce comes from the remote page and was
        // indexed unchecked — a short or unexpected nonce threw
        // StringIndexOutOfBounds (surfaced upstream as a silently
        // missing link). The token math needs 32 + 32 characters
        // and every derived index inside s.
        if (nonce.length < 64) return null
        val lo = nonce.take(32)
        val s = nonce.substring(32)
        var result = ""

        for (i in 0 until 32) {
            val index = videaSecret.indexOf(lo[i]) - 31
            val sIndex = i - index
            if (sIndex !in s.indices) return null
            result += s[sIndex]
        }

        // Generate random seed
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val randomSeed = (1..8).map { chars.random() }.joinToString("")

        val key = result.substring(16) + randomSeed
        return Triple(randomSeed, result.take(16), key)
    }

    private suspend fun parseVideoSources(xml: String, callback: (ExtractorLink) -> Unit) {
        val sourceRegex = hoistedRegex6
        val sources = sourceRegex.findAll(xml).toList()

        for (sourceMatch in sources) {
            val sourceName = sourceMatch.groupValues[1]
            val exp = sourceMatch.groupValues[2]
            var sourceUrl = sourceMatch.groupValues[3]

            // Add https if needed
            if (sourceUrl.startsWith("//")) {
                sourceUrl = "https:$sourceUrl"
            }

            // Extract hash for this source
            // Audit-5 #46: sourceName comes from the remote page
            // and was interpolated into the regex unescaped —
            // metacharacters in it broke the pattern or threw.
            val hashMatch = """<hash_value_${Regex.escape(sourceName)}>([^<]+)<""".toRegex().find(xml)

            hashMatch?.let { match ->
                val hash = match.groupValues[1]
                val finalUrl = "$sourceUrl?md5=$hash&expires=$exp".replace("&amp;", "&")

                callback(
                    newExtractorLink(
                        name,
                        "$sourceName - $name",
                        finalUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = mainUrl
                    }
                )
            }
        }
    }

    private fun rc4DecryptBytes(encryptedBytes: ByteArray, key: String): String {
        // Audit-5 #45: an empty key made the KSA loop divide by
        // keyBytes.size == 0. Bail out with an empty result,
        // which callers already treat as a failed extraction.
        if (key.isEmpty()) return ""
        // Check if data is Base64 encoded
        val isBase64 = encryptedBytes.all { byte ->
            val char = byte.toInt() and 0xFF
            char in 32..126 || char == 10 || char == 13
        }

        val actualEncryptedBytes = if (isBase64) {
            val base64String = encryptedBytes.decodeToString()
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .trim()
            base64DecodeArray(base64String)
        } else {
            encryptedBytes
        }

        val keyBytes = key.encodeToByteArray()

        // RC4 key-scheduling algorithm (KSA)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (keyBytes[i % keyBytes.size].toInt() and 0xFF)) % 256
            s[i] = s[j].also { s[j] = s[i] }
        }

        // RC4 pseudo-random generation algorithm (PRGA)
        var i = 0
        j = 0
        val result = ByteArray(actualEncryptedBytes.size)
        for (k in actualEncryptedBytes.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            s[i] = s[j].also { s[j] = s[i] }
            val keyStreamByte = s[(s[i] + s[j]) % 256]
            result[k] = ((actualEncryptedBytes[k].toInt() and 0xFF) xor keyStreamByte).toByte()
        }

        return result.decodeToString()
    }
}
