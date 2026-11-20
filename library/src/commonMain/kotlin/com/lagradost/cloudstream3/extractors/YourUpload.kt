package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("\\d{3,4}p")


open class YourUpload : ExtractorApi() {
    override val name = "Yourupload"
    override val mainUrl = "https://www.yourupload.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val sources = mutableListOf<ExtractorLink>()
        with(app.get(url).document) {
            val quality = hoistedRegex1.find(this.select("title").text())?.groupValues?.get(0)
            this.select("script").map { script ->
                if (script.data().contains("var jwplayerOptions = {")) {
                    val data = script.data().substringAfter("var jwplayerOptions = {").substringBefore(",\n")
                    val link = tryParseJson<ResponseSource>(
                        "{${data.replace("file", "\"file\"").replace("'", "\"")}}"
                    )

                    // Audit-5 #44: tryParseJson over a fragile
                    // string-rebuilt JSON is nullable — link!!
                    // threw whenever the reconstruction failed to
                    // parse. Skip the source instead.
                    link?.file?.let { file ->
                        sources.add(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = file,
                            ) {
                                this.referer = url
                                this.quality = getQualityFromName(quality)
                            }
                        )
                    }
                }
            }
        }

        return sources
    }

    @Serializable
    private data class ResponseSource(
        @JsonProperty("file") @SerialName("file") val file: String,
    )
}
