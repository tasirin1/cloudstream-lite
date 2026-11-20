package com.lagradost.cloudstream3.ui.settings.extensions

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.cmap
import com.lagradost.cloudstream3.plugins.PLUGIN_VERSION_NOT_SET
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginManager.deletePlugin
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginPath
import com.lagradost.cloudstream3.plugins.PluginManager.unloadPlugin
import com.lagradost.cloudstream3.plugins.RepositoryManager.MAX_PLUGINS_PER_LIST
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.addRepository
import com.lagradost.cloudstream3.plugins.RepositoryManager.convertRawGitUrl
import com.lagradost.cloudstream3.plugins.RepositoryManager.parseRepoUrl
import com.lagradost.cloudstream3.plugins.RepositoryManager.removeRepository
import com.lagradost.cloudstream3.plugins.RepositoryManager.upgradeToHttps
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.ui.settings.ApkUpdater.isFileDigest
import com.lagradost.cloudstream4.viewmodels.DigestPair
import com.lagradost.cloudstream4.viewmodels.Plugin
import com.lagradost.cloudstream4.viewmodels.PluginDownloadSource
import com.lagradost.cloudstream4.viewmodels.PluginInfo
import com.lagradost.cloudstream4.viewmodels.PluginStatus
import com.lagradost.cloudstream4.viewmodels.RepositoryInfo
import com.lagradost.cloudstream4.viewmodels.RepositoryManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@InternalAPI
object AndroidRepositoryManager : RepositoryManager {
    fun RepositoryInfo.toData() =
        RepositoryData(
            name = name,
            url = url,
            iconUrl = iconUrl,
            description = description
        )

    fun RepositoryData.toInfo() =
        RepositoryInfo(
            name = name,
            url = url,
            iconUrl = iconUrl,
            description = description
        )

    override fun getRepositories(): List<RepositoryInfo> {
        return ((getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES).map { info ->
            info.toInfo()
        }
    }

    override suspend fun getPlugins(
        repository: RepositoryInfo,
        repositoryUuid: Uuid
    ): Pair<RepositoryInfo, List<Plugin>> =
        withContext(Dispatchers.IO) {
            val activity =
                CommonActivity.activity ?: throw ErrorLoadingException("No activity found")

            val url = parseRepoUrl(repository.url)
            if (url.isNullOrBlank()) {
                throw ErrorLoadingException(activity.getString(R.string.error_invalid_url))
            }

            // Audit-5 #12: bounded body, then parse the text
            // (RepositoryManager.readTextBounded).
            val parsedRepository = com.lagradost.cloudstream3.utils.AppUtils.parseJson(
                com.lagradost.cloudstream3.plugins.RepositoryManager.readTextBounded(
                    app.get(
                        url = convertRawGitUrl(url),
                        cacheTime = 15,
                        cacheUnit = TimeUnit.MINUTES
                    )
                ) ?: throw ErrorLoadingException("Repository manifest too large"),
                com.lagradost.cloudstream3.plugins.Repository::class
            )

            /**
             * No sane repository should be able to DDoS the app when navigating to settings, so
             * we limit this to a maximum of 10
             *
             * Note that this list is on all verified repos = 1, as pluginLists is a list of lists
             * */
            require(parsedRepository.pluginLists.size <= 10)

            /** Add this as a new repository -> replaces the old data with new icon,name and description */
            addRepository(
                RepositoryData(
                    iconUrl = parsedRepository.iconUrl,
                    url = url,
                    name = parsedRepository.name,
                    description = parsedRepository.description
                )
            )

            val plugins = parsedRepository.pluginLists.cmap { url ->
                /** This should really be a list of size ~ 1 */
                // Audit-5 #12: bounded body, then parse the text.
                com.lagradost.cloudstream3.utils.AppUtils.parseJson(
                    com.lagradost.cloudstream3.plugins.RepositoryManager.readTextBounded(
                        app.get(
                            // Audit-4 #6: plugin-list URLs from inside the
                            // manifest get the same HTTPS upgrade as the
                            // repository entry URL (Audit-3 #11).
                            url = convertRawGitUrl(upgradeToHttps(url)),
                            cacheTime = 15,
                            cacheUnit = TimeUnit.MINUTES
                        )
                    ) ?: throw ErrorLoadingException("Plugin list too large"),
                    Array<SitePlugin>::class
                )
                    // Audit-4 #30: cap entries per list, see
                    // RepositoryManager.MAX_PLUGINS_PER_LIST.
                    .take(MAX_PLUGINS_PER_LIST)
            }.flatMap { plugins ->
                plugins.map { plugin ->
                    val file = getPluginPath(
                        context = activity,
                        internalName = plugin.internalName,
                        repositoryUrl = url
                    )
                    val language = plugin.language?.trim()
                    Plugin(
                        info = PluginInfo(
                            name = plugin.name,
                            status = plugin.status,
                            internalName = plugin.internalName,
                            version = plugin.version,
                            tvTypes = plugin.tvTypes?.mapNotNull { runCatching { TvType.valueOf(it) }.getOrNull() }
                                ?.toPersistentList()
                                ?: persistentListOf(),
                            authors = plugin.authors.toPersistentList(),
                            iconUrl = plugin.iconUrl,
                            language = if (language.isNullOrBlank()) {
                                null
                            } else {
                                language
                            },
                            description = plugin.description,
                            repositoryUrl = plugin.repositoryUrl
                        ),
                        source = PluginDownloadSource(
                            size = plugin.fileSize,
                            repositoryUrl = url,
                            // Audit-4 #6: the plugin file URL is
                            // fetched verbatim at download time.
                            pluginUrl = upgradeToHttps(plugin.url),
                            internalName = plugin.internalName,
                            digest = plugin.fileHash?.let { hash ->
                                // Audit-3 #2: a hash the repo DID
                                // provide but we cannot parse must
                                // refuse the download, not silently
                                // degrade to "no digest".
                                DigestPair.parse(hash)
                                    ?: throw ErrorLoadingException("Unparseable fileHash for plugin ${plugin.internalName}")
                            }
                        ),
                        status = PluginStatus.Idle,
                        downloadLocation = if (file.exists()) {
                            file.absolutePath
                        } else {
                            null
                        },
                        repository = repositoryUuid,
                    )
                }
            }

            val info = RepositoryInfo(
                iconUrl = parsedRepository.iconUrl,
                url = url,
                name = parsedRepository.name,
                description = parsedRepository.description,
            )
            info to plugins
        }

    /**
     * Audit-6 #9: best-effort rollback of a failed plugin update —
     * removes the unloadable replacement, moves the previously
     * installed file back from its ".bak" parking spot, and
     * restores the anchor that belongs to it (or drops the new
     * anchor when the old file had none).
     */
    private fun rollbackPluginUpdate(
        file: File,
        backupFile: File?,
        previousAnchor: String?,
    ) {
        try {
            file.delete()
            if (backupFile != null && backupFile.exists()) {
                Files.move(
                    backupFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            if (previousAnchor != null) {
                PluginManager.storePluginHash(file.absolutePath, previousAnchor)
            } else {
                PluginManager.removePluginHash(file.absolutePath)
            }
        } catch (_: Exception) {
            // Best effort only: the caller still reports the
            // failed update either way.
        }
    }

    override suspend fun downloadPlugin(
        source: PluginDownloadSource,
        downloadProgress: (Long, Long?) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val context = CommonActivity.activity ?: throw ErrorLoadingException("No activity found")
        val file = getPluginPath(
            context = context,
            internalName = source.internalName,
            repositoryUrl = source.repositoryUrl
        )

        // Fork fix (audit sesi 15 #9): anchor the verified digest for
        // the load-time integrity check, like the legacy download path
        // already does. Kept in the "sha256-<hex>" format that
        // RepositoryManager.sha256 produces and loadPlugin compares.
        val anchorHash = source.digest
            // Audit-6 #7: compare spelling-insensitively — the
            // standard name is "SHA-256" since DigestPair.parse
            // normalizes, and this used to miss every spelling
            // but the exact "sha256".
            ?.takeIf {
                it.algorithm.replace("-", "")
                    .equals("sha256", ignoreCase = true)
            }
            ?.let { pair -> "sha256-" + pair.digest.joinToString("") { "%02x".format(it) } }

        /**
         * Before we do anything that needs a network connection,
         * we actually check if we need to update it
         *
         * Because if the file digest is correct, then why do we need to do a network connection?
         * */
        if (isFileDigest(file, source.digest)) {
            if (anchorHash != null) {
                PluginManager.storePluginHash(file.absolutePath, anchorHash)
            }
            return@withContext file.absolutePath
        }

        val tempFile = File.createTempFile(file.name, ".tmp", context.cacheDir)

        // Audit-4 #32: a failed transfer (network error, digest
        // mismatch, the Audit-4 #8 size ceiling) used to leave
        // the .tmp file behind in the cache dir. The body keeps
        // its indentation; the finally removes the temp file
        // unless the move below consumed it.
        var tempMoved = false
        // Audit-6 #9: where the installed file is parked while
        // its replacement proves it loads, and the anchor that
        // belongs to it (both restored by the rollback below).
        val backupFile = file.parentFile?.let { File(it, file.name + ".bak") }
        val previousAnchor = PluginManager.storedPluginHash(file.absolutePath)
        try {
        tempFile.outputStream().use { writeStream ->
            app.get(convertRawGitUrl(source.pluginUrl)).body.use { body ->
                val readStream = body.byteStream()
                ApkUpdater.transfer(
                    writeStream = writeStream,
                    readStream = readStream,
                    length = source.size,
                    downloadProgress = downloadProgress,
                    digest = source.digest
                )
            }
        }

        // Always load the plugin
        unloadPlugin(file.absolutePath)

        // Audit-6 #9: move the installed file aside instead of
        // deleting it — it is only discarded once the replacement
        // has proven it loads (see the rollback at loadPlugin
        // below). The old flow deleted the working file here, so
        // an update whose digest matched but whose dex/manifest
        // this build cannot load destroyed the working plugin
        // permanently.
        if (backupFile != null && file.exists()) {
            Files.move(
                file.toPath(),
                backupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } else {
            file.delete() // delete if we somehow already have this downloaded?
        }
        file.parentFile?.mkdirs() // make the directories as Files.move needs this to work

        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        tempMoved = true
        } finally {
            // Audit-4 #32: see tempMoved above.
            if (!tempMoved) tempFile.delete()
        }

        // Audit-3 #1: anchor BEFORE load, like the legacy path
        // (PluginManager.downloadPlugin). Storing after load made
        // loadPlugin compare the new file against the previous
        // version's anchor, so every update via this screen failed
        // with "Unable to load plugin" and stayed broken.
        if (anchorHash != null) {
            PluginManager.storePluginHash(file.absolutePath, anchorHash)
        }

        val result = try {
            PluginManager.loadPlugin(
                context = context,
                file = file,
                data = PluginData(
                    internalName = source.internalName,
                    url = source.pluginUrl,
                    isOnline = true,
                    filePath = file.absolutePath,
                    version = PLUGIN_VERSION_NOT_SET
                )
            )
        } catch (e: Exception) {
            rollbackPluginUpdate(file, backupFile, previousAnchor)
            throw e
        }
        if (!result) {
            // Audit-6 #9: the replacement cannot load — put the
            // working file and its anchor back before reporting
            // the failure, instead of leaving the broken file in
            // place to fail at every startup with the plugin gone.
            rollbackPluginUpdate(file, backupFile, previousAnchor)
            throw ErrorLoadingException("Unable to load plugin ${source.internalName}")
        }
        backupFile?.delete()

        return@withContext file.absolutePath
    }

    override fun getLocalPlugins(localUuid: Uuid): List<Plugin> {
        val plugins = PluginManager.getPluginsLocal()
        return plugins.map { plugin ->
            Plugin(
                info = PluginInfo(
                    name = plugin.internalName,
                    internalName = plugin.internalName,
                    version = plugin.version,
                    tvTypes = persistentListOf(),
                    authors = persistentListOf(),
                    status = 1,
                    iconUrl = null,
                    language = null,
                    description = null,
                    repositoryUrl = null
                ),
                source = null,
                status = PluginStatus.Idle,
                downloadLocation = plugin.filePath,
                repository = localUuid,
            )
        }
    }

    override suspend fun deletePlugin(location: String) {
        deletePlugin(File(location))
    }

    override suspend fun deleteRepository(repository: RepositoryInfo) {
        val context = CommonActivity.activity ?: throw ErrorLoadingException("No activity found")
        removeRepository(
            context,
            repository.toData()
        )
    }

    override suspend fun updateRepositoryInfo(repository: RepositoryInfo) {
        addRepository(
            repository.toData()
        )
    }
}