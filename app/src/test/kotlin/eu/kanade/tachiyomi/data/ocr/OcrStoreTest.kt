package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OcrStoreTest {

    private val values = mutableMapOf<String, Any>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val context = mockk<Context>()

    init {
        every { context.getSharedPreferences(any(), any()) } returns preferences
        every { preferences.all } answers { values.toMap() }
        every { preferences.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
        every { preferences.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
        every { preferences.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String>(); editor }
        every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); editor }
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        every { editor.clear() } answers { values.clear(); editor }
        every { editor.commit() } returns true
    }

    @Test
    fun `restoring an interrupted queue retains the user's pause`() {
        val store = OcrStore(context, Json)
        store.save(OcrTask(1, 2, 0, false, OcrQueueStatus.PROCESSING, 3, 10))
        store.setPaused(true)

        val restored = OcrStore(context, Json)
        assertTrue(restored.normalizeForRestore())
        assertTrue(restored.isPaused())
        assertEquals(OcrQueueStatus.PENDING, restored.get(2)?.status)
        assertEquals(3, restored.get(2)?.currentPage)
        assertEquals(1, restored.getAll().size)
    }

    @Test
    fun `cancelling during a progress update cannot resurrect a chapter`() {
        val store = OcrStore(context, Json)
        store.save(OcrTask(1, 2, 0, false))
        val updateStarted = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val removeStarted = CountDownLatch(1)
        val removeFinished = CountDownLatch(1)
        val update = thread {
            store.update(2) {
                updateStarted.countDown()
                releaseUpdate.await(5, TimeUnit.SECONDS)
                it.copy(currentPage = 1)
            }
        }
        assertTrue(updateStarted.await(5, TimeUnit.SECONDS))
        val remove = thread {
            removeStarted.countDown()
            store.remove(2)
            removeFinished.countDown()
        }
        try {
            assertTrue(removeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(removeFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseUpdate.countDown()
            update.join(5000)
            remove.join(5000)
        }
        assertTrue(removeFinished.await(5, TimeUnit.SECONDS))
        assertNull(store.get(2))
    }
}
