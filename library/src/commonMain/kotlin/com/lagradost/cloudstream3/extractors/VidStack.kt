package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.newExtractorLink
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import io.ktor.http.Url

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("\"source\":\"(.*?)\"")
private val hoistedRegex2 = Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"")
private val hoistedRegex3 = Regex("\"subtitle\":\\{(.*?)\\}")


class Server1uns : VidStack() {
    override var name = "Vidstack"
    override var mainUrl = "https://server1.uns.bio"
    override var requiresReferer = true
}


open class VidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseurl = getBaseUrl(url)

        val encoded = app.get("$baseurl/api/v1/video?id=$hash", headers = headers).text.trim()

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try {
                AesHelper.decryptAES(encoded, key, iv)
            } catch (e: Exception) {
                null
            }
        } ?: throw Exception("Failed to decrypt with all IVs")

        val m3u8 = hoistedRegex1.find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        val subtitlePattern = hoistedRegex2
        val subtitleSection = hoistedRegex3.find(decryptedText)?.groupValues?.get(1)

        subtitleSection?.let { section ->
            subtitlePattern.findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$mainUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        // Audit-7 #45: an unmatched m3u8 fell back to "" and
        // was still published as a playable link.
        if (m3u8.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    private fun getBaseUrl(url: String): String {
        return try {
            Url(url).let { "${it.protocol.name}://${it.host}" }
        } catch (e: Exception) {
            Log.e("Vidstack", "getBaseUrl fallback: ${e.message}")
            mainUrl
        }
    }
}

object AesHelper {
    private val aesCbc = CryptographyProvider.Default.get(AES.CBC)

    @OptIn(DelicateCryptographyApi::class)
    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val keyBytes = key.encodeToByteArray()
        val ivBytes = iv.encodeToByteArray()
        val aesKey = aesCbc.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, keyBytes)
        val cipher = aesKey.cipher(padding = true)
        val decrypted = cipher.decryptWithIvBlocking(ivBytes, inputHex.hexToByteArray())
        return decrypted.decodeToString()
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
