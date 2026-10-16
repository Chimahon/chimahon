package eu.kanade.tachiyomi.ui.manga

internal fun List<ChapterList.Item>.pendingDownloadedOcrChapters(): List<ChapterList.Item> =
    filter { it.isDownloaded && !it.isOcrReady && !it.isOcrRunning }.distinctBy { it.id }
