package eu.kanade.tachiyomi.glance

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.canopus.chimareader.data.AnkiStatsStorage
import com.canopus.chimareader.data.MangaStatsStorage
import eu.kanade.tachiyomi.R
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.Constants
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ReadingStatsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode
        get() = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lockedMessage = lockedWidgetMessage(context)
        if (isAppLocked()) {
            provideContent { WidgetLockedState(lockedMessage) }
            return
        }

        val today = LocalDate.now().toString()
        val mangaStats = MangaStatsStorage.loadAll(context).filter { it.dateKey == today }
        val ankiStats = AnkiStatsStorage.loadAll(context).filter { it.dateKey == today }
        val novelToday = loadNovelStatsForDay(context, today)

        val totalChars = mangaStats.sumOf { it.charactersRead } + novelToday.characters
        // Manga stats store ms; novel Statistics.readingTime is seconds (same as StatsScreenModel).
        val totalTimeMs = mangaStats.sumOf { it.readingTime } + novelToday.timeMs
        val totalCards = ankiStats.sumOf { it.mangaCards + it.novelCards }

        val timeString = formatReadingTime(totalTimeMs)
        val charactersPerHour = if (totalTimeMs > 0) {
            ((totalChars.toDouble() * MS_PER_HOUR) / totalTimeMs).toInt()
        } else {
            0
        }

        val labelCharacters = context.getString(R.string.widget_stat_characters)
        val labelReadingTime = context.getString(R.string.widget_stat_reading_time)
        val labelSpeed = context.getString(R.string.widget_stat_speed)
        val labelMinedCards = context.getString(R.string.widget_stat_mined_cards)

        provideContent {
            val intent = mainActivityIntent(context, Constants.SHORTCUT_STATS)
            val size = LocalSize.current
            val isCompact = size.width < COMPACT_THRESHOLD || size.height < COMPACT_THRESHOLD

            Column(
                modifier = GlanceModifier
                    .widgetContainer()
                    .clickable(actionStartActivity(intent))
                    .padding(8.dp),
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    StatCard(
                        iconRes = R.drawable.ic_text_fields_24dp,
                        value = "%,d".format(totalChars),
                        label = labelCharacters,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    StatCard(
                        iconRes = R.drawable.ic_schedule_24dp,
                        value = timeString,
                        label = labelReadingTime,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    )
                }

                if (!isCompact) {
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        StatCard(
                            iconRes = R.drawable.ic_speed_24dp,
                            value = "%,d".format(charactersPerHour),
                            label = labelSpeed,
                            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        )
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        StatCard(
                            iconRes = R.drawable.ic_style_24dp,
                            value = totalCards.toString(),
                            label = labelMinedCards,
                            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    private fun formatReadingTime(totalTimeMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(totalTimeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private data class NovelDayTotals(val characters: Int, val timeMs: Long)

    private suspend fun loadNovelStatsForDay(context: Context, dateKey: String): NovelDayTotals {
        // The reader upserts live; no file fallback.
        return runCatching {
            val repo = Injekt.get<tachiyomi.domain.novel.repository.NovelReadingStatsRepository>()
            val dbStats = repo.getForDay(dateKey)
            NovelDayTotals(
                characters = dbStats.sumOf { it.charactersRead },
                timeMs = (dbStats.sumOf { it.readingTime } * 1000.0).toLong(),
            )
        }.getOrDefault(NovelDayTotals(0, 0L))
    }

    companion object {
        private val COMPACT_THRESHOLD = 180.dp
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
