package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Fork note (2026-11-20): extracted from the deleted KitsuApi.kt. This
// object is only the public Kitsu episode-metadata lookup (no account,
// no sync); the Kitsu account provider was removed with the other
// external sync providers (owner slimming pick 3).

// modified code from from https://github.com/saikou-app/saikou/blob/main/app/src/main/java/ani/saikou/others/Kitsu.kt
// GNU General Public License v3.0 https://github.com/saikou-app/saikou/blob/main/LICENSE.md
object Kitsu {
    private suspend fun getKitsuData(query: String): KitsuResponse {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Connection" to "keep-alive",
            "DNT" to "1",
            "Origin" to "https://kitsu.io"
        )

        return app.post(
            "https://kitsu.io/api/graphql",
            headers = headers,
            data = mapOf("query" to query)
        ).parsed<KitsuResponse>()
    }

    // Audit-10 #9: this was a plain mutableMapOf mutated from
    // concurrent coroutines with no lock and no size bound —
    // same class as the APIHolder tracker cache (Audit-10 #8).
    private val cache: MutableMap<Pair<String, String>, Map<Int, KitsuResponse.Node>> =
        mutableMapOf()

    var isEnabled = true

    suspend fun getEpisodesDetails(
        malId: String?,
        anilistId: String?,
        isResponseRequired: Boolean = true, // overrides isEnabled
    ): Map<Int, KitsuResponse.Node>? {
        if (!isResponseRequired && !isEnabled) return null
        if (anilistId != null) {
            try {
                val map = getKitsuEpisodesDetails(anilistId, "ANILIST_ANIME")
                if (!map.isNullOrEmpty()) return map
            } catch (e: Exception) {
                logError(e)
            }
        }
        if (malId != null) {
            try {
                val map = getKitsuEpisodesDetails(malId, "MYANIMELIST_ANIME")
                if (!map.isNullOrEmpty()) return map
            } catch (e: Exception) {
                logError(e)
            }
        }
        return null
    }

    @Throws
    suspend fun getKitsuEpisodesDetails(id: String, site: String): Map<Int, KitsuResponse.Node>? {
        require(id.isNotBlank()) {
            "Black id"
        }

        require(site.isNotBlank()) {
            "invalid site"
        }

        synchronized(cache) { cache[id to site] }?.let { return it }

        val query =
            """
query {
  lookupMapping(externalId: $id, externalSite: $site) {
    __typename
    ... on Anime {
      id
      episodes(first: 2000) {
        nodes {
          number
          titles {
            canonical
          }
          description
          thumbnail {
            original {
              url
            }
          }
        }
      }
    }
  }
}"""
        val result = getKitsuData(query)
        val map = (result.data?.lookupMapping?.episodes?.nodes ?: return null).mapNotNull { ep ->
            val num = ep?.num ?: return@mapNotNull null
            num to ep
        }.toMap()
        if (map.isNotEmpty()) {
            synchronized(cache) {
                if (cache.size >= 200) cache.clear()
                cache[id to site] = map
            }
        }
        return map
    }

    @Serializable
    data class KitsuResponse(
        @JsonProperty("data") @SerialName("data") val data: Data? = null,
    ) {
        @Serializable
        data class Data(
            @JsonProperty("lookupMapping") @SerialName("lookupMapping") val lookupMapping: LookupMapping? = null,
        )

        @Serializable
        data class LookupMapping(
            @JsonProperty("id") @SerialName("id") val id: String? = null,
            @JsonProperty("episodes") @SerialName("episodes") val episodes: Episodes? = null,
        )

        @Serializable
        data class Episodes(
            @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Node?>? = null,
        )

        @Serializable
        data class Node(
            @JsonProperty("number") @SerialName("number") val num: Int? = null,
            @JsonProperty("titles") @SerialName("titles") val titles: Titles? = null,
            @JsonProperty("description") @SerialName("description") val description: Description? = null,
            @JsonProperty("thumbnail") @SerialName("thumbnail") val thumbnail: Thumbnail? = null,
        )

        @Serializable
        data class Description(
            @JsonProperty("en") @SerialName("en") val en: String? = null,
        )

        @Serializable
        data class Thumbnail(
            @JsonProperty("original") @SerialName("original") val original: Original? = null,
        )

        @Serializable
        data class Original(
            @JsonProperty("url") @SerialName("url") val url: String? = null,
        )

        @Serializable
        data class Titles(
            @JsonProperty("canonical") @SerialName("canonical") val canonical: String? = null,
        )
    }
}
