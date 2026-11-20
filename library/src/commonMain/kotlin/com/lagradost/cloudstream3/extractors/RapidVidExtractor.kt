// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("""file": "(.*)",""")
private val hoistedRegex2 = Regex("""\};\s*(eval\(function[\s\S]*?)var played = \d+;""")
private val hoistedRegex3 = Regex("""file":"(.*)","label""")


open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.net"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val videoReq = app.get(url, referer=extRef).text

        val subUrls = mutableSetOf<String>()
        Regex("""captions\",\"file\":\"([^\"]+)\",\"label\":\"([^\"]+)\"""").findAll(videoReq).forEach {
            val (subUrl, subLang) = it.destructured

            if (subUrl in subUrls) { return@forEach }
            subUrls.add(subUrl)

            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = subLang.replace("\\u0131", "ı").replace("\\u0130", "İ").replace("\\u00fc", "ü").replace("\\u00e7", "ç"),
                    url  = fixUrl(subUrl.replace("\\", ""))
                )
            )
        }

        var extractedValue   = hoistedRegex1.find(videoReq)?.groupValues?.get(1)
        var decoded: String? = null

        if (extractedValue != null) {
            // Audit-6 #17: the captured value was hex-decoded
            // unguarded — a plain (unescaped) URL or any non-hex
            // chunk threw NumberFormatException and killed the
            // extraction even when the page held a usable link.
            // A value that is already a URL is used as-is; a
            // value that fails to decode fails cleanly.
            decoded = if (extractedValue.contains("://")) {
                extractedValue
            } else {
                try {
                    extractedValue.split("\\x").filter { it.isNotEmpty() }
                        .map { it.toInt(16).toByte() }.toByteArray().decodeToString()
                } catch (_: Exception) {
                    throw ErrorLoadingException("File not found")
                }
            }
        } else {
            val evalJWSsetup = hoistedRegex2.find(videoReq)?.groupValues?.get(1) ?: throw ErrorLoadingException("File not found")
            val JWSsetup      = getAndUnpack(getAndUnpack(evalJWSsetup)).replace("\\\\", "\\")
            extractedValue  = hoistedRegex3.find(JWSsetup)?.groupValues?.get(1)?.replace("\\\\x", "")

            // Audit-6 #17: same unguarded hex decode as above —
            // non-hex chunks and odd lengths now fail cleanly
            // instead of throwing NumberFormatException.
            decoded = extractedValue?.let { value ->
                try {
                    if (value.length % 2 != 0) null
                    else value.chunked(2).map { it.toInt(16).toByte() }
                        .toByteArray().decodeToString()
                } catch (_: Exception) {
                    null
                }
            } ?: throw ErrorLoadingException("File not found")
        }

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = decoded,
                type    = ExtractorLinkType.M3U8
            ) {
                this.referer = extRef
                this.quality = Qualities.Unknown.value
            }
        )
    }
}