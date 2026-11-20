package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("""\{var\s*[^\s]+\s*=\s*(\[[^]]+])""")
private val hoistedRegex2 = Regex("source\\s*src=\"([^\"]+)")


open class Mvidoo : ExtractorApi() {
    override val name = "Mvidoo"
    override val mainUrl = "https://mvidoo.com"
    override val requiresReferer = true

    private fun String.decodeHex(): String {
        // Audit-6 #34: the elements come from an array captured
        // from the remote page; one malformed element (odd
        // length, non-hex) used to throw via require()/toInt()
        // and kill the whole chain. Skip bad chunks instead.
        return chunked(2)
            .mapNotNull { chunk ->
                if (chunk.length != 2) {
                    null
                } else {
                    try {
                        chunk.toInt(16).toByte()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            .toByteArray()
            .decodeToString()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).text
        val data = hoistedRegex1.find(document)?.groupValues?.get(1)
            ?.removeSurrounding("[", "]")?.replace("\"", "")?.replace("\\x", "")?.split(",")?.map { it.decodeHex() }?.reversed()?.joinToString("") ?: return
        hoistedRegex2.find(data)?.groupValues?.get(1)?.let { link ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    link
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Range" to "bytes=0-"
                    )
                }
            )
        }
    }
}