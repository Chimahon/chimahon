package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.PdfReader

internal class PdfPageLoader(private val reader: PdfReader) : PageLoader() {
    override var isLocal = true

    override suspend fun getPages(): List<ReaderPage> {
        check(!isRecycled)
        return List(reader.pageCount) { index ->
            ReaderPage(index).apply {
                stream = { reader.getInputStream(index) }
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
