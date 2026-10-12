package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.util.system.cancelNotification
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import chimahon.ocr.OcrCacheManager
import chimahon.ocr.LensClient
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.core.common.preference.Preference

@OptIn(ExperimentalCoroutinesApi::class)
class OcrManagerTest {
    @Test
    fun testFailedTaskIsNotAutomaticallyRequeued() = runTest {
        val store = mockk<OcrStore>(relaxed = true)
        val mangas = mockk<MangaRepository>(relaxed = true)
        val chapters = mockk<ChapterRepository>(relaxed = true)
        val sources = mockk<SourceManager>(relaxed = true)
        val task = OcrTask(1, 101, 0, false)
        val tasks = mutableMapOf(101L to task)
        every { store.getAll() } answers { tasks.values.toList() }
        every { store.get(any()) } answers { tasks[firstArg<Long>()] }
        every { store.update(any(), any()) } answers {
            val id = firstArg<Long>()
            val transform = secondArg<(OcrTask) -> OcrTask>()
            tasks[id]?.let(transform)?.also { tasks[id] = it }
        }
        every { parallelOcrLimitPref.get() } returns 1
        coEvery { mangas.getMangaById(any()) } returns mockk<Manga>(relaxed = true)
        coEvery { chapters.getChapterById(any()) } returns mockk<Chapter>(relaxed = true)
        val manager = spyk(
            OcrManager(
                mockk(relaxed = true),
                null,
                null,
                mangas,
                chapters,
                sources,
                store,
                StandardTestDispatcher(testScheduler),
            ),
        )
        coEvery { manager.processTask(any(), any(), any(), any()) } returns OcrTaskResult.ERROR

        manager.runPendingQueue { false }

        assertEquals(OcrQueueStatus.ERROR, tasks[101L]?.status)
        coVerify(exactly = 1) { manager.processTask(any(), any(), any(), any()) }
    }

    companion object {
        private val dictionaryPreferences = mockk<DictionaryPreferences>(relaxed = true)
        private val parallelOcrLimitPref = mockk<Preference<Int>>(relaxed = true)
        private val changesFlow = MutableSharedFlow<Int>()

        @JvmStatic
        @BeforeAll
        fun setup() {
            mockkStatic("eu.kanade.tachiyomi.util.system.NotificationExtensionsKt")
            every { any<Context>().cancelNotification(any()) } just Runs
            if (!logcat.LogcatLogger.isInstalled) {
                logcat.LogcatLogger.install()
                logcat.LogcatLogger.loggers += object : logcat.LogcatLogger {
                    override fun log(priority: logcat.LogPriority, tag: String, message: String) {
                        println("[$priority] $tag: $message")
                    }
                }
            }
            every { dictionaryPreferences.parallelOcrLimit() } returns parallelOcrLimitPref
            every { parallelOcrLimitPref.changes() } returns changesFlow
            
            Injekt.addSingletonFactory<DictionaryPreferences> { dictionaryPreferences }
            Injekt.addSingletonFactory<OcrCacheManager> { mockk(relaxed = true) }
            Injekt.addSingletonFactory<LensClient> { mockk(relaxed = true) }
            Injekt.addSingletonFactory<DownloadManager> { mockk(relaxed = true) }
            Injekt.addSingletonFactory<DownloadProvider> { mockk(relaxed = true) }
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            unmockkStatic("eu.kanade.tachiyomi.util.system.NotificationExtensionsKt")
        }
    }

    @Test
    fun testConcurrencyLimitRespected() = runTest {
        val ocrStore = mockk<OcrStore>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)

        every { parallelOcrLimitPref.get() } returns 2

        val task1 = OcrTask(1L, 101L, 1, false, OcrQueueStatus.PENDING)
        val task2 = OcrTask(1L, 102L, 2, false, OcrQueueStatus.PENDING)
        val task3 = OcrTask(1L, 103L, 3, false, OcrQueueStatus.PENDING)
        val storeMap = mutableMapOf(101L to task1, 102L to task2, 103L to task3)
        every { ocrStore.getAll() } answers { storeMap.values.toList() }
        every { ocrStore.get(any()) } answers { storeMap[firstArg()] }

        val mockManga = mockk<Manga>(relaxed = true)
        val mockChapter = mockk<Chapter>(relaxed = true)
        coEvery { mangaRepository.getMangaById(any()) } returns mockManga
        coEvery { chapterRepository.getChapterById(any()) } returns mockChapter

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = spyk(OcrManager(mockk(relaxed = true), null, null, mangaRepository, chapterRepository, sourceManager, ocrStore, testDispatcher))
        
        var maxActiveJobs = 0
        var activeJobs = 0
        coEvery { manager.processTask(any(), any(), any(), any()) } coAnswers {
            activeJobs++
            if (activeJobs > maxActiveJobs) {
                maxActiveJobs = activeJobs
            }
            delay(1000)
            activeJobs--
            OcrTaskResult.SUCCESS
        }

        val queueJob = launch {
            manager.runPendingQueue { false }
        }

        runCurrent()
        advanceTimeBy(500)
        assertEquals(2, maxActiveJobs)
        queueJob.cancel()
    }

    @Test
    fun testDynamicLimitAdjustment() = runTest {
        val ocrStore = mockk<OcrStore>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)

        every { parallelOcrLimitPref.get() } returns 1

        val task1 = OcrTask(1L, 101L, 1, false, OcrQueueStatus.PENDING)
        val task2 = OcrTask(1L, 102L, 2, false, OcrQueueStatus.PENDING)
        val task3 = OcrTask(1L, 103L, 3, false, OcrQueueStatus.PENDING)
        val storeMap = mutableMapOf(101L to task1, 102L to task2, 103L to task3)
        every { ocrStore.getAll() } answers { storeMap.values.toList() }
        every { ocrStore.get(any()) } answers { storeMap[firstArg()] }

        val mockManga = mockk<Manga>(relaxed = true)
        val mockChapter = mockk<Chapter>(relaxed = true)
        coEvery { mangaRepository.getMangaById(any()) } returns mockManga
        coEvery { chapterRepository.getChapterById(any()) } returns mockChapter

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = spyk(OcrManager(mockk(relaxed = true), null, null, mangaRepository, chapterRepository, sourceManager, ocrStore, testDispatcher))
        
        var activeJobs = 0
        coEvery { manager.processTask(any(), any(), any(), any()) } coAnswers {
            activeJobs++
            delay(5000)
            activeJobs--
            OcrTaskResult.SUCCESS
        }

        val queueJob = launch {
            manager.runPendingQueue { false }
        }

        runCurrent()
        advanceTimeBy(500)
        assertEquals(1, activeJobs)

        every { parallelOcrLimitPref.get() } returns 3
        changesFlow.emit(3)

        runCurrent()
        advanceTimeBy(500)
        assertEquals(3, activeJobs)
        queueJob.cancel()
    }

    @Test
    fun testOcrJobCancellation() = runTest {
        val ocrStore = mockk<OcrStore>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)

        every { parallelOcrLimitPref.get() } returns 1

        val task1 = OcrTask(1L, 101L, 1, false, OcrQueueStatus.PENDING)
        val storeMap = mutableMapOf(101L to task1)
        every { ocrStore.getAll() } answers { storeMap.values.toList() }
        every { ocrStore.get(101L) } answers { storeMap[101L] }
        every { ocrStore.remove(101L) } answers { storeMap.remove(101L) }
        every { ocrStore.update(any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val transform = secondArg<(OcrTask) -> OcrTask>()
            val current = storeMap[chapterId]
            if (current != null) {
                val updated = transform(current)
                storeMap[chapterId] = updated
                updated
            } else {
                null
            }
        }

        val mockManga = mockk<Manga>(relaxed = true)
        val mockChapter = mockk<Chapter>(relaxed = true)
        coEvery { mangaRepository.getMangaById(any()) } returns mockManga
        coEvery { chapterRepository.getChapterById(any()) } returns mockChapter

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = spyk(OcrManager(mockk(relaxed = true), null, null, mangaRepository, chapterRepository, sourceManager, ocrStore, testDispatcher))
        
        var isCancelled = false
        coEvery { manager.processTask(any(), any(), any(), any()) } coAnswers {
            try {
                delay(10000)
                OcrTaskResult.SUCCESS
            } catch (e: kotlinx.coroutines.CancellationException) {
                isCancelled = true
                throw e
            }
        }

        val queueJob = launch {
            manager.runPendingQueue { false }
        }

        runCurrent()
        advanceTimeBy(500)
        manager.cancelChapter(101L)

        runCurrent()
        advanceTimeBy(500)
        assertTrue(isCancelled)
        
        // Verify no resurrection: Task is permanently removed from the map
        assertNull(storeMap[101L])
        
        queueJob.cancel()
    }

    @Test
    fun testOcrJobSuccessCleanup() = runTest {
        val ocrStore = mockk<OcrStore>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)

        every { parallelOcrLimitPref.get() } returns 1

        val task1 = OcrTask(1L, 101L, 1, false, OcrQueueStatus.PENDING)
        val storeMap = mutableMapOf(101L to task1)
        every { ocrStore.getAll() } answers { storeMap.values.toList() }
        every { ocrStore.get(101L) } answers { storeMap[101L] }
        every { ocrStore.remove(101L) } answers { storeMap.remove(101L) }

        val mockManga = mockk<Manga>(relaxed = true)
        val mockChapter = mockk<Chapter>(relaxed = true)
        coEvery { mangaRepository.getMangaById(any()) } returns mockManga
        coEvery { chapterRepository.getChapterById(any()) } returns mockChapter

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = spyk(OcrManager(mockk(relaxed = true), null, null, mangaRepository, chapterRepository, sourceManager, ocrStore, testDispatcher))
        coEvery { manager.processTask(any(), any(), any(), any()) } returns OcrTaskResult.SUCCESS

        val queueJob = launch {
            manager.runPendingQueue { false }
        }

        runCurrent()
        advanceTimeBy(500)
        assertTrue(storeMap.isEmpty())
        queueJob.cancel()
    }

    @Test
    fun testOcrJobFailureState() = runTest {
        val ocrStore = mockk<OcrStore>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>(relaxed = true)
        val chapterRepository = mockk<ChapterRepository>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)

        every { parallelOcrLimitPref.get() } returns 1

        val task1 = OcrTask(1L, 101L, 1, false, OcrQueueStatus.PENDING)
        val storeMap = mutableMapOf(101L to task1)
        every { ocrStore.getAll() } answers { storeMap.values.toList() }
        every { ocrStore.get(101L) } answers { storeMap[101L] }
        every { ocrStore.remove(101L) } answers { storeMap.remove(101L) }
        every { ocrStore.update(any(), any()) } answers {
            val chapterId = firstArg<Long>()
            val transform = secondArg<(OcrTask) -> OcrTask>()
            val current = storeMap[chapterId]
            if (current != null) {
                val updated = transform(current)
                storeMap[chapterId] = updated
                updated
            } else {
                null
            }
        }

        val mockManga = mockk<Manga>(relaxed = true)
        val mockChapter = mockk<Chapter>(relaxed = true)
        coEvery { mangaRepository.getMangaById(any()) } returns mockManga
        coEvery { chapterRepository.getChapterById(any()) } returns mockChapter

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val manager = spyk(OcrManager(mockk(relaxed = true), null, null, mangaRepository, chapterRepository, sourceManager, ocrStore, testDispatcher))
        coEvery { manager.processTask(any(), any(), any(), any()) } throws RuntimeException("Severe OCR failure")

        val queueJob = launch {
            manager.runPendingQueue { false }
        }

        runCurrent()
        advanceTimeBy(500)
        assertEquals(OcrQueueStatus.ERROR, storeMap[101L]?.status)
        queueJob.cancel()
    }
}
