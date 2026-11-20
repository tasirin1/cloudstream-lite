package com.lagradost.cloudstream3.ui.result

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.throwAbleToResource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import java.util.*


data class CurrentSynced(
    val name: String,
    val idPrefix: String,
    val isSynced: Boolean,
    val hasAccount: Boolean,
    val icon: Int?,
)

class SyncViewModel : ViewModel() {
    companion object {
        const val TAG = "SYNCVM"
    }

    private val repos = AccountManager.syncApis

    private val _metaResponse: MutableLiveData<Resource<SyncAPI.SyncResult>?> =
        MutableLiveData(null)

    val metadata: LiveData<Resource<SyncAPI.SyncResult>?> = _metaResponse

    private val _userDataResponse: MutableLiveData<Resource<SyncAPI.AbstractSyncStatus>?> =
        MutableLiveData(null)

    val userData: LiveData<Resource<SyncAPI.AbstractSyncStatus>?> = _userDataResponse

    // prefix, id
    private val syncs = mutableMapOf<String, String>()
    //private val _syncIds: MutableLiveData<MutableMap<String, String>> =
    //    MutableLiveData(mutableMapOf())
    //val syncIds: LiveData<MutableMap<String, String>> get() = _syncIds

    fun getSyncs(): Map<String, String> {
        // Audit-11 #4: this used to return the LIVE map.
        // ResultViewModel2 stores it and iterates it off-thread
        // (applyMeta), and its setMeta change-guard compares
        // against the same instance, so in-place mutations
        // were invisible to the guard. Return a snapshot copy.
        return syncs.toMap()
    }

    private val _currentSynced: MutableLiveData<List<CurrentSynced>> =
        MutableLiveData(getMissing())

    // pair of name idPrefix isSynced
    val synced: LiveData<List<CurrentSynced>> = _currentSynced

    private fun getMissing(): List<CurrentSynced> {
        return repos.map {
            CurrentSynced(
                it.name,
                it.idPrefix,
                syncs.containsKey(it.idPrefix),
                it.authUser() != null,
                it.icon,
            )
        }
    }

    fun updateSynced() {
        Log.i(TAG, "updateSynced")
        _currentSynced.postValue(getMissing())
    }

    private fun addSync(idPrefix: String, id: String): Boolean {
        if (syncs[idPrefix] == id) return false
        Log.i(TAG, "addSync $idPrefix = $id")

        syncs[idPrefix] = id
        //_syncIds.postValue(syncs)
        return true
    }

    fun addSyncs(map: Map<String, String>?): Boolean {
        var isValid = false

        map?.forEach { (prefix, id) ->
            isValid = addSync(prefix, id) || isValid
        }
        return isValid
    }

    var hasAddedFromUrl: HashSet<String> = hashSetOf()

    fun addFromUrl(url: String?) {
        // Fork change: this used to extract MAL/AniList ids from a
        // shared url and sync them, but the external sync providers
        // were removed on 2026-11-20 (owner slimming pick 3), so
        // there is nothing left to add the ids to.
        Log.i(TAG, "addFromUrl ignored (external sync removed) = $url")
    }

    fun setEpisodesDelta(delta: Int) {
        Log.i(TAG, "setEpisodesDelta = $delta")

        val user = userData.value
        if (user is Resource.Success) {
            user.value.watchedEpisodes?.plus(
                delta
            )?.let { episode ->
                setEpisodes(episode)
            }
        }
    }

    fun setEpisodes(episodes: Int) {
        Log.i(TAG, "setEpisodes = $episodes")

        if (episodes < 0) return
        val meta = metadata.value
        if (meta is Resource.Success) {
            meta.value.totalEpisodes?.let { max ->
                if (episodes > max) {
                    setEpisodes(max)
                    return
                }
            }
        }

        val user = userData.value
        if (user is Resource.Success) {
            user.value.watchedEpisodes = episodes
            _userDataResponse.postValue(Resource.Success(user.value))
        }
    }

    fun setScore(score: Score?) {
        Log.i(TAG, "setScore = $score")
        val user = userData.value
        if (user is Resource.Success) {
            user.value.score = score
            _userDataResponse.postValue(Resource.Success(user.value))
        }
    }

    fun setStatus(which: Int) {
        Log.i(TAG, "setStatus = $which")
        if (which < -1 || which > 5) return // validate input
        val user = userData.value
        if (user is Resource.Success) {
            user.value.status = SyncWatchType.fromInternalId(which)
            _userDataResponse.postValue(Resource.Success(user.value))
        }
    }

    fun publishUserData() = ioSafe {
        Log.i(TAG, "publishUserData")
        val user = userData.value
        _userDataResponse.postValue(Resource.Loading())
        if (user is Resource.Success) {
            // Audit-11 #4: snapshot first, like updateUserData /
            // updateMetadata (Audit-9 #16) — iterating the live
            // map here let a concurrent setSync/addSyncs throw
            // ConcurrentModificationException mid-publish,
            // silently updating only some repos.
            val current = ArrayList(syncs.toList())
            current.forEach { (prefix, id) ->
                repos.firstOrNull { it.idPrefix == prefix }?.updateStatus(id, user.value)
            }
        }
        updateUserData()
    }

    fun modifyMaxEpisode(episodeNum: Int) {
        Log.i(TAG, "modifyMaxEpisode = $episodeNum")
        modifyData { status ->
            status.watchedEpisodes = maxOf(
                episodeNum,
                status.watchedEpisodes ?: return@modifyData null
            )
            status
        }
    }

    /// modifies the current sync data, return null if you don't want to change it
    private fun modifyData(update: ((SyncAPI.AbstractSyncStatus) -> (SyncAPI.AbstractSyncStatus?))) =
        ioSafe {
            // Audit-11 #4: snapshot first — same live-map
            // iteration hazard as publishUserData above.
            val current = ArrayList(syncs.toList())
            current.amap { (prefix, id) ->
                repos.firstOrNull { it.idPrefix == prefix }?.let { repo ->
                    val result =
                        update(repo.status(id).getOrNull() ?: return@let null) ?: return@let null
                    Log.i(TAG, "modifyData ${repo.name} => $result")
                    repo.updateStatus(id, result)
                }
            }
        }

    fun updateUserData() = ioSafe {
        Log.i(TAG, "updateUserData")
        _userDataResponse.postValue(Resource.Loading())

        // Audit-9 #16: snapshot the syncs map first, like
        // updateMetadata below already does — reading the live
        // map inside this async job let a fast popup switch pair
        // one title's metadata with the other title's status.
        val current = ArrayList(syncs.toList())
        val status = current.firstNotNullOfOrNull { (prefix, id) ->
            repos.firstOrNull { it.idPrefix == prefix }
                ?.status(id)?.getOrNull()
        }

        if (status == null) {
            _userDataResponse.postValue(Resource.Failure(false, "No data"))
        } else {
            _userDataResponse.postValue(Resource.Success(status))
        }
    }

    private fun updateMetadata() = ioSafe {
        Log.i(TAG, "updateMetadata")

        _metaResponse.postValue(Resource.Loading())
        var lastError: Resource<SyncAPI.SyncResult> = Resource.Failure(false, "No data")
        val current = ArrayList(syncs.toList())

        current.forEach { (prefix, id) ->
            repos.firstOrNull { it.idPrefix == prefix }?.let { repo ->
                Log.i(TAG, "updateMetadata loading ${repo.idPrefix}")
                val result = repo.load(id)
                val resultValue = result.getOrNull()
                val resultError = result.exceptionOrNull()
                if (resultValue != null) {
                    _metaResponse.postValue(Resource.Success(resultValue))
                    return@ioSafe
                } else if (resultError != null) {

                    /*Log.e(
                        TAG,
                        "updateMetadata error $id at ${repo.idPrefix} ${result.errorString}"
                    )*/
                    lastError = throwAbleToResource(resultError)
                }
            }
        }
        _metaResponse.postValue(lastError)
        setEpisodesDelta(0)
    }

    fun syncName(syncName: String): String? {
        // fix because of bad old data :pensive:
        val realName = when (syncName) {
            "MAL" -> "mal"
            "Kitsu" -> "kitsu"
            "Simkl" -> "simkl"
            "AniList" -> "anilist"
            else -> syncName
        }
        return repos.firstOrNull { it.idPrefix == realName }?.idPrefix
    }

    fun setSync(syncName: String, syncId: String) {
        syncs.clear()
        syncs[syncName] = syncId
    }

    fun clear() {
        syncs.clear()
        _metaResponse.postValue(null)
        _currentSynced.postValue(getMissing())
        _userDataResponse.postValue(null)
    }

    fun updateMetaAndUser() {
        _userDataResponse.postValue(Resource.Loading())
        _metaResponse.postValue(Resource.Loading())

        Log.i(TAG, "updateMetaAndUser")
        updateMetadata()
        updateUserData()
    }
}