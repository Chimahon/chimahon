package chimahon.novel.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class NovelCategoryStorage(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val categoriesFile: File
        get() = File(context.filesDir, "novel_categories.json")

    fun loadAllCategories(): List<NovelCategory> {
        val categories = if (!categoriesFile.exists()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<NovelCategory>>(categoriesFile.readText())
            } catch (e: Exception) {
                emptyList()
            }
        }

        val categoriesWithDefault = if (categories.none { it.id == NovelCategory.UNCATEGORIZED_ID }) {
            listOf(createDefaultCategory()) + categories
        } else {
            categories
        }

        return categoriesWithDefault.sortedWith(
            compareBy<NovelCategory> { if (it.isSystemCategory) Int.MIN_VALUE else it.order }
                .thenBy { it.name },
        )
    }

    private fun createDefaultCategory() = NovelCategory(id = NovelCategory.UNCATEGORIZED_ID, name = "Default", order = -1)

    fun saveCategories(categories: List<NovelCategory>) {
        categoriesFile.writeText(json.encodeToString(categories))
    }

    fun createCategory(name: String): NovelCategory {
        val categories = loadAllCategories().toMutableList()
        val nextOrder = categories
            .filterNot { it.isSystemCategory }
            .maxOfOrNull { it.order }
            ?.plus(1)
            ?: 0
        val newCategory = NovelCategory(name = name, order = nextOrder)
        categories.add(newCategory)
        saveCategories(categories)
        return newCategory
    }

    fun deleteCategory(categoryId: String) {
        val categories = loadAllCategories().toMutableList()
        categories.removeAll { it.id == categoryId }
        saveCategories(categories)
    }

    fun updateCategory(category: NovelCategory) {
        val categories = loadAllCategories().toMutableList()
        val index = categories.indexOfFirst { it.id == category.id }
        if (index != -1) {
            categories[index] = category
            saveCategories(categories)
        }
    }

    fun setCategoryHidden(categoryId: String, hidden: Boolean) {
        val categories = loadAllCategories().toMutableList()
        val index = categories.indexOfFirst { it.id == categoryId }
        if (index != -1) {
            categories[index] = categories[index].copy(hidden = hidden)
            saveCategories(categories)
        }
    }
}
