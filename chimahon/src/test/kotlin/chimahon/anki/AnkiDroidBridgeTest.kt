package chimahon.anki

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnkiDroidBridgeTest {

    @Test
    fun `opening Anki browser keeps the caller task as the Back destination`() {
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        var launchFlags = 0

        mockkStatic(Uri::class)
        every { Uri.encode("nid:42") } returns "nid%3A42"
        every { Uri.parse("anki://x-callback-url/browser?search=nid%3A42") } returns uri

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage("com.ichi2.anki") } answers { self as Intent }
        every { anyConstructed<Intent>().flags = any() } answers {
            launchFlags = firstArg()
        }
        every { context.startActivity(any()) } just runs

        AnkiDroidBridge(context).guiBrowse("nid:42")

        verify(exactly = 1) { context.startActivity(any()) }
        assertEquals(0, launchFlags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, launchFlags and Intent.FLAG_ACTIVITY_TASK_ON_HOME)
    }

    @Test
    fun `custom Anki app package is used for intents`() {
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        var targetPackage: String? = null

        mockkStatic(Uri::class)
        every { Uri.encode("nid:42") } returns "nid%3A42"
        every { Uri.parse("anki://x-callback-url/browser?search=nid%3A42") } returns uri

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers {
            targetPackage = firstArg()
            self as Intent
        }
        every { context.startActivity(any()) } just runs

        AnkiDroidBridge(context, "io.github.pedrubik2000.kuma3").guiBrowse("nid:42")

        assertEquals("io.github.pedrubik2000.kuma3", targetPackage)
    }
}
