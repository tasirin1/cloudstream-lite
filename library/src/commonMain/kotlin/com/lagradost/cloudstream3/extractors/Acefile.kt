package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("/(?:d|download|player|f|file)/(\\w+)")
private val hoistedRegex2 = Regex("""service\s*=\s*['"]([^'"]+)""")
private val hoistedRegex3 = Regex("""['"](\S+check&id\S+?)['"]""")


open class Acefile : ExtractorApi() {
    override val name = "Acefile"
    override val mainUrl = "https://acefile.co"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = hoistedRegex1.find(url)?.groupValues?.get(1)
        val script = getAndUnpack(app.get("$mainUrl/player/${id ?: return}").text)
        val service = hoistedRegex2.find(script)?.groupValues?.get(1)
        val serverUrl = hoistedRegex3.find(script)?.groupValues?.get(1)
            ?.replace("\"+service+\"", service ?: return)

        val video = app.get(serverUrl ?: return, referer = "$mainUrl/").parsedSafe<Source>()?.data

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                video ?: return,
            )
        )

    }

    @Serializable
    data class Source(
        @JsonProperty("data") @SerialName("data") val data: String? = null,
    )
}
