package com.lagradost.cloudstream3.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ExpandableSearchList(
    var list: List<SearchResponse>, var currentPage: Int, var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

// Audit-6 #32: history is pruned to this many newest entries.
const val MAX_HISTORY_ENTRIES = 100

class SearchViewModel : ViewModel() {
    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    private var suggestionJob: Job? = null

    private var repos = apis.withLock { apis.map { APIRepository(it) } }

    fun clearSearch() {
        _searchResponse.postValue(Resource.Success(ExpandableSearchList(emptyList(), 0, false)))
        _currentSearch.postValue(emptyMap())
        expandableSearches.clear()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    // Audit-6 #10: synchronized — search() writes this map from
    // parallel amap IO threads, and the SAME live instance used
    // to be posted to LiveData, so the UI iterated it while IO
    // mutated it (the fragments already carry defensive
    // try/catch + locks against the resulting CME). Writes go
    // through the synchronized map; readers get snapshots.
    private val expandableSearches: MutableMap<String, ExpandableSearchList> =
        java.util.Collections.synchronizedMap(mutableMapOf())

    private fun expandableSearchesSnapshot(): Map<String, ExpandableSearchList> =
        synchronized(expandableSearches) { LinkedHashMap(expandableSearches) }

    private var currentSearchIndex = 0
    private var onGoingSearch: Job? = null

    fun reloadRepos() {
        repos = apis.withLock { apis.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        currentSearchIndex++
        onGoingSearch?.cancel()
        onGoingSearch = search(query, providersActive, ignoreSettings, isQuickSearch)
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    // Audit-6 #32: see the write site in search().
    private fun searchHistoryKey(query: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(query.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun pruneSearchHistory() {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: return
        if (items.size <= MAX_HISTORY_ENTRIES) return
        items.drop(MAX_HISTORY_ENTRIES).forEach { item ->
            removeKey("$currentAccount/$SEARCH_HISTORY_KEY", item.key)
        }
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    // Audit-7 #51: generation guard for the suggestions path,
    // mirroring currentSearchIndex for search() below. A slow
    // response for an old query used to post after a newer
    // query (or after clearSuggestions) and overwrite the panel.
    private var currentSuggestionIndex = 0

    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        val index = ++currentSuggestionIndex

        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }

        suggestionJob = ioSafe {
            delay(300) // Debounce
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            if (index == currentSuggestionIndex) {
                _searchSuggestions.postValue(suggestions)
            }
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        currentSuggestionIndex++
        _searchSuggestions.postValue(emptyList())
    }

    // Audit-6 #12: synchronized so the claim in expandAndReturn
    // (Set.add returning false when already present) is atomic.
    private val lock: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        val query = lastQuery ?: return null
        val repo = repos.find { it.name == name } ?: return null
        // Audit-8 #6: capture the search generation — search()
        // is guarded by currentSearchIndex, this expand path
        // never was. Without it, a slow page-2 fetch for query
        // "abc" resuming after the user searched "xyz" appended
        // abc's items into xyz's entry, overwrote its page
        // counter, and posted the polluted bundle over the new
        // search's results.
        val searchIndex = currentSearchIndex

        // Audit-6 #12: atomic claim + finally release — see
        // HomeViewModel.expandAndReturn for the failure modes
        // (double expand skipping a page; cancellation leaking
        // the lock and killing load-more for the session).
        if (!lock.add(name)) return null

        try {

        expandableSearches[name]?.let { current ->
            debugAssert({ !current.hasNext }) {
                "Expand called when not needed"
            }

            val nextPage = current.currentPage + 1
            val next = repo.search(query, nextPage)
            // Audit-8 #6: a newer search started while this page
            // was loading — drop the stale page entirely.
            if (searchIndex != currentSearchIndex) return null
            if (next is Resource.Success) {
                val nextValue = next.value
                expandableSearches[name]?.apply {
                    this.hasNext = nextValue.hasNext
                    this.currentPage = nextPage

                    debugWarning({ nextValue.items.any { outer -> this.list.any { it.url == outer.url } } }) {
                        "Expanded search contained an item that was previously already in the list.\nQuery = $query, ${nextValue.items} = ${this.list}"
                    }

                    // just to be sure we are not adding the same shit for some reason
                    // Avoids weird behavior in the recyclerview by recreating the list
                    this.list = (this.list + nextValue.items).distinctBy { it.url }
                } ?: debugWarning {
                    "Expanded an item not in search load named $name, current list is ${expandableSearches.keys}"
                }
            } else {
                current.hasNext = false
            }

            _searchResponse.postValue(Resource.Success(bundleSearch(expandableSearchesSnapshot())))
            _currentSearch.postValue(expandableSearchesSnapshot())
        }

        val item = expandableSearches[name] ?: return null
        return HomeViewModel.ExpandableHomepageList(
            HomePageList(name, item.list),
            item.currentPage,
            item.hasNext
        )
        } finally {
            lock -= name
        }
    }

    private fun bundleSearch(lists: Map<String, ExpandableSearchList>): ExpandableSearchList {
        if (lists.size == 1) {
            return lists.values.first()
        }

        val list = ArrayList<SearchResponse>()
        val nestedList =
            lists.map { it.value.list }

        // I do it this way to move the relevant search results to the top
        var index = 0
        while (true) {
            var added = 0
            for (sublist in nestedList) {
                if (sublist.size > index) {
                    list.add(sublist[index])
                    added++
                }
            }
            if (added == 0) break
            index++
        }

        return ExpandableSearchList(list, 1, false)
    }

    private fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) =
        viewModelScope.launchSafe {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return@launchSafe
            }

            if (!isQuickSearch) {
                // Audit-6 #32: the history key was the query's
                // 32-bit String.hashCode — collisions silently
                // overwrote another query's entry ("Aa"/"BB"
                // style), and entries accumulated without bound,
                // with updateHistory() loading and sorting ALL of
                // them on every call. Key by SHA-256 of the query
                // and prune to the newest MAX_HISTORY_ENTRIES.
                val key = searchHistoryKey(query)
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(), // TODO implement tv type
                        key = key,
                    )
                )
                pruneSearchHistory()
            }

            _searchResponse.postValue(Resource.Loading())
            _currentSearch.postValue(emptyMap())
            expandableSearches.clear()

            lastQuery = query

            withContext(Dispatchers.IO) { // This interrupts UI otherwise
                repos.filter { a ->
                    (ignoreSettings || (providersActive.isEmpty() || providersActive.contains(a.name))) && (!isQuickSearch || a.hasQuickSearch)
                }.amap { a -> // Parallel
                    val search = if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                    if (currentSearchIndex != currentIndex) return@amap
                    if (search is Resource.Success) {
                        val searchValue = search.value
                        expandableSearches[a.name] =
                            ExpandableSearchList(searchValue.items, 1, searchValue.hasNext)
                    }

                    _currentSearch.postValue(expandableSearchesSnapshot())
                }

                if (currentSearchIndex != currentIndex) return@withContext // this should prevent rewrite of existing data bug

                _currentSearch.postValue(expandableSearchesSnapshot())
                val list = bundleSearch(expandableSearchesSnapshot())

                _searchResponse.postValue(Resource.Success(list))
            }
        }
}
