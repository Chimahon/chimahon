package eu.kanade.tachiyomi.ui.browse.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import chimahon.novel.interactor.RegisterLocalNovelHome
import chimahon.novel.source.LocalNovelFiles
import chimahon.novel.data.BookImporter
import com.hippo.unifile.UniFile
import tachiyomi.source.local.LocalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object ImportHandler {

    suspend fun importMangaFiles(context: Context, uris: List<Uri>, folderName: String) = withContext(Dispatchers.IO) {
        val mangaRepository: MangaRepository = Injekt.get()
        val storageManager: StorageManager = Injekt.get()
        val libraryPreferences: LibraryPreferences = Injekt.get()
        
        val localSourceDir = storageManager.getLocalSourceDirectory() ?: return@withContext
        val safeFolderName = MangaImportUtil.getSafeFolderName(folderName)
        val mangaDir = localSourceDir.createDirectory(safeFolderName) ?: return@withContext
        
        uris.forEach { uri ->
            val fileName = getFileName(context, uri) ?: return@forEach
            val file = mangaDir.createFile(fileName) ?: return@forEach
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.openOutputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        addMangaToLibrary(safeFolderName, mangaRepository, libraryPreferences)
    }

    suspend fun importMangaFolder(context: Context, uri: Uri, folderName: String) = withContext(Dispatchers.IO) {
        val mangaRepository: MangaRepository = Injekt.get()
        val storageManager: StorageManager = Injekt.get()
        val libraryPreferences: LibraryPreferences = Injekt.get()
        
        val localSourceDir = storageManager.getLocalSourceDirectory() ?: return@withContext
        val safeFolderName = MangaImportUtil.getSafeFolderName(folderName)
        val mangaDir = localSourceDir.createDirectory(safeFolderName) ?: return@withContext
        
        val sourceDir = UniFile.fromUri(context, uri) ?: return@withContext
        val destChapterDir = mangaDir.createDirectory(sourceDir.name ?: "Imported Folder") ?: return@withContext
        
        sourceDir.listFiles()?.forEach { file ->
            if (!file.isDirectory) {
                val destFile = destChapterDir.createFile(file.name) ?: return@forEach
                file.openInputStream().use { input ->
                    destFile.openOutputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        
        addMangaToLibrary(safeFolderName, mangaRepository, libraryPreferences)
    }

    private suspend fun addMangaToLibrary(
        safeFolderName: String,
        mangaRepository: MangaRepository,
        libraryPreferences: LibraryPreferences
    ) {
        val existingManga = mangaRepository.getMangaByUrlAndSourceId(safeFolderName, LocalSource.ID)
        val manga = if (existingManga == null) {
            val newManga = Manga.create().copy(
                url = safeFolderName,
                ogTitle = safeFolderName,
                source = LocalSource.ID,
                favorite = true,
                dateAdded = System.currentTimeMillis(),
            )
            val added = mangaRepository.insertNetworkManga(listOf(newManga))
            if (added.isNotEmpty()) added.first() else newManga
        } else {
            if (!existingManga.favorite) {
                mangaRepository.update(MangaUpdate(id = existingManga.id, favorite = true))
            }
            existingManga
        }
        
        if (existingManga == null) {
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            if (defaultCategoryId != -1) {
                mangaRepository.setMangaCategories(manga.id, listOf(defaultCategoryId.toLong()))
            }
        }
    }

    suspend fun importNovels(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
        val novelCategoryRepository: tachiyomi.domain.novel.repository.NovelCategoryRepository = Injekt.get()

        uris.forEach { uri ->
            val result = BookImporter.importEpub(
                context,
                uri,
                targetRootUni = LocalNovelFiles.publicRootUni(context),
            )
            val bookMetadata = result.metadata ?: return@forEach

            // Default category (system 0L); merges with any existing
            // memberships on reimport.
            runCatching {
                val novelId = Injekt.get<RegisterLocalNovelHome>().register(bookMetadata.id)
                    ?: return@runCatching
                val current = novelCategoryRepository.getByNovelId(novelId).map { it.id }.toSet()
                if (0L !in current) {
                    Injekt.get<chimahon.novel.interactor.SetNovelCategories>()
                        .await(novelId, (current + 0L).toList())
                }
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    }
}
