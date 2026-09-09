package chimahon.novel.data.epub

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

class EpubExtractor(private val epubFile: File) : EpubExtractorBase() {
    private var zipFile: ZipFile? = null

    init {
        try {
            zipFile = ZipFile(epubFile)
        } catch (e: Exception) {
            throw EpubParseException("Failed to open EPUB file: ${e.message}")
        }
    }

    // getPackageOpfPath and parseContainerXml are provided by EpubExtractorBase

    override fun getFileContent(path: String): String? {
        return try {
            val entry = zipFile?.getEntry(path) ?: return null
            zipFile?.getInputStream(entry)?.bufferedReader()?.readText()
        } catch (e: Exception) {
            null
        }
    }

    override fun getFileStream(path: String): InputStream? {
        return try {
            val entry = zipFile?.getEntry(path) ?: return null
            zipFile?.getInputStream(entry)
        } catch (e: Exception) {
            null
        }
    }

    override fun getContentDirectory(packageOpfPath: String): String {
        val lastSlash = packageOpfPath.lastIndexOf('/')
        return if (lastSlash > 0) {
            packageOpfPath.substring(0, lastSlash + 1)
        } else {
            ""
        }
    }

    fun extractTo(directory: File): File {
        val extractedDir = File(directory, epubFile.nameWithoutExtension)
        if (extractedDir.exists()) {
            return extractedDir
        }

        extractedDir.mkdirs()

        zipFile?.entries()?.asSequence()?.forEach { entry ->
            val entryName = entry.name
            // Zip-slip guard (mirrors the importer): entries stay inside the target.
            val targetBase = extractedDir.canonicalFile
            if (entry.isDirectory) {
                val dir = File(targetBase, entryName).canonicalFile
                if (!dir.path.startsWith(targetBase.path + File.separator)) return@forEach
                dir.mkdirs()
            } else {
                val file = File(targetBase, entryName).canonicalFile
                if (!file.path.startsWith(targetBase.path + File.separator)) return@forEach
                file.parentFile?.mkdirs()
                zipFile?.getInputStream(entry)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        return extractedDir
    }

    override fun close() {
        try {
            zipFile?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    companion object {
        fun fromFile(epubFile: File): EpubExtractor {
            return EpubExtractor(epubFile)
        }

        fun fromDirectory(directory: File): EpubExtractorBase {
            return DirectoryExtractor(directory)
        }
    }
}

class DirectoryExtractor(private val directory: File) : EpubExtractorBase() {

    override fun getFileContent(path: String): String? {
        return try {
            val file = File(directory, path)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    override fun getFileStream(path: String): InputStream? {
        return try {
            val file = File(directory, path)
            if (file.exists()) file.inputStream() else null
        } catch (e: Exception) {
            null
        }
    }

    override fun getContentDirectory(packageOpfPath: String): String {
        val lastSlash = packageOpfPath.lastIndexOf('/')
        return if (lastSlash > 0) {
            packageOpfPath.substring(0, lastSlash + 1)
        } else {
            ""
        }
    }

    override fun close() {
        // No-op for directory-based extractor
    }
}

abstract class EpubExtractorBase {
    abstract fun getFileContent(path: String): String?
    /** Returns an open stream the caller MUST close. The extractor itself is closed by its owner. */
    abstract fun getFileStream(path: String): InputStream?
    abstract fun getContentDirectory(packageOpfPath: String): String
    abstract fun close()

    fun getPackageOpfPath(): String {
        val containerXml = getFileContent("META-INF/container.xml")
            ?: throw EpubParseException("Missing META-INF/container.xml")

        val opfPath = parseContainerXml(containerXml)
            ?: throw EpubParseException("Could not find package.opf in container.xml")

        return opfPath
    }

    private fun parseContainerXml(xml: String): String? {
        return try {
            val doc = org.jsoup.parser.Parser.xmlParser().parseInput(xml, "")
            val rootfile = doc.select("rootfile").first() ?: return null
            rootfile.attr("full-path")
        } catch (e: Exception) {
            null
        }
    }
}

class EpubParseException(message: String) : Exception(message)
