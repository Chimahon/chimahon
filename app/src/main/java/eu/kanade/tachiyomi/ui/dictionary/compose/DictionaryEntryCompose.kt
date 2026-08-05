package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.turtlekazu.furiganable.compose.m3.TextWithReading
import chimahon.DictionaryStyle
import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import chimahon.anki.AnkiProfile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryColorScheme
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryTitle
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

private data class TermCard(
    val expression: String,
    val reading: String,
    val matched: String,
    val deinflected: String,
    val process: List<String>,
    val dictGroups: List<DictionaryGroup>,
    val frequencies: List<FrequencyEntry>,
    val pitches: List<PitchEntry>,
)

private data class DictionaryGroup(
    val dictName: String,
    val title: String,
    val glosses: List<GlossaryEntry>,
)

/** A parsed kanji result, mirroring the WebView's `kanji-renderer.js` model. */
private data class KanjiCard(
    val character: String,
    val dictName: String,
    val onyomi: List<String>,
    val kunyomi: List<String>,
    val tags: List<String>,
    val definitions: List<String>,
    val stats: List<Pair<String, String>>,
)

/**
 * Parse a kanji entry JSON string (the same `buildKanjiEntryJson` payload the
 * WebView consumes) into a [KanjiCard] for native rendering.
 */
private fun parseKanjiEntryJson(json: String): KanjiCard? {
    val kanji: JSONObject = runCatching {
        JSONObject(json).optJSONObject("kanji")
    }.getOrNull() ?: return null
    return runCatching {
        val onyomi = kanji.optJSONArray("onyomi")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val kunyomi = kanji.optJSONArray("kunyomi")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val definitions = kanji.optJSONArray("definitions")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val stats = mutableListOf<Pair<String, String>>()
        kanji.optJSONObject("stats")?.let { obj ->
            obj.keys().forEach { key ->
                val v = obj.optString(key)
                if (v.isNotBlank()) stats.add(key to v)
            }
        }
        KanjiCard(
            character = kanji.optString("character", ""),
            dictName = kanji.optString("dictName", ""),
            onyomi = onyomi,
            kunyomi = kunyomi,
            tags = kanji.optString("tags", "").split(" ").filter { it.isNotBlank() },
            definitions = definitions,
            stats = stats,
        )
    }.getOrNull()
}

/**
 * Native Compose dictionary renderer for the reader popup — a drop-in for
 * [eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView]. Renders the same
 * underlying [chimahon.LookupResult] model directly, without the JSON/JS round-trip.
 */
@Composable
fun DictionaryEntryCompose(
    results: List<LookupResult>,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    headerText: String = "",
    fontSize: Int = 16,
    showFrequencyHarmonic: Boolean = false,
    showFrequencyAverage: Boolean = false,
    groupTerms: Boolean = true,
    activeProfile: AnkiProfile,
    existingExpressions: Set<String> = emptySet(),
    showPitchDiagram: Boolean = true,
    showPitchNumber: Boolean = true,
    showPitchText: Boolean = true,
    recursiveNavMode: String = "tabs",
    renderRecursiveChrome: Boolean = true,
    wordAudioEnabled: Boolean = true,
    wordAudioAutoplayOverride: Boolean? = null,
    customCss: String = "",
    groupPitches: Boolean = false,
    entryJsons: List<String>? = null,
    modifier: Modifier = Modifier,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null,
    onTabSelect: ((Int) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onContentReadyChange: ((Boolean) -> Unit)? = null,
    hideOnContentInvalidated: Boolean = true,
    forceDefaultTheme: Boolean = false,
    requestFocusOnMount: Boolean = false,
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
    val bgColor = if (isAmoled && isDark) Color.Black else colorScheme.surface
    val onBg = colorScheme.onSurface
    val accent = colorScheme.primary

    LaunchedEffect(results, isLoading) {
        onContentReadyChange?.invoke(!isLoading)
    }

    val resolveTitle: (String) -> String = remember(context) { { name -> getDictionaryTitle(context, name) } }
    val priority = remember(activeProfile, resolveTitle) {
        activeProfile.dictionaryOrder.map { resolveTitle(it) }.withIndex().associate { it.value to it.index }
    }

    val kanjiCards = remember(entryJsons, isLoading) {
        when {
            isLoading || entryJsons == null -> emptyList()
            else -> entryJsons.mapNotNull { parseKanjiEntryJson(it) }
        }
    }

    val displayed = if (isLoading) emptyList() else orderLookupResultsForDisplay(results, activeProfile, context)
    val cards = remember(displayed, groupTerms, activeProfile, priority) {
        buildCards(displayed, groupTerms, resolveTitle, priority)
    }

    Box(modifier = modifier.background(bgColor)) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            }
            kanjiCards.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    itemsIndexed(kanjiCards, key = { i, _ -> "kanji-$i" }) { i, kanji ->
                        KanjiEntryCard(
                            kanji = kanji,
                            fontSize = fontSize,
                            accent = accent,
                            onBg = onBg,
                            onRecursiveLookup = onRecursiveLookup,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    itemsIndexed(cards, key = { i, _ -> i }) { i, card ->
                        TermCardView(
                            card = card,
                            index = i,
                            fontSize = fontSize,
                            accent = accent,
                            onBg = onBg,
                            showFrequencyHarmonic = showFrequencyHarmonic,
                            showFrequencyAverage = showFrequencyAverage,
                            showPitchDiagram = showPitchDiagram,
                            showPitchNumber = showPitchNumber,
                            showPitchText = showPitchText,
                            groupPitches = groupPitches,
                            activeProfile = activeProfile,
                            mediaDataUris = mediaDataUris,
                            existingExpressions = existingExpressions,
                            onAnkiLookup = onAnkiLookup,
                            onRecursiveLookup = onRecursiveLookup,
                            wordAudioEnabled = wordAudioEnabled,
                            autoplay = wordAudioAutoplayOverride == true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun buildCards(
    results: List<LookupResult>,
    groupTerms: Boolean,
    resolveTitle: (String) -> String,
    priority: Map<String, Int>,
): List<TermCard> {
    if (results.isEmpty()) return emptyList()

    fun rank(title: String): Int = priority[title] ?: Int.MAX_VALUE

    class Acc {
        var expression = ""
        var reading = ""
        var matched = ""
        var deinflected = ""
        val process = LinkedHashSet<String>()
        val glosses = LinkedHashMap<String, DictionaryGroup>()
        val frequencies = LinkedHashMap<String, FrequencyEntry>()
        val pitches = LinkedHashMap<String, PitchEntry>()

        fun finish(): TermCard = TermCard(
            expression = expression,
            reading = reading,
            matched = matched,
            deinflected = deinflected,
            process = process.toList(),
            dictGroups = glosses.values.sortedBy { rank(it.title) },
            frequencies = frequencies.values.toList(),
            pitches = pitches.values.toList(),
        )
    }

    val out = mutableListOf<Acc>()
    for (result in results) {
        val acc: Acc
        val last = out.lastOrNull()
        if (groupTerms && last != null &&
            last.expression == result.term.expression &&
            last.reading == result.term.reading
        ) {
            acc = last
        } else {
            acc = Acc().apply {
                expression = result.term.expression
                reading = result.term.reading
                matched = result.matched
                deinflected = result.deinflected
            }
            out.add(acc)
        }
        if (acc.matched.isBlank()) acc.matched = result.matched
        for (p in result.process) acc.process.add(p.name)
        for (g in result.term.glossaries) {
            val group = acc.glosses.getOrPut(g.dictName) { DictionaryGroup(g.dictName, resolveTitle(g.dictName), mutableListOf()) }
            @Suppress("UNCHECKED_CAST")
            (group.glosses as MutableList<GlossaryEntry>).add(g)
        }
        for (f in result.term.frequencies) {
            val existing = acc.frequencies[f.dictName]
            acc.frequencies[f.dictName] = if (existing != null) f.copy(frequencies = existing.frequencies + f.frequencies) else f
        }
        for (p in result.term.pitches) acc.pitches[p.dictName] = p
    }
    return out.map { it.finish() }
}

@Composable
private fun TermCardView(
    card: TermCard,
    index: Int,
    fontSize: Int,
    accent: Color,
    onBg: Color,
    showFrequencyHarmonic: Boolean,
    showFrequencyAverage: Boolean,
    showPitchDiagram: Boolean,
    showPitchNumber: Boolean,
    showPitchText: Boolean,
    groupPitches: Boolean,
    activeProfile: AnkiProfile,
    mediaDataUris: Map<String, String>,
    existingExpressions: Set<String>,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)?,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
    wordAudioEnabled: Boolean,
    autoplay: Boolean,
    modifier: Modifier,
) {
    val overrideState = remember(card) { CollapseOverrideState(emptyMap()) }
    val collapseMode = activeProfile.dictionaryCollapseMode
    val alreadyAdded = card.expression in existingExpressions

    Column(modifier = modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FuriganaText(
                    expression = card.expression,
                    reading = card.reading,
                    color = onBg,
                    fontSize = (fontSize + 4).sp,
                )
                if (card.reading.isNotBlank() && card.reading != card.expression) {
                    Text(
                        text = card.reading,
                        color = onBg.copy(alpha = 0.65f),
                        fontSize = (fontSize - 1).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (wordAudioEnabled) {
                WordAudioButton(
                    expression = card.expression,
                    reading = card.reading,
                    accent = accent,
                    autoplay = autoplay,
                    modifier = Modifier.size(32.dp),
                )
            }
            if (onAnkiLookup != null) {
                IconButton(
                    onClick = { onAnkiLookup(index, null, card.dictGroups.firstOrNull()?.title, null, false) },
                ) {
                    Icon(
                        imageVector = if (alreadyAdded) Icons.Outlined.Check else Icons.Outlined.Add,
                        contentDescription = null,
                        tint = if (alreadyAdded) accent.copy(alpha = 0.5f) else accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        val inflected = card.process.isNotEmpty() && card.process != listOf(card.expression)
        if (inflected) {
            var showDetails by remember(card) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { showDetails = !showDetails }
                    .padding(vertical = 2.dp),
            ) {
                card.process.take(4).forEach { tag ->
                    Surface(shape = RoundedCornerShape(4.dp), color = accent.copy(alpha = 0.12f)) {
                        Text(
                            text = tag, color = accent, fontSize = (fontSize - 4).sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = if (showDetails) "\u25B2" else "\u25BC",
                    color = onBg.copy(alpha = 0.5f),
                    fontSize = (fontSize - 4).sp,
                )
            }
            AnimatedVisibility(visible = showDetails) {
                Column(Modifier.padding(top = 2.dp)) {
                    Text(
                        text = card.process.reversed().joinToString(" \u00BB "),
                        color = onBg.copy(alpha = 0.7f),
                        fontSize = (fontSize - 3).sp,
                    )
                }
            }
        }

        val freqText = buildFrequencyText(card.frequencies, showFrequencyHarmonic, showFrequencyAverage)
        if (freqText.isNotBlank()) {
            Text(freqText, color = onBg.copy(alpha = 0.75f), fontSize = (fontSize - 2).sp, modifier = Modifier.padding(top = 4.dp))
        }

        if (showPitchDiagram || showPitchNumber || showPitchText) {
            PitchSection(
                card = card,
                accent = accent,
                onBg = onBg,
                showPitchDiagram = showPitchDiagram,
                showPitchNumber = showPitchNumber,
                showPitchText = showPitchText,
                groupPitches = groupPitches,
            )
        }

        for ((i, group) in card.dictGroups.withIndex()) {
            val override = overrideState[group.title]
            val alwaysExpanded = activeProfile.dictionaryDisplayModes[group.dictName] == "always_expanded"
            val alwaysCollapsed = activeProfile.dictionaryDisplayModes[group.dictName] == "always_collapsed"
            val initial = when {
                alwaysExpanded -> true
                alwaysCollapsed -> false
                else -> when (collapseMode) {
                    AnkiProfile.DICTIONARY_COLLAPSE_EXPAND_ALL -> true
                    AnkiProfile.DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE -> i == 0
                    else -> false
                }
            }
            val expanded = override ?: initial

            Column(modifier = Modifier.padding(top = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { overrideState.toggle(initial, group.title) }
                        .padding(vertical = 4.dp),
                ) {
                    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "caret")
                    Text("▸", color = onBg.copy(alpha = 0.5f), fontSize = (fontSize - 2).sp, modifier = Modifier.rotate(rotation))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = group.title, color = accent, fontSize = (fontSize - 1).sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.padding(start = 14.dp)) {
                        group.glosses.forEach { gloss ->
                            GlossRow(
                                gloss = gloss,
                                fontSize = fontSize,
                                onBg = onBg,
                                mediaDataUris = mediaDataUris,
                                onRecursiveLookup = onRecursiveLookup,
                            )
                        }
                    }
                }
            }
        }
    }
}

private class CollapseOverrideState(initialByTitle: Map<String, Boolean>) {
    var enabled by mutableStateOf(initialByTitle)
        private set

    fun toggle(initial: Boolean, title: String) {
        val current = enabled[title] ?: initial
        enabled = enabled + (title to !current)
    }

    operator fun get(title: String): Boolean? = enabled[title]
}

@Composable
private fun PitchSection(
    card: TermCard,
    accent: Color,
    onBg: Color,
    showPitchDiagram: Boolean,
    showPitchNumber: Boolean,
    showPitchText: Boolean,
    groupPitches: Boolean,
) {
    val all = if (groupPitches && card.pitches.isNotEmpty()) {
        listOf(
            PitchEntry(
                dictName = card.pitches.joinToString(", ") { it.dictName },
                pitchPositions = card.pitches.flatMap { it.pitchPositions.toList() }.distinct().toIntArray(),
            ),
        )
    } else card.pitches
    if (all.isEmpty()) return

    Column(Modifier.padding(top = 6.dp)) {
        all.forEach { pitch ->
            if (pitch.dictName.isNotBlank()) {
            Text(pitch.dictName, color = onBg.copy(alpha = 0.5f), fontSize = 10.sp)
            }
            val positions = pitch.pitchPositions.toList()
            if (showPitchDiagram) {
                PitchAccentDiagram(
                    text = card.expression,
                    downstepPositions = positions,
                    accentColor = accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (showPitchNumber && positions.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "[" + positions.joinToString(", ") + "]",
                        color = accent,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = (if (showPitchDiagram) 8 else 0).dp, top = 2.dp),
                    )
                }
            }
            if (showPitchText) {
                Text(buildPitchText(card.expression, positions), color = onBg.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }
    }
}

private fun buildFrequencyText(frequencies: List<FrequencyEntry>, showHarmonic: Boolean, showAverage: Boolean): String {
    if (frequencies.isEmpty()) return ""

    fun collectNumbers(): List<Int> {
        val out = mutableListOf<Int>()
        val seen = mutableSetOf<String>()
        for (group in frequencies) {
            if (!seen.add(group.dictName)) continue
            group.frequencies.forEach { freq ->
                if (freq.value > 0) {
                    out.add(freq.value)
                    return@forEach
                }
            }
        }
        return out
    }

    fun harmonic(): Int? {
        val numbers = collectNumbers()
        if (numbers.isEmpty()) return null
        val n = numbers.size
        val reciprocalSum = numbers.sumOf { 1.0 / it }
        return if (reciprocalSum == 0.0) null else (n / reciprocalSum).toInt()
    }

    fun averageRank(): Double? {
        val numbers = collectNumbers()
        if (numbers.isEmpty()) return null
        return numbers.sum() / numbers.size.toDouble()
    }

    fun formatRank(v: Double): String? {
        if (!v.isFinite() || v <= 0) return null
        val rounded = (v * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    val parts = mutableListOf<String>()

    if (showHarmonic) {
        harmonic()?.let { parts.add("freq harmonic: $it") }
        if (showAverage) {
            averageRank()?.let { avg -> formatRank(avg)?.let { parts.add("avg $it") } }
        }
        return parts.joinToString("  ")
    }

    if (showAverage) {
        averageRank()?.let { avg -> formatRank(avg)?.let { parts.add("avg $it") } }
    }

    // Compact per-dictionary chips: prefer displayValue, fall back to value.
    for (group in frequencies) {
        val values = group.frequencies
            .mapNotNull { freq -> freq.displayValue.takeIf { it.isNotBlank() } ?: freq.value.takeIf { it > 0 }?.toString() }
            .distinct()
        if (values.isNotEmpty()) parts.add(values.joinToString(", "))
    }
    return parts.joinToString(" · ")
}

private fun buildPitchText(expression: String, positions: List<Int>): String {
    if (expression.isEmpty()) return ""
    val minDownstep = positions.minOrNull()
    return buildString {
        for ((i, _) in expression.withIndex()) {
            append(if (minDownstep == null || i < minDownstep) "H" else "L")
        }
    }
}

@Composable
private fun GlossRow(
    gloss: GlossaryEntry,
    fontSize: Int,
    onBg: Color,
    mediaDataUris: Map<String, String>,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
) {
    val nodes = remember(gloss.glossary) { parseGlossary(gloss.glossary) }
    Column(Modifier.padding(vertical = 3.dp)) {
        nodes.forEach { node ->
            when (node) {
                is GlossNode.Run -> {
                    val bold = node.bold
                    val italic = node.italic
                    val underline = node.underline
                    val runModifier = if (onRecursiveLookup != null && node.text.isNotBlank()) {
                        Modifier
                            .clickable { onRecursiveLookup(node.text, null, null, null, null, "term") }
                            .padding(vertical = 1.dp)
                    } else {
                        Modifier
                    }
                    Text(
                        text = node.text,
                        color = if (node.color != null) Color(node.color) else onBg,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (underline) TextDecoration.Underline else null,
                        fontSize = (fontSize - 1).sp,
                        modifier = runModifier,
                    )
                }
                is GlossNode.Ruby -> {
                    val rubyModifier = if (onRecursiveLookup != null && node.text.isNotBlank()) {
                        Modifier
                            .clickable { onRecursiveLookup(node.text, null, null, null, null, "term") }
                            .padding(vertical = 1.dp)
                    } else {
                        Modifier
                    }
                    TextWithReading(
                        formattedText = "[${node.text}[${node.ruby}]]",
                        style = TextStyle(color = onBg, fontSize = (fontSize - 1).sp),
                        furiganaFontSize = (fontSize - 1).sp * 0.5f,
                        modifier = rubyModifier,
                    )
                }
                is GlossNode.Image -> {
                    GlossImage(gloss.dictName, node.uri, mediaDataUris, onBg)
                }
                GlossNode.Break -> Spacer(Modifier.height(4.dp))
                GlossNode.Space -> Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun GlossImage(dictName: String, path: String?, mediaDataUris: Map<String, String>, onBg: Color) {
    if (path.isNullOrBlank()) return
    val dataUri = resolveMediaUri(dictName, path, mediaDataUris)
    if (dataUri == null) {
        // Media not preloaded (deferred load) — reserve space so layout doesn't jump.
        Spacer(Modifier.width(0.dp))
        return
    }
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .background(onBg.copy(alpha = 0.06f), RoundedCornerShape(4.dp)),
    ) {
        AsyncImage(
            model = dataUri,
            contentDescription = null,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 160.dp),
        )
    }
}

private fun resolveMediaUri(dictName: String, path: String, mediaDataUris: Map<String, String>): String? {
    if (mediaDataUris.isEmpty()) return null
    val cleaned = path
        .removePrefix("media://")
        .removePrefix("media:")
        .removePrefix("media:")
        .trimStart('/')
    for (candidate in listOf("$dictName\u0000$path", "$dictName\u0000$cleaned")) {
        mediaDataUris[candidate]?.let { return it }
    }
    return null
}

@Composable
private fun WordAudioButton(
    expression: String,
    reading: String,
    accent: Color,
    autoplay: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var playing by remember(expression) { mutableStateOf(false) }

    LaunchedEffect(expression, autoplay) {
        if (!autoplay) return@LaunchedEffect
        val url = WordAudioPlayer.findAudio(expression, reading)
        if (url != null) WordAudioPlayer.playUrl(url)
    }

    IconButton(
        onClick = {
            if (playing) return@IconButton
            scope.launch {
                playing = true
                val url = WordAudioPlayer.findAudio(expression, reading)
                if (url != null) WordAudioPlayer.playUrl(url)
                playing = false
            }
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Outlined.VolumeUp,
            contentDescription = null,
            tint = if (playing) accent.copy(alpha = 0.5f) else accent.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun KanjiEntryCard(
    kanji: KanjiCard,
    fontSize: Int,
    accent: Color,
    onBg: Color,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = kanji.character,
                color = onBg,
                fontSize = 48.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                if (kanji.onyomi.isNotEmpty()) {
                    Text(
                        text = "ON: ${kanji.onyomi.joinToString(", ")}",
                        color = accent,
                        fontSize = (fontSize - 1).sp,
                    )
                }
                if (kanji.kunyomi.isNotEmpty()) {
                    Text(
                        text = "KUN: ${kanji.kunyomi.joinToString(", ")}",
                        color = accent.copy(alpha = 0.9f),
                        fontSize = (fontSize - 1).sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        kanji.tags.forEach { tag ->
            Surface(shape = RoundedCornerShape(4.dp), color = accent.copy(alpha = 0.12f), modifier = Modifier.padding(top = 4.dp)) {
                Text(text = tag, color = accent, fontSize = (fontSize - 5).sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }

        kanji.definitions.forEachIndexed { i, def ->
            Row(modifier = Modifier.padding(top = (if (i == 0) 8 else 3).dp)) {
                Text(
                    text = "${i + 1}.",
                    color = accent,
                    fontSize = (fontSize - 1).sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                val defModifier = if (onRecursiveLookup != null && def.isNotBlank()) {
                    Modifier
                        .clickable { onRecursiveLookup(def, null, null, null, null, "term") }
                        .padding(vertical = 1.dp)
                } else {
                    Modifier
                }
                Text(
                    text = def,
                    color = onBg,
                    fontSize = (fontSize - 1).sp,
                    modifier = defModifier,
                )
            }
        }

        if (kanji.stats.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = onBg.copy(alpha = 0.05f),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    kanji.stats.forEach { (label, value) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Text(
                                text = label,
                                color = onBg.copy(alpha = 0.6f),
                                fontSize = (fontSize - 2).sp,
                                modifier = Modifier.width(90.dp),
                            )
                            Text(
                                text = value,
                                color = onBg,
                                fontSize = (fontSize - 2).sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}