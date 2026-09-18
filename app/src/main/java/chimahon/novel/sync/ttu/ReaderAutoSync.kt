package chimahon.novel.sync.ttu

internal enum class ReaderLifecycleAutoSyncEvent {
    Resume,
    Pause,
    Dispose,
}

internal data class ReaderLifecycleAutoSyncPlan(
    val flushAutoSyncExport: Boolean = false,
    val importOnForeground: Boolean = false,
)

internal fun readerLifecycleAutoSyncPlan(
    event: ReaderLifecycleAutoSyncEvent,
    inactiveElapsedMillis: Long? = null,
): ReaderLifecycleAutoSyncPlan = when (event) {
    ReaderLifecycleAutoSyncEvent.Resume -> ReaderLifecycleAutoSyncPlan(
        importOnForeground = inactiveElapsedMillis != null &&
            inactiveElapsedMillis >= AUTO_SYNC_FOREGROUND_THRESHOLD_MILLIS,
    )
    ReaderLifecycleAutoSyncEvent.Pause,
    ReaderLifecycleAutoSyncEvent.Dispose -> ReaderLifecycleAutoSyncPlan(
        flushAutoSyncExport = true,
    )
}

internal const val AUTO_SYNC_FOREGROUND_THRESHOLD_MILLIS = 10L * 60L * 1000L
