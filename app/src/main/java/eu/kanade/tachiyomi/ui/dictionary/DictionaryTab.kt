package eu.kanade.tachiyomi.ui.dictionary

import android.os.SystemClock
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.SearchHistoryRow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.tab.TabOptions
import chimahon.DictionaryStyle
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryEntryCompose
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryEntryPlainTextCompose
import chimahon.HoshiDicts
import chimahon.LookupResult
import chimahon.anki.AnkiCardCreator
import chimahon.anki.AnkiDroidBridge
import chimahon.anki.AnkiResult
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.TabInfo
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.history.interactor.DeleteSearchHistory
import tachiyomi.domain.history.interactor.GetSearchHistory
import tachiyomi.domain.history.interactor.UpsertSearchHistory
import tachiyomi.domain.history.model.SearchHistory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/** One entry in the recursive-lookup history stack (shared by tab and popup). */
private data class TabLookupFrame(
    val query: String,
    val results: List<LookupResult>,
    val styles: List<DictionaryStyle>,
    val mediaDataUris: Map<String, String>,
    val existingExpressions: Set<String>,
    val existingCardIds: Map<String, Long>,
    val entryJsons: List<String>? = null,
)

internal fun cacheAnkiResultNoteId(
    existingCardIds: Map<String, Long>,
    expression: String,
    ankiResult: AnkiResult,
): Map<String, Long> {
    val noteId = when (ankiResult) {
        is AnkiResult.Success -> ankiResult.noteId
        is AnkiResult.CardExists -> ankiResult.noteId
        is AnkiResult.OpenCard -> ankiResult.noteId
        else -> return existingCardIds
    }
    return existingCardIds + (expression to noteId)
}


private var cachedDictionaryPaths: chimahon.DictionaryPaths? = null
private var lastProfileHash: Int? = null
private var lastDictDirModified: Long = 0L

fun getDictionaryPaths(context: android.content.Context, activeProfileOverride: chimahon.anki.AnkiProfile? = null): chimahon.DictionaryPaths {
    val dictionariesDir = File(context.getExternalFilesDir(null), "dictionaries")
    if (!dictionariesDir.exists()) return chimahon.DictionaryPaths()

    val currentModified = dictionariesDir.lastModified()
    if (cachedDictionaryPaths != null && lastDictDirModified == currentModified) {
        val activeProfile = activeProfileOverride ?: run {
            try { Injekt.get<DictionaryPreferences>().profileStore.getActiveProfile() } catch (_: Exception) { null }
        }
        if (activeProfile != null && lastProfileHash == activeProfile.hashCode()) {
            return cachedDictionaryPaths!!
        }
    }

    val typeDirs = mapOf(
        "term" to File(dictionariesDir, "term"),
        "frequency" to File(dictionariesDir, "frequency"),
        "pitch" to File(dictionariesDir, "pitch"),
        "kanji" to File(dictionariesDir, "kanji"),
    )

    val allDictNames = typeDirs.values.flatMap { dir ->
        if (!dir.isDirectory) emptyList()
        else dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
    }.distinct()

    if (allDictNames.isEmpty()) return chimahon.DictionaryPaths()

    val prefs = try {
        DictionaryPreferences(Injekt.get())
    } catch (_: Exception) {
        return chimahon.DictionaryPaths(
            termPaths = allDictNames.filter { name -> File(typeDirs["term"]!!, name).isDirectory }.map { File(typeDirs["term"]!!, it).absolutePath }.sorted(),
            freqPaths = allDictNames.filter { name -> File(typeDirs["frequency"]!!, name).isDirectory }.map { File(typeDirs["frequency"]!!, it).absolutePath }.sorted(),
            pitchPaths = allDictNames.filter { name -> File(typeDirs["pitch"]!!, name).isDirectory }.map { File(typeDirs["pitch"]!!, it).absolutePath }.sorted(),
            kanjiPaths = allDictNames.filter { name -> File(typeDirs["kanji"]!!, name).isDirectory }.map { File(typeDirs["kanji"]!!, it).absolutePath }.sorted(),
        )
    }

    val activeProfile = activeProfileOverride ?: run {
        prefs.profileStore.migrateIfEmpty(
            defaultName = "Default",
            legacyValues = chimahon.anki.AnkiProfileStore.LegacyAnkiValues(
                deck = prefs.legacyAnkiDeck().get(),
                model = prefs.legacyAnkiModel().get(),
                fieldMap = prefs.legacyAnkiFieldMap().get(),
                tags = prefs.legacyAnkiDefaultTags().get(),
                dupCheck = prefs.legacyAnkiDuplicateCheck().get(),
                dupScope = prefs.legacyAnkiDuplicateScope().get(),
                dupAction = prefs.legacyAnkiDuplicateAction().get(),
                cropMode = prefs.legacyAnkiCropMode().get(),
            ),
            allDictNames = allDictNames,
        )
        prefs.profileStore.getActiveProfile()
    }

    val profileOrder = activeProfile.dictionaryOrder.filter { it in allDictNames }
    val newDicts = allDictNames.filter { it !in activeProfile.dictionaryOrder }
    val orderedNames = profileOrder + newDicts

    val enabled = activeProfile.enabledDictionaries
    val finalNames = if (enabled.isEmpty()) orderedNames else orderedNames.filter { it in enabled }

    fun pathsForType(type: String): List<String> {
        val typeDir = typeDirs[type] ?: return emptyList()
        return finalNames
            .filter { name -> File(typeDir, name).isDirectory }
            .map { name -> File(typeDir, name).absolutePath }
    }

    val result = chimahon.DictionaryPaths(
        termPaths = pathsForType("term"),
        freqPaths = pathsForType("frequency"),
        pitchPaths = pathsForType("pitch"),
        kanjiPaths = pathsForType("kanji"),
    )
    val currentProfileHash = activeProfile.hashCode()

    cachedDictionaryPaths = result
    lastProfileHash = currentProfileHash
    lastDictDirModified = currentModified
    return result
}

data object DictionaryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.label_dictionary),
                icon = rememberVectorPainter(Icons.Outlined.Search),
            )
        }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val sessionManager = remember { DictionarySessionManager() }

        var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
        val query = textFieldValue.text
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var hasSearched by remember { mutableStateOf(false) }
        var shouldMountWebView by remember { mutableStateOf(false) }
        var searchJob by remember { mutableStateOf<Job?>(null) }
        var retainedWebView by remember { mutableStateOf<WebView?>(null) }
        val focusManager = LocalFocusManager.current
        val focusRequester = remember { FocusRequester() }

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val searchHistoryEnabled by uiPreferences.searchHistoryEnabled().collectAsState()
        val getSearchHistory: GetSearchHistory = remember { Injekt.get() }
        val upsertSearchHistory: UpsertSearchHistory = remember { Injekt.get() }
        val deleteSearchHistory: DeleteSearchHistory = remember { Injekt.get() }
        val searchHistoryList by produceState<List<SearchHistory>>(initialValue = emptyList()) {
            if (searchHistoryEnabled) {
                getSearchHistory.subscribe(SearchHistory.SCOPE_DICTIONARY).collect { value = it }
            }
        }

        val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
        val rawProfiles by dictionaryPreferences.rawProfiles().collectAsState()
        val rawActiveProfileId by dictionaryPreferences.rawActiveProfileId().collectAsState()
        val profileStore = dictionaryPreferences.profileStore
        val activeProfile = remember(rawProfiles, rawActiveProfileId) { profileStore.getActiveProfile() }

        val ankiEnabled = activeProfile.ankiEnabled
        val ankiDeck = activeProfile.ankiDeck
        val ankiModel = activeProfile.ankiModel
        val ankiFieldMap = activeProfile.ankiFieldMap
        val ankiDupCheck = activeProfile.ankiDupCheck
        val ankiDupScope = activeProfile.ankiDupScope
        val ankiDupAction = activeProfile.ankiDupAction
        val ankiTags = activeProfile.ankiTags
        val ankiSyncOnCreate = activeProfile.ankiSyncOnCreate

        val showFreqHarmonic by dictionaryPreferences.showFrequencyHarmonic().collectAsState()
        val showFreqAverage by dictionaryPreferences.showFrequencyAverage().collectAsState()
        val showPitchDiagram by dictionaryPreferences.showPitchDiagram().collectAsState()
        val showPitchNumber by dictionaryPreferences.showPitchNumber().collectAsState()
        val showPitchText by dictionaryPreferences.showPitchText().collectAsState()
        val groupTerms by dictionaryPreferences.groupTerms().collectAsState()
        val compactGlossary by dictionaryPreferences.compactGlossary().collectAsState()
        val recursiveNavMode by dictionaryPreferences.recursiveLookupMode().collectAsState()
        val popupFontSizePref by dictionaryPreferences.fontSize().collectAsState()
        val customCss by dictionaryPreferences.customCss().collectAsState()
        val wordAudioEnabled by dictionaryPreferences.wordAudioEnabled().collectAsState()
        val autoKanaConversion by dictionaryPreferences.autoKanaConversion().collectAsState()
        val groupPitches by dictionaryPreferences.groupPitches().collectAsState()
        // ── Lookup history stack ──────────────────────────────────────────────
        val lookupStack = remember { mutableStateListOf<TabLookupFrame>() }
        var activeTabIndex by remember { mutableIntStateOf(0) }

        val currentFrame: TabLookupFrame? = lookupStack.getOrNull(activeTabIndex)
        val results: List<LookupResult> = currentFrame?.results ?: emptyList()
        val styles: List<DictionaryStyle> = currentFrame?.styles ?: emptyList()
        val mediaDataUris: Map<String, String> = currentFrame?.mediaDataUris ?: emptyMap()
        val existingExpressions: Set<String> = currentFrame?.existingExpressions ?: emptySet()
        val entryJsons: List<String>? = currentFrame?.entryJsons

        fun buildTabs(): List<TabInfo> = lookupStack.mapIndexed { i, frame ->
            TabInfo(label = frame.query.take(16), active = i == activeTabIndex)
        }

        /** Push a lookup onto the stack; cancels any in-flight search first. */
        fun stackLookup(rawQuery: String, type: String? = null) {
            searchJob?.cancel()
            shouldMountWebView = true
            searchJob = scope.launch {
                isLoading = true
                errorMessage = null

                val initialFrame: TabLookupFrame
                val lookupError: String?

                if (type == "kanji") {
                    val paths = getDictionaryPaths(context, activeProfile)
                    if (paths.kanjiPaths.isEmpty()) {
                        errorMessage = "No kanji dictionaries available"
                        isLoading = false
                        return@launch
                    }
                    sessionManager.warmUp(paths)
                    val kanjiResult = sessionManager.queryKanji(rawQuery)
                    val jsonStrings = kanjiResult?.entries?.map { e ->
                        buildKanjiEntryJson(kanjiResult.character, e).toString()
                    } ?: emptyList()

                    initialFrame = TabLookupFrame(
                        query = rawQuery,
                        results = emptyList(),
                        styles = emptyList(),
                        mediaDataUris = emptyMap(),
                        existingExpressions = emptySet(),
                        existingCardIds = emptyMap(),
                        entryJsons = jsonStrings,
                    )
                    lookupError = null
                } else {
                    val lookupResult = performLookup(
                        query = rawQuery,
                        context = context,
                        activeProfile = activeProfile,
                        sessionManager = sessionManager,
                    )

                    initialFrame = TabLookupFrame(
                        query = rawQuery,
                        results = lookupResult.results,
                        styles = lookupResult.styles,
                        mediaDataUris = lookupResult.mediaDataUris,
                        existingExpressions = emptySet(),
                        existingCardIds = emptyMap(),
                    )
                    lookupError = lookupResult.error
                }

                // Truncate forward history, then push initial results IMMEDIATELY
                while (lookupStack.size > activeTabIndex + 1) lookupStack.removeAt(lookupStack.size - 1)
                lookupStack.add(initialFrame)
                activeTabIndex = lookupStack.size - 1

                errorMessage = lookupError
                hasSearched = true
                isLoading = false

                if (initialFrame.results.isNotEmpty()) {
                    val frameIndex = activeTabIndex
                    val frameQuery = rawQuery
                    val frameResults = initialFrame.results
                    scope.launch(Dispatchers.IO) {
                        val media = sessionManager.loadMediaDataUris(frameResults)
                        if (media.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                val frame = lookupStack.getOrNull(frameIndex)
                                if (frame?.query == frameQuery && frame.results === frameResults) {
                                    lookupStack[frameIndex] = frame.copy(mediaDataUris = media)
                                }
                            }
                        }
                    }
                }

                // Now run Anki check in background without blocking the UI
                if (ankiEnabled && type != "kanji" && initialFrame.results.isNotEmpty()) {
                    val unique = initialFrame.results.map { it.term.expression }.distinct()
                    scope.launch(Dispatchers.IO) {
                        val existingCardIds = AnkiCardCreator.checkExistingCardIds(
                            context = context,
                            expressions = unique,
                            deckName = ankiDeck,
                            dupScope = ankiDupScope,
                        )
                        withContext(Dispatchers.Main) {
                            // Only update if we are still on the same frame
                            if (activeTabIndex < lookupStack.size && lookupStack[activeTabIndex].query == rawQuery) {
                                lookupStack[activeTabIndex] = lookupStack[activeTabIndex].copy(
                                    existingExpressions = existingCardIds.keys,
                                    existingCardIds = existingCardIds,
                                )
                            }
                        }
                    }
                }
            }
        }

        fun saveDictionaryHistory(targetQuery: String) {
            val trimmed = targetQuery.trim()
            if (trimmed.isNotBlank() && searchHistoryEnabled) {
                scope.launch(Dispatchers.IO) {
                    upsertSearchHistory.await(SearchHistory.SCOPE_DICTIONARY, trimmed)
                }
            }
        }

        // ── Auto-focus effect ──────────────────────────────────────────────────
        LaunchedEffect(Unit) {
            // Wait for tab animation/composition to settle
            delay(300)
            focusRequester.requestFocus()
        }

        LaunchedEffect(query) {
            if (query.isNotBlank()) {
                focusRequester.requestFocus()
            }
        }

        LaunchedEffect(query) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                if (hasSearched) {
                    lookupStack.clear()
                    activeTabIndex = 0
                    hasSearched = false
                }
                return@LaunchedEffect
            }

            if (currentFrame?.query != trimmed) {
                lookupStack.clear()
                activeTabIndex = 0
                stackLookup(trimmed)
            }
        }

        LaunchedEffect(activeProfile) {
            val trimmed = query.trim()
            if (trimmed.isNotEmpty() && hasSearched) {
                lookupStack.clear()
                activeTabIndex = 0
                stackLookup(trimmed)
            }
        }

        // Simple callback for Anki lookup - index maps to results array, glossaryIndex is optional
        val onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = if (ankiEnabled) {
            { resultIndex, glossaryIndex, selectedDict, popupSelection, forceOpen ->
                val result = results.getOrNull(resultIndex)
                if (result != null) {
                    val frameIndex = activeTabIndex
                    val expression = result.term.expression
                    scope.launch {
                        if (forceOpen) {
                            val cachedNoteId = lookupStack.getOrNull(frameIndex)?.existingCardIds?.get(expression)
                            val noteId = cachedNoteId ?: AnkiCardCreator.findExistingCardId(
                                context = context,
                                expression = expression,
                                deckName = ankiDeck,
                                dupScope = ankiDupScope,
                            ) ?: return@launch
                            val frame = lookupStack.getOrNull(frameIndex)
                            if (frame?.results?.getOrNull(resultIndex)?.term?.expression == expression) {
                                lookupStack[frameIndex] = frame.copy(
                                    existingExpressions = frame.existingExpressions + expression,
                                    existingCardIds = frame.existingCardIds + (expression to noteId),
                                )
                            }
                            AnkiDroidBridge(context).guiEditNote(noteId)
                            return@launch
                        }

                        val ankiResult = AnkiCardCreator.addToAnki(
                            context = context,
                            result = result,
                            deck = ankiDeck,
                            model = ankiModel,
                            fieldMapJson = ankiFieldMap,
                            tags = ankiTags,
                            dupCheck = ankiDupCheck,
                            dupScope = ankiDupScope,
                            dupAction = ankiDupAction,
                            glossaryIndex = glossaryIndex,
                            popupSelection = popupSelection,
                            selectedDict = selectedDict,
                            styles = styles,
                            forceOpen = forceOpen,
                            type = "novel",
                            syncOnCreate = ankiSyncOnCreate,
                            profileId = activeProfile.id,
                        )
                        if (ankiResult is AnkiResult.Success || ankiResult is AnkiResult.CardExists || ankiResult is AnkiResult.OpenCard) {
                            val frame = lookupStack.getOrNull(frameIndex)
                            if (frame?.results?.getOrNull(resultIndex)?.term?.expression == expression) {
                                val newExisting = frame.existingExpressions + expression
                                lookupStack[frameIndex] = frame.copy(
                                    existingExpressions = newExisting,
                                    existingCardIds = cacheAnkiResultNoteId(
                                        frame.existingCardIds,
                                        expression,
                                        ankiResult,
                                    ),
                                )
                            }
                        }
                        when (ankiResult) {
                            is AnkiResult.Success -> context.toast(MR.strings.anki_card_added)
                            is AnkiResult.CardExists -> context.toast(MR.strings.anki_card_exists)
                            is AnkiResult.OpenCard -> AnkiDroidBridge(context).guiEditNote(ankiResult.noteId)
                            is AnkiResult.PermissionDenied -> context.toast(MR.strings.pref_anki_permission_denied)
                            is AnkiResult.Error -> context.toast(
                                context.stringResource(MR.strings.anki_card_error, ankiResult.message),
                            )
                            is AnkiResult.NotConfigured -> context.toast(MR.strings.anki_not_configured)
                        }
                    }
                }
            }
        } else {
            null
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }

        LaunchedEffect(Unit) {
            // Warm up the native session in the background
            launch(Dispatchers.IO) {
                val paths = getDictionaryPaths(context, activeProfile)
                sessionManager.warmUp(paths)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                searchJob?.cancel()
                sessionManager.close()
                retainedWebView?.runCatching {
                    stopLoading()
                    destroy()
                }
                retainedWebView = null
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(all = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Search bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        if (autoKanaConversion && newValue.composition == null && activeProfile.languageCode == "ja" && newValue.text.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                            val (convertedText, newCursor) = KanaConverter.toKanaIME(
                                newValue.text, newValue.selection.start
                            )
                            if (convertedText != newValue.text) {
                                textFieldValue = newValue.copy(
                                    text = convertedText,
                                    selection = TextRange(newCursor),
                                )
                            } else {
                                textFieldValue = newValue
                            }
                        } else {
                            textFieldValue = newValue
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    label = { Text(stringResource(MR.strings.action_search)) },
                    placeholder = { Text(stringResource(MR.strings.action_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                textFieldValue = TextFieldValue("")
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Clear,
                                    contentDescription = stringResource(MR.strings.action_reset),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            val trimmed = query.trim()
                            if (trimmed.isNotEmpty()) {
                                saveDictionaryHistory(trimmed)
                                lookupStack.clear()
                                activeTabIndex = 0
                                stackLookup(trimmed)
                                focusManager.clearFocus()
                            }
                        },
                    ),
                )

                IconButton(
                    onClick = {
                        val trimmedQuery = query.trim()
                        if (trimmedQuery.isEmpty()) {
                            lookupStack.clear()
                            activeTabIndex = 0
                            errorMessage = null
                            hasSearched = true
                        } else {
                            saveDictionaryHistory(trimmedQuery)
                            lookupStack.clear()
                            activeTabIndex = 0
                            stackLookup(trimmedQuery)
                        }
                        focusManager.clearFocus()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(MR.strings.action_search),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Profile quick-switch
            val profiles = remember(rawProfiles) { profileStore.getProfiles() }
            if (profiles.size > 1) {
                var profileExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Profile: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = activeProfile.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { profileExpanded = true },
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = profileExpanded,
                        onDismissRequest = { profileExpanded = false },
                    ) {
                        profiles.forEach { profile ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        text = profile.name,
                                        fontWeight = if (profile.id == activeProfile.id) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    if (profile.id != activeProfile.id) {
                                        profileStore.setActiveProfile(profile.id)
                                    }
                                    profileExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // Search history chips
            if (searchHistoryEnabled && searchHistoryList.isNotEmpty()) {
                SearchHistoryRow(
                    historyList = searchHistoryList,
                    onSelectQuery = { selectedQuery ->
                        saveDictionaryHistory(selectedQuery)
                        textFieldValue = TextFieldValue(
                            text = selectedQuery,
                            selection = TextRange(selectedQuery.length),
                        )
                        lookupStack.clear()
                        activeTabIndex = 0
                        stackLookup(selectedQuery)
                        focusManager.clearFocus()
                    },
                    onDeleteQuery = { queryToDelete ->
                        if (searchHistoryEnabled) {
                            scope.launch {
                                deleteSearchHistory.await(SearchHistory.SCOPE_DICTIONARY, queryToDelete)
                            }
                        }
                    },
                    onClearAll = {
                        if (searchHistoryEnabled) {
                            scope.launch {
                                deleteSearchHistory.clearScope(SearchHistory.SCOPE_DICTIONARY)
                            }
                        }
                    },
                )
            }

            // Status / body
            when {
                isLoading -> Text(text = "Searching...")
                errorMessage != null -> Text(errorMessage.orEmpty())
                results.isEmpty() && hasSearched && entryJsons == null -> Text(stringResource(MR.strings.no_results_found))
            }

            if (shouldMountWebView) {
                val renderer by dictionaryPreferences.renderer().collectAsState()
                when (renderer) {
                    DictionaryPreferences.RENDERER_COMPOSE -> DictionaryEntryCompose(
                        results = results,
                        styles = styles,
                        mediaDataUris = mediaDataUris,
                        placeholder = if (hasSearched) stringResource(MR.strings.no_results_found) else "Search to view dictionary entries",
                        showFrequencyHarmonic = showFreqHarmonic,
                        showFrequencyAverage = showFreqAverage,
                        groupTerms = groupTerms,
                        showPitchDiagram = showPitchDiagram,
                        showPitchNumber = showPitchNumber,
                        showPitchText = showPitchText,
                        activeProfile = activeProfile,
                        existingExpressions = existingExpressions,
                        entryJsons = entryJsons,
                        customCss = customCss,
                        wordAudioEnabled = wordAudioEnabled,
                        groupPitches = groupPitches,
                        onAnkiLookup = onAnkiLookup,
                        onRecursiveLookup = { word, _, _, _, _, type -> stackLookup(word, type) },
                        onTabSelect = { idx -> if (idx in lookupStack.indices) activeTabIndex = idx },
                        onBack = { if (activeTabIndex > 0) activeTabIndex-- },
                        isLoading = isLoading,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    DictionaryPreferences.RENDERER_PLAIN_TEXT -> DictionaryEntryPlainTextCompose(
                        results = results,
                        styles = styles,
                        mediaDataUris = mediaDataUris,
                        placeholder = if (hasSearched) stringResource(MR.strings.no_results_found) else "Search to view dictionary entries",
                        showFrequencyHarmonic = showFreqHarmonic,
                        showFrequencyAverage = showFreqAverage,
                        groupTerms = groupTerms,
                        showPitchDiagram = showPitchDiagram,
                        showPitchNumber = showPitchNumber,
                        showPitchText = showPitchText,
                        activeProfile = activeProfile,
                        existingExpressions = existingExpressions,
                        entryJsons = entryJsons,
                        groupPitches = groupPitches,
                        onAnkiLookup = onAnkiLookup,
                        onRecursiveLookup = { word, _, _, _, _, type -> stackLookup(word, type) },
                        onTabSelect = { idx -> if (idx in lookupStack.indices) activeTabIndex = idx },
                        onBack = { if (activeTabIndex > 0) activeTabIndex-- },
                        isLoading = isLoading,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    else -> DictionaryEntryWebView(
                    results = results,
                    styles = styles,
                    mediaDataUris = mediaDataUris,
                    placeholder = if (hasSearched) {
                        stringResource(MR.strings.no_results_found)
                    } else {
                        "Search to view dictionary entries"
                    },
                    showFrequencyHarmonic = showFreqHarmonic,
                    showFrequencyAverage = showFreqAverage,
                    groupTerms = groupTerms,
                    showPitchDiagram = showPitchDiagram,
                    showPitchNumber = showPitchNumber,
                    showPitchText = showPitchText,
                    activeProfile = activeProfile,
                    existingExpressions = existingExpressions,
                    tabs = buildTabs(),
                    recursiveNavMode = recursiveNavMode,
                    fontSize = popupFontSizePref,
                    customCss = customCss,
                    wordAudioEnabled = wordAudioEnabled,
                    entryJsons = entryJsons,
                    webViewProvider = { context ->
                        retainedWebView ?: WebView(context).also { retainedWebView = it }
                    },
                    onAnkiLookup = onAnkiLookup,
                    onRecursiveLookup = { word, _, _, _, _, type -> stackLookup(word, type) },
                    onTabSelect = { idx ->
                        if (idx in lookupStack.indices) activeTabIndex = idx
                    },
                    onBack = {
                        if (activeTabIndex > 0) activeTabIndex--
                    },
                    groupPitches = groupPitches,
                    forceDefaultTheme = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {}
            }
        }
    }

    // -------------------------------------------------------------------------

    private suspend fun performLookup(
        query: String,
        context: android.content.Context,
        activeProfile: chimahon.anki.AnkiProfile,
        sessionManager: DictionarySessionManager,
    ): LookupUiResult {
        return withContext(Dispatchers.IO) {
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir == null) {
                return@withContext LookupUiResult(
                    results = emptyList(),
                    styles = emptyList(),
                    mediaDataUris = emptyMap(),
                    error = "Unable to access app storage.",
                    diagnostics = null,
                    debugDumpDir = null,
                )
            }

            val dictionaryPaths = getDictionaryPaths(context, activeProfile)

            if (dictionaryPaths.termPaths.isEmpty() && dictionaryPaths.freqPaths.isEmpty() && dictionaryPaths.pitchPaths.isEmpty()) {
                return@withContext LookupUiResult(
                    results = emptyList(),
                    styles = emptyList(),
                    mediaDataUris = emptyMap(),
                    error = "No imported dictionaries found. Import one in Settings > Dictionary.",
                    diagnostics = null,
                    debugDumpDir = null,
                )
            }

            val lookupResult = try {
                sessionManager.lookup(
                    query = query,
                    paths = dictionaryPaths,
                    languageCode = activeProfile.languageCode,
                )
            } catch (e: Throwable) {
                LookupUiResult(
                    results = emptyList(),
                    styles = emptyList(),
                    mediaDataUris = emptyMap(),
                    error = e.message ?: "Dictionary lookup failed",
                    diagnostics = null,
                    debugDumpDir = null,
                )
            }
            lookupResult.copy(
                results = orderLookupResultsForDisplay(lookupResult.results, activeProfile, context),
            )
        }
    }

    private class DictionarySessionManager {
        private companion object {
            private const val MAX_PRELOADED_MEDIA_ITEMS = 20
            private const val MAX_PRELOADED_MEDIA_BYTES = 256 * 1024
        }

        private var session: Long? = null
        private var configuredPaths: chimahon.DictionaryPaths = chimahon.DictionaryPaths()
        private var cachedStyles: List<DictionaryStyle> = emptyList()

        @Synchronized
        fun warmUp(paths: chimahon.DictionaryPaths) {
            val activeSession = session ?: HoshiDicts.createLookupObject().also { session = it }
            if (paths != configuredPaths) {
                HoshiDicts.rebuildQuery(
                    session = activeSession,
                    termPaths = paths.termPaths.toTypedArray(),
                    freqPaths = paths.freqPaths.toTypedArray(),
                    pitchPaths = paths.pitchPaths.toTypedArray(),
                    kanjiPaths = paths.kanjiPaths.toTypedArray(),
                )
                cachedStyles = HoshiDicts.getStyles(activeSession).toList()
                configuredPaths = paths
            }
        }

        @Synchronized
        fun queryKanji(char: String): chimahon.KanjiResult? {
            val activeSession = session ?: HoshiDicts.createLookupObject().also { session = it }
            return try {
                HoshiDicts.queryKanji(activeSession, char)
            } catch (_: Exception) { null }
        }

        @Synchronized
        fun lookup(query: String, paths: chimahon.DictionaryPaths, languageCode: String = ""): LookupUiResult {
            val t0 = SystemClock.elapsedRealtime()
            val ramBefore = currentUsedRamMb()
            var sessionCreateMs = 0L
            var rebuildMs = 0L

            val hadSession = session != null
            val activeSession = session ?: HoshiDicts.createLookupObject().also { session = it }
            if (!hadSession) {
                sessionCreateMs = SystemClock.elapsedRealtime() - t0
            }

            if (paths != configuredPaths) {
                val rebuildStart = SystemClock.elapsedRealtime()
                warmUp(paths)
                rebuildMs = SystemClock.elapsedRealtime() - rebuildStart
            }

            val lookupStart = SystemClock.elapsedRealtime()
            val effectiveLang = languageCode.lowercase()
            val genericDeinflector = chimahon.dictionary.DeinflectorRegistry.get(effectiveLang)
            val results = if (effectiveLang == "ja") {
                HoshiDicts.lookup(activeSession, query, 50, 25).toList()
            } else if (genericDeinflector != null) {
                val preprocessed = genericDeinflector.preProcess(query)
                val deinflected = preprocessed.flatMap { genericDeinflector.deinflect(it, effectiveLang) }
                val candidates = deinflected.map { it.text }.distinct()
                if (candidates.isEmpty()) {
                    emptyList()
                } else {
                    candidates.flatMap { candidate ->
                        HoshiDicts.lookup(activeSession, candidate, 50, 25).toList()
                    }.distinctBy { it.term.expression to it.term.reading }.take(50)
                }
            } else {
                HoshiDicts.lookup(activeSession, query, 50, 25).toList()
            }
            val lookupMs = SystemClock.elapsedRealtime() - lookupStart

            val totalMs = SystemClock.elapsedRealtime() - t0
            val cssBytes = cachedStyles.sumOf { it.styles.length }
            val jsonGlossaryCount = countJsonGlossaries(results)
            val diagnostics = LookupDiagnostics(
                totalMs = totalMs,
                sessionCreateMs = sessionCreateMs,
                rebuildMs = rebuildMs,
                lookupMs = lookupMs,
                mediaMs = 0L,
                ramBeforeMb = ramBefore,
                ramAfterMb = currentUsedRamMb(),
                resultCount = results.size,
                jsonGlossaryCount = jsonGlossaryCount,
                cssBytes = cssBytes,
            )

            return LookupUiResult(
                results = results,
                styles = cachedStyles,
                mediaDataUris = emptyMap(),
                error = null,
                diagnostics = diagnostics,
                debugDumpDir = null,
            )
        }

        @Synchronized
        fun loadMediaDataUris(results: List<LookupResult>): Map<String, String> {
            val activeSession = session ?: return emptyMap()
            return buildMediaDataUris(activeSession, results)
        }

        private fun countJsonGlossaries(results: List<LookupResult>): Int {
            var count = 0
            results.forEach { lookup ->
                lookup.term.glossaries.forEach { glossary ->
                    val text = glossary.glossary.trim()
                    if (text.startsWith("{") || text.startsWith("[")) {
                        count++
                    }
                }
            }
            return count
        }

        private fun buildMediaDataUris(activeSession: Long, results: List<LookupResult>): Map<String, String> {
            val requested = linkedSetOf<Pair<String, String>>()

            results.forEach { lookup ->
                lookup.term.glossaries.forEach { glossary ->
                    extractImagePaths(glossary.glossary)
                        .forEach { path -> requested += glossary.dictName to path }
                }
            }

            val limitedRequested = requested.take(MAX_PRELOADED_MEDIA_ITEMS)
            val out = LinkedHashMap<String, String>(limitedRequested.size)
            limitedRequested.forEach { (dictName, path) ->
                val bytes = HoshiDicts.getMediaFile(activeSession, dictName, path) ?: return@forEach
                if (bytes.size > MAX_PRELOADED_MEDIA_BYTES) return@forEach

                val mime = guessMimeType(path)
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUri = "data:$mime;base64,$b64"
                for (candidate in normalizeMediaPathCandidates(path)) {
                    out[mediaKey(dictName, candidate)] = dataUri
                }
            }

            return out
        }

        private fun extractImagePaths(glossary: String): Set<String> {
            val text = glossary.trim()
            if (text.isEmpty() || !(text.startsWith("{") || text.startsWith("["))) return emptySet()
            if (!text.contains("\"img\"") && !text.contains("\"image\"")) return emptySet()

            return runCatching {
                val root: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
                val found = linkedSetOf<String>()
                walkJson(root) { node ->
                    val isImageNode = node.optString("tag") == "img" || node.optString("type") == "image"
                    if (isImageNode) {
                        val candidate = node.optString("path").ifBlank { node.optString("src") }
                        candidate
                            .takeIf { it.isNotBlank() }
                            ?.let(found::add)
                    }
                }
                found
            }.getOrDefault(emptySet())
        }

        private fun walkJson(element: Any?, onObject: (JSONObject) -> Unit) {
            when (element) {
                is JSONObject -> {
                    onObject(element)
                    val keys = element.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val next = element.opt(key)
                        if (next is JSONObject || next is JSONArray) {
                            walkJson(next, onObject)
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until element.length()) {
                        val next = element.opt(i)
                        if (next is JSONObject || next is JSONArray) {
                            walkJson(next, onObject)
                        }
                    }
                }
            }
        }

        private fun guessMimeType(path: String): String {
            return when (path.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "bmp" -> "image/bmp"
                else -> "image/png"
            }
        }

        private fun mediaKey(dictName: String, path: String): String = "$dictName\u0000$path"

        private fun normalizeMediaPathCandidates(rawPath: String): Set<String> {
            val candidates = linkedSetOf(
                rawPath,
                rawPath.removePrefix("./"),
                rawPath.removePrefix("/"),
                rawPath.replace('\\', '/'),
            )

            runCatching { URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()) }
                .getOrNull()
                ?.let {
                    candidates += it
                    candidates += it.removePrefix("./")
                    candidates += it.removePrefix("/")
                    candidates += it.replace('\\', '/')
                }

            return candidates
        }

        private fun currentUsedRamMb(): Long {
            val rt = Runtime.getRuntime()
            val usedBytes = rt.totalMemory() - rt.freeMemory()
            return usedBytes / (1024L * 1024L)
        }

        @Synchronized
        fun close() {
            session?.let(HoshiDicts::destroyLookupObject)
            session = null
        }
    }

    private data class LookupUiResult(
        val results: List<LookupResult>,
        val styles: List<DictionaryStyle>,
        val mediaDataUris: Map<String, String>,
        val error: String?,
        val diagnostics: LookupDiagnostics?,
        val debugDumpDir: String?,
    )

    private data class LookupDiagnostics(
        val totalMs: Long,
        val sessionCreateMs: Long,
        val rebuildMs: Long,
        val lookupMs: Long,
        val mediaMs: Long,
        val ramBeforeMb: Long,
        val ramAfterMb: Long,
        val resultCount: Int,
        val jsonGlossaryCount: Int,
        val cssBytes: Int,
    )
}
