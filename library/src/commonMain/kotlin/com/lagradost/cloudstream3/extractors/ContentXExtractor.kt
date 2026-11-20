// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("""window\.openPlayer\('([^']+)'""")
private val hoistedRegex2 = Regex("""file\":\"([^\"]+)""")
private val hoistedRegex3 = Regex(""",\"([^']+)\",\"Türkçe""")
private val hoistedRegex4 = Regex("""file\":\"([^\"]+)""")


open class ContentX : ExtractorApi() {
    override val name            = "ContentX"
    override val mainUrl         = "https://contentx.me"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""

        val iSource  = app.get(url, referer=extRef).text
        // Audit-4 #18: find() was force-unwrapped with !! before
        // the (already present) null handling could run.
        val iExtract = hoistedRegex1.find(iSource)?.groups?.get(1)?.value ?: throw ErrorLoadingException("iExtract is null")

        val subUrls = mutableSetOf<String>()
        Regex("""\"file\":\"([^\"]+)\",\"label\":\"([^\"]+)\"""").findAll(iSource).forEach {
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

        val vidSource  = app.get("${mainUrl}/source2.php?v=${iExtract}", referer=extRef).text
        val vidExtract = hoistedRegex2.find(vidSource)?.groups?.get(1)?.value ?: throw ErrorLoadingException("vidExtract is null")
        val m3uLink    = vidExtract.replace("\\", "")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )

        val iDublaj = hoistedRegex3.find(iSource)?.groups?.get(1)?.value
        if (iDublaj != null) {
            val dublajSource  = app.get("${mainUrl}/source2.php?v=${iDublaj}", referer=extRef).text
            val dublajExtract = hoistedRegex4.find(dublajSource)?.groups?.get(1)?.value ?: throw ErrorLoadingException("dublajExtract is null")
            val dublajLink    = dublajExtract.replace("\\", "")

            callback.invoke(
                newExtractorLink(
                    source  = "${this.name} Türkçe Dublaj",
                    name    = "${this.name} Türkçe Dublaj",
                    url     = dublajLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}