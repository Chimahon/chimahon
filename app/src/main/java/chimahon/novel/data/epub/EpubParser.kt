package chimahon.novel.data.epub

import java.io.File

private val nativeImageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp")

private fun isNativeImageHref(href: String?): Boolean {
    return href != null && nativeImageExtensions.any { href.endsWith(it, ignoreCase = true) }
}

class EpubParser {

    fun parse(epubFile: File): EpubBook {
        val isDirectory = epubFile.isDirectory
        val extractor = if (isDirectory) {
            EpubExtractor.fromDirectory(epubFile)
        } else {
            EpubExtractor.fromFile(epubFile)
        }

        try {
            val packageOpfPath = extractor.getPackageOpfPath()
            val contentDir = extractor.getContentDirectory(packageOpfPath)

            val opfContent = extractor.getFileContent(packageOpfPath)
                ?: throw EpubParseException("Failed to read package.opf")

            val opfParser = OpfParser()
            val opfResult = opfParser.parseOpf(opfContent, contentDir)

            val tocParser = TocParser()
            val tableOfContents = tocParser.parseToc(opfResult.spine.toc, opfResult.manifest, extractor, contentDir)

            val coverPath = opfResult.metadata.coverId?.let { coverId ->
                opfResult.manifest.items[coverId]?.href?.let { href ->
                    if (contentDir.isNotEmpty()) "$contentDir$href" else href
                }
            }

            val spineWithTypes = opfResult.spine.items.map { spineItem ->
                val manifestItem = opfResult.manifest.items[spineItem.idref]
                val href = manifestItem?.href
                val isNativeImage = isNativeImageHref(href)
                val isImageOnly = isNativeImage

                val chapterHref = if (href != null) "$contentDir$href" else null
                val imageUrl = if (isImageOnly && chapterHref != null) {
                    if (isDirectory) {
                        "file://${File(epubFile, chapterHref).absolutePath}"
                    } else {
                        val stream = extractor.getFileStream(chapterHref)
                        if (stream != null) {
                            val cacheDir = File(System.getProperty("java.io.tmpdir"), "epub_img_cache")
                            cacheDir.mkdirs()
                            val cached = File(cacheDir, chapterHref.substringAfterLast("/"))
                            cached.parentFile?.mkdirs()
                            stream.use { it.copyTo(cached.outputStream()) }
                            "file://${cached.absolutePath}"
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
                spineItem.copy(
                    type = if (isImageOnly) SpineItemType.IMAGE_ONLY else SpineItemType.TEXT,
                    imageUrl = imageUrl,
                )
            }

            return EpubBook(
                title = opfResult.metadata.title,
                author = opfResult.metadata.creator?.name,
                language = opfResult.metadata.language,
                coverPath = coverPath,
                metadata = opfResult.metadata,
                manifest = opfResult.manifest,
                spine = opfResult.spine.copy(items = spineWithTypes),
                tableOfContents = tableOfContents,
                contentDirectory = contentDir,
                zipPath = if (!isDirectory) epubFile.absolutePath else "",
                extractedDir = if (isDirectory) epubFile else null,
            )
        } finally {
            extractor.close()
        }
    }

    fun parseChapter(epubBook: EpubBook, chapterIndex: Int): String? {
        val href = epubBook.getChapterHref(chapterIndex) ?: return null

        val extractor = if (epubBook.extractedDir != null) {
            EpubExtractor.fromDirectory(epubBook.extractedDir)
        } else {
            EpubExtractor.fromFile(File(epubBook.zipPath))
        }

        return try {
            extractor.getFileContent(href)
        } finally {
            extractor.close()
        }
    }

    companion object {
        fun fromFile(epubFile: File): EpubBook {
            return EpubParser().parse(epubFile)
        }

        fun parse(file: File): EpubBook {
            return EpubParser().parse(file)
        }
    }
}
