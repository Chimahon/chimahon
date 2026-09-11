/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Code is a mix between PlayerViewModel from mpvKt and the former
 * PlayerViewModel from Aniyomi.
 */

package eu.kanade.tachiyomi.ui.player

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Immutable
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.entries.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.episode.model.toDbEpisode
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.settings.screen.player.custombutton.CustomButtonFetchState
import eu.kanade.presentation.more.settings.screen.player.custombutton.getButtons
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.SerializableHoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.database.models.toDomainEpisode
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.player.controls.components.IndexedSegment
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.getChangedAt
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.ui.player.mining.MediaCaptureService
import eu.kanade.tachiyomi.ui.player.mining.SentenceAudioMpvPropertyReader
import eu.kanade.tachiyomi.ui.player.mining.SentenceAudioMpvSnapshotReader
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.youtube.YouTubePreferences
import eu.kanade.tachiyomi.ui.youtube.YouTubeResolver
import eu.kanade.tachiyomi.ui.youtube.allowsExternalSubtitleLookup
import eu.kanade.tachiyomi.ui.player.utils.AniSkipApi
import eu.kanade.tachiyomi.ui.player.utils.ChapterUtils.Companion.getStringRes
import eu.kanade.tachiyomi.ui.player.utils.JimakuApi
import eu.kanade.tachiyomi.ui.player.utils.JimakuEntry
import eu.kanade.tachiyomi.ui.player.utils.JimakuFile
import eu.kanade.tachiyomi.ui.player.utils.JimakuMediaGuess
import eu.kanade.tachiyomi.ui.player.utils.TrackSelect
import eu.kanade.tachiyomi.ui.player.utils.applySubtitleRegexFilters
import eu.kanade.tachiyomi.ui.player.utils.subtitleRegexFilterOptions
import eu.kanade.tachiyomi.ui.player.utils.displayName
import eu.kanade.tachiyomi.ui.player.utils.guessJimakuMedia
import eu.kanade.tachiyomi.ui.player.utils.inPlaybackOrder
import eu.kanade.tachiyomi.ui.player.utils.matchedSrtFiles
import eu.kanade.tachiyomi.ui.player.utils.selectBestJimakuEntry
import eu.kanade.tachiyomi.ui.reader.SaveImageNotifier
import eu.kanade.tachiyomi.ui.youtube.YouTubeSource
import eu.kanade.tachiyomi.ui.youtube.YouTubeVideoMetadata
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.episode.filterDownloadedEpisodes
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.model.CustomButton
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.repository.EpisodeRepository
import tachiyomi.domain.episode.service.getEpisodeSort
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.model.AnimeHistoryUpdate
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.i18n.MR
import tachiyomi.source.local.entries.anime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.first
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_SUBTITLE_HISTORY = 120
private const val VIDEO_SELECTION_DELIMITER = "\u001e"
class PlayerViewModelProviderFactory(
    private val activity: PlayerActivity,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return PlayerViewModel(activity, extras.createSavedStateHandle()) as T
    }
}

class PlayerViewModel @JvmOverloads constructor(
    private val activity: PlayerActivity,
    private val savedState: SavedStateHandle,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val episodeRepository: EpisodeRepository = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackEpisode: TrackEpisode = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val getAnimeCategories: GetCategories = Injekt.get(),
    private val getTracks: GetAnimeTracks = Injekt.get(),
    private val upsertAnimeHistory: UpsertAnimeHistory = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val setAnimeViewerFlags: SetAnimeViewerFlags = Injekt.get(),
    internal val playerPreferences: PlayerPreferences = Injekt.get(),
    internal val gesturePreferences: GesturePreferences = Injekt.get(),
    internal val subtitlePreferences: SubtitlePreferences = Injekt.get(),
    private val dictionaryPreferences: DictionaryPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val getCustomButtons: GetCustomButtons = Injekt.get(),
    private val trackSelect: TrackSelect = Injekt.get(),
    private val jimakuApi: JimakuApi = JimakuApi(),
    uiPreferences: UiPreferences = Injekt.get(),
) : ViewModel() {

    private val _currentPlaylist = MutableStateFlow<List<Episode>>(emptyList())
    val currentPlaylist = _currentPlaylist.asStateFlow()

    private val _hasPreviousEpisode = MutableStateFlow(false)
    val hasPreviousEpisode = _hasPreviousEpisode.asStateFlow()

    private val _hasNextEpisode = MutableStateFlow(false)
    val hasNextEpisode = _hasNextEpisode.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode = _currentEpisode.asStateFlow()

    private val _currentAnime = MutableStateFlow<Anime?>(null)
    val currentAnime = _currentAnime.asStateFlow()

    private val _currentSource = MutableStateFlow<AnimeSource?>(null)
    val currentSource = _currentSource.asStateFlow()

    private val _isEpisodeOnline = MutableStateFlow(false)
    val isEpisodeOnline = _isEpisodeOnline.asStateFlow()

    private val _isLoadingEpisode = MutableStateFlow(false)
    val isLoadingEpisode = _isLoadingEpisode.asStateFlow()

    private val _currentDecoder = MutableStateFlow(getDecoderFromValue(MPVLib.getPropertyString("hwdec")))
    val currentDecoder = _currentDecoder.asStateFlow()

    val mediaTitle = MutableStateFlow("")
    val animeTitle = MutableStateFlow("")

    val isLoading = MutableStateFlow(true)
    val playbackSpeed = MutableStateFlow(playerPreferences.playerSpeed().get())

    private val _subtitleTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val subtitleTracks = _subtitleTracks.asStateFlow()
    private val _selectedSubtitles = MutableStateFlow(Pair(-1, -1))
    val selectedSubtitles = _selectedSubtitles.asStateFlow()
    private val _jimakuState = MutableStateFlow<JimakuState>(JimakuState.Idle)
    val jimakuState = _jimakuState.asStateFlow()
    private val _currentSubtitleText = MutableStateFlow("")
    val currentSubtitleText = _currentSubtitleText.asStateFlow()
    private val _secondaryCurrentSubtitleText = MutableStateFlow("")
    val secondaryCurrentSubtitleText = _secondaryCurrentSubtitleText.asStateFlow()
    private val _subtitlesVisible = MutableStateFlow(true)
    val subtitlesVisible = _subtitlesVisible.asStateFlow()
    private val _subtitleHistory = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val subtitleHistory = _subtitleHistory.asStateFlow()
    private val _activeSubtitleCueIndex = MutableStateFlow<Int?>(null)
    val activeSubtitleCueIndex = _activeSubtitleCueIndex.asStateFlow()
    private val _primarySubtitleDelaySeconds = MutableStateFlow(0.0)
    val primarySubtitleDelaySeconds = _primarySubtitleDelaySeconds.asStateFlow()
    private val _subtitleSpeedSeconds = MutableStateFlow(1.0)
    val subtitleSpeedSeconds = _subtitleSpeedSeconds.asStateFlow()
    private var currentRawSubtitleText = ""
    private var lastSubtitleHistoryText = ""
    private var nextSubtitleCueIndex = 0
    private val rawParsedSubtitleCuesByTrackId = mutableMapOf<Int, List<SubtitleCue>>()
    private val rawParsedSubtitleCuesByTitle = mutableMapOf<String, List<SubtitleCue>>()
    private val parsedSubtitleCuesByTrackId = mutableMapOf<Int, List<SubtitleCue>>()
    private val parsedSubtitleCuesByTitle = mutableMapOf<String, List<SubtitleCue>>()
    private var showingParsedSubtitleTrackId: Int? = null
    private var lastAutoJimakuKey: String? = null

    private val _audioTracks = MutableStateFlow<List<VideoTrack>>(emptyList())
    val audioTracks = _audioTracks.asStateFlow()
    private val _selectedAudio = MutableStateFlow(-1)
    val selectedAudio = _selectedAudio.asStateFlow()

    val isLoadingTracks = MutableStateFlow(true)
    val isCasting = MutableStateFlow(false)

    private val _hosterList = MutableStateFlow<List<Hoster>>(emptyList())
    val hosterList = _hosterList.asStateFlow()
    private val _isLoadingHosters = MutableStateFlow(true)
    val isLoadingHosters = _isLoadingHosters.asStateFlow()
    private val _hosterState = MutableStateFlow<List<HosterState>>(emptyList())
    val hosterState = _hosterState.asStateFlow()
    private val _hosterExpandedList = MutableStateFlow<List<Boolean>>(emptyList())
    val hosterExpandedList = _hosterExpandedList.asStateFlow()
    private val _selectedHosterVideoIndex = MutableStateFlow(Pair(-1, -1))
    val selectedHosterVideoIndex = _selectedHosterVideoIndex.asStateFlow()
    private val _currentVideo = MutableStateFlow<Video?>(null)
    val currentVideo = _currentVideo.asStateFlow()
    private var videoClickHandler: (Int, Int) -> Unit = ::onSourceVideoClicked

    private val _chapters = MutableStateFlow<List<IndexedSegment>>(emptyList())
    val chapters = _chapters.asStateFlow()
    private val _currentChapter = MutableStateFlow<IndexedSegment?>(null)
    val currentChapter = _currentChapter.asStateFlow()
    private val _skipIntroText = MutableStateFlow<String?>(null)
    val skipIntroText = _skipIntroText.asStateFlow()

    private val _pos = MutableStateFlow(0f)
    val pos = _pos.asStateFlow()

    private var castProgressJob: Job? = null
    private var youtubeLoadJob: Job? = null

    val duration = MutableStateFlow(0f)

    private val _readAhead = MutableStateFlow(0f)
    val readAhead = _readAhead.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()

    // False because the video shouldn't start paused
    private val _pausedState = MutableStateFlow<Boolean?>(false)
    val pausedState = _pausedState.asStateFlow()

    private val _controlsShown = MutableStateFlow(!playerPreferences.hideControls().get())
    val controlsShown = _controlsShown.asStateFlow()
    private val _seekBarShown = MutableStateFlow(!playerPreferences.hideControls().get())
    val seekBarShown = _seekBarShown.asStateFlow()
    private val _areControlsLocked = MutableStateFlow(false)
    val areControlsLocked = _areControlsLocked.asStateFlow()

    val playerUpdate = MutableStateFlow<PlayerUpdates>(PlayerUpdates.None)
    val isBrightnessSliderShown = MutableStateFlow(false)
    val isVolumeSliderShown = MutableStateFlow(false)
    val currentBrightness = MutableStateFlow(
        runCatching {
            Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                .normalize(0f, 255f, 0f, 1f)
        }.getOrElse { 0f },
    )
    val currentVolume = MutableStateFlow(activity.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    val currentMPVVolume = MutableStateFlow(MPVLib.getPropertyInt("volume"))
    var volumeBoostCap: Int = MPVLib.getPropertyInt("volume-max")

    // Pair(startingPosition, seekAmount)
    val gestureSeekAmount = MutableStateFlow<Pair<Int, Int>?>(null)

    val sheetShown = MutableStateFlow(Sheets.None)
    val panelShown = MutableStateFlow(Panels.None)
    val dialogShown = MutableStateFlow<Dialogs>(Dialogs.None)

    private val _dismissSheet = MutableStateFlow(false)
    val dismissSheet = _dismissSheet.asStateFlow()

    private val _seekText = MutableStateFlow<String?>(null)
    val seekText = _seekText.asStateFlow()
    private val _doubleTapSeekAmount = MutableStateFlow(0)
    val doubleTapSeekAmount = _doubleTapSeekAmount.asStateFlow()
    private val _isSeekingForwards = MutableStateFlow(false)
    val isSeekingForwards = _isSeekingForwards.asStateFlow()

    private var timerJob: Job? = null
    private val _remainingTime = MutableStateFlow(0)
    val remainingTime = _remainingTime.asStateFlow()

    val cachePath: String = activity.cacheDir.path

    internal val mediaCapture = MediaCaptureService(
        context = activity,
        cachePath = cachePath,
        getVideo = { currentVideo.value },
        getSource = { currentSource.value },
        getTimeSeconds = { activity.player.timePos?.toDouble() ?: pos.value.toDouble() },
        getOcrPaddingSeconds = { dictionaryPreferences.videoOcrSentenceAudioPaddingSeconds().get().toDouble() },
        readMpvSnapshot = {
            SentenceAudioMpvSnapshotReader(object : SentenceAudioMpvPropertyReader {
                override fun string(name: String): String? = MPVLib.getPropertyString(name)
                override fun int(name: String): Int? = MPVLib.getPropertyInt(name)
                override fun boolean(name: String): Boolean? = MPVLib.getPropertyBoolean(name)
            }).read()
        },
    )

    private val _customButtons = MutableStateFlow<CustomButtonFetchState>(CustomButtonFetchState.Loading)

    private val _ocrScreenshot = MutableStateFlow<Bitmap?>(null)
    val ocrScreenshot: StateFlow<Bitmap?> = _ocrScreenshot.asStateFlow()
    private val _isCapturingOcr = MutableStateFlow(false)
    val isCapturingOcr: StateFlow<Boolean> = _isCapturingOcr.asStateFlow()
    private val _suppressTap = MutableStateFlow(false)
    val suppressTap: StateFlow<Boolean> = _suppressTap.asStateFlow()
    val customButtons = _customButtons.asStateFlow()

    private val _primaryButtonTitle = MutableStateFlow("")
    val primaryButtonTitle = _primaryButtonTitle.asStateFlow()

    private val _primaryButton = MutableStateFlow<CustomButton?>(null)
    val primaryButton = _primaryButton.asStateFlow()

    init {
        viewModelScope.launchIO {
            try {
                val buttons = getCustomButtons.getAll()
                buttons.firstOrNull { it.isFavorite }?.let {
                    _primaryButton.update { _ -> it }
                    // If the button text is not empty, it has been set buy a lua script in which
                    // case we don't want to override it
                    if (_primaryButtonTitle.value.isEmpty()) {
                        setPrimaryCustomButtonTitle(it)
                    }
                }
                activity.setupCustomButtons(buttons)
                _customButtons.update { _ -> CustomButtonFetchState.Success(buttons.toImmutableList()) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _customButtons.update { _ -> CustomButtonFetchState.Error(e.message ?: "Unable to fetch buttons") }
            }
        }
    }

    /**
     * Starts a sleep timer/cancels the current timer if [seconds] is less than 1.
     */
    fun startTimer(seconds: Int) {
        timerJob?.cancel()
        _remainingTime.value = seconds
        if (seconds < 1) return
        timerJob = viewModelScope.launch {
            for (time in seconds downTo 0) {
                _remainingTime.value = time
                delay(1000)
            }
            pause()
            withUIContext { Injekt.get<Application>().toast(MR.strings.toast_sleep_timer_ended) }
        }
    }

    fun isEpisodeOnline(): Boolean? {
        val anime = currentAnime.value ?: return null
        val episode = currentEpisode.value ?: return null
        val source = currentSource.value ?: return null
        return source is AnimeHttpSource &&
            !EpisodeLoader.isDownload(
                episode.toDomainEpisode()!!,
                anime,
            )
    }

    fun updateIsLoadingEpisode(value: Boolean) {
        _isLoadingEpisode.update { _ -> value }
    }

    private fun updateEpisodeList(episodeList: List<Episode>) {
        _currentPlaylist.update { _ -> filterEpisodeList(episodeList) }
    }

    fun getDecoder() {
        _currentDecoder.update { getDecoderFromValue(activity.player.hwdecActive) }
    }

    fun updateDecoder(decoder: Decoder) {
        MPVLib.setPropertyString("hwdec", decoder.value)
    }

    val getTrackLanguage: (Int) -> String = {
        if (it != -1) {
            MPVLib.getPropertyString("track-list/$it/lang") ?: ""
        } else {
            activity.stringResource(MR.strings.off)
        }
    }
    val getTrackTitle: (Int) -> String = {
        if (it != -1) {
            MPVLib.getPropertyString("track-list/$it/title") ?: ""
        } else {
            activity.stringResource(MR.strings.off)
        }
    }
    val getTrackMPVId: (Int) -> Int = {
        if (it != -1) {
            MPVLib.getPropertyInt("track-list/$it/id")
        } else {
            -1
        }
    }
    val getTrackType: (Int) -> String? = {
        MPVLib.getPropertyString("track-list/$it/type")
    }
    val getTrackExternalFilename: (Int) -> String? = {
        if (it != -1) {
            MPVLib.getPropertyString("track-list/$it/external-filename")
                ?.takeIf { filename -> filename.isNotBlank() }
        } else {
            null
        }
    }

    private var trackLoadingJob: Job? = null
    fun loadTracks() {
        trackLoadingJob?.cancel()
        trackLoadingJob = viewModelScope.launch {
            val possibleTrackTypes = listOf("audio", "sub")
            val subTracks = mutableListOf<VideoTrack>()
            val audioTracks = mutableListOf(
                VideoTrack(-1, activity.stringResource(MR.strings.off), null),
            )
            try {
                val tracksCount = MPVLib.getPropertyInt("track-list/count") ?: 0
                for (i in 0..<tracksCount) {
                    val type = getTrackType(i)
                    if (!possibleTrackTypes.contains(type) || type == null) continue
                    when (type) {
                        "sub" -> {
                            val track = VideoTrack(
                                id = getTrackMPVId(i),
                                name = getTrackTitle(i),
                                language = getTrackLanguage(i),
                                externalFilename = getTrackExternalFilename(i),
                            )
                            subTracks.add(track)
                            rememberParsedSubtitleTrack(track)
                        }
                        "audio" -> audioTracks.add(VideoTrack(getTrackMPVId(i), getTrackTitle(i), getTrackLanguage(i)))
                        else -> error("Unrecognized track type")
                    }
                }
            } catch (e: NullPointerException) {
                logcat(LogPriority.ERROR) { "Couldn't load tracks, probably cause mpv was destroyed" }
                return@launch
            }
            _subtitleTracks.update { subTracks }
            _audioTracks.update { audioTracks }
            applyParsedSubtitleCuesForSelectedTrack()

            if (!isLoadingTracks.value) {
                onFinishLoadingTracks()
            }
        }
    }

    /**
     * When all subtitle/audio tracks are loaded, select the preferred one based on preferences,
     * or select the first one in the list if trackSelect fails.
     */
    fun onFinishLoadingTracks() {
        if (!restoreSubtitleSelectionForCurrentEpisode()) {
            val preferredSubtitle = trackSelect.getPreferredTrackIndex(subtitleTracks.value)
            (preferredSubtitle ?: subtitleTracks.value.firstOrNull())?.let {
                activity.player.sid = it.id
                activity.player.secondarySid = -1
                applyParsedSubtitleCuesForTrack(it.id)
            }
        }

        val preferredAudio = trackSelect.getPreferredTrackIndex(audioTracks.value, subtitle = false)
        (preferredAudio ?: audioTracks.value.getOrNull(1))?.let {
            activity.player.aid = it.id
        }

        isLoadingTracks.update { _ -> true }
        updateIsLoadingEpisode(false)
        setPausedState()
    }

    @Immutable
    data class VideoTrack(
        val id: Int,
        val name: String,
        val language: String?,
        val externalFilename: String? = null,
    )

    @Immutable
    data class SubtitleCue(
        val index: Int,
        val text: String,
        val positionSeconds: Double,
        val endPositionSeconds: Double = positionSeconds + 5.0,
        val rawText: String = text,
    )

    sealed interface JimakuState {
        data object Idle : JimakuState
        data object ApiKeyRequired : JimakuState
        data class Searching(val title: String) : JimakuState
        data class EntryResults(val title: String, val entries: List<JimakuEntry>) : JimakuState
        data class LoadingFiles(val entry: JimakuEntry) : JimakuState
        data class FileResults(val entry: JimakuEntry, val files: List<JimakuFile>) : JimakuState
        data class Downloading(val file: JimakuFile) : JimakuState
        data class Error(val message: String) : JimakuState
    }

    fun loadChapters() {
        val chapters = mutableListOf<IndexedSegment>()
        val count = MPVLib.getPropertyInt("chapter-list/count")!!
        for (i in 0 until count) {
            val title = MPVLib.getPropertyString("chapter-list/$i/title")
            val time = MPVLib.getPropertyInt("chapter-list/$i/time")!!
            chapters.add(
                IndexedSegment(
                    name = title,
                    start = time.toFloat(),
                    index = 0,
                ),
            )
        }
        updateChapters(chapters.sortedBy { it.start })
    }

    fun updateChapters(chapters: List<IndexedSegment>) {
        _chapters.update { _ -> chapters }
    }

    fun selectChapter(index: Int) {
        val time = chapters.value[index].start
        seekTo(time.toInt())
    }

    fun updateChapter(index: Long) {
        if (chapters.value.isEmpty() || index == -1L) return
        _currentChapter.update { chapters.value.getOrNull(index.toInt()) ?: return }
    }

    fun addAudio(uri: Uri) {
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        val path = (if (isContentUri) uri.openContentFd(activity) else url)
            ?: return
        val name = if (isContentUri) uri.getFileName(activity) else null
        val title = name ?: url.substringAfterLast('/')
        rememberAddedAudioForCurrentEpisode(
            AddedAudioTrack(
                uriString = url,
                path = path,
                title = title,
                isContentUri = isContentUri,
            ),
        )
        if (name == null) {
            MPVLib.command(arrayOf("audio-add", path, "cached"))
        } else {
            MPVLib.command(arrayOf("audio-add", path, "cached", name))
        }
        loadTracks()
    }

    fun selectAudio(id: Int) {
        activity.player.aid = id
    }

    fun updateAudio(id: Int) {
        _selectedAudio.update { id }
    }

    fun addSubtitle(uri: Uri) {
        val url = uri.toString()
        val isContentUri = url.startsWith("content://")
        val path = (if (isContentUri) uri.openContentFd(activity) else url)
            ?: return
        val name = if (isContentUri) uri.getFileName(activity) else null
        val parsedCues = parseSubtitleUriOrPath(uri, path, name)
        val title = name ?: subtitleTitleFromUriOrPath(uri, path)
        rememberAddedSubtitleForCurrentEpisode(
            AddedSubtitleTrack(
                uriString = url,
                path = path,
                title = title,
                isContentUri = isContentUri,
            ),
        )
        if (name == null) {
            MPVLib.command(arrayOf("sub-add", path, "cached"))
        } else {
            MPVLib.command(arrayOf("sub-add", path, "cached", name))
        }
        rememberParsedSubtitleCues(title, parsedCues)
        loadTracks()
    }

    fun dismissJimakuDialog() {
        _jimakuState.update { JimakuState.Idle }
    }

    fun updateJimakuTitle(title: String) {
        subtitlePreferences.jimakuTitleForAnime(currentAnime.value?.id).set(title.trim())
    }

    fun saveJimakuApiKeyAndSearch(apiKey: String) {
        val trimmedApiKey = apiKey.trim()
        if (trimmedApiKey.isBlank()) return

        subtitlePreferences.jimakuApiKey().set(trimmedApiKey)
        _jimakuState.update { JimakuState.Idle }
        searchJimakuSubtitles()
    }

    fun getCurrentJimakuTitle(): String {
        return guessCurrentJimakuMedia().title
    }

    fun searchJimakuSubtitles() {
        if (!currentVideoAllowsExternalSubtitleLookup()) return

        val apiKey = subtitlePreferences.jimakuApiKey().get().trim()
        if (apiKey.isBlank()) {
            _jimakuState.update { JimakuState.ApiKeyRequired }
            return
        }

        val guess = guessCurrentJimakuMedia()
        if (guess.title.isBlank()) {
            activity.toast("Set a Jimaku title before searching")
            return
        }

        loadJimakuSubtitles(apiKey, guess, showFeedback = true)
    }

    fun autoLoadJimakuSubtitlesOnVideoOpen() {
        if (!currentVideoAllowsExternalSubtitleLookup()) return

        val apiKey = subtitlePreferences.jimakuApiKey().get().trim()
        if (apiKey.isBlank()) return

        val guess = guessCurrentJimakuMedia()
        if (guess.title.isBlank()) return

        val key = listOf(
            currentEpisode.value?.id?.toString().orEmpty(),
            currentVideo.value?.videoUrl.orEmpty(),
            guess.title,
            guess.season?.toString().orEmpty(),
            guess.episode?.toString().orEmpty(),
        ).joinToString("|")
        if (lastAutoJimakuKey == key) return
        lastAutoJimakuKey = key

        loadJimakuSubtitles(apiKey, guess, showFeedback = false)
    }

    private fun loadJimakuSubtitles(apiKey: String, guess: JimakuMediaGuess, showFeedback: Boolean) {
        _jimakuState.update { JimakuState.Searching(guess.displayName()) }
        viewModelScope.launchIO {
            try {
                val entries = jimakuApi.searchEntries(apiKey, guess.title)
                if (entries.isEmpty()) {
                    updateJimakuFailure("No Jimaku entries found for \"${guess.title}\"", showFeedback)
                    return@launchIO
                }

                val entry = selectBestJimakuEntry(entries, guess.title)
                    ?: entries.first()

                _jimakuState.update { JimakuState.LoadingFiles(entry) }
                val files = jimakuApi.getFiles(apiKey, entry.id, guess.episode)
                    .matchedSrtFiles(guess, episodeFiltered = guess.episode != null)
                    .ifEmpty {
                        if (guess.episode == null) {
                            emptyList()
                        } else {
                            jimakuApi.getFiles(apiKey, entry.id, null)
                                .matchedSrtFiles(guess, episodeFiltered = false)
                        }
                    }
                    .distinctBy { it.url }

                if (files.isEmpty()) {
                    val episodeText = guess.episode?.let { " episode $it" }.orEmpty()
                    updateJimakuFailure("No matching SRT Jimaku subtitles found for ${entry.name}$episodeText", showFeedback)
                    return@launchIO
                }

                downloadJimakuSubtitles(apiKey, files, showFeedback)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                updateJimakuFailure(e.message ?: "Jimaku search failed", showFeedback)
            }
        }
    }

    fun loadJimakuFiles(entry: JimakuEntry) {
        if (!currentVideoAllowsExternalSubtitleLookup()) return

        val apiKey = subtitlePreferences.jimakuApiKey().get().trim()
        val guess = guessCurrentJimakuMedia()
        val episode = guess.episode

        _jimakuState.update { JimakuState.LoadingFiles(entry) }
        viewModelScope.launchIO {
            try {
                val files = jimakuApi.getFiles(apiKey, entry.id, episode)
                    .matchedSrtFiles(guess, episodeFiltered = episode != null)
                _jimakuState.update {
                    if (files.isEmpty()) {
                        JimakuState.Error("No matching SRT Jimaku subtitles found for ${entry.name}")
                    } else {
                        JimakuState.FileResults(entry, files)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                _jimakuState.update { JimakuState.Error(e.message ?: "Failed to load Jimaku subtitles") }
            }
        }
    }

    fun downloadJimakuSubtitle(file: JimakuFile) {
        if (!currentVideoAllowsExternalSubtitleLookup()) return

        val apiKey = subtitlePreferences.jimakuApiKey().get().trim()
        viewModelScope.launchIO {
            downloadJimakuSubtitles(apiKey, listOf(file), showFeedback = true)
        }
    }

    private fun currentVideoAllowsExternalSubtitleLookup(): Boolean {
        return currentVideo.value?.allowsExternalSubtitleLookup() ?: true
    }

    private suspend fun downloadJimakuSubtitles(apiKey: String, files: List<JimakuFile>, showFeedback: Boolean) {
        val outputDir = File(activity.cacheDir, "jimaku_subtitles")
        var added = 0
        try {
            files.forEach { file ->
                _jimakuState.update { JimakuState.Downloading(file) }
                val subtitleFile = jimakuApi.downloadFile(apiKey, file, outputDir)
                rememberParsedSubtitleFile(subtitleFile, file.name)
                rememberAddedSubtitleForCurrentEpisode(
                    AddedSubtitleTrack(
                        uriString = subtitleFile.toURI().toString(),
                        path = subtitleFile.absolutePath,
                        title = file.name,
                        isContentUri = false,
                    ),
                )
                withUIContext {
                    MPVLib.command(arrayOf("sub-add", subtitleFile.absolutePath, "cached", file.name))
                }
                added += 1
            }
            withUIContext {
                selectFirstJimakuSubtitle(files)
                loadTracks()
                _jimakuState.update { JimakuState.Idle }
                if (showFeedback) {
                    activity.toast(
                        if (added == 1) {
                            "Jimaku subtitle added"
                        } else {
                            "Added $added Jimaku subtitles"
                        },
                    )
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            updateJimakuFailure(e.message ?: "Failed to download Jimaku subtitle", showFeedback)
        }
    }

    private fun selectFirstJimakuSubtitle(files: List<JimakuFile>) {
        val fileNames = files.map { it.name }.toSet()
        runCatching {
            val tracksCount = MPVLib.getPropertyInt("track-list/count") ?: 0
            for (i in 0..<tracksCount) {
                if (getTrackType(i) != "sub") continue
                if (getTrackTitle(i) in fileNames) {
                    activity.player.sid = getTrackMPVId(i)
                    return
                }
            }
        }.onFailure { logcat(LogPriority.ERROR, it) }
    }

    private fun updateJimakuFailure(message: String, showFeedback: Boolean) {
        _jimakuState.update {
            if (showFeedback) {
                JimakuState.Error(message)
            } else {
                JimakuState.Idle
            }
        }
    }

    private fun guessCurrentJimakuMedia(): JimakuMediaGuess {
        val overrideTitle = subtitlePreferences.jimakuTitleForAnime(currentAnime.value?.id).get().trim()
        val candidates = listOfNotNull(
            currentAnime.value?.title,
            animeTitle.value.takeIf { it.isNotBlank() },
            mediaTitle.value.takeIf { it.isNotBlank() },
            currentVideo.value?.videoTitle?.takeIf { it.isNotBlank() },
            currentVideo.value?.videoUrl?.takeIf { it.isNotBlank() },
        )
        val parsedCandidates = candidates.mapNotNull { guessJimakuMedia(it) }
        val parsed = parsedCandidates.firstOrNull { it.season != null || it.episode != null }
            ?: parsedCandidates.firstOrNull()
        val title = overrideTitle
            .ifBlank { currentAnime.value?.title.orEmpty() }
            .ifBlank { parsed?.title.orEmpty() }
            .ifBlank { animeTitle.value }
            .ifBlank { mediaTitle.value }
        val episode = currentEpisode.value?.episode_number
            ?.takeIf { it >= 0f }
            ?.toInt()
            ?: parsed?.episode
        val season = parsed?.season

        return JimakuMediaGuess(title.trim(), episode, season)
    }

    private fun String.cleanMpvSubtitleText(): String {
        return stripDelimitedBlocks('{', '}')
            .replace("""\N""", "\n")
            .replace("""\n""", "\n")
            .replace("""\h""", " ")
            .stripDelimitedBlocks('<', '>')
            .lines()
            .map { it.trim() }
            .map { it.collapseHorizontalWhitespace() }
            .filter { line -> line.isNotBlank() && line.any { it.isLetterOrDigit() } }
            .joinToString("\n")
    }

    private fun String.stripDelimitedBlocks(open: Char, close: Char): String {
        var depth = 0
        return buildString(length) {
            for (char in this@stripDelimitedBlocks) {
                when {
                    char == open -> depth++
                    char == close && depth > 0 -> depth--
                    depth == 0 -> append(char)
                }
            }
        }
    }

    private fun String.collapseHorizontalWhitespace(): String {
        var lastWasSpace = false
        return buildString(length) {
            for (char in this@collapseHorizontalWhitespace) {
                if (char == ' ' || char == '\t') {
                    if (!lastWasSpace) append(' ')
                    lastWasSpace = true
                } else {
                    append(char)
                    lastWasSpace = false
                }
            }
        }.trim()
    }

    private fun String.toEffectiveSubtitleText(): String {
        return applySubtitleRegexFilters(subtitlePreferences.subtitleRegexFilterOptions())
    }

    private fun List<SubtitleCue>.toEffectiveSubtitleCues(): List<SubtitleCue> {
        return mapNotNull { cue ->
            val rawText = cue.rawText.ifBlank { cue.text }
            val effectiveText = rawText.toEffectiveSubtitleText()
            if (effectiveText.isBlank()) {
                null
            } else {
                cue.copy(text = effectiveText, rawText = rawText)
            }
        }
    }

    private fun refreshParsedSubtitleRegexFilters() {
        parsedSubtitleCuesByTitle.clear()
        rawParsedSubtitleCuesByTitle.forEach { (title, cues) ->
            parsedSubtitleCuesByTitle[title] = cues.toEffectiveSubtitleCues()
        }

        parsedSubtitleCuesByTrackId.clear()
        rawParsedSubtitleCuesByTrackId.forEach { (trackId, cues) ->
            parsedSubtitleCuesByTrackId[trackId] = cues.toEffectiveSubtitleCues()
        }
    }

    fun refreshSubtitleRegexFilters() {
        refreshParsedSubtitleRegexFilters()
        _currentSubtitleText.update { currentRawSubtitleText.toEffectiveSubtitleText() }

        showingParsedSubtitleTrackId?.let {
            applyParsedSubtitleCuesForTrack(it)
            return
        }

        _subtitleHistory.update { cues -> cues.toEffectiveSubtitleCues() }
        updateActiveSubtitleCueFromPosition(pos.value.toDouble())
    }

    private fun rememberParsedSubtitleTrack(track: VideoTrack) {
        if (track.id == -1) return

        rawParsedSubtitleCuesByTrackId[track.id]?.let {
            rememberParsedSubtitleCues(track.name, it)
            return
        }

        val cues = track.externalFilename
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.let { parseSubtitleFile(it) }
            .orEmpty()
            .ifEmpty {
                rawParsedSubtitleCuesByTitle[track.name]
                    ?: rawParsedSubtitleCuesByTitle[File(track.name).name]
                    ?: track.externalFilename
                        ?.let { rawParsedSubtitleCuesByTitle[File(it).name] }
                        .orEmpty()
            }

        if (cues.isNotEmpty()) {
            rawParsedSubtitleCuesByTrackId[track.id] = cues
            parsedSubtitleCuesByTrackId[track.id] = cues.toEffectiveSubtitleCues()
            rememberParsedSubtitleCues(track.name, cues)
        }
    }

    private fun rememberParsedSubtitleFile(file: File, title: String) {
        rememberParsedSubtitleCues(title, parseSubtitleFile(file))
    }

    private fun rememberParsedSubtitleCues(title: String, cues: List<SubtitleCue>) {
        if (title.isBlank() || cues.isEmpty()) return

        val indexedRawCues = cues.mapIndexed { index, cue ->
            val rawText = cue.rawText.ifBlank { cue.text }
            cue.copy(index = index, text = rawText, rawText = rawText)
        }
        val indexedCues = indexedRawCues.toEffectiveSubtitleCues()
        rawParsedSubtitleCuesByTitle[title] = indexedRawCues
        parsedSubtitleCuesByTitle[title] = indexedCues
        File(title).name
            .takeIf { it.isNotBlank() && it != title }
            ?.let {
                rawParsedSubtitleCuesByTitle[it] = indexedRawCues
                parsedSubtitleCuesByTitle[it] = indexedCues
            }

        val matchingTrack = subtitleTracks.value.firstOrNull { track ->
            track.name == title ||
                File(track.name).name == File(title).name ||
                track.externalFilename?.let { File(it).name == File(title).name } == true
        }
        if (matchingTrack != null) {
            rawParsedSubtitleCuesByTrackId[matchingTrack.id] = indexedRawCues
            parsedSubtitleCuesByTrackId[matchingTrack.id] = indexedCues
            if (selectedSubtitles.value.first == matchingTrack.id) {
                applyParsedSubtitleCuesForTrack(matchingTrack.id)
            }
        }
    }

    private fun applyParsedSubtitleCuesForSelectedTrack() {
        applyParsedSubtitleCuesForTrack(selectedSubtitles.value.first)
    }

    private fun applyParsedSubtitleCuesForTrack(trackId: Int) {
        if (trackId == -1) {
            showingParsedSubtitleTrackId = null
            lastSubtitleHistoryText = ""
            currentRawSubtitleText = ""
            _currentSubtitleText.update { "" }
            _secondaryCurrentSubtitleText.update { "" }
            _subtitleHistory.update { emptyList() }
            _activeSubtitleCueIndex.update { null }
            return
        }

        val track = subtitleTracks.value.firstOrNull { it.id == trackId }
        val rawCues = rawParsedSubtitleCuesByTrackId[trackId]
            ?: track?.name?.let { rawParsedSubtitleCuesByTitle[it] }
            ?: track?.name?.let { rawParsedSubtitleCuesByTitle[File(it).name] }
            ?: track?.externalFilename?.let { rawParsedSubtitleCuesByTitle[File(it).name] }
        val cues = parsedSubtitleCuesByTrackId[trackId]
            ?: track?.name?.let { parsedSubtitleCuesByTitle[it] }
            ?: track?.name?.let { parsedSubtitleCuesByTitle[File(it).name] }
            ?: track?.externalFilename?.let { parsedSubtitleCuesByTitle[File(it).name] }

        if (cues.isNullOrEmpty()) {
            if (!rawCues.isNullOrEmpty()) {
                showingParsedSubtitleTrackId = trackId
                lastSubtitleHistoryText = ""
                nextSubtitleCueIndex = rawCues.maxOfOrNull { it.index + 1 } ?: 0
                _subtitleHistory.update { emptyList() }
                _activeSubtitleCueIndex.update { null }
                return
            }
            if (showingParsedSubtitleTrackId != null) {
                showingParsedSubtitleTrackId = null
                _subtitleHistory.update { emptyList() }
                _activeSubtitleCueIndex.update { null }
                lastSubtitleHistoryText = ""
                nextSubtitleCueIndex = 0
            }
            return
        }

        showingParsedSubtitleTrackId = trackId
        lastSubtitleHistoryText = ""
        nextSubtitleCueIndex = cues.maxOfOrNull { it.index + 1 } ?: 0
        _subtitleHistory.update { cues }
        updateActiveSubtitleCueFromPosition(pos.value.toDouble())
    }

    private fun updateActiveSubtitleCueFromPosition(positionSeconds: Double, text: String = currentSubtitleText.value) {
        val cues = subtitleHistory.value
        if (cues.isEmpty()) {
            _activeSubtitleCueIndex.update { null }
            return
        }

        val delaySeconds = currentPrimarySubtitleDelaySeconds()
        val speed = currentSubtitleSpeed()
        val cueByTime = cues.lastOrNull {
            positionSeconds >= it.effectiveStartSeconds(delaySeconds, speed) &&
                positionSeconds <= it.effectiveEndSeconds(delaySeconds, speed)
        }
        val cueByText = text.takeIf { it.isNotBlank() }?.let { cleanedText ->
            val normalizedText = cleanedText.normalizedSubtitleCueText()
            cues
                .filter { it.text.normalizedSubtitleCueText() == normalizedText }
                .minByOrNull { kotlin.math.abs(it.effectiveStartSeconds(delaySeconds, speed) - positionSeconds) }
        }

        _activeSubtitleCueIndex.update { cueByTime?.index ?: cueByText?.index }
    }

    private fun SubtitleCue.effectiveStartSeconds(
        delaySeconds: Double = currentPrimarySubtitleDelaySeconds(),
        speed: Double = currentSubtitleSpeed(),
    ): Double {
        return positionSeconds * speed + delaySeconds
    }

    private fun SubtitleCue.effectiveEndSeconds(
        delaySeconds: Double = currentPrimarySubtitleDelaySeconds(),
        speed: Double = currentSubtitleSpeed(),
    ): Double {
        return endPositionSeconds * speed + delaySeconds
    }

    private fun currentPrimarySubtitleDelaySeconds(): Double {
        return primarySubtitleDelaySeconds.value
    }

    private fun currentSubtitleSpeed(): Double {
        return subtitleSpeedSeconds.value
    }

    fun updatePrimarySubtitleDelayMillis(delayMillis: Int) {
        _primarySubtitleDelaySeconds.update { delayMillis / 1000.0 }
        updateActiveSubtitleCueFromPosition(pos.value.toDouble())
    }

    fun updateSubtitleSpeed(speed: Double) {
        _subtitleSpeedSeconds.update { speed }
        updateActiveSubtitleCueFromPosition(pos.value.toDouble())
    }

    private fun String.normalizedSubtitleCueText(): String {
        return cleanMpvSubtitleText()
            .replace("\n", "")
            .collapseHorizontalWhitespace()
    }

    private fun parseSubtitleUriOrPath(uri: Uri, path: String, name: String?): List<SubtitleCue> {
        val title = name ?: subtitleTitleFromUriOrPath(uri, path)
        return runCatching {
            when {
                uri.scheme == "content" -> {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        parseSubtitleContent(title, input.bufferedReader().readText())
                    }.orEmpty()
                }
                uri.scheme == "file" -> uri.path
                    ?.let { File(it) }
                    ?.let { parseSubtitleFile(it) }
                    .orEmpty()
                path.startsWith("file://") -> Uri.parse(path).path
                    ?.let { File(it) }
                    ?.let { parseSubtitleFile(it) }
                    .orEmpty()
                path.startsWith("fd://") || path.startsWith("http://") || path.startsWith("https://") -> emptyList()
                else -> parseSubtitleFile(File(path))
            }
        }.getOrElse {
            logcat(LogPriority.ERROR, it)
            emptyList()
        }
    }

    private fun subtitleTitleFromUriOrPath(uri: Uri, path: String): String {
        return uri.lastPathSegment
            ?: Uri.parse(path).lastPathSegment
            ?: File(path).name
    }

    private fun parseSubtitleFile(file: File): List<SubtitleCue> {
        if (!file.exists() || !file.isFile) return emptyList()
        return runCatching {
            parseSubtitleContent(file.name, file.readText())
        }.getOrElse {
            logcat(LogPriority.ERROR, it)
            emptyList()
        }
    }

    private fun parseSubtitleContent(fileName: String, content: String): List<SubtitleCue> {
        val normalized = content
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val cues = when {
            extension == "ass" || extension == "ssa" || normalized.contains("[Events]", ignoreCase = true) -> {
                parseAssSubtitleContent(normalized)
            }
            else -> parseSrtOrVttSubtitleContent(normalized)
        }
        return cues.mapIndexed { index, cue -> cue.copy(index = index) }
    }

    private fun parseSrtOrVttSubtitleContent(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        content
            .split(Regex("\n{2,}"))
            .forEach { block ->
                val lines = block.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != "WEBVTT" }
                val timeIndex = lines.indexOfFirst { it.contains("-->") }
                if (timeIndex == -1) return@forEach

                val timeParts = lines[timeIndex].split("-->", limit = 2)
                val start = timeParts.getOrNull(0)?.let { parseSubtitleTimestampSeconds(it) } ?: return@forEach
                val end = timeParts.getOrNull(1)?.let { parseSubtitleTimestampSeconds(it) } ?: (start + 5.0)
                val text = lines.drop(timeIndex + 1)
                    .joinToString("\n")
                    .cleanMpvSubtitleText()

                if (text.isNotBlank()) {
                    cues += SubtitleCue(
                        index = cues.size,
                        text = text,
                        positionSeconds = start,
                        endPositionSeconds = end.coerceAtLeast(start + 1.0),
                    )
                }
            }
        return cues
    }

    private fun parseAssSubtitleContent(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var inEvents = false
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.equals("[Events]", ignoreCase = true) -> {
                    inEvents = true
                    return@forEach
                }
                line.startsWith("[") && !line.equals("[Events]", ignoreCase = true) -> {
                    inEvents = false
                    return@forEach
                }
                !inEvents -> return@forEach
                line.startsWith("Format:", ignoreCase = true) -> {
                    format = line.substringAfter(':')
                        .split(',')
                        .map { it.trim().lowercase() }
                    return@forEach
                }
                !line.startsWith("Dialogue:", ignoreCase = true) -> return@forEach
            }

            val startIndex = format.indexOf("start")
            val endIndex = format.indexOf("end")
            val textIndex = format.indexOf("text")
            if (startIndex == -1 || endIndex == -1 || textIndex == -1 || format.isEmpty()) return@forEach

            val values = line.substringAfter(':')
                .trimStart()
                .split(",", limit = format.size)
            if (values.size <= maxOf(startIndex, endIndex, textIndex)) return@forEach

            val start = parseSubtitleTimestampSeconds(values[startIndex]) ?: return@forEach
            val end = parseSubtitleTimestampSeconds(values[endIndex]) ?: (start + 5.0)
            val text = values[textIndex].cleanMpvSubtitleText()
            if (text.isBlank()) return@forEach

            cues += SubtitleCue(
                index = cues.size,
                text = text,
                positionSeconds = start,
                endPositionSeconds = end.coerceAtLeast(start + 1.0),
            )
        }
        return cues
    }

    private fun parseSubtitleTimestampSeconds(raw: String): Double? {
        val token = raw
            .trim()
            .substringBefore(' ')
            .substringBefore('\t')
            .replace(',', '.')
        val parts = token.split(':')
        if (parts.size < 2) return null

        val seconds = parts.lastOrNull()?.toDoubleOrNull() ?: return null
        val minutes = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return null
        val hours = parts.getOrNull(parts.size - 3)?.toIntOrNull() ?: 0
        return hours * 3600.0 + minutes * 60.0 + seconds
    }

    fun selectSub(id: Int) {
        val selectedSubs = selectedSubtitles.value
        _selectedSubtitles.update {
            when (id) {
                selectedSubs.first -> Pair(selectedSubs.second, -1)
                selectedSubs.second -> Pair(selectedSubs.first, -1)
                else -> {
                    if (selectedSubs.first != -1) {
                        Pair(selectedSubs.first, id)
                    } else {
                        Pair(id, -1)
                    }
                }
            }
        }
        activity.player.secondarySid = _selectedSubtitles.value.second
        activity.player.sid = _selectedSubtitles.value.first
        applyParsedSubtitleCuesForSelectedTrack()
        rememberSubtitleSelectionForCurrentEpisode()
    }

    fun updateSubtitle(sid: Int, secondarySid: Int) {
        _selectedSubtitles.update { Pair(sid, secondarySid) }
        applyParsedSubtitleCuesForTrack(sid)
    }

    fun updateSubtitleText(text: String?) {
        val cleaned = text.orEmpty().cleanMpvSubtitleText()
        val effectiveText = cleaned.toEffectiveSubtitleText()
        currentRawSubtitleText = cleaned
        _currentSubtitleText.update { effectiveText }
        if (showingParsedSubtitleTrackId != null) {
            updateActiveSubtitleCueFromPosition(pos.value.toDouble(), effectiveText)
            return
        }
        updateSubtitleHistory(cleaned, effectiveText)
    }

    fun updateSecondarySubtitleText(text: String?) {
        val cleaned = text.orEmpty().cleanMpvSubtitleText()
        _secondaryCurrentSubtitleText.update { cleaned.toEffectiveSubtitleText() }
    }

    private fun updateSubtitleHistory(rawText: String, text: String) {
        if (text.isBlank()) {
            lastSubtitleHistoryText = ""
            _activeSubtitleCueIndex.update { null }
            return
        }

        val existing = subtitleHistory.value.lastOrNull { it.text == text }
        if (existing != null && text == lastSubtitleHistoryText) {
            _activeSubtitleCueIndex.update { existing.index }
            return
        }

        // Reuse existing cue if same text appears again (e.g. after rewind)
        if (existing != null) {
            _activeSubtitleCueIndex.update { existing.index }
            lastSubtitleHistoryText = text
            return
        }

        lastSubtitleHistoryText = text
        val cue = SubtitleCue(
            index = nextSubtitleCueIndex++,
            text = text,
            rawText = rawText,
            positionSeconds = pos.value.toDouble(),
        )
        _subtitleHistory.update { cues -> (cues + cue).takeLast(MAX_SUBTITLE_HISTORY) }
        _activeSubtitleCueIndex.update { cue.index }
    }

    fun selectSubtitleCue(index: Int) {
        val cue = subtitleHistory.value.firstOrNull { it.index == index } ?: return
        seekTo(cue.effectiveStartSeconds().coerceAtLeast(0.0))
        _activeSubtitleCueIndex.update { cue.index }
    }

    fun seekToAdjacentSubtitle(forward: Boolean) {
        // When no parsed subtitle cues are available, use mpv's native sub-seek
        // which is reliable regardless of playback position
        if (showingParsedSubtitleTrackId == null) {
            MPVLib.command(arrayOf("sub-seek", if (forward) "1" else "-1", "primary"))
            return
        }

        val delaySeconds = currentPrimarySubtitleDelaySeconds()
        val speed = currentSubtitleSpeed()
        val positionSeconds = pos.value.toDouble()
        val target = if (forward) {
            subtitleHistory.value
                .filter { it.effectiveStartSeconds(delaySeconds, speed) > positionSeconds }
                .minByOrNull { it.effectiveStartSeconds(delaySeconds, speed) }
        } else {
            subtitleHistory.value
                .filter { it.effectiveEndSeconds(delaySeconds, speed) < positionSeconds }
                .maxByOrNull { it.effectiveEndSeconds(delaySeconds, speed) }
        }

        if (target == null) {
            MPVLib.command(arrayOf("sub-seek", if (forward) "1" else "-1", "primary"))
            return
        }

        seekTo(target.effectiveStartSeconds(delaySeconds, speed).coerceAtLeast(0.0))
        _activeSubtitleCueIndex.update { target.index }
    }

    fun replayCurrentSubtitle() {
        val currentCue = activeSubtitleCueIndex.value?.let { activeCueIndex ->
            subtitleHistory.value.firstOrNull { it.index == activeCueIndex }
        }
        if (currentCue == null) {
            MPVLib.command(arrayOf("sub-seek", "0", "primary"))
            return
        }

        seekTo(currentCue.effectiveStartSeconds().coerceAtLeast(0.0))
        _activeSubtitleCueIndex.update { currentCue.index }
    }

    fun setSubtitlesVisible(visible: Boolean) {
        MPVLib.setPropertyBoolean("sub-visibility", visible)
        _subtitlesVisible.update { visible }
        playerUpdate.update {
            PlayerUpdates.ShowText(
                activity.stringResource(
                    if (visible) MR.strings.player_subtitles_shown else MR.strings.player_subtitles_hidden,
                ),
            )
        }
    }

    fun updatePlayBackPos(pos: Float) {
        onSecondReached(pos.toInt(), duration.value.toInt())
        _pos.update { pos }
        if (showingParsedSubtitleTrackId != null) {
            updateActiveSubtitleCueFromPosition(pos.toDouble())
        }
    }

    fun updateReadAhead(value: Long) {
        _readAhead.update { value.toFloat() }
    }

    private fun updatePausedState() {
        if (pausedState.value == null) {
            _pausedState.update { _ -> paused.value }
        }
    }

    private fun setPausedState() {
        pausedState.value?.let {
            if (it) {
                pause()
            } else {
                unpause()
            }

            _pausedState.update { _ -> null }
        }
    }

    fun pauseUnpause() {
        if (paused.value) {
            unpause()
        } else {
            pause()
        }
    }

    fun pause() {
        activity.player.paused = true
        _paused.update { true }
        runCatching {
            activity.updatePictureInPictureParamsIfAvailable()
        }
    }

    fun unpause() {
        restartIfAtEnd()
        clearEndOfFileState()
        activity.player.paused = false
        _paused.update { false }
    }

    private val showStatusBar = playerPreferences.showSystemStatusBar().get()
    fun showControls() {
        if (sheetShown.value != Sheets.None ||
            panelShown.value != Panels.None ||
            dialogShown.value != Dialogs.None
        ) {
            return
        }
        if (showStatusBar) {
            activity.windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }
        _controlsShown.update { true }
    }

    fun hideControls() {
        activity.windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        _controlsShown.update { false }
    }

    fun requestOcr() {
        if (_isCapturingOcr.value) return
        _isCapturingOcr.value = true
        _suppressTap.value = true
        if (_controlsShown.value) {
            hideControls()
        }
        pause()
        viewModelScope.launch {
            val screenshot = captureVideoFrameForOcr()
            _isCapturingOcr.value = false
            _suppressTap.value = false
            if (screenshot == null) {
                eventChannel.send(Event.OcrFailed)
            } else {
                _ocrScreenshot.value = screenshot
            }
        }
    }

    fun dismissOcrScreenshot() {
        _ocrScreenshot.value = null
    }

    fun hideSeekBar() {
        _seekBarShown.update { false }
    }

    fun showSeekBar() {
        if (sheetShown.value != Sheets.None) return
        _seekBarShown.update { true }
    }

    fun lockControls() {
        _areControlsLocked.update { true }
    }

    fun unlockControls() {
        _areControlsLocked.update { false }
    }

    fun dismissSheet() {
        _dismissSheet.update { _ -> true }
    }

    private fun resetDismissSheet() {
        _dismissSheet.update { _ -> false }
    }

    fun showSheet(sheet: Sheets) {
        sheetShown.update { sheet }
        if (sheet == Sheets.None) {
            resetDismissSheet()
            showControls()
        } else {
            hideControls()
            panelShown.update { Panels.None }
            dialogShown.update { Dialogs.None }
        }
    }

    fun showPanel(panel: Panels) {
        panelShown.update { panel }
        if (panel == Panels.None) {
            showControls()
        } else {
            hideControls()
            sheetShown.update { Sheets.None }
            dialogShown.update { Dialogs.None }
        }
    }

    fun showDialog(dialog: Dialogs) {
        dialogShown.update { dialog }
        if (dialog == Dialogs.None) {
            showControls()
        } else {
            hideControls()
            sheetShown.update { Sheets.None }
            panelShown.update { Panels.None }
        }
    }

    fun seekBy(offset: Int, precise: Boolean = false) {
        clearEndOfFileState()
        MPVLib.command(arrayOf("seek", offset.toString(), if (precise) "relative+exact" else "relative"))
    }

    fun seekTo(position: Int, precise: Boolean = true) {
        seekTo(position.toDouble(), precise)
    }

    fun seekTo(position: Double, precise: Boolean = true) {
        val duration = activity.player.duration?.toDouble() ?: 0.0
        if (position < 0.0 || position > duration) return
        val target = if (duration > 0.0 && position >= duration) duration - 1.0 else position
        clearEndOfFileState()
        MPVLib.command(arrayOf("seek", target.coerceAtLeast(0.0).toString(), if (precise) "absolute" else "absolute+keyframes"))
    }

    private fun clearEndOfFileState() {
        runCatching {
            if (MPVLib.getPropertyBoolean("eof-reached") == true) {
                MPVLib.setPropertyBoolean("eof-reached", false)
            }
        }
    }

    private fun restartIfAtEnd() {
        val durationSeconds = activity.player.duration ?: duration.value.toInt()
        if (durationSeconds > 0 && pos.value >= durationSeconds - 0.5f) {
            seekTo(0)
        }
    }

    fun changeBrightnessTo(
        brightness: Float,
    ) {
        currentBrightness.update { _ -> brightness.coerceIn(-0.75f, 1f) }
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = brightness.coerceIn(0f, 1f)
        }
    }

    fun displayBrightnessSlider() {
        isBrightnessSliderShown.update { true }
    }

    val maxVolume = activity.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun changeVolumeBy(change: Int) {
        val mpvVolume = MPVLib.getPropertyInt("volume")
        val step = Math.round(maxVolume * 0.05f).coerceAtLeast(1)
        val signedStep = if (change > 0) step else -step

        if (volumeBoostCap > 0 && currentVolume.value == maxVolume) {
            val boostStep = if (change > 0) 5 else -5
            val finalMPVVolume = (mpvVolume + boostStep).coerceAtLeast(100)
            if (change < 0 && mpvVolume == 100) {
                changeVolumeTo(currentVolume.value + signedStep)
            } else if (finalMPVVolume in 100..volumeBoostCap + 100) {
                changeMPVVolumeTo(finalMPVVolume)
            }
        } else {
            changeVolumeTo(currentVolume.value + signedStep)
        }
    }

    fun changeVolumeTo(volume: Int) {
        val newVolume = volume.coerceIn(0..maxVolume)
        activity.audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            newVolume,
            0,
        )
        currentVolume.update { newVolume }
    }

    fun changeMPVVolumeTo(volume: Int) {
        MPVLib.setPropertyInt("volume", volume)
    }

    fun setMPVVolume(volume: Int) {
        if (volume != currentMPVVolume.value) displayVolumeSlider()
        currentMPVVolume.update { volume }
    }

    fun displayVolumeSlider() {
        isVolumeSliderShown.update { true }
    }

    fun setAutoPlay(value: Boolean) {
        val textRes = if (value) {
            MR.strings.enable_auto_play
        } else {
            MR.strings.disable_auto_play
        }
        playerUpdate.update { PlayerUpdates.ShowTextResource(textRes) }
        playerPreferences.autoplayEnabled().set(value)
    }

    @Suppress("DEPRECATION")
    fun changeVideoAspect(aspect: VideoAspect) {
        var ratio = -1.0
        var pan = 1.0
        when (aspect) {
            VideoAspect.Crop -> {
                pan = 1.0
            }

            VideoAspect.Fit -> {
                pan = 0.0
                MPVLib.setPropertyDouble("panscan", 0.0)
            }

            VideoAspect.Stretch -> {
                val dm = DisplayMetrics()
                activity.windowManager.defaultDisplay.getRealMetrics(dm)
                ratio = dm.widthPixels / dm.heightPixels.toDouble()
                pan = 0.0
            }
        }
        MPVLib.setPropertyDouble("panscan", pan)
        MPVLib.setPropertyDouble("video-aspect-override", ratio)
        playerPreferences.aspectState().set(aspect)
        playerUpdate.update { PlayerUpdates.AspectRatio }
    }

    fun cycleScreenRotations() {
        activity.requestedOrientation = when (activity.requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            -> {
                playerPreferences.defaultPlayerOrientationType().set(PlayerOrientation.SensorPortrait)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }

            else -> {
                playerPreferences.defaultPlayerOrientationType().set(PlayerOrientation.SensorLandscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    fun handleLuaInvocation(property: String, value: String) {
        val data = value
            .removePrefix("\"")
            .removeSuffix("\"")
            .ifEmpty { return }

        when (property.substringAfterLast("/")) {
            "show_text" -> playerUpdate.update { PlayerUpdates.ShowText(data) }
            "toggle_ui" -> {
                when (data) {
                    "show" -> showControls()
                    "toggle" -> {
                        if (controlsShown.value) hideControls() else showControls()
                    }
                    "hide" -> {
                        sheetShown.update { Sheets.None }
                        panelShown.update { Panels.None }
                        dialogShown.update { Dialogs.None }
                        hideControls()
                    }
                }
            }
            "show_panel" -> {
                when (data) {
                    "subtitle_settings" -> showPanel(Panels.SubtitleSettings)
                    "subtitle_delay" -> showPanel(Panels.SubtitleDelay)
                    "audio_delay" -> showPanel(Panels.AudioDelay)
                    "video_filters" -> showPanel(Panels.VideoFilters)
                }
            }
            "set_button_title" -> {
                _primaryButtonTitle.update { _ -> data }
            }
            "reset_button_title" -> {
                _customButtons.value.getButtons().firstOrNull { it.isFavorite }?.let {
                    setPrimaryCustomButtonTitle(it)
                }
            }
            "switch_episode" -> {
                when (data) {
                    "n" -> changeEpisode(false)
                    "p" -> changeEpisode(true)
                }
            }
            "launch_int_picker" -> {
                val (title, nameFormat, start, stop, step, pickerProperty) = data.split("|")
                val defaultValue = MPVLib.getPropertyInt(pickerProperty)
                showDialog(
                    Dialogs.IntegerPicker(
                        defaultValue = defaultValue,
                        minValue = start.toInt(),
                        maxValue = stop.toInt(),
                        step = step.toInt(),
                        nameFormat = nameFormat,
                        title = title,
                        onChange = { MPVLib.setPropertyInt(pickerProperty, it) },
                        onDismissRequest = { showDialog(Dialogs.None) },
                    ),
                )
            }
            "pause" -> {
                when (data) {
                    "pause" -> pause()
                    "unpause" -> unpause()
                    "pauseunpause" -> pauseUnpause()
                }
            }
            "seek_to_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekToWithText(seekValue.toInt(), text)
            }
            "seek_by_with_text" -> {
                val (seekValue, text) = data.split("|", limit = 2)
                seekByWithText(seekValue.toInt(), text)
            }
            "seek_by" -> seekByWithText(data.toInt(), null)
            "seek_to" -> seekToWithText(data.toInt(), null)
            "toggle_button" -> {
                fun showButton() {
                    if (_primaryButton.value == null) {
                        _primaryButton.update {
                            customButtons.value.getButtons().firstOrNull { it.isFavorite }
                        }
                    }
                }

                when (data) {
                    "show" -> showButton()
                    "hide" -> _primaryButton.update { null }
                    "toggle" -> if (_primaryButton.value == null) showButton() else _primaryButton.update { null }
                }
            }

            "software_keyboard" -> when (data) {
                "show" -> forceShowSoftwareKeyboard()
                "hide" -> forceHideSoftwareKeyboard()
                "toggle" -> if (inputMethodManager.isActive) {
                    forceHideSoftwareKeyboard()
                } else {
                    forceShowSoftwareKeyboard()
                }
            }
        }

        MPVLib.setPropertyString(property, "")
    }

    private operator fun <T> List<T>.component6(): T = get(5)

    private val inputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun forceShowSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    private fun forceHideSoftwareKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private val doubleTapToSeekDuration = gesturePreferences.skipLengthPreference().get()
    private val preciseSeek = gesturePreferences.playerSmoothSeek().get()
    private val showSeekBar = gesturePreferences.showSeekBar().get()

    private fun seekToWithText(seekValue: Int, text: String?) {
        _isSeekingForwards.value = seekValue > 0
        _doubleTapSeekAmount.value = seekValue - pos.value.toInt()
        _seekText.update { _ -> text }
        seekTo(seekValue, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    private fun seekByWithText(value: Int, text: String?) {
        _doubleTapSeekAmount.update { if (value < 0 && it < 0 || pos.value + value > duration.value) 0 else it + value }
        _seekText.update { text }
        _isSeekingForwards.value = value > 0
        seekBy(value, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    fun updateSeekAmount(amount: Int) {
        _doubleTapSeekAmount.update { _ -> amount }
    }

    fun updateSeekText(value: String?) {
        _seekText.update { _ -> value }
    }

    fun leftSeek() {
        if (pos.value > 0) {
            _doubleTapSeekAmount.value -= doubleTapToSeekDuration
        }
        _isSeekingForwards.value = false
        seekBy(-doubleTapToSeekDuration, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    fun rightSeek() {
        if (pos.value < duration.value) {
            _doubleTapSeekAmount.value += doubleTapToSeekDuration
        }
        _isSeekingForwards.value = true
        seekBy(doubleTapToSeekDuration, preciseSeek)
        if (showSeekBar) showSeekBar()
    }

    fun resetHosterState() {
        _pausedState.update { _ -> false }
        currentHosterList = null
        _hosterState.update { _ -> emptyList() }
        _hosterList.update { _ -> emptyList() }
        _hosterExpandedList.update { _ -> emptyList() }
        _selectedHosterVideoIndex.update { _ -> Pair(-1, -1) }
    }

    fun changeEpisode(previous: Boolean, autoPlay: Boolean = false) {
        if (previous && !hasPreviousEpisode.value) {
            activity.showToast(activity.stringResource(MR.strings.no_prev_episode))
            return
        }

        if (!previous && !hasNextEpisode.value) {
            activity.showToast(activity.stringResource(MR.strings.no_next_episode))
            return
        }

        activity.changeEpisode(
            episodeId = getAdjacentEpisodeId(previous = previous),
            autoPlay = autoPlay,
        )
    }

    fun handleLeftDoubleTap() {
        when (gesturePreferences.leftDoubleTapGesture().get()) {
            SingleActionGesture.Seek -> {
                leftSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapLeft.keyCode))
            }
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> changeEpisode(true)
        }
    }

    fun handleCenterDoubleTap() {
        when (gesturePreferences.centerDoubleTapGesture().get()) {
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapCenter.keyCode))
            }
            SingleActionGesture.Seek -> {}
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> {}
        }
    }

    fun handleRightDoubleTap() {
        when (gesturePreferences.rightDoubleTapGesture().get()) {
            SingleActionGesture.Seek -> {
                rightSeek()
            }
            SingleActionGesture.PlayPause -> {
                pauseUnpause()
            }
            SingleActionGesture.Custom -> {
                MPVLib.command(arrayOf("keypress", CustomKeyCodes.DoubleTapRight.keyCode))
            }
            SingleActionGesture.None -> {}
            SingleActionGesture.Switch -> changeEpisode(false)
        }
    }

    override fun onCleared() {
        if (currentEpisode.value != null) {
            saveWatchingProgress(currentEpisode.value!!)
            episodeToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    fun updateCastProgress(position: Float) {
        _pos.update { position }
    }

    fun resumeFromCast() {
        val lastPosition = _pos.value

        logcat { "Reanudando el video local desde: $lastPosition segundos" }

        if (lastPosition > 0) {
            seekTo(lastPosition.toInt()) // Mueve el reproductor local a la última posición
        }
    }

    // ====== OLD ======

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    val incognitoMode = basePreferences.incognitoMode().get()
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading().get()

    internal val relativeTime = uiPreferences.relativeTime().get()
    internal val dateFormat = UiPreferences.dateFormat(uiPreferences.dateFormat().get())

    /**
     * The position in the current video. Used to restore from process kill.
     */
    private var episodePosition = savedState.get<Long>("episode_position")
        set(value) {
            savedState["episode_position"] = value
            field = value
        }

    /**
     * The current video's quality index. Used to restore from process kill.
     */
    private var qualityIndex = savedState.get<Pair<Int, Int>>("quality_index") ?: Pair(-1, -1)
        set(value) {
            savedState["quality_index"] = value
            field = value
        }

    private var preferredHosterKey = savedState.get<String>("preferred_hoster_key")
        set(value) {
            savedState["preferred_hoster_key"] = value
            field = value
        }

    private var preferredVideoKey = savedState.get<String>("preferred_video_key")
        set(value) {
            savedState["preferred_video_key"] = value
            field = value
        }

    private data class AddedSubtitleTrack(
        val uriString: String,
        val path: String,
        val title: String?,
        val isContentUri: Boolean,
    )

    private data class AddedAudioTrack(
        val uriString: String,
        val path: String,
        val title: String?,
        val isContentUri: Boolean,
    )

    private data class SubtitleTrackSelection(
        val primaryKey: String?,
        val secondaryKey: String?,
    )

    private val addedSubtitleTracksByEpisodeId = mutableMapOf<Long, MutableList<AddedSubtitleTrack>>()
    private val addedAudioTracksByEpisodeId = mutableMapOf<Long, MutableList<AddedAudioTrack>>()
    private val subtitleTrackSelectionsByEpisodeId = mutableMapOf<Long, SubtitleTrackSelection>()
    private var pendingSubtitleSelectionKey: String? = null

    /**
     * The episode id of the currently loaded episode. Used to restore from process kill.
     */
    private var episodeId = savedState.get<Long>("episode_id") ?: -1L
        set(value) {
            savedState["episode_id"] = value
            field = value
        }

    private var episodeToDownload: Download? = null

    private fun filterEpisodeList(episodes: List<Episode>): List<Episode> {
        val anime = currentAnime.value ?: return episodes
        val selectedEpisode = episodes.find { it.id == episodeId }
            ?: error("Requested episode of id $episodeId not found in episode list")

        val episodesForPlayer = episodes.filterNot {
            anime.unseenFilterRaw == Anime.EPISODE_SHOW_SEEN &&
                !it.seen ||
                anime.unseenFilterRaw == Anime.EPISODE_SHOW_UNSEEN &&
                it.seen ||
                anime.downloadedFilterRaw == Anime.EPISODE_SHOW_DOWNLOADED &&
                !downloadManager.isEpisodeDownloaded(
                    it.name,
                    it.scanlator,
                    anime.title,
                    anime.source,
                ) ||
                anime.downloadedFilterRaw == Anime.EPISODE_SHOW_NOT_DOWNLOADED &&
                downloadManager.isEpisodeDownloaded(
                    it.name,
                    it.scanlator,
                    anime.title,
                    anime.source,
                ) ||
                anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_BOOKMARKED &&
                !it.bookmark ||
                anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_NOT_BOOKMARKED &&
                it.bookmark ||
                // AM (FILLERMARK) -->
                anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_FILLERMARKED &&
                !it.fillermark ||
                anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_NOT_FILLERMARKED &&
                it.fillermark
            // <-- AM (FILLERMARK)
        }.toMutableList()

        if (episodesForPlayer.all { it.id != episodeId }) {
            // Input arrives already sorted from initEpisodeList; re-insert at the
            // original position instead of appending so prev/next stay in order.
            val order = episodes.mapNotNull { it.id }.withIndex().associate { it.value to it.index }
            val selectedOrder = selectedEpisode.id?.let { order[it] } ?: Int.MAX_VALUE
            val insertAt = episodesForPlayer.indexOfFirst { ep -> (ep.id?.let { order[it] } ?: Int.MAX_VALUE) > selectedOrder }
                .takeIf { it >= 0 } ?: episodesForPlayer.size
            episodesForPlayer.add(insertAt, selectedEpisode)
        }

        return episodesForPlayer
            .inPlaybackOrder(anime.sorting == Anime.EPISODE_SORTING_SOURCE)
    }

    fun getCurrentEpisodeIndex(): Int {
        return currentPlaylist.value.indexOfFirst { currentEpisode.value?.id == it.id }
    }

    private fun getAdjacentEpisodeId(previous: Boolean): Long {
        val newIndex = if (previous) getCurrentEpisodeIndex() - 1 else getCurrentEpisodeIndex() + 1

        return when {
            previous && getCurrentEpisodeIndex() == 0 -> -1L
            !previous && currentPlaylist.value.lastIndex == getCurrentEpisodeIndex() -> -1L
            else -> currentPlaylist.value.getOrNull(newIndex)?.id ?: -1L
        }
    }

    fun updateHasNextEpisode(value: Boolean) {
        _hasNextEpisode.update { _ -> value }
    }

    fun updateHasPreviousEpisode(value: Boolean) {
        _hasPreviousEpisode.update { _ -> value }
    }

    fun showEpisodeListDialog() {
        if (currentAnime.value != null) {
            showDialog(Dialogs.EpisodeList)
        }
    }

    /**
     * Called when the activity is saved and not changing configurations. It updates the database
     * to persist the current progress of the active episode.
     */
    fun onSaveInstanceStateNonConfigurationChange() {
        val currentEpisode = currentEpisode.value ?: return
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(currentEpisode)
        }
    }

    // ====== Initialize anime, episode, hoster, and video list ======

    fun updateIsLoadingHosters(value: Boolean) {
        _isLoadingHosters.update { _ -> value }
    }

    /**
     * Whether this presenter is initialized yet.
     */
    private fun needsInit(): Boolean {
        return currentAnime.value == null || currentEpisode.value == null
    }

    data class InitResult(
        val hosterList: List<Hoster>?,
        val videoIndex: Pair<Int, Int>,
        val position: Long?,
    )

    private var currentHosterList: List<Hoster>? = null

    class ExceptionWithStringResource(
        message: String,
        val stringResource: StringResource,
    ) : Exception(message)

    private fun Hoster.selectionKey(): String {
        return hosterName.trim()
    }

    private fun Video.selectionKey(): String {
        return listOf(
            videoTitle,
            resolution?.toString().orEmpty(),
            bitrate?.toString().orEmpty(),
        ).joinToString("\u001f")
    }

    private fun rememberPreferredVideoSelection(hosterIndex: Int, videoIndex: Int) {
        val hoster = hosterList.value.getOrNull(hosterIndex) ?: return
        val video = (_hosterState.value.getOrNull(hosterIndex) as? HosterState.Ready)
            ?.videoList
            ?.getOrNull(videoIndex)
            ?: return

        preferredHosterKey = hoster.selectionKey()
        preferredVideoKey = video.selectionKey()
        persistPreferredVideoSelectionForCurrentEpisode()
    }

    private fun findRememberedVideoIndex(hoster: Hoster, videoList: List<Video>): Int {
        val rememberedHosterKey = preferredHosterKey ?: return -1
        val rememberedVideoKey = preferredVideoKey ?: return -1
        if (hoster.selectionKey() != rememberedHosterKey) return -1
        return videoList.indexOfFirst { it.selectionKey() == rememberedVideoKey }
    }

    private fun findRequestedVideoIndex(
        hoster: Hoster,
        videoList: List<Video>,
        hosterIndex: Int,
        requestedHosterIndex: Int,
        requestedVideoIndex: Int,
    ): Int {
        if (requestedHosterIndex != -1) {
            return if (hosterIndex == requestedHosterIndex && requestedVideoIndex in videoList.indices) {
                requestedVideoIndex
            } else {
                -1
            }
        }

        return findRememberedVideoIndex(hoster, videoList)
    }

    private fun restorePreferredVideoSelectionForEpisode(episodeId: Long) {
        playerPreferences.preferredVideoSelectionForEpisode(episodeId)
            .get()
            .decodePreferredVideoSelection()
            ?.let { (hosterKey, videoKey) ->
                preferredHosterKey = hosterKey
                preferredVideoKey = videoKey
            }
    }

    private fun persistPreferredVideoSelectionForCurrentEpisode() {
        val episodeId = currentEpisode.value?.id ?: return
        val hosterKey = preferredHosterKey ?: return
        val videoKey = preferredVideoKey ?: return
        playerPreferences.preferredVideoSelectionForEpisode(episodeId)
            .set(encodePreferredVideoSelection(hosterKey, videoKey))
    }

    private fun encodePreferredVideoSelection(hosterKey: String, videoKey: String): String {
        return listOf(hosterKey, videoKey).joinToString(VIDEO_SELECTION_DELIMITER)
    }

    private fun String.decodePreferredVideoSelection(): Pair<String, String>? {
        if (isBlank()) return null
        val parts = split(VIDEO_SELECTION_DELIMITER, limit = 2)
        return if (parts.size == 2) Pair(parts[0], parts[1]) else null
    }

    private fun subtitleSelectionKey(title: String?, path: String?, language: String? = null): String {
        val displayName = title?.takeIf { it.isNotBlank() }
            ?: path?.let { File(it).name }?.takeIf { it.isNotBlank() }
            ?: path.orEmpty()
        return listOf(displayName, language.orEmpty()).joinToString("\u001f")
    }

    private fun VideoTrack.selectionKey(): String {
        return subtitleSelectionKey(
            title = name,
            path = externalFilename,
            language = language,
        )
    }

    private fun AddedSubtitleTrack.selectionKey(): String {
        return subtitleSelectionKey(
            title = title,
            path = if (isContentUri) uriString else path,
        )
    }

    private fun currentSubtitleEpisodeId(): Long? {
        return currentEpisode.value?.id
    }

    private fun rememberAddedSubtitleForCurrentEpisode(track: AddedSubtitleTrack) {
        val episodeId = currentSubtitleEpisodeId() ?: return
        val tracks = addedSubtitleTracksByEpisodeId.getOrPut(episodeId) { mutableListOf() }
        val sourceKey = if (track.isContentUri) track.uriString else track.path
        if (tracks.none { existing ->
                existing.isContentUri == track.isContentUri &&
                    (if (existing.isContentUri) existing.uriString else existing.path) == sourceKey
            }
        ) {
            tracks += track
        }
        pendingSubtitleSelectionKey = track.selectionKey()
    }

    private fun rememberSubtitleSelectionForCurrentEpisode() {
        val episodeId = currentSubtitleEpisodeId() ?: return
        val (primarySid, secondarySid) = selectedSubtitles.value
        val tracks = subtitleTracks.value
        subtitleTrackSelectionsByEpisodeId[episodeId] = SubtitleTrackSelection(
            primaryKey = tracks.firstOrNull { it.id == primarySid }?.selectionKey(),
            secondaryKey = tracks.firstOrNull { it.id == secondarySid }?.selectionKey(),
        )
    }

    fun restoreAddedSubtitlesForCurrentEpisode(): Int {
        val episodeId = currentSubtitleEpisodeId() ?: return 0
        val tracks = addedSubtitleTracksByEpisodeId[episodeId].orEmpty()
        var restored = 0
        tracks.forEach { track ->
            val path = if (track.isContentUri) {
                Uri.parse(track.uriString).openContentFd(activity)
            } else {
                track.path
            } ?: return@forEach

            if (track.title == null) {
                MPVLib.command(arrayOf("sub-add", path, "cached"))
            } else {
                MPVLib.command(arrayOf("sub-add", path, "cached", track.title))
            }
            restored += 1
        }
        return restored
    }

    private fun rememberAddedAudioForCurrentEpisode(track: AddedAudioTrack) {
        val episodeId = currentEpisode.value?.id ?: return
        val tracks = addedAudioTracksByEpisodeId.getOrPut(episodeId) { mutableListOf() }
        val sourceKey = if (track.isContentUri) track.uriString else track.path
        if (tracks.none { existing ->
                existing.isContentUri == track.isContentUri &&
                    (if (existing.isContentUri) existing.uriString else existing.path) == sourceKey
            }
        ) {
            tracks += track
        }
    }

    fun restoreAddedAudioForCurrentEpisode(): Int {
        val episodeId = currentEpisode.value?.id ?: return 0
        val tracks = addedAudioTracksByEpisodeId[episodeId].orEmpty()
        var restored = 0
        tracks.forEach { track ->
            val path = if (track.isContentUri) {
                Uri.parse(track.uriString).openContentFd(activity)
            } else {
                track.path
            } ?: return@forEach

            if (track.title == null) {
                MPVLib.command(arrayOf("audio-add", path, "cached"))
            } else {
                MPVLib.command(arrayOf("audio-add", path, "cached", track.title))
            }
            restored += 1
        }
        return restored
    }

    private fun restoreSubtitleSelectionForCurrentEpisode(): Boolean {
        val episodeId = currentSubtitleEpisodeId() ?: return false
        val tracks = subtitleTracks.value
        val savedSelection = subtitleTrackSelectionsByEpisodeId[episodeId]

        if (savedSelection != null && savedSelection.primaryKey == null) {
            activity.player.secondarySid = -1
            activity.player.sid = -1
            _selectedSubtitles.update { Pair(-1, -1) }
            applyParsedSubtitleCuesForTrack(-1)
            pendingSubtitleSelectionKey = null
            return true
        }

        val primaryKey = savedSelection?.primaryKey
            ?: pendingSubtitleSelectionKey
            ?: addedSubtitleTracksByEpisodeId[episodeId]?.lastOrNull()?.selectionKey()
            ?: return false
        val primaryTrack = tracks.firstOrNull { it.selectionKey() == primaryKey } ?: return false
        val secondaryTrack = savedSelection
            ?.secondaryKey
            ?.let { secondaryKey ->
                tracks.firstOrNull { track -> track.id != primaryTrack.id && track.selectionKey() == secondaryKey }
            }

        activity.player.secondarySid = secondaryTrack?.id ?: -1
        activity.player.sid = primaryTrack.id
        _selectedSubtitles.update { Pair(primaryTrack.id, secondaryTrack?.id ?: -1) }
        applyParsedSubtitleCuesForTrack(primaryTrack.id)
        pendingSubtitleSelectionKey = null
        return true
    }

    fun loadStandaloneVideo(video: Video) {
        currentHosterList = null
        qualityIndex = Pair(-1, -1)

        _currentAnime.update { null }
        _currentEpisode.update { null }
        _currentSource.update { null }
        _currentPlaylist.update { emptyList() }
        _hosterList.update { emptyList() }
        _hosterState.update { emptyList() }
        _hosterExpandedList.update { emptyList() }
        _selectedHosterVideoIndex.update { Pair(-1, -1) }
        _currentVideo.update { video }
        _isEpisodeOnline.update { false }
        _hasPreviousEpisode.update { false }
        _hasNextEpisode.update { false }
        updateIsLoadingEpisode(false)
        updateIsLoadingHosters(false)
        isLoading.update { false }

        val title = video.videoTitle.ifBlank { video.videoUrl.substringAfterLast('/').substringBefore('?') }
        animeTitle.update { title }
        mediaTitle.update { title }
        MPVLib.setPropertyString("user-data/current-anime/anime-title", title)

        activity.setVideo(video, position = 0L)
    }

    fun loadYoutubeVideo(videoUrl: String)
    {
        youtubeLoadJob?.cancel()
        youtubeLoadJob = viewModelScope.launchIO {
            try {
                // Get all stream metadata, then get channel info
                val prefs = YouTubePreferences(Injekt.get<Application>())
                val videoMetadata = YouTubeResolver.resolveVideo(YouTubeResolver.getVideoId(videoUrl), prefs.preferredQuality)

                val episode = createYoutubeEpisode(videoMetadata, prefs)
                    ?: throw IllegalStateException("Failed to create youtube episode")

                val streams = videoMetadata.videoStreams
                if (streams.isEmpty())
                    throw IllegalStateException("No playable video streams found")

                saveCurrentEpisodeWatchingProgress()
                updateIsLoadingEpisode(true)
                updateIsLoadingHosters(true)

                val hoster = Hoster(hosterName = "YouTube", videoList = videoMetadata.videoStreams)
                val selected = streams.firstOrNull { it.preferred }
                    ?: selectYouTubeStream(streams, prefs.preferredQuality)
                val vidIndex = streams.indexOf(selected)

                val initResult = init(episode.animeId, episode.id, listOf(hoster).serialize(), 0, vidIndex)
                if (!initResult.second.getOrDefault(false)) {
                    val exception = initResult.second.exceptionOrNull() ?: IllegalStateException(
                        "Unknown error",
                    )

                    throw exception
                }

                updateIsLoadingHosters(false)
                loadHosters(
                    source = currentSource.value!!,
                    hosterList = initResult.first.hosterList ?: emptyList(),
                    hosterIndex = initResult.first.videoIndex.first,
                    videoIndex = initResult.first.videoIndex.second,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateIsLoadingHosters(false)
                    activity.toast("Failed to load video: ${e.message}")
                }
            }
        }
    }

    private suspend fun createYoutubeEpisode(videoMetadata: YouTubeVideoMetadata, prefs: YouTubePreferences) : tachiyomi.domain.episode.model.Episode?
    {
        suspend fun createAndAddEpisode(episodeId: Long, animeId: Long) : tachiyomi.domain.episode.model.Episode {
            val episode = tachiyomi.domain.episode.model.Episode.create().copy(
                id = episodeId, // Once inserted it will change so it doesnt matter
                animeId = animeId,
                url = videoMetadata.videoUrl,
                name = videoMetadata.videoName,
                totalSeconds = videoMetadata.videoLength,
                dateFetch = System.currentTimeMillis(),
                dateUpload = videoMetadata.videoUploadDate,
                scanlator = videoMetadata.videoType,
                previewUrl = videoMetadata.videoThumbnailUrl,
                //summary = videoMetadata.videoDescription, removed because it looks ugly
                episodeNumber = 0.0
            )
            val episodes = episodeRepository.addAll(listOf(episode))
            return episodes.first()
        }

        val episodeId = videoMetadata.videoId.hashCode().toLong()

        // Try to find existing anime entry, and add the current episode
        val animeEntry = animeRepository.getAnimeByUrlAndSourceId(videoMetadata.channelUrl, YouTubeSource.id)
        if (animeEntry != null)
        {
            val episode = episodeRepository.getEpisodeByUrlAndAnimeId(videoMetadata.videoUrl, animeEntry.id)
            if (episode == null)
                return createAndAddEpisode(episodeId, animeEntry.id)

            return episode
        }

        val channelMetadata = YouTubeResolver.resolveChannel(videoMetadata.channelId)

        // We create a new channel entry
        val newChannelEntry = Anime.create().copy(
            id = videoMetadata.channelId.hashCode().toLong(),
            url = videoMetadata.channelUrl,
            source = YouTubeSource.id,

            ogTitle = videoMetadata.channelName,
            ogDescription = channelMetadata?.description,
            ogThumbnailUrl = channelMetadata?.avatarUrl,
            backgroundUrl = channelMetadata?.bannerUrl,

            dateAdded = System.currentTimeMillis(),
            lastModifiedAt = System.currentTimeMillis(),
            coverLastModified = System.currentTimeMillis(),
            backgroundLastModified = System.currentTimeMillis(),

            favorite = prefs.addNewChannelsToLibrary
            )

        val newAnimeId = animeRepository.insertAnime(newChannelEntry)
            ?: return null

        return createAndAddEpisode(episodeId, newAnimeId)
    }

    private fun selectYouTubeStream(streams: List<Video>, targetQuality: String): Video {
        val targetPixels = YouTubeResolver.parseResolution(targetQuality)
        return streams.filter { YouTubeResolver.parseResolution(it.videoTitle) in 1..targetPixels }
            .maxByOrNull { YouTubeResolver.parseResolution(it.videoTitle) }
            ?: streams.filter { YouTubeResolver.parseResolution(it.videoTitle) > 0 }
                .minByOrNull { YouTubeResolver.parseResolution(it.videoTitle) }
            ?: streams.first()
    }

    suspend fun init(
        animeId: Long,
        initialEpisodeId: Long,
        hostList: String,
        hostIndex: Int,
        vidIndex: Int,
    ): Pair<InitResult, Result<Boolean>> {
        val defaultResult = InitResult(currentHosterList, qualityIndex, null)
        if (!needsInit()) return Pair(defaultResult, Result.success(true))
        return try {
            val anime = getAnime.await(animeId)
            if (anime != null) {
                _currentAnime.update { _ -> anime }
                animeTitle.update { _ -> anime.title }
                sourceManager.isInitialized.first { it }
                if (episodeId == -1L) episodeId = initialEpisodeId

                checkTrackers(anime)

                updateEpisodeList(initEpisodeList(anime))

                val episode = currentPlaylist.value.first { it.id == episodeId }
                val source = sourceManager.getOrStub(anime.source)

                _currentEpisode.update { _ -> episode }
                _currentSource.update { _ -> source }

                updateEpisode(episode)

                _hasPreviousEpisode.update { _ -> getCurrentEpisodeIndex() != 0 }
                _hasNextEpisode.update { _ -> getCurrentEpisodeIndex() != currentPlaylist.value.size - 1 }

                // Write to mpv table
                MPVLib.setPropertyString("user-data/current-anime/anime-title", anime.title)
                MPVLib.setPropertyInt("user-data/current-anime/intro-length", getAnimeSkipIntroLength())
                applySubtitleDelayForAnime(anime.id)
                MPVLib.setPropertyString(
                    "user-data/current-anime/category",
                    getAnimeCategories.await(anime.id).joinToString {
                        it.name
                    },
                )

                val currentEp = currentEpisode.value
                    ?: throw ExceptionWithStringResource("No episode loaded", MR.strings.no_episode_loaded)
                val hasExplicitVideoRequest = hostList.isNotBlank() || hostIndex != -1 || vidIndex != -1
                if (!hasExplicitVideoRequest && qualityIndex == Pair(-1, -1)) {
                    currentEp.id?.let(::restorePreferredVideoSelectionForEpisode)
                }
                if (hostList.isNotBlank()) {
                    currentHosterList = hostList.toHosterList().ifEmpty {
                        currentHosterList = null
                        throw ExceptionWithStringResource(
                            "Hoster selected from empty list",
                            MR.strings.select_hoster_from_empty_list,
                        )
                    }
                    qualityIndex = Pair(hostIndex, vidIndex)
                } else {
                    EpisodeLoader.getHosters(currentEp.toDomainEpisode()!!, anime, source)
                        .takeIf { it.isNotEmpty() }
                        ?.also { currentHosterList = it }
                        ?: run {
                            currentHosterList = null
                            throw ExceptionWithStringResource("Hoster list is empty", MR.strings.no_hosters)
                        }
                }

                val result = InitResult(
                    hosterList = currentHosterList,
                    videoIndex = qualityIndex,
                    position = episodePosition,
                )
                Pair(result, Result.success(true))
            } else {
                // Unlikely but okay
                Pair(defaultResult, Result.success(false))
            }
        } catch (e: Throwable) {
            Pair(defaultResult, Result.failure(e))
        }
    }

    private fun updateEpisode(episode: Episode) {
        mediaTitle.update { _ -> episode.name }
        _isEpisodeOnline.update { _ -> isEpisodeOnline() == true }
        MPVLib.setPropertyDouble("user-data/current-anime/episode-number", episode.episode_number.toDouble())
    }

    private fun initEpisodeList(anime: Anime): List<Episode> {
        val episodes = runBlocking { getEpisodesByAnimeId.await(anime.id) }

        return episodes
            .sortedWith(getEpisodeSort(anime, sortDescending = false))
            .run {
                if (basePreferences.downloadedOnly().get()) {
                    filterDownloadedEpisodes(anime)
                } else {
                    this
                }
            }
            .map { it.toDbEpisode() }
    }

    private var hasTrackers: Boolean = false
    private val checkTrackers: (Anime) -> Unit = { anime ->
        val tracks = runBlocking { getTracks.await(anime.id) }
        hasTrackers = tracks.isNotEmpty()
    }

    private var getHosterVideoLinksJob: Job? = null

    fun cancelHosterVideoLinksJob() {
        getHosterVideoLinksJob?.cancel()
    }

    /**
     * Set the video list for hosters.
     */
    fun loadHosters(source: AnimeSource, hosterList: List<Hoster>, hosterIndex: Int, videoIndex: Int) {
        val hasFoundPreferredVideo = AtomicBoolean(false)
        val hasRequestedVideo = hosterIndex != -1 || (preferredHosterKey != null && preferredVideoKey != null)

        _hosterList.update { _ -> hosterList }
        _hosterExpandedList.update { _ ->
            List(hosterList.size) { true }
        }

        getHosterVideoLinksJob?.cancel()
        getHosterVideoLinksJob = viewModelScope.launchIO {
            _hosterState.update { _ ->
                hosterList.map { hoster ->
                    if (hoster.videoList == null) {
                        HosterState.Loading(hoster.hosterName)
                    } else {
                        val videoList = hoster.videoList!!
                        HosterState.Ready(
                            hoster.hosterName,
                            videoList,
                            List(videoList.size) { Video.State.QUEUE },
                        )
                    }
                }
            }

            try {
                coroutineScope {
                    hosterList.mapIndexed { hosterIdx, hoster ->
                        async {
                            val hosterState = EpisodeLoader.loadHosterVideos(source, hoster)

                            _hosterState.updateAt(hosterIdx, hosterState)

                            if (hosterState is HosterState.Ready) {
                                val targetVideoIndex = findRequestedVideoIndex(
                                    hoster = hoster,
                                    videoList = hosterState.videoList,
                                    hosterIndex = hosterIdx,
                                    requestedHosterIndex = hosterIndex,
                                    requestedVideoIndex = videoIndex,
                                )
                                if (targetVideoIndex != -1 && hasFoundPreferredVideo.compareAndSet(false, true)) {
                                    hosterState.videoList.getOrNull(targetVideoIndex)?.let {
                                        hasFoundPreferredVideo.set(true)
                                        val success = loadVideo(
                                            source,
                                            it,
                                            hosterIdx,
                                            targetVideoIndex,
                                            rememberSelection = hosterIndex != -1,
                                        )
                                        if (!success) {
                                            hasFoundPreferredVideo.set(false)
                                        }
                                    }
                                }

                                val prefIndex = hosterState.videoList.indexOfFirst { it.preferred }
                                if (prefIndex != -1 && hosterIndex == -1 && !hasRequestedVideo) {
                                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                                        if (selectedHosterVideoIndex.value == Pair(-1, -1)) {
                                            val success =
                                                loadVideo(
                                                    source,
                                                    hosterState.videoList[prefIndex],
                                                    hosterIdx,
                                                    prefIndex,
                                                    rememberSelection = false,
                                                )
                                            if (!success) {
                                                hasFoundPreferredVideo.set(false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()

                    if (hasFoundPreferredVideo.compareAndSet(false, true)) {
                        val (hosterIdx, videoIdx) = HosterLoader.selectBestVideo(hosterState.value)
                        if (hosterIdx == -1) {
                            throw ExceptionWithStringResource("No available videos", MR.strings.no_available_videos)
                        }

                        val video = (hosterState.value[hosterIdx] as HosterState.Ready).videoList[videoIdx]

                        loadVideo(source, video, hosterIdx, videoIdx, rememberSelection = false)
                    }
                }
            } catch (e: CancellationException) {
                _hosterState.update { _ ->
                    hosterList.map { HosterState.Idle(it.hosterName) }
                }

                throw e
            }
        }
    }

    private suspend fun loadVideo(
        source: AnimeSource?,
        video: Video,
        hosterIndex: Int,
        videoIndex: Int,
        rememberSelection: Boolean = false,
    ): Boolean {
        val selectedHosterState = (_hosterState.value[hosterIndex] as? HosterState.Ready) ?: return false
        updateIsLoadingEpisode(true)

        val oldSelectedIndex = _selectedHosterVideoIndex.value
        _selectedHosterVideoIndex.update { _ -> Pair(hosterIndex, videoIndex) }

        _hosterState.updateAt(
            hosterIndex,
            selectedHosterState.getChangedAt(videoIndex, video, Video.State.LOAD_VIDEO),
        )

        // Pause until everything has loaded
        updatePausedState()
        pause()

        val resolvedVideo = if (selectedHosterState.videoState[videoIndex] != Video.State.READY) {
            HosterLoader.getResolvedVideo(source, video)
        } else {
            video
        }

        if (resolvedVideo == null || resolvedVideo.videoUrl.isEmpty()) {
            if (currentVideo.value == null) {
                _hosterState.updateAt(
                    hosterIndex,
                    selectedHosterState.getChangedAt(videoIndex, video, Video.State.ERROR),
                )

                val (newHosterIdx, newVideoIdx) = HosterLoader.selectBestVideo(hosterState.value)
                if (newHosterIdx == -1) {
                    if (_hosterState.value.any { it is HosterState.Loading }) {
                        _selectedHosterVideoIndex.update { _ -> Pair(-1, -1) }
                        return false
                    } else {
                        throw ExceptionWithStringResource("No available videos", MR.strings.no_available_videos)
                    }
                }

                val newVideo = (hosterState.value[newHosterIdx] as HosterState.Ready).videoList[newVideoIdx]

                return loadVideo(source, newVideo, newHosterIdx, newVideoIdx, rememberSelection = false)
            } else {
                _selectedHosterVideoIndex.update { _ -> oldSelectedIndex }
                _hosterState.updateAt(
                    hosterIndex,
                    selectedHosterState.getChangedAt(videoIndex, video, Video.State.ERROR),
                )
                return false
            }
        }

        _hosterState.updateAt(
            hosterIndex,
            selectedHosterState.getChangedAt(videoIndex, resolvedVideo, Video.State.READY),
        )

        _currentVideo.update { _ -> resolvedVideo }

        qualityIndex = Pair(hosterIndex, videoIndex)
        if (rememberSelection) {
            rememberPreferredVideoSelection(hosterIndex, videoIndex)
        }

        activity.setVideo(resolvedVideo)
        return true
    }

    fun onVideoClicked(hosterIndex: Int, videoIndex: Int) {
        videoClickHandler(hosterIndex, videoIndex)
    }

    private fun onSourceVideoClicked(hosterIndex: Int, videoIndex: Int) {
        val hosterState = _hosterState.value[hosterIndex] as? HosterState.Ready
        val video = hosterState?.videoList
            ?.getOrNull(videoIndex)
            ?: return // Shouldn't happen, but just in case™

        val videoState = hosterState.videoState
            .getOrNull(videoIndex)
            ?: return

        if (videoState == Video.State.ERROR) {
            return
        }

        viewModelScope.launchIO {
            val success = loadVideo(
                currentSource.value,
                video,
                hosterIndex,
                videoIndex,
                rememberSelection = true,
            )
            if (success) {
                if (sheetShown.value == Sheets.QualityTracks) {
                    dismissSheet()
                }
            } else {
                updateIsLoadingEpisode(false)
            }
        }
    }

    fun onHosterClicked(index: Int) {
        when (hosterState.value[index]) {
            is HosterState.Ready -> {
                _hosterExpandedList.updateAt(index, !_hosterExpandedList.value[index])
            }
            is HosterState.Idle -> {
                val hosterName = hosterList.value[index].hosterName
                _hosterState.updateAt(index, HosterState.Loading(hosterName))

                viewModelScope.launchIO {
                    val hosterState = EpisodeLoader.loadHosterVideos(currentSource.value!!, hosterList.value[index])
                    _hosterState.updateAt(index, hosterState)
                }
            }
            is HosterState.Loading, is HosterState.Error -> {}
        }
    }

    private fun <T> MutableStateFlow<List<T>>.updateAt(index: Int, newValue: T) {
        this.update { values ->
            values.toMutableList().apply {
                this[index] = newValue
            }
        }
    }

    data class EpisodeLoadResult(
        val hosterList: List<Hoster>?,
        val episodeTitle: String,
        val source: AnimeSource,
    )

    suspend fun loadEpisode(episodeId: Long?): EpisodeLoadResult? {
        val anime = currentAnime.value ?: return null
        val source = sourceManager.getOrStub(anime.source)

        val chosenEpisode = currentPlaylist.value.firstOrNull { ep -> ep.id == episodeId } ?: return null

        val hosters = withIOContext {
            try {
                EpisodeLoader.getHosters(
                    chosenEpisode.toDomainEpisode()!!,
                    anime,
                    source,
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { e.message ?: "Error getting links" }
                null
            }
        }

        currentHosterList = hosters

        if (!hosters.isNullOrEmpty()) {
            _currentEpisode.update { _ -> chosenEpisode }
            updateEpisode(chosenEpisode)
            applySubtitleDelayForAnime(anime.id)
            chosenEpisode.id?.let(::restorePreferredVideoSelectionForEpisode)

            this@PlayerViewModel.episodeId = chosenEpisode.id!!
            updateHasPreviousEpisode(getCurrentEpisodeIndex() != 0)
            updateHasNextEpisode(getCurrentEpisodeIndex() != currentPlaylist.value.size - 1)
        }

        return EpisodeLoadResult(
            hosterList = hosters,
            episodeTitle = anime.title + " - " + chosenEpisode.name,
            source = source,
        )
    }

    private fun applySubtitleDelayForAnime(animeId: Long) {
        val episodeId = currentEpisode.value?.id
        val primaryDelayMillis = subtitlePreferences.subtitlesDelayForEpisode(episodeId, animeId).get()
        MPVLib.setOptionString("sub-delay", (primaryDelayMillis / 1000.0).toString())
        MPVLib.setOptionString(
            "secondary-sub-delay",
            (subtitlePreferences.subtitlesSecondaryDelayForEpisode(episodeId, animeId).get() / 1000.0).toString(),
        )
        updatePrimarySubtitleDelayMillis(primaryDelayMillis)
        updateSubtitleSpeed(subtitlePreferences.subtitlesSpeedForEpisode(episodeId).get().toDouble())
    }

    /**
     * Called every time a second is reached in the player. Used to mark the flag of episode being
     * seen, update tracking services, enqueue downloaded episode deletion and download next episode.
     */
    private fun onSecondReached(position: Int, duration: Int) {
        if (isLoadingEpisode.value) return
        val currentEp = currentEpisode.value ?: return
        if (episodeId == -1L) return
        if (duration == 0) return

        val seconds = position * 1000L
        val totalSeconds = duration * 1000L
        // Save last second seen and mark as seen if needed
        currentEp.last_second_seen = seconds
        currentEp.total_seconds = totalSeconds

        episodePosition = seconds

        val progress = playerPreferences.progressPreference().get()
        val shouldTrack = !incognitoMode || hasTrackers
        if (seconds >= totalSeconds * progress && shouldTrack) {
            val wasSeen = currentEp.seen
            currentEp.seen = true
            updateTrackEpisodeSeen(currentEp)
            deleteEpisodeIfNeeded(currentEp)
            if (!wasSeen) {
                startSyncOnEpisodeSeen()
            }
        }

        saveWatchingProgress(currentEp)

        val inDownloadRange = seconds.toDouble() / totalSeconds > 0.35
        if (inDownloadRange) {
            downloadNextEpisodes()
        }
    }

    private fun downloadNextEpisodes() {
        if (downloadAheadAmount == 0) return
        val anime = currentAnime.value ?: return

        // Only download ahead if current + next episode is already downloaded too to avoid jank
        if (getCurrentEpisodeIndex() == currentPlaylist.value.lastIndex) return
        val currentEpisode = currentEpisode.value ?: return

        val nextEpisode = currentPlaylist.value[getCurrentEpisodeIndex() + 1]
        val episodesAreDownloaded =
            EpisodeLoader.isDownload(currentEpisode.toDomainEpisode()!!, anime) &&
                EpisodeLoader.isDownload(nextEpisode.toDomainEpisode()!!, anime)

        viewModelScope.launchIO {
            if (!episodesAreDownloaded) {
                return@launchIO
            }
            val episodesToDownload = getNextEpisodes.await(anime.id, nextEpisode.id!!)
                .take(downloadAheadAmount)
            downloadManager.downloadEpisodes(anime, episodesToDownload)
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last episode actually exists.
     * If both conditions are satisfied enqueues episode for delete
     * @param chosenEpisode current episode, which is going to be marked as seen.
     */
    private fun deleteEpisodeIfNeeded(chosenEpisode: Episode) {
        // Determine which episode should be deleted and enqueue
        val currentEpisodePosition = currentPlaylist.value.indexOf(chosenEpisode)
        val removeAfterSeenSlots = downloadPreferences.removeAfterReadSlots().get()
        val episodeToDelete = currentPlaylist.value.getOrNull(
            currentEpisodePosition - removeAfterSeenSlots,
        )
        // If episode is completely seen no need to download it
        episodeToDownload = null

        // Check if deleting option is enabled and episode exists
        if (removeAfterSeenSlots != -1 && episodeToDelete != null) {
            enqueueDeleteSeenEpisodes(episodeToDelete)
        }
    }

    fun saveCurrentEpisodeWatchingProgress() {
        currentEpisode.value?.let { saveWatchingProgress(it) }
    }

    /**
     * Called when episode is changed in player or when activity is paused.
     */
    private fun saveWatchingProgress(episode: Episode) {
        viewModelScope.launchNonCancellable {
            saveEpisodeProgress(episode)
            saveEpisodeHistory(episode)
        }
    }

    /**
     * Saves this [episode] progress (last second seen and whether it's seen).
     * If incognito mode isn't on or has at least 1 tracker
     */
    private suspend fun saveEpisodeProgress(episode: Episode) {
        if (!incognitoMode || hasTrackers) {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episode.id!!,
                    seen = episode.seen,
                    bookmark = episode.bookmark,
                    lastSecondSeen = episode.last_second_seen,
                    totalSeconds = episode.total_seconds,
                ),
            )
        }
    }

    /**
     * Saves this [episode] last seen history if incognito mode isn't on.
     */
    private suspend fun saveEpisodeHistory(episode: Episode) {
        if (!incognitoMode) {
            val episodeId = episode.id!!
            val seenAt = Date()
            upsertAnimeHistory.await(
                AnimeHistoryUpdate(episodeId, seenAt, 0),
            )
        }
    }

    /**
     * Bookmarks the currently active episode.
     */
    fun bookmarkEpisode(episodeId: Long?, bookmarked: Boolean) {
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    bookmark = bookmarked,
                ),
            )
        }
    }

    // AM (FILLERMARK) -->
    /**
     * Fillermarks the currently active episode.
     */
    fun fillermarkEpisode(episodeId: Long?, fillermarked: Boolean) {
        viewModelScope.launchNonCancellable {
            updateEpisode.await(
                EpisodeUpdate(
                    id = episodeId!!,
                    fillermark = fillermarked,
                ),
            )
        }
    }
    // <-- AM (FILLERMARK)

    fun takeScreenshot(cachePath: String, showSubtitles: Boolean): InputStream? {
        val filename = cachePath + "/${System.currentTimeMillis()}_mpv_screenshot_tmp.png"
        val subtitleFlag = if (showSubtitles) "subtitles" else "video"

        MPVLib.command(arrayOf("screenshot-to-file", filename, subtitleFlag))
        val tempFile = File(filename).takeIf { it.exists() && it.length() > 0L }
            ?: return fallbackScreenshotStream()
        val newFile = File("$cachePath/mpv_screenshot.png")

        newFile.delete()
        tempFile.renameTo(newFile)
        return newFile.takeIf { it.exists() }?.inputStream()
            ?: fallbackScreenshotStream()
    }

    private fun fallbackScreenshotStream(): InputStream? =
        runBlocking { mediaCapture.captureScreenshotStreamViaFfmpeg() }

    suspend fun captureVideoFrameForOcr(): Bitmap? = mediaCapture.captureVideoFrameForOcr()

    internal fun createSubtitleAudioMediaRequest(startSeconds: Double?, endSeconds: Double?) =
        mediaCapture.createSubtitleAudioMediaRequest(startSeconds, endSeconds)

    internal fun createVideoOcrAudioMediaRequest() = mediaCapture.createVideoOcrAudioMediaRequest()

    suspend fun captureAnimatedVideoForAnki(startSeconds: Double?, endSeconds: Double?): ByteArray? =
        mediaCapture.captureAnimatedVideoForAnki(startSeconds, endSeconds)

    suspend fun captureVideoOcrAnimatedForAnki(): ByteArray? = mediaCapture.captureVideoOcrAnimatedForAnki()

    /**
     * Saves the screenshot on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(imageStream: () -> InputStream, timePos: Int?) {
        val anime = currentAnime.value ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val seconds = timePos?.let { Utils.prettyTime(it) } ?: return
        val filename = generateFilename(anime, seconds) ?: return

        // Pictures directory.
        val relativePath = DiskUtil.buildValidFilename(anime.title)

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                notifier.onComplete(uri)
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    /**
     * Shares the screenshot and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(imageStream: () -> InputStream, timePos: Int?) {
        val anime = currentAnime.value ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val seconds = timePos?.let { Utils.prettyTime(it) } ?: return
        val filename = generateFilename(anime, seconds) ?: return

        try {
            viewModelScope.launchIO {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = imageStream,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(Event.ShareImage(uri, seconds))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /**
     * Sets the screenshot as cover and notifies the UI of the result.
     */
    fun setAsCover(imageStream: () -> InputStream) {
        val anime = currentAnime.value ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                anime.editCover(Injekt.get(), imageStream())
                if (anime.isLocal() || anime.favorite) {
                    SetAsCover.Success
                } else {
                    SetAsCover.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCover.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    /**
     * Results of the save image feature.
     */
    sealed class SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult()
        class Error(val error: Throwable) : SaveImageResult()
    }

    private fun updateTrackEpisodeSeen(episode: Episode) {
        if (basePreferences.incognitoMode().get() || !hasTrackers) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        val anime = currentAnime.value ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackEpisode.await(context, anime.id, episode.episode_number.toDouble())
        }
    }

    private fun startSyncOnEpisodeSeen() {
        if (incognitoMode || !syncPreferences.isSyncEnabled()) return

        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        if (syncTriggerOpt.syncOnEpisodeSeen) {
            SyncDataJob.startNow(Injekt.get<Application>())
        }
    }

    /**
     * Enqueues this [episode] to be deleted when [deletePendingEpisodes] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteSeenEpisodes(episode: Episode) {
        if (!episode.seen) return
        val anime = currentAnime.value ?: return
        viewModelScope.launchNonCancellable {
            downloadManager.enqueueEpisodesToDelete(listOf(episode.toDomainEpisode()!!), anime)
        }
    }

    /**
     * Deletes all the pending episodes. This operation will run in a background thread and errors
     * are ignored.
     */
    fun deletePendingEpisodes() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingEpisodes()
        }
    }

    /**
     * Returns the skipIntroLength used by this anime or the default one.
     */
    fun getAnimeSkipIntroLength(): Int {
        val default = gesturePreferences.defaultIntroLength().get()
        val anime = currentAnime.value ?: return default
        val skipIntroLength = anime.skipIntroLength
        val skipIntroDisable = anime.skipIntroDisable
        return when {
            skipIntroDisable -> 0
            skipIntroLength <= 0 -> default
            else -> anime.skipIntroLength
        }
    }

    /**
     * Updates the skipIntroLength for the open anime.
     */
    fun setAnimeSkipIntroLength(skipIntroLength: Long) {
        val anime = currentAnime.value ?: return
        if (!anime.favorite) return
        // Skip unnecessary database operation
        if (skipIntroLength == getAnimeSkipIntroLength().toLong()) return
        viewModelScope.launchIO {
            setAnimeViewerFlags.awaitSetSkipIntroLength(anime.id, skipIntroLength)
            _currentAnime.update { _ -> getAnime.await(anime.id) }
        }
    }

    /**
     * Generate a filename for the given [anime] and [timePos]
     */
    private fun generateFilename(
        anime: Anime,
        timePos: String,
    ): String? {
        val episode = currentEpisode.value ?: return null
        val filenameSuffix = " - $timePos"
        return DiskUtil.buildValidFilename(
            "${anime.title} - ${episode.name}".takeBytes(
                DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
            ),
        ) + filenameSuffix
    }

    /**
     * Returns the response of the AniSkipApi for this episode.
     * just works if tracking is enabled.
     */
    suspend fun aniSkipResponse(playerDuration: Int?): List<TimeStamp>? {
        val animeId = currentAnime.value?.id ?: return null
        val trackerManager = Injekt.get<TrackerManager>()
        var malId: Long?
        val episodeNumber = currentEpisode.value?.episode_number?.toInt() ?: return null
        if (getTracks.await(animeId).isEmpty()) {
            logcat { "AniSkip: No tracks found for anime $animeId" }
            return null
        }

        getTracks.await(animeId).map { track ->
            val tracker = trackerManager.get(track.trackerId)
            malId = when (tracker) {
                is MyAnimeList -> track.remoteId
                is Anilist -> AniSkipApi().getMalIdFromAL(track.remoteId)
                else -> null
            }
            val duration = playerDuration ?: return null
            return malId?.let {
                AniSkipApi().getResult(it.toInt(), episodeNumber, duration.toLong())
            }
        }
        return null
    }

    val introSkipEnabled = playerPreferences.enableSkipIntro().get()
    private val autoSkip = playerPreferences.autoSkipIntro().get()
    private val netflixStyle = playerPreferences.enableNetflixStyleIntroSkip().get()

    private val defaultWaitingTime = playerPreferences.waitingTimeIntroSkip().get()
    var waitingSkipIntro = defaultWaitingTime

    fun setChapter(position: Float) {
        getCurrentChapter(position)?.let { (chapterIndex, chapter) ->
            if (currentChapter.value != chapter) {
                _currentChapter.update { _ -> chapter }
            }

            if (!introSkipEnabled) {
                return
            }

            if (chapter.chapterType == ChapterType.Other) {
                _skipIntroText.update { _ -> null }
                waitingSkipIntro = defaultWaitingTime
            } else {
                val nextChapterPos = chapters.value.getOrNull(chapterIndex + 1)?.start ?: pos.value

                if (netflixStyle) {
                    // show a toast with the seconds before the skip
                    if (waitingSkipIntro == defaultWaitingTime) {
                        activity.showToast(
                            "Skip Intro: ${activity.stringResource(
                                MR.strings.player_aniskip_dontskip_toast,
                                chapter.name,
                                waitingSkipIntro,
                            )}",
                        )
                    }
                    showSkipIntroButton(chapter, nextChapterPos, waitingSkipIntro)
                    waitingSkipIntro--
                } else if (autoSkip) {
                    seekToWithText(
                        seekValue = nextChapterPos.toInt(),
                        text = activity.stringResource(MR.strings.player_intro_skipped, chapter.name),
                    )
                } else {
                    updateSkipIntroButton(chapter.chapterType)
                }
            }
        }
    }

    private fun updateSkipIntroButton(chapterType: ChapterType) {
        val skipButtonString = chapterType.getStringRes()

        _skipIntroText.update { _ ->
            skipButtonString?.let {
                activity.stringResource(
                    MR.strings.player_skip_action,
                    activity.stringResource(skipButtonString),
                )
            }
        }
    }

    private fun showSkipIntroButton(chapter: IndexedSegment, nextChapterPos: Float, waitingTime: Int) {
        if (waitingTime > -1) {
            if (waitingTime > 0) {
                _skipIntroText.update { _ -> activity.stringResource(MR.strings.player_aniskip_dontskip) }
            } else {
                seekToWithText(
                    seekValue = nextChapterPos.toInt(),
                    text = activity.stringResource(MR.strings.player_aniskip_skip, chapter.name),
                )
            }
        } else {
            // when waitingTime is -1, it means that the user cancelled the skip
            updateSkipIntroButton(chapter.chapterType)
        }
    }

    fun onSkipIntro() {
        getCurrentChapter()?.let { (chapterIndex, chapter) ->
            // this stops the counter
            if (waitingSkipIntro > 0 && netflixStyle) {
                waitingSkipIntro = -1
                return
            }

            val nextChapterPos = chapters.value.getOrNull(chapterIndex + 1)?.start ?: pos.value

            seekToWithText(
                seekValue = nextChapterPos.toInt(),
                text = activity.stringResource(MR.strings.player_aniskip_skip, chapter.name),
            )
        }
    }

    private fun getCurrentChapter(position: Float? = null): IndexedValue<IndexedSegment>? {
        return chapters.value.withIndex()
            .filter { it.value.start <= (position ?: pos.value) }
            .maxByOrNull { it.value.start }
    }

    fun setPrimaryCustomButtonTitle(button: CustomButton) {
        _primaryButtonTitle.update { _ -> button.name }
    }

    sealed class Event {
        data class SetCoverResult(val result: SetAsCover) : Event()
        data class SavedImage(val result: SaveImageResult) : Event()
        data class ShareImage(val uri: Uri, val seconds: String) : Event()
        data object OcrFailed : Event()
    }

}

fun isTorrentUrl(videoUrl: String): Boolean = videoUrl.endsWith(".torrent") || videoUrl.startsWith("magnet:")

fun CustomButton.execute() {
    MPVLib.command(arrayOf("script-message", "call_button_$id"))
}

fun CustomButton.executeLongPress() {
    MPVLib.command(arrayOf("script-message", "call_button_${id}_long"))
}

fun Float.normalize(inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    return (this - inMin) * (outMax - outMin) / (inMax - inMin) + outMin
}
