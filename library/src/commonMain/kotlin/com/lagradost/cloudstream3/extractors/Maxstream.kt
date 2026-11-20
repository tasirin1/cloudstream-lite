package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("cript\">eval((.|\\n)*?)</script>")
private val hoistedRegex2 = Regex("""src:"((.|\n)*?)",type""")


open class Maxstream : ExtractorApi() {
    override var name = "Maxstream"
    override var mainUrl = "https://maxstream.video/"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val extractedLinksList: MutableList<ExtractorLink> = mutableListOf()
        val response = app.get(url).text
        val jstounpack = hoistedRegex1.find(response)?.groups?.get(1)?.value
        val unpacjed = JsUnpacker(jstounpack).unpack()
        // Audit-7 #45: missing match -> "null" URL published.
        val extractedUrl = unpacjed?.let { hoistedRegex2.find(it) }?.groups?.get(1)?.value ?: return null

        M3u8Helper.generateM3u8(
            name,
            extractedUrl,
            url,
            headers = mapOf("referer" to url)
        ).forEach { link ->
            extractedLinksList.add(link)
        }

        return extractedLinksList
    }
}