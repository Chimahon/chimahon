package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OcrStore(
    context: Context,
    private val json: Json = Injekt.get(),
) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<OcrTask> {
        return preferences.all
            .filterKeys { it != COUNTER_KEY && it != PAUSED_KEY }
            .mapNotNull { (_, value) -> (value as? String)?.let(::deserialize) }
            .sortedBy { it.order }
    }

    fun get(chapterId: Long): OcrTask? {
        return preferences.getString(chapterId.toString(), null)
            ?.let(::deserialize)
    }

    fun hasRunnableTasks(): Boolean {
        return getAll().any { it.status.isRunnable() }
    }

    @Synchronized
    fun enqueue(requests: List<OcrEnqueueRequest>): Boolean {
        if (requests.isEmpty()) return false

        val existing = getAll().associateBy { it.chapterId }.toMutableMap()
        var counter = preferences.getInt(COUNTER_KEY, 0)
        var changed = false

        requests.forEach { request ->
            val current = existing[request.chapterId]
            when {
                current == null -> {
                    existing[request.chapterId] = OcrTask(
                        mangaId = request.mangaId,
                        chapterId = request.chapterId,
                        order = counter++,
                        waitForDownload = request.waitForDownload,
                        status = if (request.waitForDownload) OcrQueueStatus.WAITING_DOWNLOAD else OcrQueueStatus.PENDING,
                    )
                    changed = true
                }
                current.status == OcrQueueStatus.ERROR -> {
                    existing[request.chapterId] = current.copy(
                        waitForDownload = request.waitForDownload,
                        status = if (request.waitForDownload) OcrQueueStatus.WAITING_DOWNLOAD else OcrQueueStatus.PENDING,
                        currentPage = 0,
                        totalPages = 0,
                    )
                    changed = true
                }
                request.waitForDownload && !current.waitForDownload -> {
                    existing[request.chapterId] = current.copy(waitForDownload = true)
                    changed = true
                }
            }
        }

        if (!changed) return false

        preferences.edit(commit = true) {
            putInt(COUNTER_KEY, counter)
            existing.values.forEach { putString(it.chapterId.toString(), serialize(it)) }
        }
        return true
    }

    @Synchronized
    fun save(task: OcrTask) {
        preferences.edit(commit = true) {
            putString(task.chapterId.toString(), serialize(task))
        }
    }

    @Synchronized
    fun update(chapterId: Long, transform: (OcrTask) -> OcrTask): OcrTask? {
        val current = get(chapterId) ?: return null
        val updated = transform(current)
        save(updated)
        return updated
    }

    @Synchronized
    fun remove(chapterId: Long) {
        preferences.edit(commit = true) {
            remove(chapterId.toString())
        }
    }

    @Synchronized
    fun removeAll(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        preferences.edit(commit = true) {
            chapterIds.forEach { remove(it.toString()) }
        }
    }

    fun isPaused(): Boolean = preferences.getBoolean(PAUSED_KEY, false)

    @Synchronized
    fun setPaused(paused: Boolean) {
        preferences.edit(commit = true) { putBoolean(PAUSED_KEY, paused) }
    }

    @Synchronized
    fun normalizeForRestore(): Boolean {
        val tasks = getAll()
        if (tasks.isEmpty()) return false

        var changed = false
        val normalized = tasks.mapNotNull { task ->
            when (task.status) {
                OcrQueueStatus.PROCESSING -> {
                    changed = true
                    task.copy(status = OcrQueueStatus.PENDING)
                }
                OcrQueueStatus.COMPLETED,
                OcrQueueStatus.CANCELLED,
                -> {
                    changed = true
                    null
                }
                else -> task
            }
        }

        if (!changed) return false

        val counter = preferences.getInt(COUNTER_KEY, 0)
        val paused = isPaused()
        preferences.edit(commit = true) {
            clear()
            putInt(COUNTER_KEY, counter)
            putBoolean(PAUSED_KEY, paused)
            normalized.forEach { putString(it.chapterId.toString(), serialize(it)) }
        }
        return true
    }

    private fun serialize(task: OcrTask): String {
        return json.encodeToString(task)
    }

    private fun deserialize(value: String): OcrTask? {
        return try {
            json.decodeFromString<OcrTask>(value)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "active_ocr"
        private const val COUNTER_KEY = "__counter"
        private const val PAUSED_KEY = "__paused"
    }
}

data class OcrEnqueueRequest(
    val mangaId: Long,
    val chapterId: Long,
    val waitForDownload: Boolean,
)

@Serializable
data class OcrTask(
    val mangaId: Long,
    val chapterId: Long,
    val order: Int,
    val waitForDownload: Boolean,
    val status: OcrQueueStatus = OcrQueueStatus.PENDING,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
)

fun OcrQueueStatus.isActionable(): Boolean {
    return this in listOf(
        OcrQueueStatus.PENDING,
        OcrQueueStatus.WAITING_DOWNLOAD,
        OcrQueueStatus.PROCESSING,
    )
}

fun OcrQueueStatus.isRunnable(): Boolean {
    return this in listOf(
        OcrQueueStatus.PENDING,
        OcrQueueStatus.PROCESSING,
    )
}
