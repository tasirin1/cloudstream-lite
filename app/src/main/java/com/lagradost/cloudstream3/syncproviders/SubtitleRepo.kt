package com.lagradost.cloudstream3.syncproviders

import androidx.annotation.WorkerThread
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleEntity
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount

/** Stateless safe abstraction of SubtitleAPI */
class SubtitleRepo(override val api: SubtitleAPI) : AuthRepo(api) {
    companion object {
        data class SavedSearchResponse(
            val unixTime: Long,
            val response: List<SubtitleEntity>,
            val query: SubtitleSearch,
            val idPrefix: String,
            // Audit-13 #27: see SavedResourceResponse.account.
            val account: String
        )

        data class SavedResourceResponse(
            val unixTime: Long,
            val response: SubtitleResource,
            val query: SubtitleEntity,
            // Audit-13 #27: the profile that produced this
            // entry — resource links are bought with that
            // profile's provider token.
            val account: String
        )

        // maybe make this a generic struct? right now there is a lot of boilerplate
        private val searchCache = atomicListOf<SavedSearchResponse>()
        private var searchCacheIndex: Int = 0
        private val resourceCache = atomicListOf<SavedResourceResponse>()
        private var resourceCacheIndex: Int = 0
        const val CACHE_SIZE = 20
    }

    @WorkerThread
    suspend fun resource(data: SubtitleEntity): Result<SubtitleResource> = runCatching {
        // Audit-13 #27: these caches live in the companion and
        // are shared by every profile, but a cached resource
        // link is bought with the CURRENT profile's provider
        // token — without scoping, profile B was served
        // profile A's account-bound download links (and kept
        // using them after A logged out). Scope both caches
        // by the active account.
        val account = currentAccount
        val cached = resourceCache.withLock {
            var found: SubtitleResource? = null
            for (item in resourceCache) {
                // 20 min save
                if (item.account == account && item.query == data && (unixTime - item.unixTime) < 60 * 20) {
                    found = item.response
                    break
                }
            }
            found
        }
        if (cached != null) return@runCatching cached

        val returnValue = api.resource(freshAuth(), data)
        resourceCache.withLock {
            val add = SavedResourceResponse(unixTime, returnValue, data, account)
            if (resourceCache.size > CACHE_SIZE) {
                resourceCache[resourceCacheIndex] = add // rolling cache
                resourceCacheIndex = (resourceCacheIndex + 1) % CACHE_SIZE
            } else {
                resourceCache.add(add)
            }
        }
        returnValue
    }

    @WorkerThread
    suspend fun search(query: SubtitleSearch): Result<List<SubtitleEntity>> {
        return runCatching {
            // Audit-13 #27: see resource().
            val account = currentAccount
            val cached = searchCache.withLock {
                var found: List<SubtitleEntity>? = null
                for (item in searchCache) {
                    // 120 min save
                    if (item.account == account && item.idPrefix == idPrefix && item.query == query && (unixTime - item.unixTime) < 60 * 120) {
                        found = item.response
                        break
                    }
                }
                found
            }

            if (cached != null) return@runCatching cached
            val returnValue = api.search(freshAuth(), query) ?: emptyList()

            // only cache valid return values
            if (returnValue.isNotEmpty()) {
                val add = SavedSearchResponse(unixTime, returnValue, query, idPrefix, account)
                searchCache.withLock {
                    if (searchCache.size > CACHE_SIZE) {
                        searchCache[searchCacheIndex] = add // rolling cache
                        searchCacheIndex = (searchCacheIndex + 1) % CACHE_SIZE
                    } else {
                        searchCache.add(add)
                    }
                }
            }
            returnValue
        }
    }
}
