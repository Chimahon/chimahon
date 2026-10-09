package eu.kanade.tachiyomi.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelBundleInstallerTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun replacementRemovesObsoleteFilesAndUsesNewContent() {
        val installed = File(directory, "screenai_models").apply { mkdirs() }
        File(installed, "model.bin").writeText("old")
        File(installed, "obsolete.bin").writeText("unused")
        ModelBundleInstaller.install(archive("screenai_models/model.bin" to "new"), directory, "screenai_models", listOf("model.bin"))
        assertEquals("new", File(installed, "model.bin").readText())
        assertFalse(File(installed, "obsolete.bin").exists())
        assertFalse(File(directory, ".screenai_models.previous").exists())
    }

    @Test
    fun incompleteReplacementPreservesInstalledBundle() {
        val installed = File(directory, "paddle_ocr").apply { mkdirs() }
        File(installed, "model.bin").writeText("working")
        assertThrows(IllegalStateException::class.java) {
            ModelBundleInstaller.install(archive("paddle_ocr/other.bin" to "incomplete"), directory, "paddle_ocr", listOf("model.bin"))
        }
        assertEquals("working", File(installed, "model.bin").readText())
        assertFalse(File(installed, "other.bin").exists())
    }

    @Test
    fun rejectsTraversalAndCrossBundleEntries() {
        for (entry in listOf("screenai_models/../../outside.bin", "paddle_ocr/model.bin")) {
            assertThrows(IllegalStateException::class.java) {
                ModelBundleInstaller.install(archive(entry to "bad"), directory, "screenai_models", listOf("model.bin"))
            }
        }
        assertFalse(File(directory.parentFile, "outside.bin").exists())
        assertFalse(File(directory, "paddle_ocr").exists())
    }

    @Test
    fun restoresBackupAfterInterruptedDirectorySwap() {
        val previous = File(directory, ".screenai_models.previous").apply { mkdirs() }
        File(previous, "model.bin").writeText("working")
        ModelBundleInstaller.recover(directory, "screenai_models")
        assertEquals("working", File(directory, "screenai_models/model.bin").readText())
        assertFalse(previous.exists())
    }

    @Test
    fun panelDirectoryReplacementRemovesOldRuntimeFiles() {
        val installed = File(directory, "panel_detector").apply { mkdirs() }
        File(installed, "obsolete.so").writeText("old runtime")
        val candidate = File(directory, "candidate").apply { mkdirs() }
        File(candidate, "model.tflite").writeText("updated")
        ModelBundleInstaller.replace(candidate, directory, "panel_detector", listOf("model.tflite"))
        assertTrue(File(installed, "model.tflite").exists())
        assertFalse(File(installed, "obsolete.so").exists())
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }
}
