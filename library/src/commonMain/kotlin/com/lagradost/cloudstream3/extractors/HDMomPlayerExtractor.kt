// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Audit-3 #46: constant regexes hoisted out of function bodies;
// they were recompiled on every call.
private val hoistedRegex1 = Regex("""bePlayer\('([^']+)',\s*'(\{[^\}]+\})'\);""")
private val hoistedRegex2 = Regex("""video_location\":\"([^\"]+)""")
private val hoistedRegex3 = Regex("""file:\"([^\"]+)""")
private val hoistedRegex4 = Regex("""tracks:\[([^\]]+)""")


open class HDMomPlayer : ExtractorApi() {
    override val name = "HDMomPlayer"
    override val mainUrl = "https://hdmomplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val m3uLink: String?
        val extRef = referer ?: ""
        val iSource = app.get(url, referer = extRef).text
        val bePlayer = hoistedRegex1.find(iSource)?.groupValues
        if (bePlayer != null) {
            val bePlayerPass = bePlayer.get(1)
            val bePlayerData = bePlayer.get(2)
            val encrypted = AesHelper.cryptoAESHandler(bePlayerData, bePlayerPass.encodeToByteArray(), false)?.replace("\\", "") ?: throw ErrorLoadingException("failed to decrypt")
            m3uLink = hoistedRegex2.find(encrypted)?.groupValues?.get(1)
        } else {
            m3uLink = hoistedRegex3.find(iSource)?.groupValues?.get(1)
            val trackStr = hoistedRegex4.find(iSource)?.groupValues?.get(1)
            if (trackStr != null) {
                val tracks = parseJson<List<Track>>("[${trackStr}]")
                for (track in tracks) {
                    if (track.file == null || track.label == null) continue
                    if (track.label.contains("Forced")) continue
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = track.label,
                            url = fixUrl(mainUrl + track.file),
                        )
                    )
                }
            }
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3uLink ?: throw ErrorLoadingException("m3u link not found"),
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    @Serializable
    data class Track(
        @JsonProperty("file") @SerialName("file") val file: String?,
        @JsonProperty("label") @SerialName("label") val label: String?,
        @JsonProperty("kind") @SerialName("kind") val kind: String?,
        @JsonProperty("language") @SerialName("language") val language: String?,
        @JsonProperty("default") @SerialName("default") val default: String?,
    )
}
