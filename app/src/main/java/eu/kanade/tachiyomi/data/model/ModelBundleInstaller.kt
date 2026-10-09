package eu.kanade.tachiyomi.data.model

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Validate a complete bundle before swapping directories, removing obsolete files on success. */
internal object ModelBundleInstaller {
    @Synchronized
    fun recover(filesDir: File, bundle: String) {
        val target = File(filesDir, bundle)
        val previous = File(filesDir, ".$bundle.previous")
        if (previous.exists() && !target.exists()) {
            check(previous.renameTo(target)) { "Could not restore previous model bundle" }
        } else if (previous.exists()) {
            check(previous.deleteRecursively()) { "Could not remove obsolete model files" }
        }
    }

    @Synchronized
    fun install(input: InputStream, filesDir: File, bundle: String, required: List<String>) {
        require(bundle == "screenai_models" || bundle == "paddle_ocr")
        filesDir.mkdirs()
        recover(filesDir, bundle)
        val staging = File.createTempFile(".$bundle-", ".staging", filesDir)
        check(staging.delete() && staging.mkdir())
        val candidate = File(staging, bundle).canonicalFile
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    check(path == "$bundle/" || path.startsWith("$bundle/")) { "Unexpected model archive entry: $path" }
                    val file = File(staging, path).canonicalFile
                    check(file == candidate || file.path.startsWith(candidate.path + File.separator)) {
                        "Unsafe model archive entry: $path"
                    }
                    if (entry.isDirectory) {
                        check(file.isDirectory || file.mkdirs())
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            replace(candidate, filesDir, bundle, required)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun replace(candidate: File, filesDir: File, bundle: String, required: List<String>) {
        require(bundle in setOf("screenai_models", "paddle_ocr", "panel_detector"))
        check(required.all { File(candidate, it).let { file -> file.isFile && file.length() > 0L } }) {
            "The model archive is incomplete"
        }
        recover(filesDir, bundle)
        val target = File(filesDir, bundle)
        val previous = File(filesDir, ".$bundle.previous")
        check(!previous.exists() || previous.deleteRecursively()) { "Could not remove previous model backup" }
        if (target.exists()) check(target.renameTo(previous)) { "Could not back up installed models" }
        if (!candidate.renameTo(target)) {
            if (previous.exists()) check(previous.renameTo(target)) { "Could not restore installed models" }
            error("Could not install model bundle")
        }
        previous.deleteRecursively()
    }
}
