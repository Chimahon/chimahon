package eu.kanade.tachiyomi.glance

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import chimahon.novel.data.BookStorage
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.ui.detail.SourceChapterBookBuilder
import chimahon.novel.ui.reader.NovelReaderActivity
import eu.kanade.tachiyomi.R
import tachiyomi.core.common.Constants
import tachiyomi.domain.novel.model.toSNNovel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DecimalFormat

class NovelProgressWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lockedMessage = lockedWidgetMessage(context)
        if (isAppLocked()) {
            provideContent { WidgetLockedState(lockedMessage) }
            return
        }

        val lastNovel = BookStorage.loadAllBooks(context).maxByOrNull { it.lastAccess }
        // Latest history row carries the real chapter name + progress; the
        // sidecar is only a legacy fallback.
        data class WidgetResume(
            val title: String?,
            val chapterLabel: String,
            val progress: Double,
            val bookDir: java.io.File?,
            val novelId: Long?,
            val chapterIndex: Int?,
        )
        val resume: WidgetResume? = runCatching {
            val historyRepo = Injekt.get<tachiyomi.domain.novel.repository.NovelHistoryRepository>()
            val chapterRepo = Injekt.get<tachiyomi.domain.novel.repository.NovelChapterRepository>()
            val novelRepo = Injekt.get<tachiyomi.domain.novel.repository.NovelRepository>()
            val latest = historyRepo.getHistoryWithRelations("").firstOrNull()
            if (latest != null) {
                val novel = runCatching { novelRepo.getNovelById(latest.novelId) }.getOrNull()
                val dir = novel?.let {
                    if (it.isLocal || it.source == tachiyomi.domain.novel.model.Novel.LOCAL_SOURCE_ID) {
                        it.localFolder?.takeIf { f -> f.isNotBlank() }?.let { folder ->
                            // Content gate (not mere existence): epub-only
                            // public dirs parse nothing until extracted.
                            BookStorage.getBookDirectory(context, folder)
                                .takeIf { d -> BookStorage.hasImportedBookContent(d) }
                        }
                    } else {
                        val source = runCatching {
                            Injekt.get<NovelSourceManager>().getNovelSource(it.source)
                        }.getOrNull()
                        if (source != null) {
                            val bookId = SourceChapterBookBuilder.bookId(source, it.toSNNovel())
                            BookStorage.getBookDirectory(context, bookId)
                                .takeIf { d -> BookStorage.hasImportedBookContent(d) }
                        } else {
                            null
                        }
                    }
                }
                val chapterIndex = runCatching {
                    chapterRepo.getChaptersByNovelId(latest.novelId)
                        .sortedBy { c -> c.chapterNumber }
                        .indexOfFirst { c -> c.id == latest.chapterId }
                        .takeIf { i -> i >= 0 }
                }.getOrNull()
                WidgetResume(
                    title = latest.novelTitle,
                    chapterLabel = latest.chapterName,
                    progress = latest.chapterProgress,
                    bookDir = dir,
                    novelId = latest.novelId,
                    chapterIndex = chapterIndex,
                )
            } else {
                null
            }
        }.getOrNull() ?: lastNovel?.let { book ->
            val bookDir = BookStorage.getBookDirectory(context, book.folder ?: book.id)
            val bookmark = BookStorage.loadBookmark(bookDir)
            WidgetResume(
                title = book.title,
                chapterLabel = bookmark?.let {
                    novelChapterLabelFromStarts(
                        chapterStarts = book.chapterStarts,
                        exploredChars = it.characterCount,
                        spineChapterIndex = it.chapterIndex,
                    )
                } ?: context.getString(R.string.widget_chapter_unknown),
                progress = bookmark?.progress ?: 0.0,
                bookDir = bookDir.takeIf { BookStorage.hasImportedBookContent(it) },
                novelId = null,
                chapterIndex = bookmark?.chapterIndex,
            )
        }
        val bookDir = resume?.bookDir

        val noRecentNovels = context.getString(R.string.widget_no_recent_novels)
        val currentNovelLabel = context.getString(R.string.widget_current_novel)
        val unknownTitle = context.getString(R.string.widget_unknown_title)

        provideContent {
            val intent = if (bookDir != null) {
                Intent(context, NovelReaderActivity.activityClass).apply {
                    putExtra(NovelReaderActivity.EXTRA_BOOK_DIR, bookDir.absolutePath)
                    resume?.novelId?.let { putExtra(NovelReaderActivity.EXTRA_NOVEL_ID, it) }
                    resume?.chapterIndex?.let { putExtra(NovelReaderActivity.EXTRA_CHAPTER_INDEX, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else {
                mainActivityIntent(context, Constants.SHORTCUT_NOVELS)
            }

            Box(
                modifier = GlanceModifier
                    .widgetContainer()
                    .clickable(actionStartActivity(intent)),
            ) {
                if (resume == null) {
                    WidgetEmptyState(noRecentNovels)
                } else {
                    Column(
                        modifier = GlanceModifier.fillMaxSize().padding(16.dp),
                    ) {
                        Text(
                            text = currentNovelLabel,
                            style = TextStyle(
                                color = GlanceTheme.Primary,
                                fontSize = WidgetHeaderTitleSize,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = GlanceModifier.padding(bottom = 4.dp),
                        )
                        Text(
                            text = resume.title ?: unknownTitle,
                            style = TextStyle(
                                color = GlanceTheme.OnSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 2,
                        )

                        Spacer(modifier = GlanceModifier.defaultWeight())

                        val chapterLabel = resume.chapterLabel
                        val progressPercent = PROGRESS_FORMAT.format(resume.progress * 100)

                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                text = chapterLabel,
                                style = TextStyle(
                                    color = GlanceTheme.OnSurfaceVariant,
                                    fontSize = 12.sp,
                                ),
                                modifier = GlanceModifier.defaultWeight(),
                                maxLines = 1,
                            )
                            Text(
                                text = "$progressPercent%",
                                style = TextStyle(
                                    color = GlanceTheme.OnSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val PROGRESS_FORMAT = DecimalFormat("#.##")

        /**
         * 1-based chapter number matching [eu.kanade.tachiyomi.ui.stats.StatsScreenModel] novel logic:
         * [chapterStarts] are char offsets at chapter boundaries (last entry = book total).
         * Current chapter = last start index with start <= exploredChars.
         */
        internal fun novelChapterLabelFromStarts(
            chapterStarts: List<Int>?,
            exploredChars: Int,
            spineChapterIndex: Int,
        ): String {
            if (chapterStarts != null && chapterStarts.size > 1) {
                val boundaries = chapterStarts.dropLast(1)
                val idx = boundaries.indexOfLast { it <= exploredChars }
                val number = if (idx >= 0) idx + 1 else 1
                return "Chapter $number"
            }
            return "Chapter ${spineChapterIndex + 1}"
        }
    }
}
