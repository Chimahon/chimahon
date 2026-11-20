package chimahon.ai

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.MediaInfo
import chimahon.anki.AnkiCardCreator
import chimahon.anki.AnkiProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val collectionAnkiExportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private enum class TtsEngineState {
    INITIALIZING,
    READY,
    UNAVAILABLE,
}

@Composable
private fun rememberWordSpeaker(languageMode: String): (String) -> Unit {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    var engineState by remember { mutableStateOf(TtsEngineState.INITIALIZING) }
    val ttsHolder = remember { arrayOfNulls<TextToSpeech>(1) }

    DisposableEffect(appContext) {
        var engineRef: TextToSpeech? = null
        val engine = TextToSpeech(appContext) { status ->
            val hasEngine = runCatching { engineRef?.engines?.isNotEmpty() == true }.getOrDefault(false)
            engineState = if (status == TextToSpeech.SUCCESS && hasEngine) {
                TtsEngineState.READY
            } else {
                TtsEngineState.UNAVAILABLE
            }
        }
        engineRef = engine
        if (runCatching { engine.engines.isEmpty() }.getOrDefault(false)) {
            engineState = TtsEngineState.UNAVAILABLE
        }
        ttsHolder[0] = engine
        onDispose {
            runCatching {
                engine.stop()
                engine.shutdown()
            }
            ttsHolder[0] = null
            engineState = TtsEngineState.INITIALIZING
        }
    }

    return remember(appContext, languageMode) {
        { rawText: String ->
            val clean = rawText.trim()
            val engine = ttsHolder[0]
            if (clean.isEmpty()) {
                // No-op on blank text
            } else if (engineState == TtsEngineState.UNAVAILABLE) {
                Toast.makeText(context, "No text-to-speech engine available on this device", Toast.LENGTH_SHORT).show()
            } else if (engineState != TtsEngineState.READY || engine == null) {
                Toast.makeText(context, "Text-to-speech engine initializing...", Toast.LENGTH_SHORT).show()
            } else {
                val targetLocale = AiTtsLocaleHelper.resolveLocale(clean, languageMode)
                val langStatus = engine.setLanguage(targetLocale)
                if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(
                        context,
                        "TTS voice data not installed for ${targetLocale.displayLanguage}",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "chimahon_word_${System.nanoTime()}")
                }
            }
        }
    }
}

private data class LookupHistoryItem(
    val word: String,
    val sentence: String,
    val entry: CustomAiDictionaryEntry?,
)

@Composable
fun CustomAiPopup(
    initialWord: String,
    initialSentence: String,
    onDismiss: () -> Unit,
    visible: Boolean = true,
    charOffset: Int = -1,
    matchedTerm: String? = null,
    activeProfile: AnkiProfile? = null,
    mediaInfo: MediaInfo? = null,
    mediaType: String = "manga",
    titleId: String? = null,
    onCaptureScreen: (suspend () -> Bitmap?)? = null,
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onTextInputActiveChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiPrefs = remember(context) { AiPreferences(context) }
    val currentOnDrag by rememberUpdatedState(onDrag)

    val resolvedInitialWord = remember(initialWord, initialSentence, charOffset, matchedTerm) {
        CustomAiDictionaryEngine.extractTargetWord(
            rawLookup = initialWord,
            sentence = initialSentence,
            charOffset = charOffset,
            matchedTerm = matchedTerm,
        )
    }
    var word by remember(resolvedInitialWord, initialSentence) { mutableStateOf(resolvedInitialWord) }
    var sentence by remember(resolvedInitialWord, initialSentence) { mutableStateOf(initialSentence) }
    var hasApiKey by remember { mutableStateOf(aiPrefs.groqApiKey.isNotBlank()) }
    val popupDesign = remember(aiPrefs.popupDesign) { aiPrefs.popupDesign }
    val speakWord = rememberWordSpeaker(languageMode = aiPrefs.languageMode)

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<CustomAiDictionaryEntry?>(null) }
    var cachedEntryToShow by remember { mutableStateOf<CustomAiDictionaryEntry?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var lookupHistory by remember(initialWord, initialSentence) {
        mutableStateOf<List<LookupHistoryItem>>(emptyList())
    }

    // Collection & Save State (only the active collection shard is loaded into memory)
    var showCollectionsView by remember { mutableStateOf(false) }
    var collectionIndex by remember { mutableStateOf<List<WordCollectionMeta>>(emptyList()) }
    var selectedCollectionId by remember { mutableStateOf<String?>(null) }
    var activeCollection by remember { mutableStateOf<WordCollection?>(null) }
    var isCreatingCollection by remember { mutableStateOf(false) }
    var isDropdownSearchActive by remember { mutableStateOf(false) }
    var isExportingCollectionToAnki by remember { mutableStateOf(false) }

    // Image Viewer / Picker & Two-Stage Attachment State
    var showImagePanel by remember { mutableStateOf(false) }
    var isImagePickerMode by remember { mutableStateOf(false) }
    var stagedImage by remember { mutableStateOf<StagedAiImage?>(null) }
    var inspectedSavedItem by remember { mutableStateOf<Pair<String, SavedWordItem>?>(null) }
    var persistedAttachment by remember { mutableStateOf<PersistedWordAttachment?>(null) }
    var cachedImageSearchQuery by remember { mutableStateOf<String?>(null) }
    var cachedImageSearchResults by remember { mutableStateOf<List<AiWebImageItem>>(emptyList()) }

    fun drillDownLookup(nextWord: String, nextSentence: String = sentence) {
        val cleanNext = nextWord.trim()
        if (cleanNext.isEmpty() || cleanNext.equals(word.trim(), ignoreCase = true)) return
        lookupHistory = lookupHistory + LookupHistoryItem(
            word = word,
            sentence = sentence,
            entry = entry,
        )
        inspectedSavedItem = null
        stagedImage = null
        persistedAttachment = null
        showImagePanel = false
        isImagePickerMode = false
        word = cleanNext
        sentence = nextSentence
    }

    fun navigateBackInHistory() {
        val previous = lookupHistory.lastOrNull() ?: return
        lookupHistory = lookupHistory.dropLast(1)
        inspectedSavedItem = null
        stagedImage = null
        persistedAttachment = null
        showImagePanel = false
        isImagePickerMode = false
        word = previous.word
        sentence = previous.sentence
        cachedEntryToShow = previous.entry
        if (previous.entry == null) {
            retryTrigger++
        }
    }

    DisposableEffect(isCreatingCollection, isDropdownSearchActive, showCollectionsView, hasApiKey) {
        onTextInputActiveChange((showCollectionsView && (isCreatingCollection || isDropdownSearchActive)) || !hasApiKey)
        onDispose { onTextInputActiveChange(false) }
    }

    LaunchedEffect(visible) {
        if (!visible) {
            stagedImage = null
            showImagePanel = false
            isImagePickerMode = false
            showCollectionsView = false
            isCreatingCollection = false
            isDropdownSearchActive = false
        }
    }

    LaunchedEffect(Unit) {
        val index = WordCollectionRepository.getCollectionIndex(context)
        collectionIndex = index
        val targetId = selectedCollectionId?.takeIf { id -> index.any { it.id == id } } ?: index.firstOrNull()?.id
        selectedCollectionId = targetId
        activeCollection = targetId?.let { WordCollectionRepository.getCollection(context, it) }
    }

    // Keep persistedAttachment synchronized when viewing an already-saved word
    val matchedSavedTarget: Pair<String, SavedWordItem>? = remember(
        inspectedSavedItem,
        activeCollection,
        word,
        sentence,
    ) {
        val cleanW = word.trim()
        val cleanS = sentence.trim()
        val explicit = inspectedSavedItem
        if (explicit != null &&
            explicit.second.word.equals(cleanW, ignoreCase = true) &&
            explicit.second.sentence.equals(cleanS, ignoreCase = true)
        ) {
            explicit
        } else {
            val col = activeCollection
            val found = col?.items?.firstOrNull {
                it.word.equals(cleanW, ignoreCase = true) && it.sentence.equals(cleanS, ignoreCase = true)
            }
            if (col != null && found != null) col.id to found else null
        }
    }

    LaunchedEffect(matchedSavedTarget?.first, matchedSavedTarget?.second?.id, matchedSavedTarget?.second?.imageFileName) {
        val pair = matchedSavedTarget
        val item = pair?.second
        val fileName = item?.imageFileName
        if (pair != null && item != null && !fileName.isNullOrBlank()) {
            val file = WordCollectionRepository.getWordImageFile(context, fileName)
            val bmp = AiImageManager.loadBitmapFromFile(file)
            persistedAttachment = PersistedWordAttachment(
                collectionId = pair.first,
                itemId = item.id,
                imageFileName = fileName,
                source = StagedImageSource.fromStorageValue(item.imageSource),
                previewBitmap = bmp,
            )
        } else {
            persistedAttachment = null
        }
    }

    LaunchedEffect(word, sentence, retryTrigger, hasApiKey) {
        showCollectionsView = false
        showImagePanel = false
        isImagePickerMode = false
        isCreatingCollection = false
        errorMessage = null

        val cached = cachedEntryToShow
        if (cached != null) {
            cachedEntryToShow = null
            entry = cached
            isLoading = false
            return@LaunchedEffect
        }

        if (!hasApiKey) {
            isLoading = false
            entry = null
            return@LaunchedEffect
        }

        isLoading = true
        val result = CustomAiDictionaryEngine.lookup(
            context = context,
            word = word,
            sentence = sentence,
        )
        result.fold(
            onSuccess = { data ->
                entry = data
                isLoading = false
            },
            onFailure = { err ->
                errorMessage = err.message ?: "Failed to fetch AI breakdown."
                isLoading = false
            },
        )
    }

    val cleanCurrentWord = word.trim()
    val isWordSavedAnywhere = remember(collectionIndex, activeCollection, cleanCurrentWord) {
        val lower = cleanCurrentWord.lowercase()
        lower.isNotEmpty() && (
            collectionIndex.any { lower in it.wordsLowercase } ||
                activeCollection?.items?.any { it.word.equals(cleanCurrentWord, ignoreCase = true) } == true
            )
    }
    val hasActiveImageAttachment = stagedImage != null || persistedAttachment != null

    val isPaperVermilion = popupDesign == AiPreferences.DESIGN_NEO_BENTO
    val isCosmicVioletActive = popupDesign == AiPreferences.DESIGN_COSMIC_VIOLET &&
        !showCollectionsView &&
        !showImagePanel &&
        hasApiKey &&
        errorMessage == null
    val hasSelfContainedChrome = showCollectionsView || showImagePanel || isCosmicVioletActive
    val containerBgColor = when {
        popupDesign == AiPreferences.DESIGN_COSMIC_VIOLET && (isCosmicVioletActive || showCollectionsView || showImagePanel) -> Color(0xFF0B0A14)
        popupDesign == AiPreferences.DESIGN_GLASS_INDIGO && (showCollectionsView || showImagePanel) -> Color(0xFF0E101A)
        isPaperVermilion -> Color(0xFFFAF7F2)
        else -> MaterialTheme.colorScheme.surface
    }

    fun openImageViewer() {
        showCollectionsView = false
        isCreatingCollection = false
        isDropdownSearchActive = false
        isImagePickerMode = false
        showImagePanel = true
    }

    fun clearAttachedImage() {
        if (stagedImage != null) {
            stagedImage = null
            Toast.makeText(context, "Staged image cleared", Toast.LENGTH_SHORT).show()
        }
        val persisted = persistedAttachment
        if (persisted != null) {
            scope.launch {
                val updatedShard = WordCollectionRepository.clearWordImage(
                    context = context,
                    collectionId = persisted.collectionId,
                    itemId = persisted.itemId,
                )
                if (selectedCollectionId == persisted.collectionId) {
                    activeCollection = updatedShard
                }
                inspectedSavedItem?.let { (colId, item) ->
                    if (colId == persisted.collectionId && item.id == persisted.itemId) {
                        inspectedSavedItem = colId to item.copy(imageFileName = null, imageSource = null)
                    }
                }
                persistedAttachment = null
                Toast.makeText(context, "Attached image removed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(containerBgColor)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.x, dragAmount.y)
                }
            },
    ) {
        if (!hasSelfContainedChrome) {
            // 1. Sticky Compact Header
            PopupHeaderBar(
                word = word,
                reading = entry?.reading.orEmpty(),
                isSaved = isWordSavedAnywhere,
                hasAttachedImage = hasActiveImageAttachment,
                isCollectionsView = showCollectionsView,
                canGoBack = lookupHistory.isNotEmpty(),
                isPaperVermilion = isPaperVermilion,
                onSpeakWord = { speakWord(entry?.word ?: word) },
                onNavigateBack = { navigateBackInHistory() },
                onOpenImages = { openImageViewer() },
                onToggleCollections = {
                    scope.launch {
                        val refreshedIndex = WordCollectionRepository.getCollectionIndex(context)
                        collectionIndex = refreshedIndex
                        val targetId = selectedCollectionId?.takeIf { id -> refreshedIndex.any { it.id == id } }
                            ?: refreshedIndex.firstOrNull()?.id
                        selectedCollectionId = targetId
                        activeCollection = targetId?.let { WordCollectionRepository.getCollection(context, it) }
                        showCollectionsView = !showCollectionsView
                        showImagePanel = false
                        isImagePickerMode = false
                        if (!showCollectionsView) {
                            isCreatingCollection = false
                            isDropdownSearchActive = false
                        }
                    }
                },
                onDismiss = onDismiss,
            )

            HorizontalDivider(
                thickness = if (isPaperVermilion) 1.dp else 0.5.dp,
                color = if (isPaperVermilion) Color(0xFFD8CFC2) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }

        // Compact Staged/Attached Image Preview Chip (shown when an image is attached on the main breakdown)
        if (!showCollectionsView && !showImagePanel && hasActiveImageAttachment) {
            StagedAttachmentPreviewChip(
                popupDesign = popupDesign,
                stagedImage = stagedImage,
                persistedAttachment = persistedAttachment,
                onOpenPicker = {
                    isImagePickerMode = true
                    showImagePanel = true
                },
                onClear = { clearAttachedImage() },
            )
        }

        // 2. Body: Image Viewer/Picker, Collection Manager, Missing API Key, or Dictionary Breakdown
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (showImagePanel) {
                val activeQuery = (entry?.word ?: word).trim()
                val existingSource = stagedImage?.source ?: persistedAttachment?.source
                AiImageViewerPickerBody(
                    popupDesign = popupDesign,
                    queryWord = activeQuery,
                    isPickerMode = isImagePickerMode,
                    existingAttachmentSource = existingSource,
                    cachedQuery = cachedImageSearchQuery,
                    cachedResults = cachedImageSearchResults,
                    onCacheUpdated = { q, items ->
                        cachedImageSearchQuery = q
                        cachedImageSearchResults = items
                    },
                    onCaptureScreen = onCaptureScreen,
                    onEnterPickerMode = { isImagePickerMode = true },
                    onCancelOrBack = {
                        if (isImagePickerMode) {
                            isImagePickerMode = false
                        } else {
                            showImagePanel = false
                        }
                    },
                    onDismiss = onDismiss,
                    onCommitPickedImage = { picked ->
                        val savedTarget = matchedSavedTarget
                        if (savedTarget != null) {
                            scope.launch {
                                val updatedShard = WordCollectionRepository.updateWordImage(
                                    context = context,
                                    collectionId = savedTarget.first,
                                    itemId = savedTarget.second.id,
                                    jpegBytes = picked.jpegBytes,
                                    imageSource = picked.source.storageValue,
                                )
                                if (updatedShard != null) {
                                    if (selectedCollectionId == savedTarget.first) {
                                        activeCollection = updatedShard
                                    }
                                    val updatedItem = updatedShard.items.firstOrNull { it.id == savedTarget.second.id }
                                    if (updatedItem != null) {
                                        inspectedSavedItem = savedTarget.first to updatedItem
                                    }
                                    persistedAttachment = PersistedWordAttachment(
                                        collectionId = savedTarget.first,
                                        itemId = savedTarget.second.id,
                                        imageFileName = WordCollectionRepository.deterministicImageFileName(savedTarget.second.id),
                                        source = picked.source,
                                        previewBitmap = picked.previewBitmap,
                                    )
                                    stagedImage = null
                                    Toast.makeText(
                                        context,
                                        if (existingSource != null) "Attached image updated" else "Image attached to saved word",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                isImagePickerMode = false
                                showImagePanel = false
                            }
                        } else {
                            stagedImage = picked
                            isImagePickerMode = false
                            showImagePanel = false
                            Toast.makeText(
                                context,
                                "Image staged — tap Save to Collection to keep",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            } else if (showCollectionsView) {
                CollectionsManagerBody(
                    popupDesign = popupDesign,
                    currentWord = cleanCurrentWord,
                    currentSentence = sentence.trim(),
                    currentEntry = entry,
                    collectionIndex = collectionIndex,
                    activeCollection = activeCollection,
                    isCreatingCollection = isCreatingCollection,
                    onCreatingCollectionChange = { isCreatingCollection = it },
                    onDropdownSearchActiveChange = { isDropdownSearchActive = it },
                    onGoBack = {
                        showCollectionsView = false
                        isCreatingCollection = false
                        isDropdownSearchActive = false
                    },
                    onDismiss = onDismiss,
                    onSelectCollection = { id ->
                        selectedCollectionId = id
                        scope.launch {
                            activeCollection = WordCollectionRepository.getCollection(context, id)
                        }
                    },
                    onSelectWord = { savedItem ->
                        val colId = activeCollection?.id
                        inspectedSavedItem = if (colId != null) colId to savedItem else null
                        stagedImage = null
                        word = savedItem.word
                        sentence = savedItem.sentence
                        cachedEntryToShow = savedItem.entry
                        if (savedItem.entry == null) {
                            retryTrigger++
                        }
                        showCollectionsView = false
                        isDropdownSearchActive = false
                    },
                    onSaveToCollection = { collectionId ->
                        scope.launch {
                            val pending = stagedImage
                            val res = WordCollectionRepository.saveWordToCollection(
                                context = context,
                                collectionId = collectionId,
                                word = cleanCurrentWord,
                                sentence = sentence,
                                entry = entry,
                                stagedImageBytes = pending?.jpegBytes,
                                stagedImageSource = pending?.source?.storageValue,
                            )
                            res.fold(
                                onSuccess = { updatedShard ->
                                    selectedCollectionId = collectionId
                                    activeCollection = updatedShard
                                    collectionIndex = WordCollectionRepository.getCollectionIndex(context)
                                    val savedItem = updatedShard.items.firstOrNull {
                                        it.word.equals(cleanCurrentWord, ignoreCase = true) &&
                                            it.sentence.equals(sentence.trim(), ignoreCase = true)
                                    }
                                    if (savedItem != null) {
                                        inspectedSavedItem = collectionId to savedItem
                                    }
                                    if (pending != null) {
                                        stagedImage = null
                                    }
                                    Toast.makeText(context, "Saved to \"${updatedShard.name}\"", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { err ->
                                    Toast.makeText(context, err.message ?: "Could not save word", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    onCreateCollection = { name ->
                        scope.launch {
                            val res = WordCollectionRepository.createCollection(context, name)
                            res.fold(
                                onSuccess = { created ->
                                    collectionIndex = WordCollectionRepository.getCollectionIndex(context)
                                    selectedCollectionId = created.id
                                    activeCollection = created
                                    isCreatingCollection = false
                                    Toast.makeText(context, "Created \"${created.name}\"", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { err ->
                                    Toast.makeText(context, err.message ?: "Could not create collection", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    onDeleteCollection = { collectionId ->
                        scope.launch {
                            val updatedIndex = WordCollectionRepository.deleteCollection(context, collectionId)
                            collectionIndex = updatedIndex
                            if (inspectedSavedItem?.first == collectionId) {
                                inspectedSavedItem = null
                                persistedAttachment = null
                            }
                            val nextId = if (selectedCollectionId == collectionId) {
                                updatedIndex.firstOrNull()?.id
                            } else {
                                selectedCollectionId
                            }
                            selectedCollectionId = nextId
                            activeCollection = nextId?.let { WordCollectionRepository.getCollection(context, it) }
                            Toast.makeText(context, "Collection deleted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    isExportingToAnki = isExportingCollectionToAnki,
                    onRemoveWord = { collectionId, itemId ->
                        scope.launch {
                            val updatedShard = WordCollectionRepository.removeWordFromCollection(
                                context = context,
                                collectionId = collectionId,
                                itemId = itemId,
                            )
                            if (inspectedSavedItem?.second?.id == itemId) {
                                inspectedSavedItem = null
                                persistedAttachment = null
                            }
                            if (selectedCollectionId == collectionId) {
                                activeCollection = updatedShard
                            }
                            collectionIndex = WordCollectionRepository.getCollectionIndex(context)
                            Toast.makeText(context, "Removed saved word", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClearCollection = { collectionId ->
                        scope.launch {
                            val updatedShard = WordCollectionRepository.clearCollection(
                                context = context,
                                collectionId = collectionId,
                            )
                            if (inspectedSavedItem?.first == collectionId) {
                                inspectedSavedItem = null
                                persistedAttachment = null
                            }
                            if (selectedCollectionId == collectionId) {
                                activeCollection = updatedShard
                            }
                            collectionIndex = WordCollectionRepository.getCollectionIndex(context)
                            Toast.makeText(context, "Collection cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onExportCollectionToAnki = { collection ->
                        if (isExportingCollectionToAnki || collection.items.isEmpty()) return@CollectionsManagerBody
                        isExportingCollectionToAnki = true
                        Toast.makeText(
                            context,
                            "Exporting ${collection.items.size} words from \"${collection.name}\" to Anki...",
                            Toast.LENGTH_SHORT,
                        ).show()
                        collectionAnkiExportScope.launch {
                            try {
                                val res = AnkiCardCreator.addCollectionToAnki(
                                    context = context,
                                    items = collection.items,
                                    deck = activeProfile?.ankiDeck.orEmpty(),
                                    model = activeProfile?.ankiModel.orEmpty(),
                                    fieldMapJson = activeProfile?.ankiFieldMap.orEmpty(),
                                    tags = activeProfile?.ankiTags.orEmpty(),
                                    dupCheck = activeProfile?.ankiDupCheck ?: true,
                                    dupScope = activeProfile?.ankiDupScope ?: "collection",
                                    dupAction = activeProfile?.ankiDupAction ?: "prevent",
                                    media = mediaInfo,
                                    type = mediaType,
                                    syncOnCreate = activeProfile?.ankiSyncOnCreate ?: false,
                                    profileId = activeProfile?.id.orEmpty(),
                                    titleId = titleId,
                                )
                                if (res.exportedItemIds.isNotEmpty()) {
                                    if (inspectedSavedItem?.second?.id in res.exportedItemIds) {
                                        inspectedSavedItem = null
                                        persistedAttachment = null
                                    }
                                    val updatedShard = WordCollectionRepository.removeWordsFromCollection(
                                        context = context,
                                        collectionId = collection.id,
                                        itemIds = res.exportedItemIds,
                                    )
                                    if (selectedCollectionId == collection.id) {
                                        activeCollection = updatedShard
                                    }
                                    collectionIndex = WordCollectionRepository.getCollectionIndex(context)
                                }
                                val msg = when {
                                    res.permissionDenied -> "AnkiDroid permission denied. Grant permission in Anki settings."
                                    res.notConfigured -> "Anki deck or note type is not configured."
                                    res.added == 0 && res.updated == 0 && res.failed > 0 ->
                                        "Anki export failed: ${res.firstErrorMessage ?: "Unknown error"}"
                                    else -> buildString {
                                        append("Anki export complete: ${res.added} added")
                                        if (res.updated > 0) append(", ${res.updated} updated")
                                        if (res.skippedDuplicates > 0) append(", ${res.skippedDuplicates} skipped")
                                        if (res.failed > 0) append(", ${res.failed} failed")
                                    }
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            } finally {
                                isExportingCollectionToAnki = false
                            }
                        }
                    },
                )
            } else {
                when {
                    !hasApiKey -> {
                        MissingApiKeyBody(
                            isPaperVermilion = isPaperVermilion,
                            onSaveApiKey = { key ->
                                aiPrefs.groqApiKey = key
                                hasApiKey = aiPrefs.groqApiKey.isNotBlank()
                                retryTrigger++
                            },
                        )
                    }
                    errorMessage != null -> {
                        ErrorStateBody(
                            message = errorMessage!!,
                            isPaperVermilion = isPaperVermilion,
                            onRetry = { retryTrigger++ },
                        )
                    }
                    isLoading || entry != null -> {
                        DictionaryEntryBody(
                            word = word,
                            entry = if (isLoading) null else entry,
                            isLoading = isLoading,
                            sentence = sentence,
                            popupDesign = popupDesign,
                            isSaved = isWordSavedAnywhere,
                            hasAttachedImage = hasActiveImageAttachment,
                            canGoBack = lookupHistory.isNotEmpty(),
                            onSpeakWord = { speakWord(entry?.word ?: word) },
                            onNavigateBack = { navigateBackInHistory() },
                            onRefresh = { retryTrigger++ },
                            onDismiss = onDismiss,
                            onOpenImages = { openImageViewer() },
                            onOpenCollections = {
                                scope.launch {
                                    val refreshedIndex = WordCollectionRepository.getCollectionIndex(context)
                                    collectionIndex = refreshedIndex
                                    val targetId = selectedCollectionId?.takeIf { id -> refreshedIndex.any { it.id == id } }
                                        ?: refreshedIndex.firstOrNull()?.id
                                    selectedCollectionId = targetId
                                    activeCollection = targetId?.let { WordCollectionRepository.getCollection(context, it) }
                                    showCollectionsView = true
                                }
                            },
                            onDrillDown = { target -> drillDownLookup(target) },
                        )
                    }
                }
            }
        }

        if (!hasSelfContainedChrome) {
            // 3. Reserved Footer Slot
            HorizontalDivider(
                thickness = if (isPaperVermilion) 1.dp else 0.5.dp,
                color = if (isPaperVermilion) Color(0xFFD8CFC2) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isPaperVermilion) Color(0xFFF2ECE1) else MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "AI Dictionary • Context Mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPaperVermilion) Color(0xFF6B6257) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (isPaperVermilion) "FOLIO" else "BETA",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isPaperVermilion) Color(0xFFC84B31) else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PopupHeaderBar(
    word: String,
    reading: String,
    isSaved: Boolean,
    hasAttachedImage: Boolean,
    isCollectionsView: Boolean,
    canGoBack: Boolean,
    isPaperVermilion: Boolean = false,
    onSpeakWord: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenImages: () -> Unit,
    onToggleCollections: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primaryInk = if (isPaperVermilion) Color(0xFF1E1B18) else MaterialTheme.colorScheme.onSurface
    val secondaryInk = if (isPaperVermilion) Color(0xFF6B6257) else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = if (isPaperVermilion) Color(0xFFC84B31) else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isCollectionsView || canGoBack) 6.dp else 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCollectionsView) {
            IconButton(
                onClick = onToggleCollections,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back to definition",
                    tint = primaryInk,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Save & Collections",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                ),
                color = primaryInk,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            if (canGoBack) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Previous word",
                        tint = accentColor,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = word.ifBlank { "Lookup" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = primaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (reading.isNotBlank() && reading != word) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isPaperVermilion) Color(0xFFF2ECE1) else MaterialTheme.colorScheme.secondaryContainer,
                            border = if (isPaperVermilion) BorderStroke(1.dp, Color(0xFFD8CFC2)) else null,
                        ) {
                            Text(
                                text = reading,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isPaperVermilion) Color(0xFF6B6257) else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (word.isNotBlank()) {
                        IconButton(
                            onClick = onSpeakWord,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VolumeUp,
                                contentDescription = "Pronounce word",
                                tint = accentColor,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }

            // Image Viewer / Picker entry directly beside Save to Collection
            IconButton(
                onClick = onOpenImages,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = "Images & Screenshot",
                    tint = if (hasAttachedImage) accentColor else secondaryInk,
                    modifier = Modifier.size(19.dp),
                )
            }

            IconButton(
                onClick = onToggleCollections,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkAdd,
                    contentDescription = "Save to collection",
                    tint = if (isSaved) accentColor else secondaryInk,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close popup",
                tint = secondaryInk,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun extractSynonymHead(raw: String): String =
    raw.substringBefore("(").substringBefore("（").trim()

@Composable
private fun DictionaryEntryBody(
    word: String,
    entry: CustomAiDictionaryEntry?,
    isLoading: Boolean,
    sentence: String,
    popupDesign: String,
    isSaved: Boolean,
    hasAttachedImage: Boolean,
    canGoBack: Boolean,
    onSpeakWord: () -> Unit,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenCollections: () -> Unit,
    onDrillDown: (String) -> Unit,
) {
    when (popupDesign) {
        AiPreferences.DESIGN_GLASS_INDIGO -> ObsidianIndigoGlassBody(
            word = word,
            entry = entry,
            isLoading = isLoading,
            sentence = sentence,
            onDrillDown = onDrillDown,
        )
        AiPreferences.DESIGN_NEO_BENTO -> PaperAndVermilionBody(
            word = word,
            entry = entry,
            isLoading = isLoading,
            sentence = sentence,
            onDrillDown = onDrillDown,
        )
        AiPreferences.DESIGN_COSMIC_VIOLET -> CosmicVioletBody(
            word = word,
            entry = entry,
            isLoading = isLoading,
            sentence = sentence,
            isSaved = isSaved,
            hasAttachedImage = hasAttachedImage,
            canGoBack = canGoBack,
            onSpeakWord = onSpeakWord,
            onNavigateBack = onNavigateBack,
            onRefresh = onRefresh,
            onDismiss = onDismiss,
            onOpenImages = onOpenImages,
            onOpenCollections = onOpenCollections,
            onDrillDown = onDrillDown,
        )
        else -> ClassicCardsBody(
            word = word,
            entry = entry,
            isLoading = isLoading,
            sentence = sentence,
            onDrillDown = onDrillDown,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClassicCardsBody(
    word: String,
    entry: CustomAiDictionaryEntry?,
    isLoading: Boolean,
    sentence: String,
    onDrillDown: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sentence.isNotBlank()) {
            item { ContextSentenceBanner(sentence = sentence.trim(), targetWord = (entry?.word ?: word).trim()) }
        }
        item {
            SectionCard(title = "MEANING IN CONTEXT", icon = Icons.Rounded.MenuBook, isPrimary = true) {
                if (isLoading || entry == null) {
                    Text(
                        text = "Analyzing...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (entry.partOfSpeech.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            Text(
                                text = entry.partOfSpeech,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = entry.contextualMeaning,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (entry.literalMeaning.isNotBlank() && !entry.literalMeaning.equals(entry.contextualMeaning, ignoreCase = true)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Root / Literal: ${entry.literalMeaning}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SectionCard(title = "AI NUANCE & REGISTER", icon = Icons.Rounded.Tune) {
                if (isLoading || entry == null) {
                    Text(
                        text = "Analyzing...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NuanceChip(label = "Formality", value = entry.nuance.formality)
                        NuanceChip(label = "Frequency", value = entry.nuance.frequency)
                    }
                    if (entry.nuance.dailyConversation.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = entry.nuance.dailyConversation,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        if (isLoading || entry == null || entry.alternativeWords.isNotEmpty()) {
            item {
                SectionCard(title = "ALTERNATIVE WORDS", icon = Icons.Rounded.SwapHoriz) {
                    if (isLoading || entry == null) {
                        Text(
                            text = "Analyzing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            entry.alternativeWords.forEach { alt ->
                                val cleanSynonym = extractSynonymHead(alt)
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = cleanSynonym.isNotEmpty()) { onDrillDown(cleanSynonym) }
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                ) {
                                    Text("•", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                    Text(alt, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (isLoading || entry == null || entry.sentenceBreakdown.isNotEmpty()) {
            item {
                SectionCard(title = "SENTENCE BREAKDOWN", icon = Icons.Rounded.Notes) {
                    if (isLoading || entry == null) {
                        Text(
                            text = "Analyzing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            entry.sentenceBreakdown.forEachIndexed { index, chunk ->
                                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = chunk.segment.isNotBlank()) { onDrillDown(chunk.segment.trim()) }
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                ) {
                                    Text(chunk.segment, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
                                    Text(chunk.meaning, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (isLoading || entry == null || entry.grammarBreakdown.isNotEmpty()) {
            item {
                SectionCard(title = "GRAMMAR BREAKDOWN", icon = Icons.Rounded.AccountTree) {
                    if (isLoading || entry == null) {
                        Text(
                            text = "Analyzing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            entry.grammarBreakdown.forEachIndexed { index, point ->
                                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)) {
                                        Text(
                                            text = point.pattern,
                                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                    Text(point.explanation, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObsidianIndigoGlassBody(
    word: String,
    entry: CustomAiDictionaryEntry?,
    isLoading: Boolean,
    sentence: String,
    onDrillDown: (String) -> Unit,
) {
    val obsidian = Color(0xFF0E101A)
    val indigo = Color(0xFF2B2E63)
    val glassSurface = Color(0xFF171A2A).copy(alpha = 0.9f)
    val glassBorder = Color(0xFF6366F1).copy(alpha = 0.35f)
    val indigoAccent = Color(0xFFA5B4FC)
    val textPrimary = Color(0xFFF1F5F9)
    val textSecondary = Color(0xFF94A3B8)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(obsidian),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(obsidian, indigo)))
                    .border(0.75.dp, glassBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MEANING", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp), color = indigoAccent.copy(alpha = 0.85f))
                        if (!isLoading && entry != null && entry.partOfSpeech.isNotBlank()) {
                            Surface(shape = CircleShape, color = Color(0xFF6366F1).copy(alpha = 0.25f), border = BorderStroke(0.5.dp, indigoAccent.copy(alpha = 0.4f))) {
                                Text(entry.partOfSpeech, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = indigoAccent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                    }
                    if (isLoading || entry == null) {
                        Text("Analyzing...", style = MaterialTheme.typography.bodyMedium, color = textSecondary)
                    } else {
                        Text(entry.contextualMeaning, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp), color = textPrimary)
                        if (entry.literalMeaning.isNotBlank() && !entry.literalMeaning.equals(entry.contextualMeaning, ignoreCase = true)) {
                            Text("Literal: ${entry.literalMeaning}", style = MaterialTheme.typography.bodySmall, color = textSecondary)
                        }
                    }
                    if (sentence.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.28f), modifier = Modifier.fillMaxWidth()) {
                            Text(sentence.trim(), style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), color = textSecondary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = glassSurface, border = BorderStroke(0.75.dp, glassBorder), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("NUANCE & REGISTER", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp), color = textSecondary)
                    if (isLoading || entry == null) {
                        Text("Analyzing...", style = MaterialTheme.typography.bodyMedium, color = textSecondary)
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Formality" to entry.nuance.formality, "Frequency" to entry.nuance.frequency).forEach { (label, value) ->
                                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF232742), modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = textSecondary)
                                        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = indigoAccent)
                                    }
                                }
                            }
                        }
                        if (entry.nuance.dailyConversation.isNotBlank()) {
                            Text(entry.nuance.dailyConversation, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), color = textPrimary.copy(alpha = 0.92f))
                        }
                    }
                }
            }
        }
        if (isLoading || entry == null || entry.alternativeWords.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = glassSurface, border = BorderStroke(0.75.dp, glassBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SYNONYMS & ALTERNATIVES", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp), color = textSecondary)
                        if (isLoading || entry == null) {
                            Text("Analyzing...", style = MaterialTheme.typography.bodyMedium, color = textSecondary)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                entry.alternativeWords.forEach { alt ->
                                    val cleanSynonym = extractSynonymHead(alt)
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = cleanSynonym.isNotEmpty()) { onDrillDown(cleanSynonym) }
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                    ) {
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = indigoAccent,
                                        )
                                        Text(
                                            text = alt,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                            color = textPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (isLoading || entry == null || entry.sentenceBreakdown.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = glassSurface, border = BorderStroke(0.75.dp, glassBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SENTENCE BREAKDOWN", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp), color = textSecondary)
                        if (isLoading || entry == null) {
                            Text("Analyzing...", style = MaterialTheme.typography.bodyMedium, color = textSecondary)
                        } else {
                            entry.sentenceBreakdown.forEach { chunk ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = chunk.segment.isNotBlank()) { onDrillDown(chunk.segment.trim()) }.padding(vertical = 3.dp, horizontal = 4.dp),
                                ) {
                                    Text(chunk.segment, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = indigoAccent)
                                    Text(chunk.meaning, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = textPrimary.copy(alpha = 0.9f))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (isLoading || entry == null || entry.grammarBreakdown.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = glassSurface, border = BorderStroke(0.75.dp, glassBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("GRAMMAR", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp), color = textSecondary)
                        if (isLoading || entry == null) {
                            Text("Analyzing...", style = MaterialTheme.typography.bodyMedium, color = textSecondary)
                        } else {
                            entry.grammarBreakdown.forEach { point ->
                                Text(point.pattern, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = indigoAccent)
                                Text(point.explanation, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = textPrimary.copy(alpha = 0.9f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaperAndVermilionBody(
    word: String,
    entry: CustomAiDictionaryEntry?,
    isLoading: Boolean,
    sentence: String,
    onDrillDown: (String) -> Unit,
) {
    val washiCanvas = Color(0xFFFAF7F2)
    val linenTile = Color(0xFFF2ECE1)
    val cardPaper = Color(0xFFFFFDF9)
    val sumiBorder = Color(0xFFD8CFC2)
    val sumiPrimary = Color(0xFF1E1B18)
    val taupeSecondary = Color(0xFF6B6257)
    val vermilion = Color(0xFFC84B31)
    val vermilionTint = vermilion.copy(alpha = 0.10f)
    val folioShape = RoundedCornerShape(10.dp)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(washiCanvas),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Context Sentence Banner (Sand-Linen Folio Strip)
        if (sentence.isNotBlank()) {
            item {
                val cleanSentence = sentence.trim()
                val target = (entry?.word ?: word).trim()
                val annotatedSentence = remember(cleanSentence, target) {
                    buildAnnotatedString {
                        val idx = if (target.isNotEmpty()) {
                            cleanSentence.indexOf(target, ignoreCase = true)
                        } else {
                            -1
                        }
                        if (idx >= 0) {
                            append(cleanSentence.substring(0, idx))
                            withStyle(
                                SpanStyle(
                                    color = vermilion,
                                    fontWeight = FontWeight.Bold,
                                    background = vermilionTint,
                                ),
                            ) {
                                append(cleanSentence.substring(idx, idx + target.length))
                            }
                            append(cleanSentence.substring(idx + target.length))
                        } else {
                            append(cleanSentence)
                        }
                    }
                }
                Surface(
                    shape = folioShape,
                    color = linenTile,
                    border = BorderStroke(1.dp, sumiBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "CONTEXT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            ),
                            color = taupeSecondary,
                        )
                        Text(
                            text = annotatedSentence,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            ),
                            color = sumiPrimary,
                        )
                    }
                }
            }
        }

        // 2. Primary Meaning Folio Card (with Thin Vermilion Left Accent Bar & Hanko POS Stamp)
        item {
            Surface(
                shape = folioShape,
                color = cardPaper,
                border = BorderStroke(1.dp, sumiBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.5.dp)
                            .fillMaxHeight()
                            .background(vermilion),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MenuBook,
                                    contentDescription = null,
                                    tint = vermilion,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "MEANING",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    ),
                                    color = taupeSecondary,
                                )
                            }

                            if (!isLoading && entry != null && entry.partOfSpeech.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = vermilionTint,
                                    border = BorderStroke(1.dp, vermilion.copy(alpha = 0.55f)),
                                ) {
                                    Text(
                                        text = entry.partOfSpeech,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = vermilion,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        if (isLoading || entry == null) {
                            Text(
                                text = "Analyzing...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = taupeSecondary,
                            )
                        } else {
                            Text(
                                text = entry.contextualMeaning,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 27.sp,
                                ),
                                color = sumiPrimary,
                            )

                            if (entry.literalMeaning.isNotBlank() &&
                                !entry.literalMeaning.equals(entry.contextualMeaning, ignoreCase = true)
                            ) {
                                HorizontalDivider(
                                    thickness = 0.75.dp,
                                    color = sumiBorder.copy(alpha = 0.75f),
                                )
                                Text(
                                    text = "Root / Literal: ${entry.literalMeaning}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 13.5.sp,
                                        lineHeight = 19.sp,
                                    ),
                                    color = taupeSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Nuance & Register Callout Block (Warm Sand-Linen Tile + Stamped Tags)
        item {
            Surface(
                shape = folioShape,
                color = linenTile,
                border = BorderStroke(1.dp, sumiBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = vermilion,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "NUANCE & REGISTER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            ),
                            color = taupeSecondary,
                        )
                    }

                    if (isLoading || entry == null) {
                        Text(
                            text = "Analyzing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = taupeSecondary,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = cardPaper,
                                border = BorderStroke(1.dp, sumiBorder),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Formality:",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                        color = taupeSecondary,
                                    )
                                    Text(
                                        text = entry.nuance.formality,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = sumiPrimary,
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = cardPaper,
                                border = BorderStroke(1.dp, vermilion.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Frequency:",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                        color = taupeSecondary,
                                    )
                                    Text(
                                        text = entry.nuance.frequency,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = vermilion,
                                    )
                                }
                            }
                        }

                        if (entry.nuance.dailyConversation.isNotBlank()) {
                            Text(
                                text = entry.nuance.dailyConversation,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.5.sp,
                                    lineHeight = 20.5.sp,
                                ),
                                color = sumiPrimary,
                            )
                        }
                    }
                }
            }
        }

        // 4. Alternative Words (Hairline-Separated Vertical List)
        if (isLoading || entry == null || entry.alternativeWords.isNotEmpty()) {
            item {
                Surface(
                    shape = folioShape,
                    color = cardPaper,
                    border = BorderStroke(1.dp, sumiBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                tint = vermilion,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "ALTERNATIVE WORDS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                ),
                                color = taupeSecondary,
                            )
                        }

                        if (isLoading || entry == null) {
                            Text(
                                text = "Analyzing...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = taupeSecondary,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                entry.alternativeWords.forEachIndexed { index, alt ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            thickness = 0.75.dp,
                                            color = sumiBorder.copy(alpha = 0.65f),
                                        )
                                    }
                                    val cleanSynonym = extractSynonymHead(alt)
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(enabled = cleanSynonym.isNotEmpty()) {
                                                onDrillDown(cleanSynonym)
                                            }
                                            .padding(vertical = 5.dp, horizontal = 4.dp),
                                    ) {
                                        Text(
                                            text = "▪",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = vermilion,
                                        )
                                        Text(
                                            text = alt,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 14.5.sp,
                                                lineHeight = 20.5.sp,
                                            ),
                                            color = sumiPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Sentence Breakdown (Hairline-Separated Vertical List)
        if (isLoading || entry == null || entry.sentenceBreakdown.isNotEmpty()) {
            item {
                Surface(
                    shape = folioShape,
                    color = cardPaper,
                    border = BorderStroke(1.dp, sumiBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Notes,
                                contentDescription = null,
                                tint = vermilion,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "SENTENCE BREAKDOWN",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                ),
                                color = taupeSecondary,
                            )
                        }

                        if (isLoading || entry == null) {
                            Text(
                                text = "Analyzing...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = taupeSecondary,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                entry.sentenceBreakdown.forEachIndexed { index, chunk ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            thickness = 0.75.dp,
                                            color = sumiBorder.copy(alpha = 0.65f),
                                        )
                                    }
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(enabled = chunk.segment.isNotBlank()) {
                                                onDrillDown(chunk.segment.trim())
                                            }
                                            .padding(vertical = 5.dp, horizontal = 4.dp),
                                    ) {
                                        Text(
                                            text = chunk.segment,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            color = vermilion,
                                        )
                                        Text(
                                            text = chunk.meaning,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp,
                                            ),
                                            color = sumiPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Grammar Breakdown
        if (isLoading || entry == null || entry.grammarBreakdown.isNotEmpty()) {
            item {
                Surface(
                    shape = folioShape,
                    color = cardPaper,
                    border = BorderStroke(1.dp, sumiBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccountTree,
                                contentDescription = null,
                                tint = vermilion,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "GRAMMAR NOTES",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                ),
                                color = taupeSecondary,
                            )
                        }

                        if (isLoading || entry == null) {
                            Text(
                                text = "Analyzing...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = taupeSecondary,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                entry.grammarBreakdown.forEachIndexed { index, point ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            thickness = 0.75.dp,
                                            color = sumiBorder.copy(alpha = 0.65f),
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = linenTile,
                                            border = BorderStroke(1.dp, sumiBorder),
                                        ) {
                                            Text(
                                                text = point.pattern,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                                color = sumiPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            )
                                        }
                                        Text(
                                            text = point.explanation,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp,
                                            ),
                                            color = sumiPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CosmicVioletBody(
    word: String,
    entry: CustomAiDictionaryEntry?,
    isLoading: Boolean,
    sentence: String,
    isSaved: Boolean,
    hasAttachedImage: Boolean,
    canGoBack: Boolean,
    onSpeakWord: () -> Unit,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenCollections: () -> Unit,
    onDrillDown: (String) -> Unit,
) {
    val displayWord = entry?.word ?: word
    var showAllAlternatives by remember(displayWord) { mutableStateOf(false) }

    val cosmicBg = Color(0xFF0B0A14)
    val cardBg = Color(0xFF151226)
    val pillBg = Color(0xFF1D1932)
    val borderSubtle = Color.White.copy(alpha = 0.10f)
    val violetAccent = Color(0xFFA78BFA)
    val violetBright = Color(0xFF8B5CF6)
    val textPrimary = Color(0xFFF8FAFC)
    val textSecondary = Color(0xFFD1D5DB)
    val textMuted = Color(0xFF9CA3AF)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cosmicBg)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF3B1D7A).copy(alpha = 0.42f), Color.Transparent),
                    center = Offset(720f, 160f),
                    radius = 680f,
                ),
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Custom Top Header Bar ("Chimahon AI" + "Powered by GROQ" + Images/Save/Close)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    Surface(
                        onClick = onNavigateBack,
                        shape = CircleShape,
                        color = pillBg,
                        border = BorderStroke(0.75.dp, borderSubtle),
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = textPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = violetAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Chimahon AI",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = textPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF231B3D),
                        border = BorderStroke(0.75.dp, violetBright.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = "Powered by GROQ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = Color(0xFFC4B5FD),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }

                Surface(
                    onClick = onOpenImages,
                    shape = CircleShape,
                    color = if (hasAttachedImage) Color(0xFF2B214D) else pillBg,
                    border = BorderStroke(
                        0.75.dp,
                        if (hasAttachedImage) violetAccent.copy(alpha = 0.65f) else borderSubtle,
                    ),
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = "Images & Screenshot",
                            tint = if (hasAttachedImage) violetAccent else textPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    onClick = onOpenCollections,
                    shape = CircleShape,
                    color = if (isSaved) Color(0xFF2B214D) else pillBg,
                    border = BorderStroke(
                        0.75.dp,
                        if (isSaved) violetAccent.copy(alpha = 0.65f) else borderSubtle,
                    ),
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkAdd,
                            contentDescription = "Save to Collection",
                            tint = if (isSaved) violetAccent else textPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = pillBg,
                    border = BorderStroke(0.75.dp, borderSubtle),
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = textPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // 2. Scrollable Body
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Hero Word + Reading + Audio + Orbital Sparkle + Contextual Meaning
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        CosmicOrbitalSparkle(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(width = 110.dp, height = 78.dp),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = displayWord.ifBlank { "Lookup" },
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 27.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp,
                                ),
                                color = textPrimary,
                                modifier = Modifier.padding(end = 90.dp),
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val readingDisplay = if (entry != null) {
                                    entry.reading.takeIf { it.isNotBlank() && it != entry.word }
                                        ?: entry.partOfSpeech
                                } else {
                                    ""
                                }
                                if (readingDisplay.isNotBlank()) {
                                    Text(
                                        text = readingDisplay,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        color = textMuted,
                                    )
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textMuted,
                                    )
                                }
                                Surface(
                                    onClick = onSpeakWord,
                                    shape = CircleShape,
                                    color = Color(0xFF3B2870).copy(alpha = 0.65f),
                                    border = BorderStroke(0.75.dp, violetAccent.copy(alpha = 0.35f)),
                                    modifier = Modifier.size(26.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.VolumeUp,
                                            contentDescription = "Pronounce word",
                                            tint = Color(0xFFC4B5FD),
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = if (isLoading || entry == null) "Analyzing..." else entry.contextualMeaning,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.5.sp,
                                    lineHeight = 20.5.sp,
                                ),
                                color = if (isLoading || entry == null) textMuted else textSecondary,
                            )
                        }
                    }
                }

                // AI Nuance Card
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = cardBg,
                        border = BorderStroke(0.75.dp, borderSubtle),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF6D28D9), Color(0xFF4C1D95)),
                                        ),
                                    )
                                    .border(1.dp, violetAccent.copy(alpha = 0.45f), CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lightbulb,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(21.dp),
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "AI Nuance",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = textPrimary,
                                    )
                                    if (!isLoading && entry != null) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF251E42),
                                            border = BorderStroke(0.75.dp, violetBright.copy(alpha = 0.38f)),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.SignalCellularAlt,
                                                    contentDescription = null,
                                                    tint = violetAccent,
                                                    modifier = Modifier.size(12.dp),
                                                )
                                                Text(
                                                    text = entry.nuance.frequency,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Medium,
                                                    ),
                                                    color = Color(0xFFC4B5FD),
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isLoading || entry == null) {
                                    Text(
                                        text = "Analyzing...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = textMuted,
                                    )
                                } else {
                                    val nuanceBody = buildString {
                                        if (entry.nuance.formality.isNotBlank()) {
                                            append(entry.nuance.formality)
                                            if (entry.nuance.dailyConversation.isNotBlank()) append(". ")
                                        }
                                        append(entry.nuance.dailyConversation)
                                    }
                                    if (nuanceBody.isNotBlank()) {
                                        Text(
                                            text = nuanceBody,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 13.sp,
                                                lineHeight = 18.5.sp,
                                            ),
                                            color = textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Alternative Words Section (Ordered Vertical List)
                if (isLoading || entry == null || entry.alternativeWords.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(0.75.dp, borderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.GridView,
                                            contentDescription = null,
                                            tint = violetAccent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "Alternative Words",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = textPrimary,
                                        )
                                    }
                                    if (entry != null && entry.alternativeWords.size > 4) {
                                        Text(
                                            text = if (showAllAlternatives) "Show less" else "See all →",
                                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                            color = violetAccent,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { showAllAlternatives = !showAllAlternatives }
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                    }
                                }

                                if (isLoading || entry == null) {
                                    Text(
                                        text = "Analyzing...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = textMuted,
                                    )
                                } else {
                                    val visibleAlternatives = if (showAllAlternatives) {
                                        entry.alternativeWords
                                    } else {
                                        entry.alternativeWords.take(4)
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        visibleAlternatives.forEach { alt ->
                                            val cleanSynonym = extractSynonymHead(alt)
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable(enabled = cleanSynonym.isNotEmpty()) { onDrillDown(cleanSynonym) }
                                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                            ) {
                                                Text(
                                                    text = "•",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.5.sp,
                                                    ),
                                                    color = violetAccent,
                                                )
                                                Text(
                                                    text = alt,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 13.5.sp,
                                                        lineHeight = 19.sp,
                                                    ),
                                                    color = textSecondary,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Sentence Breakdown Card (Sentence Context + Clean Vertical List)
                if (isLoading || entry == null || entry.sentenceBreakdown.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(0.75.dp, borderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FormatListBulleted,
                                        contentDescription = null,
                                        tint = violetAccent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Sentence Breakdown",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = textPrimary,
                                    )
                                }

                                if (sentence.isNotBlank()) {
                                    val cleanSentence = sentence.trim()
                                    val target = displayWord.trim()
                                    val annotatedSentence = remember(cleanSentence, target) {
                                        buildAnnotatedString {
                                            val idx = if (target.isNotEmpty()) {
                                                cleanSentence.indexOf(target, ignoreCase = true)
                                            } else {
                                                -1
                                            }
                                            if (idx >= 0) {
                                                append(cleanSentence.substring(0, idx))
                                                withStyle(
                                                    SpanStyle(
                                                        color = violetAccent,
                                                        fontWeight = FontWeight.Bold,
                                                    ),
                                                ) {
                                                    append(cleanSentence.substring(idx, idx + target.length))
                                                }
                                                append(cleanSentence.substring(idx + target.length))
                                            } else {
                                                append(cleanSentence)
                                            }
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = pillBg,
                                        border = BorderStroke(0.75.dp, borderSubtle),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Text(
                                                text = "Sentence context",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                                color = violetAccent,
                                            )
                                            Text(
                                                text = annotatedSentence,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 13.5.sp,
                                                    lineHeight = 19.sp,
                                                ),
                                                color = textPrimary,
                                            )
                                        }
                                    }
                                }

                                if (isLoading || entry == null) {
                                    Text(
                                        text = "Analyzing...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = textMuted,
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        entry.sentenceBreakdown.forEachIndexed { index, chunk ->
                                            if (index > 0) {
                                                HorizontalDivider(
                                                    thickness = 0.5.dp,
                                                    color = borderSubtle,
                                                )
                                            }
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable(enabled = chunk.segment.isNotBlank()) {
                                                        onDrillDown(chunk.segment.trim())
                                                    }
                                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                            ) {
                                                Text(
                                                    text = chunk.segment,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                    ),
                                                    color = Color(0xFFC4B5FD),
                                                )
                                                Text(
                                                    text = chunk.meaning,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 13.sp,
                                                        lineHeight = 18.5.sp,
                                                    ),
                                                    color = textSecondary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Grammar Breakdown Card
                if (isLoading || entry == null || entry.grammarBreakdown.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(0.75.dp, borderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.School,
                                        contentDescription = null,
                                        tint = violetAccent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "Grammar Breakdown",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = textPrimary,
                                    )
                                }

                                if (isLoading || entry == null) {
                                    Text(
                                        text = "Analyzing...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = textMuted,
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        entry.grammarBreakdown.forEach { point ->
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                if (point.pattern.isNotBlank()) {
                                                    Text(
                                                        text = point.pattern,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                        ),
                                                        color = Color(0xFFC4B5FD),
                                                    )
                                                }
                                                if (point.explanation.isNotBlank()) {
                                                    Text(
                                                        text = point.explanation,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 13.sp,
                                                            lineHeight = 18.5.sp,
                                                        ),
                                                        color = textSecondary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Example Sentences Card
                val displayExamples = when {
                    isLoading || entry == null -> emptyList()
                    entry.exampleSentences.isNotEmpty() -> entry.exampleSentences
                    sentence.isNotBlank() -> listOf(sentence.trim())
                    else -> emptyList()
                }
                if (isLoading || entry == null || displayExamples.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(0.75.dp, borderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ChatBubble,
                                        contentDescription = null,
                                        tint = violetAccent,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Text(
                                        text = "Example Sentences",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = textPrimary,
                                    )
                                }

                                if (isLoading || entry == null) {
                                    Text(
                                        text = "Analyzing...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = textMuted,
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        displayExamples.forEach { example ->
                                            val annotated = remember(example, entry.word) {
                                                buildAnnotatedString {
                                                    val target = entry.word.trim()
                                                    val idx = if (target.isNotEmpty()) {
                                                        example.indexOf(target, ignoreCase = true)
                                                    } else {
                                                        -1
                                                    }
                                                    if (idx >= 0) {
                                                        append(example.substring(0, idx))
                                                        withStyle(
                                                            SpanStyle(
                                                                color = violetAccent,
                                                                fontWeight = FontWeight.SemiBold,
                                                            ),
                                                        ) {
                                                            append(example.substring(idx, idx + target.length))
                                                        }
                                                        append(example.substring(idx + target.length))
                                                    } else {
                                                        append(example)
                                                    }
                                                }
                                            }
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                Text(
                                                    text = "•",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                    ),
                                                    color = violetAccent,
                                                )
                                                Text(
                                                    text = annotated,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 13.sp,
                                                        lineHeight = 18.5.sp,
                                                    ),
                                                    color = textSecondary,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Docked Bottom Action Bar ("Save to Collection" + Gradient "Refresh")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cosmicBg.copy(alpha = 0.96f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    onClick = onOpenCollections,
                    shape = CircleShape,
                    color = Color(0xFF18162A),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            contentDescription = null,
                            tint = if (isSaved) violetAccent else Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSaved) "Saved" else "Save to Collection",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFA855F7), Color(0xFF6366F1)),
                            ),
                        )
                        .clickable { onRefresh() },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Refresh",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CosmicOrbitalSparkle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width * 0.60f
        val cy = size.height * 0.50f

        rotate(degrees = -22f, pivot = Offset(cx, cy)) {
            drawOval(
                color = Color(0xFF8B5CF6).copy(alpha = 0.28f),
                topLeft = Offset(cx - size.width * 0.45f, cy - size.height * 0.24f),
                size = Size(size.width * 0.90f, size.height * 0.48f),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawOval(
                color = Color(0xFFA78BFA).copy(alpha = 0.55f),
                topLeft = Offset(cx - size.width * 0.45f, cy - size.height * 0.24f),
                size = Size(size.width * 0.90f, size.height * 0.48f),
                style = Stroke(width = 0.8.dp.toPx()),
            )
        }

        val starRadius = 11.dp.toPx()
        val innerRadius = starRadius * 0.28f
        val starPath = Path().apply {
            moveTo(cx, cy - starRadius)
            lineTo(cx + innerRadius, cy - innerRadius)
            lineTo(cx + starRadius, cy)
            lineTo(cx + innerRadius, cy + innerRadius)
            lineTo(cx, cy + starRadius)
            lineTo(cx - innerRadius, cy + innerRadius)
            lineTo(cx - starRadius, cy)
            lineTo(cx - innerRadius, cy - innerRadius)
            close()
        }
        drawCircle(
            color = Color(0xFF8B5CF6).copy(alpha = 0.28f),
            radius = starRadius * 1.4f,
            center = Offset(cx, cy),
        )
        drawPath(starPath, color = Color(0xFFC4B5FD))
    }
}

@Composable
private fun StagedAttachmentPreviewChip(
    popupDesign: String,
    stagedImage: StagedAiImage?,
    persistedAttachment: PersistedWordAttachment?,
    onOpenPicker: () -> Unit,
    onClear: () -> Unit,
) {
    val palette = rememberCollectionPalette(popupDesign)
    val previewBitmap = stagedImage?.previewBitmap ?: persistedAttachment?.previewBitmap
    val source = stagedImage?.source ?: persistedAttachment?.source ?: return
    val isStagedOnly = stagedImage != null
    val statusText = when {
        isStagedOnly && source == StagedImageSource.SCREENSHOT -> "Screenshot staged • Save word to keep"
        isStagedOnly && source == StagedImageSource.WEB_IMAGE -> "Online image staged • Save word to keep"
        source == StagedImageSource.SCREENSHOT -> "Screenshot attached to card"
        else -> "Online image attached to card"
    }
    val chipShape = if (popupDesign == AiPreferences.DESIGN_NEO_BENTO) {
        RoundedCornerShape(8.dp)
    } else {
        RoundedCornerShape(10.dp)
    }

    Surface(
        onClick = onOpenPicker,
        shape = chipShape,
        color = palette.cardBg,
        border = BorderStroke(0.75.dp, palette.accent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.elevatedPillBg)
                    .border(0.5.dp, palette.border, RoundedCornerShape(6.dp)),
            ) {
                if (previewBitmap != null && !previewBitmap.isRecycled) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Attached preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Tap to replace image",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = palette.textMuted,
                )
            }

            Surface(
                onClick = onClear,
                shape = CircleShape,
                color = palette.elevatedPillBg,
                border = BorderStroke(0.5.dp, palette.border),
                modifier = Modifier.size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove attached image",
                        tint = palette.danger,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AiImageViewerPickerBody(
    popupDesign: String,
    queryWord: String,
    isPickerMode: Boolean,
    existingAttachmentSource: StagedImageSource?,
    cachedQuery: String?,
    cachedResults: List<AiWebImageItem>,
    onCacheUpdated: (String, List<AiWebImageItem>) -> Unit,
    onCaptureScreen: (suspend () -> Bitmap?)?,
    onEnterPickerMode: () -> Unit,
    onCancelOrBack: () -> Unit,
    onDismiss: () -> Unit,
    onCommitPickedImage: (StagedAiImage) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val initialCached = remember(queryWord, cachedQuery, cachedResults) {
        AiImageManager.getCachedSearchResults(queryWord)
            ?: if (cachedQuery == queryWord && cachedResults.isNotEmpty()) cachedResults else emptyList()
    }
    var isSearching by remember(queryWord) {
        mutableStateOf(initialCached.isEmpty())
    }
    var searchError by remember(queryWord) { mutableStateOf<String?>(null) }
    var imageResults by remember(queryWord) {
        mutableStateOf(initialCached)
    }
    var searchRetryTrigger by remember { mutableIntStateOf(0) }

    // Picker Mode selection state (ephemeral until Save / Update is tapped)
    val gridListState = rememberLazyListState()
    var selectedWebItem by remember(isPickerMode, queryWord) { mutableStateOf<AiWebImageItem?>(null) }
    var pendingScreenshot by remember(isPickerMode, queryWord) { mutableStateOf<StagedAiImage?>(null) }
    var isCommitting by remember { mutableStateOf(false) }
    var isCapturingScreenshot by remember { mutableStateOf(false) }

    LaunchedEffect(isPickerMode, queryWord) {
        gridListState.scrollToItem(0)
    }

    LaunchedEffect(queryWord, searchRetryTrigger) {
        if (queryWord.isBlank()) {
            isSearching = false
            searchError = "No target word to search."
            return@LaunchedEffect
        }
        if (searchRetryTrigger == 0) {
            val cached = AiImageManager.getCachedSearchResults(queryWord)
                ?: if (cachedQuery == queryWord && cachedResults.isNotEmpty()) cachedResults else null
            if (!cached.isNullOrEmpty()) {
                imageResults = cached
                isSearching = false
                searchError = null
                return@LaunchedEffect
            }
        }
        isSearching = true
        searchError = null
        val res = AiImageManager.searchDuckDuckGoImages(
            context = context,
            query = queryWord,
            forceRefresh = searchRetryTrigger > 0,
        )
        res.fold(
            onSuccess = { items ->
                imageResults = items
                onCacheUpdated(queryWord, items)
                isSearching = false
            },
            onFailure = { err ->
                searchError = err.message ?: "DuckDuckGo image search failed."
                isSearching = false
            },
        )
    }

    val isClassic = popupDesign == AiPreferences.DESIGN_CLASSIC_CARDS
    val isGlassIndigo = popupDesign == AiPreferences.DESIGN_GLASS_INDIGO
    val isPaperVermilion = popupDesign == AiPreferences.DESIGN_NEO_BENTO
    val isCosmicViolet = popupDesign == AiPreferences.DESIGN_COSMIC_VIOLET

    val bgColor = when {
        isCosmicViolet -> Color(0xFF0B0A14)
        isGlassIndigo -> Color(0xFF0E101A)
        isPaperVermilion -> Color(0xFFFAF7F2)
        else -> MaterialTheme.colorScheme.surface
    }
    val cardColor = when {
        isCosmicViolet -> Color(0xFF151226)
        isGlassIndigo -> Color(0xFF171A2A).copy(alpha = 0.92f)
        isPaperVermilion -> Color(0xFFFFFDF9)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val pillColor = when {
        isCosmicViolet -> Color(0xFF1D1932)
        isGlassIndigo -> Color(0xFF232742)
        isPaperVermilion -> Color(0xFFF2ECE1)
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val borderColor = when {
        isCosmicViolet -> Color.White.copy(alpha = 0.12f)
        isGlassIndigo -> Color(0xFF6366F1).copy(alpha = 0.35f)
        isPaperVermilion -> Color(0xFFD8CFC2)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    val accentColor = when {
        isCosmicViolet -> Color(0xFFA78BFA)
        isGlassIndigo -> Color(0xFFA5B4FC)
        isPaperVermilion -> Color(0xFFC84B31)
        else -> MaterialTheme.colorScheme.primary
    }
    val textPrimary = when {
        isCosmicViolet -> Color(0xFFF8FAFC)
        isGlassIndigo -> Color(0xFFF1F5F9)
        isPaperVermilion -> Color(0xFF1E1B18)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val textMuted = when {
        isCosmicViolet -> Color(0xFF9CA3AF)
        isGlassIndigo -> Color(0xFF94A3B8)
        isPaperVermilion -> Color(0xFF6B6257)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val cardCorner = when {
        isPaperVermilion -> 8.dp
        isClassic -> 12.dp
        else -> 14.dp
    }

    val hasPendingChoice = pendingScreenshot != null || selectedWebItem != null
    val primaryActionLabel = if (existingAttachmentSource != null) "Update" else "Save"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .then(
                if (isCosmicViolet) {
                    Modifier.background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF3B1D7A).copy(alpha = 0.36f), Color.Transparent),
                            center = Offset(720f, 140f),
                            radius = 650f,
                        ),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Preset-Tailored Header Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isGlassIndigo) {
                            Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF0E101A), Color(0xFF2B2E63))))
                        } else {
                            Modifier.background(bgColor)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        onClick = onCancelOrBack,
                        shape = if (isPaperVermilion) RoundedCornerShape(8.dp) else CircleShape,
                        color = pillColor,
                        border = BorderStroke(0.75.dp, borderColor),
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = textPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = queryWord.ifBlank { "Images" },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Surface(
                                shape = if (isPaperVermilion) RoundedCornerShape(4.dp) else CircleShape,
                                color = if (isPaperVermilion) accentColor.copy(alpha = 0.12f) else pillColor,
                                border = BorderStroke(0.75.dp, accentColor.copy(alpha = 0.45f)),
                            ) {
                                Text(
                                    text = if (isPickerMode) "PICKER" else "VIEWER",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.7.sp,
                                    ),
                                    color = accentColor,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            text = if (isPickerMode) {
                                "Select an image or capture screen"
                            } else {
                                "DuckDuckGo Images • Read-only preview"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Surface(
                        onClick = { searchRetryTrigger++ },
                        shape = if (isPaperVermilion) RoundedCornerShape(8.dp) else CircleShape,
                        color = pillColor,
                        border = BorderStroke(0.75.dp, borderColor),
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reload images",
                                tint = textPrimary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }

                    Surface(
                        onClick = onDismiss,
                        shape = if (isPaperVermilion) RoundedCornerShape(8.dp) else CircleShape,
                        color = pillColor,
                        border = BorderStroke(0.75.dp, borderColor),
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close popup",
                                tint = textPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = if (isPaperVermilion) 1.dp else 0.5.dp, color = borderColor)

            // Pinned Picker Controls (always visible above image grid without scrolling)
            if (isPickerMode && (existingAttachmentSource != null || onCaptureScreen != null)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (existingAttachmentSource != null) {
                        val reminderLabel = if (existingAttachmentSource == StagedImageSource.SCREENSHOT) {
                            "Screenshot currently attached"
                        } else {
                            "Online image currently attached"
                        }
                        Surface(
                            shape = RoundedCornerShape(cardCorner),
                            color = cardColor,
                            border = BorderStroke(0.75.dp, accentColor.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isPaperVermilion) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.5.dp)
                                            .fillMaxHeight()
                                            .background(accentColor),
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 11.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PhotoLibrary,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = reminderLabel,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = textPrimary,
                                    )
                                }
                            }
                        }
                    }

                    if (onCaptureScreen != null) {
                        val shotPreview = pendingScreenshot?.previewBitmap
                        Surface(
                            onClick = {
                                if (!isCapturingScreenshot && !isCommitting) {
                                    isCapturingScreenshot = true
                                    scope.launch {
                                        try {
                                            val rawBmp = onCaptureScreen.invoke()
                                            if (rawBmp == null) {
                                                Toast.makeText(
                                                    context,
                                                    "Unable to capture screen frame",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                val prepared = AiImageManager.prepareScreenshotImage(rawBmp)
                                                prepared.fold(
                                                    onSuccess = { shot ->
                                                        pendingScreenshot = shot
                                                        selectedWebItem = null
                                                    },
                                                    onFailure = { err ->
                                                        Toast.makeText(
                                                            context,
                                                            err.message ?: "Screenshot failed",
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    },
                                                )
                                            }
                                        } finally {
                                            isCapturingScreenshot = false
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(cardCorner),
                            color = if (pendingScreenshot != null) accentColor.copy(alpha = 0.16f) else cardColor,
                            border = BorderStroke(
                                if (pendingScreenshot != null) 1.5.dp else 0.75.dp,
                                if (pendingScreenshot != null) accentColor else borderColor,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = accentColor.copy(alpha = 0.18f),
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isCapturingScreenshot) {
                                            CircularProgressIndicator(
                                                strokeWidth = 2.dp,
                                                color = accentColor,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.CameraAlt,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (pendingScreenshot != null) {
                                            "Screenshot captured"
                                        } else {
                                            "Use screenshot"
                                        },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = textPrimary,
                                    )
                                    Text(
                                        text = if (pendingScreenshot != null) {
                                            "Tap $primaryActionLabel below to attach"
                                        } else {
                                            "Capture current reading screen"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = textMuted,
                                    )
                                }
                                if (shotPreview != null && !shotPreview.isRecycled) {
                                    Image(
                                        bitmap = shotPreview.asImageBitmap(),
                                        contentDescription = "Captured screenshot preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 48.dp, height = 36.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .border(1.dp, accentColor, RoundedCornerShape(6.dp)),
                                    )
                                }
                                if (pendingScreenshot != null) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Scrollable DuckDuckGo Image Results Grid
            LazyColumn(
                state = gridListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // DuckDuckGo Image Results Grid (or clean non-blocking error/loading state)
                when {
                    isSearching -> {
                        item {
                            Surface(
                                shape = RoundedCornerShape(cardCorner),
                                color = cardColor,
                                border = BorderStroke(0.75.dp, borderColor),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        color = accentColor,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "Searching DuckDuckGo Images for \"$queryWord\"...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = textMuted,
                                    )
                                }
                            }
                        }
                    }
                    searchError != null -> {
                        item {
                            Surface(
                                shape = RoundedCornerShape(cardCorner),
                                color = cardColor,
                                border = BorderStroke(0.75.dp, borderColor),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = searchError!!,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = textMuted,
                                    )
                                    Surface(
                                        onClick = { searchRetryTrigger++ },
                                        shape = if (isPaperVermilion) RoundedCornerShape(6.dp) else CircleShape,
                                        color = pillColor,
                                        border = BorderStroke(0.75.dp, borderColor),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = "Retry search",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = textPrimary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val rows = imageResults.chunked(2)
                        items(
                            items = rows,
                            key = { row -> row.joinToString("_") { it.id } },
                        ) { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowItems.forEach { item ->
                                    val isSelected = isPickerMode &&
                                        pendingScreenshot == null &&
                                        selectedWebItem?.id == item.id
                                    Surface(
                                        shape = RoundedCornerShape(cardCorner),
                                        color = cardColor,
                                        border = BorderStroke(
                                            width = if (isSelected) 2.dp else 0.75.dp,
                                            color = if (isSelected) accentColor else borderColor,
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.15f)
                                            .then(
                                                if (isPickerMode) {
                                                    Modifier.clickable {
                                                        pendingScreenshot = null
                                                        selectedWebItem = item
                                                    }
                                                } else {
                                                    // Viewer Mode: strictly read-only browsing (no zoom modal, no selection)
                                                    Modifier
                                                },
                                            ),
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            AiRemoteThumbnail(
                                                url = item.thumbnailUrl,
                                                contentDescription = item.title.ifBlank { queryWord },
                                                placeholderColor = pillColor,
                                                indicatorColor = accentColor,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            if (isSelected) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = accentColor,
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(6.dp)
                                                        .size(22.dp),
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Check,
                                                            contentDescription = "Selected",
                                                            tint = if (isGlassIndigo) Color(0xFF0E101A) else Color.White,
                                                            modifier = Modifier.size(14.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // 3. Docked Bottom Action Bar (Viewer Mode vs Picker Mode)
            HorizontalDivider(thickness = if (isPaperVermilion) 1.dp else 0.5.dp, color = borderColor)
            val buttonShape = if (isPaperVermilion) RoundedCornerShape(8.dp) else CircleShape
            val primaryCtaBrush = when {
                isCosmicViolet -> Brush.horizontalGradient(listOf(Color(0xFFA855F7), Color(0xFF6366F1)))
                isGlassIndigo -> Brush.horizontalGradient(listOf(Color(0xFF4F46E5), Color(0xFF6366F1)))
                isPaperVermilion -> Brush.horizontalGradient(listOf(Color(0xFFC84B31), Color(0xFFA93B24)))
                else -> Brush.horizontalGradient(listOf(accentColor, accentColor))
            }

            if (!isPickerMode) {
                // Viewer Mode: NO Save or Update button; only action to enter Picker Mode
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(buttonShape)
                            .background(primaryCtaBrush)
                            .clickable { onEnterPickerMode() },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhotoLibrary,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = if (existingAttachmentSource != null) {
                                    "Replace Attached Image"
                                } else {
                                    "Switch to Picker"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = Color.White,
                            )
                        }
                    }
                }
            } else {
                // Picker Mode: Cancel + Save (first-time) or Update (when an image is already attached)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        onClick = { if (!isCommitting) onCancelOrBack() },
                        shape = buttonShape,
                        color = pillColor,
                        border = BorderStroke(0.75.dp, borderColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = textPrimary,
                            )
                        }
                    }

                    val canCommit = hasPendingChoice && !isCommitting
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(buttonShape)
                            .then(
                                if (canCommit) {
                                    Modifier.background(primaryCtaBrush)
                                } else {
                                    Modifier.background(pillColor)
                                },
                            )
                            .clickable(enabled = canCommit) {
                                isCommitting = true
                                scope.launch {
                                    try {
                                        val shot = pendingScreenshot
                                        if (shot != null) {
                                            onCommitPickedImage(shot)
                                        } else {
                                            val webItem = selectedWebItem
                                            if (webItem != null) {
                                                val prepared = AiImageManager.downloadAndPrepareWebImage(webItem)
                                                prepared.fold(
                                                    onSuccess = { ready -> onCommitPickedImage(ready) },
                                                    onFailure = { err ->
                                                        Toast.makeText(
                                                            context,
                                                            err.message ?: "Could not download image",
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    },
                                                )
                                            }
                                        }
                                    } finally {
                                        isCommitting = false
                                    }
                                }
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (isCommitting) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = if (isCommitting) "Saving..." else primaryActionLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = if (canCommit) Color.White else textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class CollectionThemePalette(
    val background: Color,
    val cardBg: Color,
    val elevatedPillBg: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val danger: Color,
    val ctaBrush: Brush,
    val isDark: Boolean,
)

@Composable
private fun rememberCollectionPalette(popupDesign: String): CollectionThemePalette {
    val scheme = MaterialTheme.colorScheme
    return when (popupDesign) {
        AiPreferences.DESIGN_COSMIC_VIOLET -> CollectionThemePalette(
            background = Color(0xFF0B0A14),
            cardBg = Color(0xFF151226),
            elevatedPillBg = Color(0xFF1D1932),
            border = Color.White.copy(alpha = 0.12f),
            accent = Color(0xFFA78BFA),
            onAccent = Color.White,
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFFD1D5DB),
            textMuted = Color(0xFF9CA3AF),
            danger = Color(0xFFF87171),
            ctaBrush = Brush.horizontalGradient(listOf(Color(0xFFA855F7), Color(0xFF6366F1))),
            isDark = true,
        )
        AiPreferences.DESIGN_GLASS_INDIGO -> CollectionThemePalette(
            background = Color(0xFF0E101A),
            cardBg = Color(0xFF171A2A),
            elevatedPillBg = Color(0xFF232742),
            border = Color(0xFF6366F1).copy(alpha = 0.35f),
            accent = Color(0xFFA5B4FC),
            onAccent = Color(0xFF0E101A),
            textPrimary = Color(0xFFF1F5F9),
            textSecondary = Color(0xFFCBD5E1),
            textMuted = Color(0xFF94A3B8),
            danger = Color(0xFFF87171),
            ctaBrush = Brush.horizontalGradient(listOf(Color(0xFF4F46E5), Color(0xFF6366F1))),
            isDark = true,
        )
        AiPreferences.DESIGN_NEO_BENTO -> CollectionThemePalette(
            background = Color(0xFFFAF7F2),
            cardBg = Color(0xFFFFFDF9),
            elevatedPillBg = Color(0xFFF2ECE1),
            border = Color(0xFFD8CFC2),
            accent = Color(0xFFC84B31),
            onAccent = Color(0xFFFFFDF9),
            textPrimary = Color(0xFF1E1B18),
            textSecondary = Color(0xFF3A342E),
            textMuted = Color(0xFF6B6257),
            danger = Color(0xFFB3261E),
            ctaBrush = Brush.horizontalGradient(listOf(Color(0xFFC84B31), Color(0xFFA93B24))),
            isDark = false,
        )
        else -> CollectionThemePalette(
            background = scheme.surface,
            cardBg = scheme.surfaceContainerLow,
            elevatedPillBg = scheme.secondaryContainer,
            border = scheme.outlineVariant.copy(alpha = 0.6f),
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            textPrimary = scheme.onSurface,
            textSecondary = scheme.onSurface.copy(alpha = 0.85f),
            textMuted = scheme.onSurfaceVariant,
            danger = scheme.error,
            ctaBrush = Brush.horizontalGradient(listOf(scheme.primary, scheme.primary)),
            isDark = false,
        )
    }
}

@Composable
private fun CollectionsManagerBody(
    popupDesign: String,
    currentWord: String,
    currentSentence: String,
    currentEntry: CustomAiDictionaryEntry?,
    collectionIndex: List<WordCollectionMeta>,
    activeCollection: WordCollection?,
    isCreatingCollection: Boolean,
    onCreatingCollectionChange: (Boolean) -> Unit,
    onDropdownSearchActiveChange: (Boolean) -> Unit,
    isExportingToAnki: Boolean,
    onGoBack: () -> Unit,
    onDismiss: () -> Unit,
    onSelectCollection: (String) -> Unit,
    onSelectWord: (SavedWordItem) -> Unit,
    onSaveToCollection: (String) -> Unit,
    onCreateCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onClearCollection: (String) -> Unit,
    onRemoveWord: (collectionId: String, itemId: String) -> Unit,
    onExportCollectionToAnki: (WordCollection) -> Unit,
) {
    val context = LocalContext.current
    val palette = rememberCollectionPalette(popupDesign)
    var newCollectionName by remember { mutableStateOf("") }
    var collectionDropdownExpanded by remember { mutableStateOf(false) }
    var collectionSearchQuery by remember { mutableStateOf("") }
    var pendingDeleteCollectionId by remember { mutableStateOf<String?>(null) }
    var pendingClearCollectionId by remember { mutableStateOf<String?>(null) }

    val isAtMaxCollections = collectionIndex.size >= WordCollectionRepository.MAX_COLLECTIONS

    DisposableEffect(collectionDropdownExpanded, collectionIndex.size) {
        onDropdownSearchActiveChange(collectionDropdownExpanded && collectionIndex.size > 6)
        onDispose { onDropdownSearchActiveChange(false) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .then(
                if (popupDesign == AiPreferences.DESIGN_COSMIC_VIOLET) {
                    Modifier.background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF3B1D7A).copy(alpha = 0.35f), Color.Transparent),
                            center = Offset(720f, 140f),
                            radius = 650f,
                        ),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Adaptive Top Bar (Symmetrical 30.dp Back + FolderOpen Title + 30.dp Close)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    onClick = onGoBack,
                    shape = CircleShape,
                    color = palette.elevatedPillBg,
                    border = BorderStroke(0.75.dp, palette.border),
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Go back",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "Collections",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = palette.elevatedPillBg,
                    border = BorderStroke(0.75.dp, palette.border),
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = palette.border)

            // 2. Scrollable Body
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Quick Save Current Word Banner (preserves saving current word into active/chosen collection)
                if (currentWord.isNotEmpty() && activeCollection != null) {
                    val alreadyInActive = activeCollection.items.any {
                        it.word.equals(currentWord, ignoreCase = true) &&
                            it.sentence.equals(currentSentence, ignoreCase = true)
                    }
                    val isActiveFull = !alreadyInActive &&
                        activeCollection.items.size >= WordCollectionRepository.MAX_WORDS_PER_COLLECTION

                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.cardBg,
                            border = BorderStroke(0.75.dp, palette.accent.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "CURRENT WORD",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp,
                                        ),
                                        color = palette.accent,
                                    )
                                    Text(
                                        text = currentWord,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (currentEntry == null) {
                                        Text(
                                            text = "Saves word & context immediately",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.textMuted,
                                        )
                                    }
                                }

                                Surface(
                                    onClick = {
                                        if (isActiveFull) {
                                            Toast.makeText(
                                                context,
                                                "Collection limit reached (${WordCollectionRepository.MAX_WORDS_PER_COLLECTION} words max). Export to Anki or remove words first.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        } else {
                                            onSaveToCollection(activeCollection.id)
                                        }
                                    },
                                    shape = CircleShape,
                                    color = when {
                                        alreadyInActive -> palette.elevatedPillBg
                                        isActiveFull -> palette.danger.copy(alpha = 0.2f)
                                        else -> palette.accent
                                    },
                                    border = BorderStroke(
                                        0.75.dp,
                                        if (isActiveFull) palette.danger.copy(alpha = 0.55f) else palette.accent.copy(alpha = 0.5f),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (alreadyInActive) Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
                                            contentDescription = null,
                                            tint = when {
                                                alreadyInActive -> palette.accent
                                                isActiveFull -> palette.danger
                                                else -> palette.onAccent
                                            },
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = when {
                                                alreadyInActive -> "Saved"
                                                isActiveFull -> "Full (15/15)"
                                                else -> "Save Here"
                                            },
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = when {
                                                alreadyInActive -> palette.accent
                                                isActiveFull -> palette.danger
                                                else -> palette.onAccent
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Collection Selector Dropdown + "Create collection" & "Delete collection" Controls
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = palette.cardBg,
                        border = BorderStroke(0.75.dp, palette.border),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "COLLECTION (${collectionIndex.size}/${WordCollectionRepository.MAX_COLLECTIONS})",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.8.sp,
                                        ),
                                        color = palette.textMuted,
                                    )
                                }

                                Surface(
                                    onClick = {
                                        if (!isCreatingCollection && isAtMaxCollections) {
                                            Toast.makeText(
                                                context,
                                                "Collection limit reached (${WordCollectionRepository.MAX_COLLECTIONS} collections max). Delete a collection first.",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        } else {
                                            newCollectionName = ""
                                            pendingDeleteCollectionId = null
                                            pendingClearCollectionId = null
                                            onCreatingCollectionChange(!isCreatingCollection)
                                        }
                                    },
                                    shape = CircleShape,
                                    color = if (!isCreatingCollection && isAtMaxCollections) {
                                        palette.danger.copy(alpha = 0.16f)
                                    } else {
                                        palette.elevatedPillBg
                                    },
                                    border = BorderStroke(
                                        0.75.dp,
                                        if (!isCreatingCollection && isAtMaxCollections) {
                                            palette.danger.copy(alpha = 0.5f)
                                        } else {
                                            palette.accent.copy(alpha = 0.45f)
                                        },
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (isCreatingCollection) Icons.Rounded.Close else Icons.Rounded.Add,
                                            contentDescription = null,
                                            tint = if (!isCreatingCollection && isAtMaxCollections) palette.danger else palette.accent,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = when {
                                                isCreatingCollection -> "Cancel"
                                                isAtMaxCollections -> "Max (15/15)"
                                                else -> "Create collection"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (!isCreatingCollection && isAtMaxCollections) palette.danger else palette.textPrimary,
                                        )
                                    }
                                }
                            }

                            if (isAtMaxCollections) {
                                Text(
                                    text = "Maximum 15 collections reached. Delete an existing collection to create a new one.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.danger,
                                )
                            }

                            // Inline Create Collection Input
                            if (isCreatingCollection) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = newCollectionName,
                                        onValueChange = { newCollectionName = it },
                                        placeholder = {
                                            Text(
                                                text = "Collection name...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = palette.textMuted,
                                            )
                                        },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.textPrimary),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = palette.accent,
                                            unfocusedBorderColor = palette.border,
                                            cursorColor = palette.accent,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Surface(
                                        onClick = {
                                            if (newCollectionName.isNotBlank()) {
                                                onCreateCollection(newCollectionName)
                                                newCollectionName = ""
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (newCollectionName.isNotBlank()) palette.accent else palette.elevatedPillBg,
                                    ) {
                                        Text(
                                            text = "Create",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (newCollectionName.isNotBlank()) palette.onAccent else palette.textMuted,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        )
                                    }
                                }
                            }

                            // Collection Dropdown Menu + Delete Collection Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    Surface(
                                        onClick = {
                                            if (collectionIndex.isNotEmpty()) {
                                                collectionSearchQuery = ""
                                                collectionDropdownExpanded = true
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = palette.elevatedPillBg,
                                        border = BorderStroke(0.75.dp, palette.border),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = if (activeCollection != null) {
                                                    "${activeCollection.name} (${activeCollection.items.size}/${WordCollectionRepository.MAX_WORDS_PER_COLLECTION})"
                                                } else {
                                                    "No collections"
                                                },
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = palette.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(
                                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                                contentDescription = "Select collection",
                                                tint = palette.accent,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }

                                    val showSearchAndRecent = collectionIndex.size > 6
                                    val visibleDropdownCollections = remember(collectionIndex, collectionSearchQuery, showSearchAndRecent) {
                                        if (!showSearchAndRecent) {
                                            collectionIndex
                                        } else {
                                            val q = collectionSearchQuery.trim()
                                            if (q.isEmpty()) {
                                                collectionIndex
                                                    .sortedByDescending { it.updatedAtMillis }
                                                    .take(3)
                                            } else {
                                                collectionIndex.filter { it.name.contains(q, ignoreCase = true) }
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = collectionDropdownExpanded,
                                        onDismissRequest = {
                                            collectionDropdownExpanded = false
                                            collectionSearchQuery = ""
                                        },
                                        modifier = Modifier.background(palette.cardBg),
                                    ) {
                                        if (showSearchAndRecent) {
                                            OutlinedTextField(
                                                value = collectionSearchQuery,
                                                onValueChange = { collectionSearchQuery = it },
                                                placeholder = {
                                                    Text(
                                                        text = "Search collections...",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = palette.textMuted,
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Search,
                                                        contentDescription = null,
                                                        tint = palette.textMuted,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                },
                                                trailingIcon = if (collectionSearchQuery.isNotEmpty()) {
                                                    {
                                                        IconButton(
                                                            onClick = { collectionSearchQuery = "" },
                                                            modifier = Modifier.size(24.dp),
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Close,
                                                                contentDescription = "Clear search",
                                                                tint = palette.textMuted,
                                                                modifier = Modifier.size(14.dp),
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    null
                                                },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.textPrimary),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = palette.accent,
                                                    unfocusedBorderColor = palette.border,
                                                    cursorColor = palette.accent,
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                            )

                                            if (collectionSearchQuery.trim().isEmpty()) {
                                                Text(
                                                    text = "Recent collections",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.6.sp,
                                                    ),
                                                    color = palette.textMuted,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                                )
                                            }
                                        }

                                        if (visibleDropdownCollections.isEmpty()) {
                                            Text(
                                                text = "No matching collections",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = palette.textMuted,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            )
                                        } else {
                                            visibleDropdownCollections.forEach { col ->
                                                val isSelected = col.id == activeCollection?.id
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        ) {
                                                            Text(
                                                                text = "${col.name} (${col.wordCount}/${WordCollectionRepository.MAX_WORDS_PER_COLLECTION})",
                                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                ),
                                                                color = if (isSelected) palette.accent else palette.textPrimary,
                                                            )
                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Rounded.Check,
                                                                    contentDescription = null,
                                                                    tint = palette.accent,
                                                                    modifier = Modifier.size(16.dp),
                                                                )
                                                            }
                                                        }
                                                    },
                                                    onClick = {
                                                        collectionDropdownExpanded = false
                                                        collectionSearchQuery = ""
                                                        pendingDeleteCollectionId = null
                                                        pendingClearCollectionId = null
                                                        onSelectCollection(col.id)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                if (activeCollection != null) {
                                    val isConfirmingDelete = pendingDeleteCollectionId == activeCollection.id
                                    Surface(
                                        onClick = {
                                            if (isConfirmingDelete) {
                                                pendingDeleteCollectionId = null
                                                onDeleteCollection(activeCollection.id)
                                            } else {
                                                pendingDeleteCollectionId = activeCollection.id
                                                pendingClearCollectionId = null
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isConfirmingDelete) {
                                            palette.danger.copy(alpha = 0.22f)
                                        } else {
                                            palette.elevatedPillBg
                                        },
                                        border = BorderStroke(0.75.dp, palette.danger.copy(alpha = 0.55f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "Delete collection",
                                                tint = palette.danger,
                                                modifier = Modifier.size(17.dp),
                                            )
                                            if (isConfirmingDelete) {
                                                Text(
                                                    text = "Delete?",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = palette.danger,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Words List Header + Clear All Option
                if (activeCollection != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "SAVED WORDS (${activeCollection.items.size}/${WordCollectionRepository.MAX_WORDS_PER_COLLECTION})",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                ),
                                color = palette.textMuted,
                            )

                            if (activeCollection.items.isNotEmpty()) {
                                val isConfirmingClear = pendingClearCollectionId == activeCollection.id
                                Text(
                                    text = if (isConfirmingClear) "Confirm clear all?" else "Clear words",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isConfirmingClear) palette.danger else palette.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            if (isConfirmingClear) {
                                                pendingClearCollectionId = null
                                                onClearCollection(activeCollection.id)
                                            } else {
                                                pendingClearCollectionId = activeCollection.id
                                                pendingDeleteCollectionId = null
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    if (activeCollection.items.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = palette.cardBg,
                                border = BorderStroke(0.75.dp, palette.border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "No words saved in \"${activeCollection.name}\" yet. Save a word above or create another collection.",
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                    color = palette.textMuted,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    } else {
                        items(
                            items = activeCollection.items,
                            key = { it.id },
                        ) { savedItem ->
                            SavedWordRowCard(
                                item = savedItem,
                                palette = palette,
                                onSelect = { onSelectWord(savedItem) },
                                onDelete = { onRemoveWord(activeCollection.id, savedItem.id) },
                            )
                        }
                    }
                }
            }

            // 3. Docked Bottom Primary CTA ("Export words to Anki")
            if (activeCollection != null) {
                HorizontalDivider(thickness = 0.5.dp, color = palette.border)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.background)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    val canExport = activeCollection.items.isNotEmpty() && !isExportingToAnki
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(CircleShape)
                            .then(
                                if (canExport) {
                                    Modifier.background(palette.ctaBrush)
                                } else {
                                    Modifier.background(palette.elevatedPillBg)
                                },
                            )
                            .clickable(enabled = canExport) {
                                onExportCollectionToAnki(activeCollection)
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Upload,
                                contentDescription = null,
                                tint = if (canExport) Color.White else palette.textMuted,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = when {
                                    isExportingToAnki -> "Exporting (${activeCollection.items.size}) to Anki..."
                                    activeCollection.items.isEmpty() -> "Export words to Anki (0)"
                                    else -> "Export words to Anki (${activeCollection.items.size})"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = if (canExport) Color.White else palette.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedWordRowCard(
    item: SavedWordItem,
    palette: CollectionThemePalette,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        color = palette.cardBg,
        border = BorderStroke(0.75.dp, palette.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.word,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = palette.accent,
                    )
                    if (item.reading.isNotBlank() && item.reading != item.word) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = palette.elevatedPillBg,
                            border = BorderStroke(0.5.dp, palette.border),
                        ) {
                            Text(
                                text = item.reading,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (!item.imageFileName.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = palette.accent.copy(alpha = 0.14f),
                            border = BorderStroke(0.5.dp, palette.accent.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = "Has attached image",
                                    tint = palette.accent,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = if (item.imageSource == StagedImageSource.SCREENSHOT.storageValue) "Shot" else "Img",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = palette.accent,
                                )
                            }
                        }
                    }
                }

                if (item.contextualMeaning.isNotBlank()) {
                    Text(
                        text = item.contextualMeaning,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.sentence.isNotBlank()) {
                    Text(
                        text = item.sentence,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete word",
                    tint = palette.danger.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MissingApiKeyBody(
    isPaperVermilion: Boolean = false,
    onSaveApiKey: (String) -> Unit,
) {
    var apiKeyInput by remember { mutableStateOf("") }
    val primaryInk = if (isPaperVermilion) Color(0xFF1E1B18) else MaterialTheme.colorScheme.onSurface
    val secondaryInk = if (isPaperVermilion) Color(0xFF6B6257) else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = if (isPaperVermilion) Color(0xFFC84B31) else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(30.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Groq API Key Required",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = primaryInk,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Enter your Groq API key to enable context-aware AI definitions.",
            style = MaterialTheme.typography.bodySmall,
            color = secondaryInk,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            placeholder = {
                Text(
                    "gsk_...",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryInk,
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = primaryInk),
            colors = if (isPaperVermilion) {
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = Color(0xFFD8CFC2),
                    cursorColor = accentColor,
                )
            } else {
                OutlinedTextFieldDefaults.colors()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(10.dp))
        FilledTonalButton(
            onClick = { onSaveApiKey(apiKeyInput) },
            enabled = apiKeyInput.trim().isNotEmpty(),
            colors = if (isPaperVermilion) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = accentColor,
                    contentColor = Color(0xFFFFFDF9),
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Save Key & Lookup", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ContextSentenceBanner(
    sentence: String,
    targetWord: String,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val annotatedSentence = remember(sentence, targetWord, primaryColor) {
        buildAnnotatedString {
            if (targetWord.isEmpty()) {
                append(sentence)
            } else {
                val startIndex = sentence.indexOf(targetWord, ignoreCase = true)
                if (startIndex >= 0) {
                    append(sentence.substring(0, startIndex))
                    withStyle(
                        SpanStyle(
                            color = primaryColor,
                            fontWeight = FontWeight.Bold,
                            background = primaryColor.copy(alpha = 0.14f),
                        )
                    ) {
                        append(sentence.substring(startIndex, startIndex + targetWord.length))
                    }
                    append(sentence.substring(startIndex + targetWord.length))
                } else {
                    append(sentence)
                }
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = annotatedSentence,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    isPrimary: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isPrimary) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isPrimary) 0.75.dp else 0.5.dp,
                color = if (isPrimary) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                },
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isPrimary) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    },
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.9.sp,
                    ),
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    },
                )
            }
            content()
        }
    }
}

@Composable
private fun NuanceChip(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$label:",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ErrorStateBody(
    message: String,
    isPaperVermilion: Boolean = false,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPaperVermilion) Color(0xFFB3261E) else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        FilledTonalButton(
            onClick = onRetry,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            colors = if (isPaperVermilion) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFFC84B31),
                    contentColor = Color(0xFFFFFDF9),
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Retry", style = MaterialTheme.typography.labelMedium)
        }
    }
}
