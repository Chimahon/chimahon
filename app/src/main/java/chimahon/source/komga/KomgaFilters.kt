package chimahon.source.komga

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UriFilter {
    fun addToUri(builder: HttpUrl.Builder)
}

class TypeSelect :
    Filter.Select<String>(
        "Search for",
        arrayOf("Series", "Read lists", "Books"),
    )

class SeriesSort(selection: Filter.Sort.Selection? = null) :
    Filter.Sort(
        "Sort",
        arrayOf("Relevance", "Alphabetically", "Date added", "Date updated", "Random"),
        selection ?: Filter.Sort.Selection(0, true),
    )

class UnreadFilter :
    Filter.CheckBox("Unread", false),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        if (!state) return
        builder.addQueryParameter("read_status", "UNREAD")
        builder.addQueryParameter("read_status", "IN_PROGRESS")
    }
}

class InProgressFilter :
    Filter.CheckBox("In Progress", false),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        if (!state) return
        builder.addQueryParameter("read_status", "IN_PROGRESS")
    }
}

class ReadFilter :
    Filter.CheckBox("Read", false),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        if (!state) return
        builder.addQueryParameter("read_status", "READ")
    }
}

class LibraryFilter(
    libraries: List<LibraryDto>,
    defaultLibraries: Set<String>,
) : UriMultiSelectFilter(
    "Libraries",
    "library_id",
    libraries.map {
        UriMultiSelectOption(it.name, it.id).apply {
            state = defaultLibraries.contains(it.id)
        }
    },
)

class UriMultiSelectOption(name: String, val id: String = name) : Filter.CheckBox(name, false)

open class UriMultiSelectFilter(
    name: String,
    private val param: String,
    genres: List<UriMultiSelectOption>,
) : Filter.Group<UriMultiSelectOption>(name, genres),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val whatToInclude = state.filter { it.state }.map { it.id }
        if (whatToInclude.isNotEmpty()) {
            builder.addQueryParameter(param, whatToInclude.joinToString(","))
        }
    }
}

class AuthorFilter(val author: AuthorDto) : Filter.CheckBox(author.name, false)

class AuthorGroup(
    role: String,
    authors: List<AuthorFilter>,
) : Filter.Group<AuthorFilter>(role.replaceFirstChar { it.titlecase() }, authors),
    UriFilter {
    override fun addToUri(builder: HttpUrl.Builder) {
        val authorsToInclude = state.filter { it.state }.map { it.author }
        authorsToInclude.forEach {
            builder.addQueryParameter("author", "${it.name},${it.role}")
        }
    }
}

class CollectionSelect(
    val collections: List<CollectionFilterEntry>,
) : Filter.Select<String>("Collection", collections.map { it.name }.toTypedArray())

data class CollectionFilterEntry(val name: String, val id: String? = null)
