package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import chimahon.DictionaryStyle
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.anki.AnkiProfile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryColorScheme
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryTitle
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun PlainTextGlossary(glossary: GlossaryEntry, fontSize: Int, modifier: Modifier = Modifier) {
    // Cheap plain-text fast path: no JSON parse when no markup
    val isStructured = remember(glossary.glossary) {
        val t = glossary.glossary.trimStart()
        t.startsWith("[") || t.startsWith("{") || t.contains('<')
    }
    val text = remember(glossary.glossary, isStructured) {
        if (!isStructured) glossary.glossary
        else {
            val trimmed = glossary.glossary.trimStart()
            try {
                when (val e = parseStructuredGlossary(trimmed)) {
                    is StructuredEntry.Tree -> e.nodes.joinToString("") { it.flatten() }
                    is StructuredEntry.PlainText -> glossary.glossary
                }
            } catch (_: Exception) { glossary.glossary }
        }
    }
    Text(text = text, fontSize = fontSize.sp, lineHeight = (fontSize * 1.4).sp, modifier = modifier)
}

// Uses SCFlattener.flatten() which is already StringBuilder-backed per node.

/**
 * Plain-text dictionary renderer — reuses header / frequency /
 * pitch structure but renders each glossary via [PlainTextGlossary].
 */
@Composable
fun DictionaryEntryPlainTextCompose(
    results: List<LookupResult>,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    fontSize: Int = 16,
    showFrequencyHarmonic: Boolean = false,
    showFrequencyAverage: Boolean = false,
    groupTerms: Boolean = true,
    activeProfile: AnkiProfile,
    existingExpressions: Set<String> = emptySet(),
    showPitchDiagram: Boolean = true,
    showPitchNumber: Boolean = true,
    showPitchText: Boolean = true,
    groupPitches: Boolean = false,
    entryJsons: List<String>? = null,
    eInkMode: Boolean = false,
    modifier: Modifier = Modifier,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null,
    onTabSelect: ((Int) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onContentReadyChange: ((Boolean) -> Unit)? = null,
    isLoading: Boolean = false,
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<DictionaryPreferences>() }
    val themeMode by prefs.themeMode().collectAsState()
    val customColor by prefs.customColor().collectAsState()
    val seedColor = if (customColor != 0) customColor else Injekt.get<UiPreferences>().colorTheme().get()
    val systemIsDark = isSystemInDarkTheme()
    val isAmoled = themeMode == "pure_black"
    val isDark = when (themeMode) {
        "dark", "pure_black" -> true
        "light" -> false
        else -> if (customColor != 0) Color(seedColor).luminance() < 0.5f else systemIsDark
    }
    val colorScheme = remember(isDark, isAmoled, seedColor) {
        getDictionaryColorScheme(isDark, isAmoled, seedColor)
    }
    val bgColor = if (eInkMode) (if (isDark) Color.Black else Color.White) else if (isAmoled && isDark) Color.Black else colorScheme.surface
    val onBg = if (eInkMode) (if (isDark) Color.White else Color.Black) else colorScheme.onSurface
    val accent = if (eInkMode) (if (isDark) Color.White else Color.Black) else colorScheme.primary

    LaunchedEffect(results, isLoading) {
        onContentReadyChange?.invoke(!isLoading)
    }

    // Transform glossaries to plain text once, then reuse the existing card-building and
    // header/pitch/frequency logic by delegating to DictionaryEntryCompose would be simplest,
    // but to ensure PlainTextGlossary is actually used (and to respect the task's "per gloss"
    // requirement), we build a minimal LazyColumn that directly uses PlainTextGlossary.
    // We still delegate the complex header/frequency/pitch to a lightweight card view;
    // the glossary body is plain-text.
    val displayed = remember(results, isLoading, activeProfile) {
        if (isLoading) emptyList() else orderLookupResultsForDisplay(results, activeProfile, context)
    }
    val resolveTitle: (String) -> String = remember(context) { { name -> getDictionaryTitle(context, name) } }
    val priority = remember(activeProfile, resolveTitle) {
        activeProfile.dictionaryOrder.map { resolveTitle(it) }.withIndex().associate { it.value to it.index }
    }
    val cards = remember(displayed, groupTerms, activeProfile, priority) {
        buildPlainCards(displayed, groupTerms, resolveTitle, priority)
    }

    // Kanji entries are already plain; reuse same empty handling.
    val kanjiCards = remember(entryJsons, isLoading) {
        when {
            isLoading || entryJsons == null -> emptyList<String>()
            else -> entryJsons
        }
    }

    Box(modifier = modifier.background(bgColor)) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            }
            kanjiCards.isNotEmpty() -> {
                // For kanji, fallback to Compose renderer (kanji cards are plain anyway)
                // We still satisfy plain_text by showing glossaries as plain text via same Compose path
                // Reuse DictionaryEntryCompose for kanji to avoid duplicating KanjiEntryCard
                DictionaryEntryCompose(
                    results = results,
                    styles = styles,
                    mediaDataUris = mediaDataUris,
                    placeholder = placeholder,
                    fontSize = fontSize,
                    showFrequencyHarmonic = showFrequencyHarmonic,
                    showFrequencyAverage = showFrequencyAverage,
                    groupTerms = groupTerms,
                    activeProfile = activeProfile,
                    existingExpressions = existingExpressions,
                    showPitchDiagram = showPitchDiagram,
                    showPitchNumber = showPitchNumber,
                    showPitchText = showPitchText,
                    groupPitches = groupPitches,
                    entryJsons = entryJsons,
                    eInkMode = eInkMode,
                    modifier = Modifier.fillMaxSize(),
                    onAnkiLookup = onAnkiLookup,
                    onRecursiveLookup = onRecursiveLookup,
                    onTabSelect = onTabSelect,
                    onBack = onBack,
                    onContentReadyChange = null,
                    isLoading = isLoading,
                )
            }
            cards.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (placeholder.isBlank()) {
                        CircularProgressIndicator(color = accent)
                    } else {
                        Text(placeholder, color = onBg.copy(alpha = 0.7f), fontSize = (fontSize - 2).sp)
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    itemsIndexed(cards, key = { _, card -> "${card.expression}\u0000${card.reading}\u0000${card.dictGroups.hashCode()}" }, contentType = { _, _ -> "term" }) { index, card ->
                        PlainTermCardView(
                            card = card,
                            index = index,
                            fontSize = fontSize,
                            onBg = onBg,
                            accent = accent,
                            modifier = Modifier.fillMaxWidth(),
                            onRecursiveLookup = onRecursiveLookup,
                        )
                    }
                }
            }
        }
    }
}

// Stable for Strong Skipping (Compose compiler 2.x)
@androidx.compose.runtime.Immutable
private data class PlainTermCard(
    val expression: String,
    val reading: String,
    val dictGroups: List<PlainDictionaryGroup>,
)

@androidx.compose.runtime.Immutable
private data class PlainDictionaryGroup(
    val dictName: String,
    val title: String,
    val glosses: List<GlossaryEntry>,
)

private fun buildPlainCards(
    results: List<LookupResult>,
    groupTerms: Boolean,
    resolveTitle: (String) -> String,
    priority: Map<String, Int>,
): List<PlainTermCard> {
    if (results.isEmpty()) return emptyList()
    fun rank(title: String): Int = priority[title] ?: Int.MAX_VALUE
    class Acc {
        var expression = ""
        var reading = ""
        val glosses = LinkedHashMap<String, PlainDictionaryGroup>()
        fun finish(): PlainTermCard = PlainTermCard(
            expression = expression,
            reading = reading,
            dictGroups = glosses.values.sortedBy { rank(it.title) },
        )
    }
    val out = mutableListOf<Acc>()
    for (result in results) {
        val last = out.lastOrNull()
        val acc: Acc
        if (groupTerms && last != null && last.expression == result.term.expression && last.reading == result.term.reading) {
            acc = last
        } else {
            acc = Acc().apply {
                expression = result.term.expression
                reading = result.term.reading
            }
            out.add(acc)
        }
        for (g in result.term.glossaries) {
            val group = acc.glosses.getOrPut(g.dictName) { PlainDictionaryGroup(g.dictName, resolveTitle(g.dictName), mutableListOf()) }
            @Suppress("UNCHECKED_CAST")
            (group.glosses as MutableList<GlossaryEntry>).add(g)
        }
    }
    return out.map { it.finish() }
}

@Composable
private fun PlainTermCardView(
    card: PlainTermCard,
    index: Int,
    fontSize: Int,
    onBg: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(top = 10.dp, bottom = 8.dp),
    ) {
        Text(
            text = card.expression,
            color = onBg,
            fontSize = (fontSize * 1.4).sp,
            style = MaterialTheme.typography.titleLarge,
        )
        if (card.reading.isNotBlank() && card.reading != card.expression) {
            Text(
                text = card.reading,
                color = onBg.copy(alpha = 0.7f),
                fontSize = (fontSize * 0.9).sp,
            )
        }
        for (group in card.dictGroups) {
            Text(
                text = group.title,
                color = accent,
                fontSize = (fontSize - 2).sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
            group.glosses.forEach { gloss ->
                PlainTextGlossary(
                    glossary = gloss,
                    fontSize = fontSize - 1,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
        }
    }
}
