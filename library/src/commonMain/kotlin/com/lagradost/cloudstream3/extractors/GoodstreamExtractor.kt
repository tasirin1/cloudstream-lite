package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("file|player")
private val hoistedRegex2 = Regex("file: \"(https:\\/\\/[a-z0-9.\\/-_?=&]+)\",")


class GoodstreamExtractor : ExtractorApi() {
    override var name = "Goodstream"
    override val mainUrl = "https://goodstream.uno"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("script").map { script ->
            if (script.data().contains(hoistedRegex1)) {
                val urlRegex = hoistedRegex2
                // Audit-5 #43: ?.let — the plain .let ran even
                // when the regex found nothing, and link!! then
                // threw on a format change.
                urlRegex.find(script.data())?.groupValues?.get(1)?.let { link ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            link,
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
        }
    }
}