package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("(?<=master)(.*)(?=hls)")
private val hoistedRegex2 = Regex("(?<=\\|file\\|)(.*)(?=\\|remove\\|)")


open class UpstreamExtractor : ExtractorApi() {
    override val name: String = "Upstream"
    override val mainUrl: String = "https://upstream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        //Log.i(this.name, "Result => (no extractor) ${url}")
        val doc = app.get(url, referer = referer).text
        if (doc.isNotBlank()) {
            var reg = hoistedRegex1
            val result = reg.find(doc)?.groupValues?.map {
                it.trim('|')
            }?.toList()
            reg = hoistedRegex2
            val domainList = reg.find(doc)?.groupValues?.get(1)?.split("|")
            var domain = when (!domainList.isNullOrEmpty()) {
                true -> {
                    if (domainList.isNotEmpty()) {
                        var domName = ""
                        for (part in domainList) {
                            domName = "${part}.${domName}"
                        }
                        domName.trimEnd('.')
                    } else {
                        ""
                    }
                }
                false -> ""
            }
            //Log.i(this.name, "Result => (domain) ${domain}")
            if (domain.isEmpty()) {
                domain = "s96.upstreamcdn.co"
                //Log.i(this.name, "Result => (default domain) ${domain}")
            }

            result?.forEach {
                val linkUrl = "https://${domain}/hls/${it}/master.m3u8"
                M3u8Helper.generateM3u8(
                    this.name,
                    linkUrl,
                    "$mainUrl/",
                    headers = mapOf("Origin" to mainUrl)
                ).forEach(callback)
            }
        }
    }
}