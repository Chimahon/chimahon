package chimahon.novel.sync.ttu

import android.content.Context
import android.util.Log
import chimahon.novel.data.Statistics
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * TTU progress + statistics sync over Google Drive, wire-compatible with the
 * iOS reader. Local state flows through [TtuLocalStore] (DB rows for
 * registered books, sidecars otherwise). Statistics merges are monotonic
 * max-merges, never additive, so re-syncing is always a no-op.
 */
class TtuSyncManager(
    private val context: Context,
    private val authManager: TtuOAuthManager,
    private val settingsRepository: SyncSettingsRepository,
    private val folderNames: TtuFolderNames = TtuFolderNames(context),
    private val localStore: TtuLocalStore = TtuLocalStoreImpl(context),
    private val driveClient: TtuDriveClient = TtuDriveClient(context, authManager),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    val settingsFlow: Flow<SyncSettings>
        get() = settingsRepository.settings

    val autoSyncEnabled: Boolean
        get() = settingsRepository.currentSettings().autoSyncEnabled

    val autoSyncOnOpen: Boolean
        get() = settingsRepository.currentSettings().autoSyncOnOpen

    val autoSyncOnClose: Boolean
        get() = settingsRepository.currentSettings().autoSyncOnClose

    val autoSyncPeriodic: Boolean
        get() = settingsRepository.currentSettings().autoSyncPeriodic

    val autoSyncIntervalMins: Int
        get() = settingsRepository.currentSettings().autoSyncIntervalMins

    val statisticsSyncEnabled: Boolean
        get() = settingsRepository.currentSettings().statisticsSyncEnabled

    val statisticsSyncMode: StatisticsSyncMode
        get() = settingsRepository.currentSettings().statisticsSyncMode

    fun loadSettings(): SyncSettings = settingsRepository.currentSettings()

    fun saveSettings(settings: SyncSettings) {
        settingsRepository.update { settings }
    }

    fun updateSettings(transform: (SyncSettings) -> SyncSettings) {
        settingsRepository.update(transform)
    }

    val isEnabled: Boolean get() = settingsRepository.currentSettings().enabled && authManager.isConnected

    fun clearCache() {
        driveClient.clearCache()
    }

    suspend fun listBooks(): List<TtuBookRef> = localStore.listBooks()

    suspend fun syncBook(
        ref: TtuBookRef,
        direction: SyncDirection = SyncDirection.AUTO,
        importOnly: Boolean = false,
    ): SyncResult {
        Log.d(TAG, "syncBook requested: folder='${ref.folder}', title='${ref.title}', direction=$direction, importOnly=$importOnly")
        if (!isEnabled) {
            Log.d(
                TAG,
                "syncBook skipped: enabled=${settingsRepository.currentSettings().enabled}, connected=${authManager.isConnected}",
            )
            return SyncResult.Skipped
        }

        val state = localStore.read(ref)
        if (state == null) {
            Log.d(TAG, "syncBook skipped: no local state for folder='${ref.folder}'")
            return SyncResult.Skipped
        }

        return try {
            performSync(state, direction, importOnly).also {
                Log.d(TAG, "syncBook finished: title='${ref.title}', result=$it")
            }
        } catch (e: DriveFileNotFoundException) {
            Log.w(TAG, "syncBook got stale Drive file, clearing cache and retrying", e)
            driveClient.clearCache()
            try {
                performSync(state, direction, importOnly).also {
                    Log.d(TAG, "syncBook retry finished: title='${ref.title}', result=$it")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "syncBook retry failed: title='${ref.title}'", e2)
                SyncResult.Failed(ref.title, e2.message ?: "Unknown error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "syncBook failed: title='${ref.title}'", e)
            SyncResult.Failed(ref.title, e.message ?: "Unknown error")
        }
    }

    private suspend fun performSync(
        state: TtuLocalState,
        direction: SyncDirection,
        importOnly: Boolean,
    ): SyncResult {
        val displayTitle = state.ref.title
        val rootId = driveClient.findOrCreateRootFolder()
        val sanitizedTitle = TtuSyncRules.sanitizeTtuFilename(displayTitle)
        val folderName = folderNames.get(state.novelId, state.ref.folder) ?: sanitizedTitle

        val bookFolderId = driveClient.findOrCreateBookFolder(
            rootId = rootId,
            folderName = folderName,
            coverDataProvider = state.coverBytes?.let { bytes -> { bytes } },
        )
        folderNames.set(state.novelId, state.ref.folder, folderName)

        val remoteFiles = driveClient.listSyncFiles(bookFolderId)
        Log.d(
            TAG,
            "performSync state: folder='$folderName', localLastModified=${state.lastModified}, remoteProgress=${remoteFiles.progress?.name}",
        )

        val resolvedDirection = if (direction != SyncDirection.AUTO) {
            direction
        } else {
            TtuSyncRules.determineDirection(state.lastModified, remoteFiles.progress)
        }
        Log.d(TAG, "performSync direction: requested=$direction, resolved=$resolvedDirection")

        if (importOnly && resolvedDirection != SyncDirection.IMPORT) {
            Log.d(TAG, "performSync importOnly skipped: resolvedDirection=$resolvedDirection")
            return SyncResult.Synced(displayTitle)
        }

        return when (resolvedDirection) {
            SyncDirection.IMPORT -> importFromTtu(state, remoteFiles, displayTitle)
            SyncDirection.EXPORT -> exportToTtu(state, remoteFiles, bookFolderId, displayTitle)
            SyncDirection.SYNCED -> SyncResult.Synced(displayTitle)
            SyncDirection.AUTO -> SyncResult.Skipped
        }
    }

    private suspend fun importFromTtu(
        state: TtuLocalState,
        remoteFiles: DriveSyncFiles,
        displayTitle: String,
    ): SyncResult {
        var imported = false
        var importedCharacterCount = state.characterCount

        if (remoteFiles.progress != null) {
            try {
                val content = driveClient.downloadFile(remoteFiles.progress.id)
                val ttuProgress = json.decodeFromString<TtuProgress>(content)
                val (chapterIndex, fraction) = resolveCharacterPosition(state, ttuProgress)
                localStore.writePosition(
                    ref = state.ref,
                    chapterIndex = chapterIndex,
                    progress = fraction,
                    characterCount = ttuProgress.exploredCharCount,
                    lastModified = ttuProgress.lastBookmarkModified,
                )
                importedCharacterCount = ttuProgress.exploredCharCount
                imported = true
                Log.d(
                    TAG,
                    "importProgress saved: title='$displayTitle', remoteFile='${remoteFiles.progress.name}', " +
                        "chapter=$chapterIndex, progress=$fraction, chars=$importedCharacterCount",
                )
            } catch (e: Exception) {
                Log.w(TAG, "importProgress failed: title='$displayTitle', file='${remoteFiles.progress.name}'", e)
            }
        } else {
            Log.d(TAG, "importProgress skipped: title='$displayTitle', no remote progress file")
        }

        if (settingsRepository.currentSettings().statisticsSyncEnabled && remoteFiles.statistics != null) {
            try {
                val content = driveClient.downloadFile(remoteFiles.statistics.id)
                val remoteStats = json.decodeFromString<List<Statistics>>(content)
                val merged = mergeStatistics(
                    local = state.statistics,
                    remote = remoteStats,
                    mode = settingsRepository.currentSettings().statisticsSyncMode,
                    title = displayTitle,
                )
                localStore.writeStatistics(
                    state.ref,
                    merged,
                    replace = settingsRepository.currentSettings().statisticsSyncMode == StatisticsSyncMode.Replace,
                )
            } catch (e: Exception) {
                Log.w(TAG, "importStatistics failed: title='$displayTitle'", e)
            }
        }

        return if (imported) SyncResult.Imported(displayTitle, importedCharacterCount) else SyncResult.Synced(displayTitle)
    }

    private suspend fun exportToTtu(
        state: TtuLocalState,
        remoteFiles: DriveSyncFiles,
        bookFolderId: String,
        displayTitle: String,
    ): SyncResult {
        var progressExported = false
        var progressCharacterCount = state.characterCount

        if (state.lastModified != null) {
            val charBasedProgress = if (state.totalCharacters > 0) {
                state.characterCount.toDouble() / state.totalCharacters
            } else {
                Log.w(TAG, "exportProgress using position fraction fallback: title='$displayTitle'")
                state.progress
            }.coerceIn(0.0, 1.0)

            // Fetch remote progress to preserve dataId.
            val remoteProgress = try {
                remoteFiles.progress?.let { driveClient.downloadFile(it.id) }
                    ?.let { json.decodeFromString<TtuProgress>(it) }
            } catch (_: Exception) {
                null
            }

            val ttuProgress = TtuProgress(
                dataId = remoteProgress?.dataId ?: 0,
                exploredCharCount = state.characterCount,
                progress = charBasedProgress,
                lastBookmarkModified = state.lastModified,
            )
            val content = json.encodeToString(TtuProgress.serializer(), ttuProgress)
            val fileName = TtuSyncRules.progressFileName(ttuProgress)

            if (remoteFiles.progress != null) {
                driveClient.updateFile(remoteFiles.progress.id, fileName, content)
            } else {
                driveClient.uploadFile(bookFolderId, fileName, content)
            }
            progressExported = true
            progressCharacterCount = state.characterCount
            Log.d(
                TAG,
                "exportProgress saved: title='$displayTitle', file='$fileName', chars=${state.characterCount}, progress=$charBasedProgress",
            )
        } else {
            Log.w(TAG, "exportProgress skipped: title='$displayTitle', never read locally")
        }

        if (settingsRepository.currentSettings().statisticsSyncEnabled && state.statistics.isNotEmpty()) {
            val merged = if (remoteFiles.statistics != null) {
                try {
                    val remoteContent = driveClient.downloadFile(remoteFiles.statistics.id)
                    val remoteStats = json.decodeFromString<List<Statistics>>(remoteContent)
                    mergeStatistics(
                        local = remoteStats,
                        remote = state.statistics,
                        mode = settingsRepository.currentSettings().statisticsSyncMode,
                        title = displayTitle,
                    )
                } catch (_: Exception) {
                    state.statistics
                }
            } else {
                state.statistics
            }
            val content = json.encodeToString(merged)
            val fileName = TtuSyncRules.statisticsFileName(merged)
            if (remoteFiles.statistics != null) {
                driveClient.updateFile(remoteFiles.statistics.id, fileName, content)
            } else {
                driveClient.uploadFile(bookFolderId, fileName, content)
            }
        }

        return if (progressExported) {
            SyncResult.Exported(displayTitle, progressCharacterCount)
        } else {
            SyncResult.Synced(displayTitle)
        }
    }

    /**
     * Maps a remote explored-character count onto (chapterIndex, fraction).
     * Exact for file books; proportional fallback for virtual books.
     */
    private fun resolveCharacterPosition(state: TtuLocalState, remote: TtuProgress): Pair<Int, Double> {
        val sizes = state.chapterSizes
        val total = state.totalCharacters
        if (sizes.isNullOrEmpty() || total <= 0) {
            val count = state.chapterCount.coerceAtLeast(1)
            val index = (remote.progress.coerceIn(0.0, 1.0) * count).toInt().coerceIn(0, count - 1)
            return index to remote.progress.coerceIn(0.0, 1.0)
        }
        val clamped = remote.exploredCharCount.coerceIn(0, (total - 1).toInt())
        var running = 0L
        sizes.forEachIndexed { i, size ->
            if (size <= 0) {
                if (clamped == running.toInt()) return i to 0.0
                return@forEachIndexed
            }
            if (clamped < running + size) {
                return i to ((clamped - running).toDouble() / size).coerceIn(0.0, 1.0)
            }
            running += size
        }
        return (sizes.size - 1) to 1.0
    }

    companion object {
        private const val TAG = "TtuSyncManager"

        /**
         * Monotonic max-merge over per-day counters (assignment-based, never
         * additive, so re-syncing is a no-op).
         */
        fun mergeStatistics(
            local: List<Statistics>,
            remote: List<Statistics>,
            mode: StatisticsSyncMode,
            title: String,
        ): List<Statistics> {
            if (mode == StatisticsSyncMode.Replace) return remote
            val grouped = linkedMapOf<String, Statistics>()
            for (stat in local) grouped[stat.dateKey] = stat
            for (stat in remote) {
                val existing = grouped[stat.dateKey]
                grouped[stat.dateKey] = if (existing == null) {
                    stat
                } else {
                    val newer = if (stat.charactersRead > existing.charactersRead ||
                        (stat.charactersRead == existing.charactersRead &&
                            stat.readingTime > existing.readingTime)
                    ) {
                        stat
                    } else {
                        existing
                    }
                    existing.copy(
                        charactersRead = maxOf(existing.charactersRead, stat.charactersRead),
                        readingTime = maxOf(existing.readingTime, stat.readingTime),
                        minReadingSpeed = minNonZero(existing.minReadingSpeed, stat.minReadingSpeed),
                        altMinReadingSpeed = minNonZero(existing.altMinReadingSpeed, stat.altMinReadingSpeed),
                        lastReadingSpeed = newer.lastReadingSpeed,
                        maxReadingSpeed = maxOf(existing.maxReadingSpeed, stat.maxReadingSpeed),
                        lastStatisticModified = maxOf(existing.lastStatisticModified, stat.lastStatisticModified),
                        completedBook = existing.completedBook ?: stat.completedBook,
                    )
                }
            }
            return grouped.values.map { if (it.title.isBlank()) it.copy(title = title) else it }
        }

        private fun minNonZero(first: Int, second: Int): Int = when {
            first <= 0 -> second
            second <= 0 -> first
            else -> minOf(first, second)
        }
    }
}
