package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("((https:|http:)\\/\\/.*\\.m3u8)")



open class PlayerVoxzer : ExtractorApi() {
    override var name = "Voxzer"
    override var mainUrl = "https://player.voxzer.org"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val listurl = url.replace("/view/","/list/")
        val urltext = app.get(listurl, referer = url).text
        val m3u8regex = hoistedRegex1
        val sources = mutableListOf<ExtractorLink>()
        val listm3 = m3u8regex.find(urltext)?.value
        if (listm3?.contains("m3u8") == true)
            M3u8Helper.generateM3u8(
                name,
                listm3,
                url,
                headers = app.get(url).headers.toMap()
            ).forEach { link ->
                sources.add(link)
            }
        return sources
    }
}