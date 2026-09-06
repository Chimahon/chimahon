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

package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import eu.kanade.presentation.more.settings.screen.player.custombutton.getButtons
import eu.kanade.presentation.theme.playerRippleConfiguration
import eu.kanade.tachiyomi.ui.player.CastManager
import eu.kanade.tachiyomi.ui.player.Dialogs
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.VideoAspect
import eu.kanade.tachiyomi.ui.player.cast.components.CastSheet
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessOverlay
import eu.kanade.tachiyomi.ui.player.controls.components.BrightnessSlider
import eu.kanade.tachiyomi.ui.player.controls.components.ControlsButton
import eu.kanade.tachiyomi.ui.player.controls.components.SeekbarWithTimers
import eu.kanade.tachiyomi.ui.player.controls.components.TextPlayerUpdate
import eu.kanade.tachiyomi.ui.player.controls.components.VolumeSlider
import eu.kanade.tachiyomi.ui.player.controls.components.panels.SubtitlesBorderStyle
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.toFixed
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.isLookupStartChar
import eu.kanade.tachiyomi.util.system.toast
import `is`.xyz.mpv.MPVLib
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs

@Suppress("CompositionLocalAllowlist")
val LocalPlayerButtonsClickEvent = staticCompositionLocalOf { {} }

@Composable
fun PlayerControls(
    viewModel: PlayerViewModel,
    castManager: CastManager,
    onBackPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCastSheet by remember { mutableStateOf(false) }
    val castState by castManager.castState.collectAsState()

    val spacing = MaterialTheme.padding
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val gesturePreferences = remember { Injekt.get<GesturePreferences>() }
    val audioPreferences = remember { Injekt.get<AudioPreferences>() }
    val subtitlePreferences = remember { Injekt.get<SubtitlePreferences>() }
    val interactionSource = remember { MutableInteractionSource() }
    val controlsShown by viewModel.controlsShown.collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val seekBarShown by viewModel.seekBarShown.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingEpisode by viewModel.isLoadingEpisode.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val position by viewModel.pos.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val gestureSeekAmount by viewModel.gestureSeekAmount.collectAsState()
    val doubleTapSeekAmount by viewModel.doubleTapSeekAmount.collectAsState()
    val seekText by viewModel.seekText.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val currentBrightness by viewModel.currentBrightness.collectAsState()
    val currentSubtitleText by viewModel.currentSubtitleText.collectAsState()
    val secondaryCurrentSubtitleText by viewModel.secondaryCurrentSubtitleText.collectAsState()
    val subtitlesVisible by viewModel.subtitlesVisible.collectAsState()
    val subtitleCues by viewModel.subtitleHistory.collectAsState()
    val activeSubtitleCueIndex by viewModel.activeSubtitleCueIndex.collectAsState()
    val primarySubtitleDelaySeconds by viewModel.primarySubtitleDelaySeconds.collectAsState()
    val panel by viewModel.panelShown.collectAsState()
    val activeSubtitleCue = remember(subtitleCues, activeSubtitleCueIndex) {
        subtitleCues.firstOrNull { it.index == activeSubtitleCueIndex }
    }

    val playerTimeToDisappear by playerPreferences.playerTimeToDisappear().collectAsState()
    var isSeeking by remember { mutableStateOf(false) }
    var resetControls by remember { mutableStateOf(true) }
    var subtitleLookupRequest by remember { mutableStateOf<SubtitleLookupRequest?>(null) }
    var wasPlayerAlreadyPause by remember { mutableStateOf(false) }
    val customButtons by viewModel.customButtons.collectAsState()
    val customButton by viewModel.primaryButton.collectAsState()

    LaunchedEffect(
        controlsShown,
        paused,
        isSeeking,
        resetControls,
    ) {
        if (controlsShown && !paused && !isSeeking) {
            delay(playerTimeToDisappear.toLong())
            viewModel.hideControls()
        }
    }

    val overlayTarget = if (controlsShown && !areControlsLocked) .8f else 0f
    val transparentOverlay by animateFloatAsState(
        targetValue = overlayTarget,
        animationSpec = if (overlayTarget > 0f) playerControlsEnterAnimationSpec() else playerControlsExitAnimationSpec(),
        label = "controls_transparent_overlay",
    )
    val openSubtitleLookup: (SubtitleLookupSelection) -> Unit = openSubtitleLookup@{ subtitleLookup ->
        if (subtitleLookupRequest?.matchesTap(subtitleLookup) == true) {
            subtitleLookupRequest = null
            if (!wasPlayerAlreadyPause) viewModel.unpause()
            return@openSubtitleLookup
        }
        val currentPanel = viewModel.panelShown.value
        if (
            viewModel.sheetShown.value != Sheets.None ||
            currentPanel != Panels.None ||
            viewModel.dialogShown.value != Dialogs.None
        ) {
            return@openSubtitleLookup
        }
        wasPlayerAlreadyPause = viewModel.paused.value
        viewModel.pause()
        subtitleLookupRequest = SubtitleLookupRequest(
            lookupString = subtitleLookup.lookupString,
            fullText = subtitleLookup.fullText,
            charOffset = subtitleLookup.charOffset,
            tapCharOffset = subtitleLookup.tapCharOffset,
            lineText = subtitleLookup.lineText,
            lineIndex = subtitleLookup.lineIndex,
            lineStartOffset = subtitleLookup.lineStartOffset,
            anchorX = subtitleLookup.anchorX,
            anchorY = subtitleLookup.anchorY,
            anchorWidth = subtitleLookup.anchorWidth,
            anchorHeight = subtitleLookup.anchorHeight,
            lineLeft = subtitleLookup.lineLeft,
            lineTop = subtitleLookup.lineTop,
            lineWidth = subtitleLookup.lineWidth,
            lineHeight = subtitleLookup.lineHeight,
            cueStartSeconds = subtitleLookup.cueStartSeconds,
            cueEndSeconds = subtitleLookup.cueEndSeconds,
        )
    }
    val togglePanel: (Panels) -> Unit = { panel ->
        viewModel.showPanel(
            if (viewModel.panelShown.value == panel) {
                Panels.None
            } else {
                panel
            },
        )
    }
    val dismissVideoOcr = {
        viewModel.dismissOcrScreenshot()
    }
    val captureVideoOcr = {
        viewModel.requestOcr()
    }
    GestureHandler(
        viewModel = viewModel,
        interactionSource = interactionSource,
    )
    Box(Modifier.fillMaxSize()) {
        if (subtitleLookupRequest != null) {
            Box(Modifier.fillMaxSize().clickable {
                subtitleLookupRequest = null
                if (!wasPlayerAlreadyPause) viewModel.unpause()
            })
        }
        PlayerSubtitleTextLayer(
            text = if (subtitlesVisible) currentSubtitleText else "",
            cue = activeSubtitleCue,
            subtitleDelaySeconds = primarySubtitleDelaySeconds,
            request = subtitleLookupRequest,
            onLookup = openSubtitleLookup,
        )
        PlayerSubtitleTextLayer(
            text = if (subtitlesVisible) secondaryCurrentSubtitleText else "",
            topAligned = true,
        )
    }
    DoubleTapToSeekOvals(doubleTapSeekAmount, seekText, interactionSource)
    CompositionLocalProvider(
        LocalRippleConfiguration provides playerRippleConfiguration,
        LocalPlayerButtonsClickEvent provides { resetControls = !resetControls },
        LocalContentColor provides Color.White,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
            ConstraintLayout(
                modifier = modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            Pair(0f, Color.Black),
                            Pair(.2f, Color.Transparent),
                            Pair(.7f, Color.Transparent),
                            Pair(1f, Color.Black),
                        ),
                        alpha = transparentOverlay,
                    )
                    .padding(horizontal = MaterialTheme.padding.medium),
            ) {
                val (
                    topLeftControls, topRightControls, castButton,
                    volumeSlider, brightnessSlider,
                    unlockControlsButton,
                    bottomRightControls, bottomLeftControls,
                    centerControls, seekbar, playerUpdates,
                    leftSideOcrButton, rightSideSubtitleButtons,
                ) = createRefs()

                val hasPreviousEpisode by viewModel.hasPreviousEpisode.collectAsState()
                val hasNextEpisode by viewModel.hasNextEpisode.collectAsState()
                val isBrightnessSliderShown by viewModel.isBrightnessSliderShown.collectAsState()
                val isVolumeSliderShown by viewModel.isVolumeSliderShown.collectAsState()
                val brightness by viewModel.currentBrightness.collectAsState()
                val volume by viewModel.currentVolume.collectAsState()
                val mpvVolume by viewModel.currentMPVVolume.collectAsState()
                val swapVolumeAndBrightness by gesturePreferences.swapVolumeBrightness().collectAsState()
                val reduceMotion by playerPreferences.reduceMotion().collectAsState()

                LaunchedEffect(volume, mpvVolume, isVolumeSliderShown) {
                    delay(2000)
                    if (isVolumeSliderShown) viewModel.isVolumeSliderShown.update { false }
                }
                LaunchedEffect(brightness, isBrightnessSliderShown) {
                    delay(2000)
                    if (isBrightnessSliderShown) viewModel.isBrightnessSliderShown.update { false }
                }
                AnimatedVisibility(
                    isBrightnessSliderShown,
                    enter =
                    if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) {
                            if (swapVolumeAndBrightness) -it else it
                        } +
                            fadeIn(
                                playerControlsEnterAnimationSpec(),
                            )
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit =
                    if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) {
                            if (swapVolumeAndBrightness) -it else it
                        } +
                            fadeOut(
                                playerControlsExitAnimationSpec(),
                            )
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(brightnessSlider) {
                        if (swapVolumeAndBrightness) {
                            start.linkTo(parent.start, spacing.medium)
                        } else {
                            end.linkTo(parent.end, spacing.medium)
                        }
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    BrightnessSlider(
                        brightness = brightness,
                        positiveRange = 0f..1f,
                        negativeRange = 0f..0.75f,
                    )
                }

                AnimatedVisibility(
                    isVolumeSliderShown,
                    enter =
                    if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) {
                            if (swapVolumeAndBrightness) it else -it
                        } +
                            fadeIn(
                                playerControlsEnterAnimationSpec(),
                            )
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit =
                    if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) {
                            if (swapVolumeAndBrightness) it else -it
                        } +
                            fadeOut(
                                playerControlsExitAnimationSpec(),
                            )
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(volumeSlider) {
                        if (swapVolumeAndBrightness) {
                            end.linkTo(parent.end, spacing.medium)
                        } else {
                            start.linkTo(parent.start, spacing.medium)
                        }
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    val boostCap by audioPreferences.volumeBoostCap().collectAsState()
                    val displayVolumeAsPercentage by playerPreferences.displayVolPer().collectAsState()
                    VolumeSlider(
                        volume = volume,
                        mpvVolume = mpvVolume,
                        range = 0..viewModel.maxVolume,
                        boostRange = if (boostCap > 0) 0..audioPreferences.volumeBoostCap().get() else null,
                        displayAsPercentage = displayVolumeAsPercentage,
                    )
                }

                val currentPlayerUpdate by viewModel.playerUpdate.collectAsState()
                val aspectRatio by playerPreferences.aspectState().collectAsState()
                LaunchedEffect(currentPlayerUpdate, aspectRatio) {
                    if (currentPlayerUpdate is PlayerUpdates.DoubleSpeed || currentPlayerUpdate is PlayerUpdates.None) {
                        return@LaunchedEffect
                    }
                    delay(2000)
                    viewModel.playerUpdate.update { PlayerUpdates.None }
                }
                AnimatedVisibility(
                    currentPlayerUpdate !is PlayerUpdates.None,
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    modifier = Modifier.constrainAs(playerUpdates) {
                        linkTo(parent.start, parent.end)
                        linkTo(parent.top, parent.bottom, bias = 0.2f)
                    },
                ) {
                    when (currentPlayerUpdate) {
                        // is PlayerUpdates.DoubleSpeed -> DoubleSpeedPlayerUpdate()
                        is PlayerUpdates.AspectRatio -> TextPlayerUpdate(stringResource(aspectRatio.titleRes))
                        is PlayerUpdates.ShowText -> TextPlayerUpdate(
                            (currentPlayerUpdate as PlayerUpdates.ShowText).value,
                        )
                        is PlayerUpdates.ShowTextResource -> TextPlayerUpdate(
                            stringResource((currentPlayerUpdate as PlayerUpdates.ShowTextResource).textResource),
                        )
                        else -> {}
                    }
                }

                AnimatedVisibility(
                    controlsShown && areControlsLocked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.constrainAs(unlockControlsButton) {
                        top.linkTo(parent.top, spacing.medium)
                        start.linkTo(parent.start, spacing.medium)
                    },
                ) {
                    ControlsButton(
                        Icons.Filled.Lock,
                        onClick = { viewModel.unlockControls() },
                    )
                }
                AnimatedVisibility(
                    visible =
                    (controlsShown && !areControlsLocked || gestureSeekAmount != null) ||
                        isLoading ||
                        isLoadingEpisode,
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    modifier = Modifier.constrainAs(centerControls) {
                        end.linkTo(parent.absoluteRight)
                        start.linkTo(parent.absoluteLeft)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    val showLoadingCircle by playerPreferences.showLoadingCircle().collectAsState()
                    MiddlePlayerControls(
                        hasPrevious = hasPreviousEpisode,
                        onSkipPrevious = { viewModel.changeEpisode(true) },
                        hasNext = hasNextEpisode,
                        onSkipNext = { viewModel.changeEpisode(false) },
                        isLoading = isLoading,
                        isLoadingEpisode = isLoadingEpisode,
                        controlsShown = controlsShown,
                        areControlsLocked = areControlsLocked,
                        showLoadingCircle = showLoadingCircle,
                        paused = paused,
                        gestureSeekAmount = gestureSeekAmount,
                        onPlayPauseClick = viewModel::pauseUnpause,
                        enter = fadeIn(playerControlsEnterAnimationSpec()),
                        exit = fadeOut(playerControlsExitAnimationSpec()),
                    )
                }
                AnimatedVisibility(
                    controlsShown && !areControlsLocked,
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    modifier = Modifier.constrainAs(leftSideOcrButton) {
                        start.linkTo(parent.start, spacing.small)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    ControlsButton(
                        icon = Icons.Default.Search,
                        onClick = captureVideoOcr,
                        horizontalSpacing = MaterialTheme.padding.mediumSmall,
                        iconSize = MaterialTheme.padding.large,
                    )
                }
                AnimatedVisibility(
                    controlsShown && !areControlsLocked,
                    enter = fadeIn(playerControlsEnterAnimationSpec()),
                    exit = fadeOut(playerControlsExitAnimationSpec()),
                    modifier = Modifier.constrainAs(rightSideSubtitleButtons) {
                        end.linkTo(parent.end, spacing.small)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    },
                ) {
                    ControlsButton(
                        icon = Icons.Default.FormatListBulleted,
                        onClick = { togglePanel(Panels.SubtitleSideList) },
                        horizontalSpacing = MaterialTheme.padding.mediumSmall,
                        iconSize = MaterialTheme.padding.large,
                    )
                }
                AnimatedVisibility(
                    visible = (controlsShown || seekBarShown) && !areControlsLocked,
                    enter = if (!reduceMotion) {
                        slideInVertically(playerControlsEnterAnimationSpec()) { it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutVertically(playerControlsExitAnimationSpec()) { it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(seekbar) {
                        bottom.linkTo(parent.bottom, spacing.medium)
                    },
                ) {
                    val invertDuration by playerPreferences.invertDuration().collectAsState()
                    val readAhead by viewModel.readAhead.collectAsState()
                    val preciseSeeking by gesturePreferences.playerSmoothSeek().collectAsState()
                    SeekbarWithTimers(
                        position = position,
                        duration = duration,
                        readAheadValue = readAhead,
                        onValueChange = {
                            isSeeking = true
                            viewModel.updatePlayBackPos(it)
                            viewModel.seekTo(it.toInt(), preciseSeeking)
                        },
                        onValueChangeFinished = { isSeeking = false },
                        timersInverted = Pair(false, invertDuration),
                        durationTimerOnCLick = { playerPreferences.invertDuration().set(!invertDuration) },
                        positionTimerOnClick = {},
                        chapters = chapters.map { it.toSegment() }.toImmutableList(),
                    )
                }
                val mediaTitle by viewModel.mediaTitle.collectAsState()
                val animeTitle by viewModel.animeTitle.collectAsState()
                AnimatedVisibility(
                    controlsShown && !areControlsLocked,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(topLeftControls) {
                        top.linkTo(parent.top, spacing.medium)
                        start.linkTo(parent.start)
                        width = Dimension.fillToConstraints
                        end.linkTo(topRightControls.start)
                    },
                ) {
                    TopLeftPlayerControls(
                        animeTitle = animeTitle,
                        mediaTitle = mediaTitle,
                        onTitleClick = { viewModel.showEpisodeListDialog() },
                        onBackClick = onBackPress,
                    )
                }
                // Top right controls
                val autoPlayEnabled by playerPreferences.autoplayEnabled().collectAsState()
                val isEpisodeOnline by viewModel.isEpisodeOnline.collectAsState()
                AnimatedVisibility(
                    controlsShown && !areControlsLocked,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(topRightControls) {
                        top.linkTo(parent.top, spacing.medium)
                        end.linkTo(parent.end)
                    },
                ) {
                    TopRightPlayerControls(
                        autoPlayEnabled = autoPlayEnabled,
                        onToggleAutoPlay = { viewModel.setAutoPlay(it) },
                        onSubtitlesClick = { viewModel.showSheet(Sheets.SubtitleTracks) },
                        onSubtitlesLongClick = { viewModel.showPanel(Panels.SubtitleSettings) },
                        onAudioClick = { viewModel.showSheet(Sheets.AudioTracks) },
                        onAudioLongClick = { viewModel.showPanel(Panels.AudioDelay) },
                        onQualityClick = { viewModel.showSheet(Sheets.QualityTracks) },
                        isEpisodeOnline = isEpisodeOnline,
                        onMoreClick = { viewModel.showSheet(Sheets.More) },
                        onMoreLongClick = { viewModel.showPanel(Panels.VideoFilters) },
                        castState = castState,
                        onCastClick = { showCastSheet = true },
                        isCastEnabled = { playerPreferences.enableCast().get() },
                    )
                }
                // Bottom right controls
                val skipIntroButton by viewModel.skipIntroText.collectAsState()
                val customButtonTitle by viewModel.primaryButtonTitle.collectAsState()
                AnimatedVisibility(
                    controlsShown && !areControlsLocked,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(bottomRightControls) {
                        bottom.linkTo(seekbar.top)
                        end.linkTo(seekbar.end)
                    },
                ) {
                    val activity = LocalContext.current as PlayerActivity
                    BottomRightPlayerControls(
                        customButton = customButton,
                        customButtonTitle = customButtonTitle,
                        skipIntroButton = skipIntroButton,
                        onPressSkipIntroButton = viewModel::onSkipIntro,
                        isPipAvailable = activity.isPipSupportedAndEnabled,
                        onPipClick = {
                            if (!viewModel.isLoadingEpisode.value) {
                                activity.enterPictureInPictureIfAvailable()
                            }
                        },
                        onAspectClick = {
                            viewModel.changeVideoAspect(
                                when (aspectRatio) {
                                    VideoAspect.Fit -> VideoAspect.Stretch
                                    VideoAspect.Stretch -> VideoAspect.Crop
                                    VideoAspect.Crop -> VideoAspect.Fit
                                },
                            )
                        },
                    )
                }
                // Bottom left controls
                val playbackSpeed by viewModel.playbackSpeed.collectAsState()
                AnimatedVisibility(
                    controlsShown && !areControlsLocked,
                    enter = if (!reduceMotion) {
                        slideInHorizontally(playerControlsEnterAnimationSpec()) { -it } +
                            fadeIn(playerControlsEnterAnimationSpec())
                    } else {
                        fadeIn(playerControlsEnterAnimationSpec())
                    },
                    exit = if (!reduceMotion) {
                        slideOutHorizontally(playerControlsExitAnimationSpec()) { -it } +
                            fadeOut(playerControlsExitAnimationSpec())
                    } else {
                        fadeOut(playerControlsExitAnimationSpec())
                    },
                    modifier = Modifier.constrainAs(bottomLeftControls) {
                        bottom.linkTo(seekbar.top)
                        start.linkTo(seekbar.start)
                        width = Dimension.fillToConstraints
                        end.linkTo(bottomRightControls.start)
                    },
                ) {
                    BottomLeftPlayerControls(
                        playbackSpeed,
                        currentChapter = currentChapter?.toSegment(),
                        onLockControls = viewModel::lockControls,
                        onCycleRotation = viewModel::cycleScreenRotations,
                        onPlaybackSpeedChange = {
                            MPVLib.setPropertyDouble("speed", it.toDouble())
                        },
                        onOpenSheet = viewModel::showSheet,
                    )
                }
            }
        }

        val sheetShown by viewModel.sheetShown.collectAsState()
        val dismissSheet by viewModel.dismissSheet.collectAsState()
        val subtitles by viewModel.subtitleTracks.collectAsState()
        val selectedSubtitles by viewModel.selectedSubtitles.collectAsState()
        val jimakuState by viewModel.jimakuState.collectAsState()
        val anime by viewModel.currentAnime.collectAsState()
        val currentEpisode by viewModel.currentEpisode.collectAsState()
        val jimakuTitle by subtitlePreferences.jimakuTitleForAnime(anime?.id).collectAsState()
        val audioTracks by viewModel.audioTracks.collectAsState()
        val selectedAudio by viewModel.selectedAudio.collectAsState()
        val isLoadingHosters by viewModel.isLoadingHosters.collectAsState()
        val hosterState by viewModel.hosterState.collectAsState()
        val expandedState by viewModel.hosterExpandedList.collectAsState()
        val selectedHosterVideoIndex by viewModel.selectedHosterVideoIndex.collectAsState()
        val decoder by viewModel.currentDecoder.collectAsState()
        val speed by viewModel.playbackSpeed.collectAsState()
        val sleepTimerTimeRemaining by viewModel.remainingTime.collectAsState()
        val showSubtitles by subtitlePreferences.screenshotSubtitles().collectAsState()
        val showFailedHosters by playerPreferences.showFailedHosters().collectAsState()
        val emptyHosters by playerPreferences.showEmptyHosters().collectAsState()

        PlayerSheets(
            sheetShown = sheetShown,
            subtitles = subtitles.toImmutableList(),
            selectedSubtitles = selectedSubtitles.toList().toImmutableList(),
            jimakuState = jimakuState,
            jimakuTitle = jimakuTitle,
            currentJimakuTitle = viewModel.getCurrentJimakuTitle(),
            onAddSubtitle = viewModel::addSubtitle,
            onSelectSubtitle = viewModel::selectSub,
            onSearchJimaku = viewModel::searchJimakuSubtitles,
            onSaveJimakuApiKeyAndSearch = viewModel::saveJimakuApiKeyAndSearch,
            onSelectJimakuEntry = viewModel::loadJimakuFiles,
            onSelectJimakuFile = viewModel::downloadJimakuSubtitle,
            onDismissJimaku = viewModel::dismissJimakuDialog,
            onUpdateJimakuTitle = viewModel::updateJimakuTitle,
            audioTracks = audioTracks.toImmutableList(),
            selectedAudio = selectedAudio,
            onAddAudio = viewModel::addAudio,
            onSelectAudio = viewModel::selectAudio,

            isLoadingHosters = isLoadingHosters,

            hosterState = hosterState,
            expandedState = expandedState,
            selectedVideoIndex = selectedHosterVideoIndex,
            onClickHoster = viewModel::onHosterClicked,
            onClickVideo = viewModel::onVideoClicked,
            displayHosters = Pair(showFailedHosters, emptyHosters),

            chapter = currentChapter?.toSegment(),
            chapters = chapters.map { it.toSegment() }.toImmutableList(),
            onSeekToChapter = {
                viewModel.selectChapter(it)
                viewModel.dismissSheet()
                viewModel.unpause()
            },
            decoder = decoder,
            onUpdateDecoder = viewModel::updateDecoder,
            speed = speed,
            onSpeedChange = { MPVLib.setPropertyDouble("speed", it.toFixed(2).toDouble()) },
            sleepTimerTimeRemaining = sleepTimerTimeRemaining,
            onStartSleepTimer = viewModel::startTimer,
            buttons = customButtons.getButtons().toImmutableList(),

            showSubtitles = showSubtitles,
            onToggleShowSubtitles = { subtitlePreferences.screenshotSubtitles().set(it) },
            cachePath = viewModel.cachePath,
            onSetAsCover = viewModel::setAsCover,
            onShare = { viewModel.shareImage(it, viewModel.pos.value.toInt()) },
            onSave = { viewModel.saveImage(it, viewModel.pos.value.toInt()) },
            takeScreenshot = viewModel::takeScreenshot,
            onDismissScreenshot = {
                viewModel.showSheet(Sheets.None)
                viewModel.unpause()
            },
            onOpenPanel = viewModel::showPanel,
            onDismissRequest = { viewModel.showSheet(Sheets.None) },
            dismissSheet = dismissSheet,
        )
        PlayerPanels(
            panelShown = panel,
            subtitleCues = subtitleCues.toImmutableList(),
            activeSubtitleCueIndex = activeSubtitleCueIndex,
            animeId = anime?.id,
            episodeId = currentEpisode?.id,
            onSelectSubtitleCue = viewModel::selectSubtitleCue,
            onPrimarySubtitleDelayMillisChange = viewModel::updatePrimarySubtitleDelayMillis,
            onSubtitleSpeedChange = viewModel::updateSubtitleSpeed,
            onSubtitleRegexFiltersChanged = viewModel::refreshSubtitleRegexFilters,
            onDismissRequest = { viewModel.showPanel(Panels.None) },
        )

        val activity = LocalContext.current as PlayerActivity
        val dialog by viewModel.dialogShown.collectAsState()
        val playlist by viewModel.currentPlaylist.collectAsState()

        PlayerDialogs(
            dialogShown = dialog,
            episodeDisplayMode = anime?.displayMode,
            episodeList = playlist,
            currentEpisodeIndex = viewModel.getCurrentEpisodeIndex(),
            dateRelativeTime = viewModel.relativeTime,
            dateFormat = viewModel.dateFormat,
            onBookmarkClicked = viewModel::bookmarkEpisode,
            onEpisodeClicked = {
                viewModel.showDialog(Dialogs.None)
                activity.changeEpisode(it)
            },
            onDismissRequest = { viewModel.showDialog(Dialogs.None) },
        )

        PlayerSubtitleLookupPopup(
            viewModel = viewModel,
            request = subtitleLookupRequest,
            onDismiss = {
                subtitleLookupRequest = null
                if (!wasPlayerAlreadyPause) viewModel.unpause()
            },
            onTermMatched = { count, offset ->
                subtitleLookupRequest = subtitleLookupRequest?.copy(
                    matchedCharCount = count,
                    matchOffset = offset,
                )
            },
        )

        BrightnessOverlay(
            brightness = currentBrightness,
        )

        val ocrScreenshot by viewModel.ocrScreenshot.collectAsState()
        PlayerVideoOcrOverlay(
            viewModel = viewModel,
            screenshot = ocrScreenshot,
            onDismiss = dismissVideoOcr,
        )
    }

    if (showCastSheet) {
        CastSheet(
            castManager = castManager,
            viewModel = viewModel,
            onDismissRequest = { showCastSheet = false },
        )
    }
}

@Composable
private fun PlayerSubtitleTextLayer(
    text: String,
    cue: PlayerViewModel.SubtitleCue? = null,
    subtitleDelaySeconds: Double = 0.0,
    request: SubtitleLookupRequest? = null,
    onLookup: (SubtitleLookupSelection) -> Unit = {},
    modifier: Modifier = Modifier,
    topAligned: Boolean = false,
    bottomPadding: Dp? = null,
    widthFraction: Float = 0.92f,
    maxWidth: Dp = 980.dp,
    fontSizeFactor: Float = 0.52f,
    minFontSize: Float = 18f,
    maxFontSize: Float = 42f,
) {
    val subtitleText = remember(text) {
        text.lines()
            .map { it.trim().collapseHorizontalWhitespace() }
            .joinToString("\n")
    }

    val subtitlePreferences = remember { Injekt.get<SubtitlePreferences>() }
    val subtitleFontSize by subtitlePreferences.subtitleFontSize().collectAsState()
    val subtitleScale by subtitlePreferences.subtitleFontScale().collectAsState()
    val subtitlePos by subtitlePreferences.subtitlePos().collectAsState()
    val textColor by subtitlePreferences.textColorSubtitles().collectAsState()
    val borderColor by subtitlePreferences.borderColorSubtitles().collectAsState()
    val backgroundColor by subtitlePreferences.backgroundColorSubtitles().collectAsState()
    val borderStyle by subtitlePreferences.borderStyleSubtitles().collectAsState()
    val borderSize by subtitlePreferences.subtitleBorderSize().collectAsState()
    val bold by subtitlePreferences.boldSubtitles().collectAsState()
    val italic by subtitlePreferences.italicSubtitles().collectAsState()

    if (subtitleText.isBlank()) return

    var textLayout by remember(subtitleText) { mutableStateOf<TextLayoutResult?>(null) }
    var textLayerOrigin by remember(subtitleText) { mutableStateOf(Offset.Zero) }
    val fontSizeSp = (subtitleFontSize * subtitleScale * fontSizeFactor).coerceIn(minFontSize, maxFontSize)
    val resolvedBottomPadding = bottomPadding ?: (28f + (100 - subtitlePos).coerceIn(0, 100) * 2.2f).dp
    val outlineWidth = borderSize.coerceAtLeast(1) * 1.8f
    val lookupHighlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val boxBackgroundColor = Color(backgroundColor).let {
        if (it.alpha == 0f && borderStyle != SubtitlesBorderStyle.OutlineAndShadow) {
            Color.Black.copy(alpha = 0.78f)
        } else {
            it
        }
    }
    val baseStyle = TextStyle(
        color = Color(textColor),
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * 1.18f).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
    )
    val outlineColor = Color(borderColor).copy(
        alpha = minOf(Color(borderColor).alpha, Color(textColor).alpha),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .then(
                if (topAligned) {
                    Modifier.padding(top = 24.dp)
                } else {
                    Modifier.padding(bottom = resolvedBottomPadding)
                },
            ),
        contentAlignment = if (topAligned) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .widthIn(max = maxWidth)
                .onGloballyPositioned { coordinates ->
                    textLayerOrigin = coordinates.positionInRoot()
                }
                .drawBehind {
                    val layout = textLayout ?: return@drawBehind
                    if (borderStyle != SubtitlesBorderStyle.OutlineAndShadow) {
                        val backgroundRects = when (borderStyle) {
                            SubtitlesBorderStyle.OpaqueBox -> layout.subtitleLineBackgroundRects(
                                subtitleText,
                                fullLineWidth = true,
                            )
                            SubtitlesBorderStyle.BackgroundBox -> layout.subtitleLineBackgroundRects(
                                subtitleText,
                                fullLineWidth = false,
                            )
                            SubtitlesBorderStyle.OutlineAndShadow -> emptyList()
                        }
                        backgroundRects.forEach { rect ->
                            drawRoundRect(
                                color = boxBackgroundColor,
                                topLeft = Offset(rect.left, rect.top),
                                size = Size(rect.width, rect.height),
                                cornerRadius = CornerRadius(4f, 4f),
                            )
                        }
                    }

                    val activeRequest = request?.takeIf {
                        it.fullText == subtitleText && it.matchedCharCount > 0
                    } ?: return@drawBehind
                    val start = (activeRequest.charOffset + activeRequest.matchOffset)
                        .coerceIn(0, subtitleText.length)
                    if (start >= subtitleText.length) return@drawBehind
                    val count = activeRequest.matchedCharCount
                    val end = (start + count).coerceIn(start + 1, subtitleText.length)
                    layout.highlightRects(subtitleText, start, end).forEach { rect ->
                        drawRoundRect(
                            color = lookupHighlightColor,
                            topLeft = Offset(rect.left, rect.top),
                            size = Size(rect.width, rect.height),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                    }
                }
                // Timing can arrive after the text, or change when an identical line repeats.
                .pointerInput(subtitleText, textLayout, textLayerOrigin, subtitleDelaySeconds, cue) {
                    detectTapGestures(
                        onTap = { position ->
                            val layout = textLayout ?: return@detectTapGestures
                            layout.subtitleLookupSelectionForTap(subtitleText, position, cue, subtitleDelaySeconds)
                                ?.offsetBy(textLayerOrigin)
                                ?.let(onLookup)
                        },
                        onLongPress = { position ->
                            val layout = textLayout ?: return@detectTapGestures
                            layout.subtitleLookupSelectionForTap(subtitleText, position, cue, subtitleDelaySeconds)
                                ?.offsetBy(textLayerOrigin)
                                ?.let(onLookup)
                        },
                    )
                },
        ) {
            if (borderStyle == SubtitlesBorderStyle.OutlineAndShadow) {
                Text(
                    text = subtitleText,
                    modifier = Modifier.fillMaxWidth(),
                    color = outlineColor,
                    style = baseStyle.copy(
                        color = outlineColor,
                        drawStyle = Stroke(width = outlineWidth),
                    ),
                )
            }

            Text(
                text = subtitleText,
                modifier = Modifier.fillMaxWidth(),
                style = baseStyle,
                onTextLayout = { textLayout = it },
            )
        }
    }
}

private data class SubtitleLookupSelection(
    val lookupString: String,
    val fullText: String,
    val charOffset: Int,
    val tapCharOffset: Int,
    val lineText: String,
    val lineIndex: Int,
    val lineStartOffset: Int,
    val anchorX: Float,
    val anchorY: Float,
    val anchorWidth: Float,
    val anchorHeight: Float,
    val lineLeft: Float,
    val lineTop: Float,
    val lineWidth: Float,
    val lineHeight: Float,
    val cueStartSeconds: Double? = null,
    val cueEndSeconds: Double? = null,
)

private fun TextLayoutResult.subtitleLookupSelectionForTap(
    text: String,
    position: Offset,
    cue: PlayerViewModel.SubtitleCue?,
    subtitleDelaySeconds: Double,
): SubtitleLookupSelection? {
    if (text.isBlank()) return null
    val offset = lookupOffsetForPosition(text, position) ?: return null
    if (offset !in text.indices || !isLookupStartChar(text[offset])) return null
    val lookupString = extractOcrLookupString(text, offset).take(80).trim()
    if (lookupString.isBlank()) return null
    val anchor = lookupAnchorRect(text, offset, lookupString) ?: return null
    val lineIndex = getLineForOffset(offset.coerceIn(0, text.lastIndex))
    val lineStart = getLineStart(lineIndex)
    val lineEnd = getLineEnd(lineIndex, visibleEnd = true).coerceAtLeast(lineStart)
    val lineText = text.substring(lineStart, lineEnd)
    val lineBounds = lineBounds(lineIndex)

    return SubtitleLookupSelection(
        lookupString = lookupString,
        fullText = text,
        charOffset = offset,
        tapCharOffset = offset,
        lineText = lineText,
        lineIndex = lineIndex,
        lineStartOffset = lineStart,
        anchorX = anchor.left,
        anchorY = getLineTop(0),
        anchorWidth = anchor.width,
        anchorHeight = getLineBottom(lineCount - 1) - getLineTop(0),
        lineLeft = lineBounds.left,
        lineTop = lineBounds.top,
        lineWidth = lineBounds.width,
        lineHeight = lineBounds.height,
        cueStartSeconds = cue?.positionSeconds?.plus(subtitleDelaySeconds),
        cueEndSeconds = cue?.endPositionSeconds?.plus(subtitleDelaySeconds),
    )
}

private data class SubtitleAnchorRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private fun SubtitleLookupSelection.offsetBy(offset: Offset): SubtitleLookupSelection {
    return copy(
        anchorX = anchorX + offset.x,
        anchorY = anchorY + offset.y,
        lineLeft = lineLeft + offset.x,
        lineTop = lineTop + offset.y,
    )
}

private fun SubtitleLookupRequest.matchesTap(selection: SubtitleLookupSelection): Boolean {
    if (fullText != selection.fullText || lineIndex != selection.lineIndex) return false

    val start = (charOffset + matchOffset).coerceIn(0, fullText.length)
    val count = matchedCharCount.takeIf { it > 0 } ?: 1
    val end = (start + count).coerceIn(start + 1, fullText.length)

    return selection.tapCharOffset in start until end ||
        (
            selection.lookupString == lookupString &&
                selection.tapCharOffset == tapCharOffset &&
                selection.lineStartOffset == lineStartOffset
            )
}

private fun TextLayoutResult.lookupOffsetForPosition(text: String, position: Offset): Int? {
    if (text.isBlank()) return null
    val lineIndex = getLineForVerticalPosition(position.y).coerceIn(0, lineCount - 1)
    val lineStart = getLineStart(lineIndex).coerceIn(0, text.length)
    val lineEnd = getLineEnd(lineIndex, visibleEnd = true).coerceIn(lineStart, text.length)
    val lineBounds = lineBounds(lineIndex)
    val verticalSlop = (lineBounds.height * 0.55f).coerceIn(10f, 28f)
    if (position.y < lineBounds.top - verticalSlop || position.y > lineBounds.bottom + verticalSlop) {
        return null
    }

    return (lineStart until lineEnd)
        .filter { offset ->
            val char = text[offset]
            char.isSubtitleLookupChar()
        }
        .mapNotNull { offset ->
            val box = getBoundingBox(offset)
            if (box.width <= 0f || box.height <= 0f) return@mapNotNull null
            val dx = when {
                position.x < box.left -> box.left - position.x
                position.x > box.right -> position.x - box.right
                else -> 0f
            }
            val dy = when {
                position.y < box.top -> box.top - position.y
                position.y > box.bottom -> position.y - box.bottom
                else -> 0f
            }
            val horizontalLimit = (box.width * 0.9f).coerceIn(10f, 28f)
            val verticalLimit = (box.height * 0.8f).coerceIn(12f, 30f)
            if (dx <= horizontalLimit && dy <= verticalLimit) {
                val centerX = (box.left + box.right) / 2f
                val centerY = (box.top + box.bottom) / 2f
                val centerDistance = abs(position.x - centerX) + abs(position.y - centerY) * 0.55f
                offset to (dx * 2.2f + dy * 1.4f + centerDistance * 0.2f)
            } else {
                null
            }
        }
        .minByOrNull { it.second }
        ?.first
}

private fun TextLayoutResult.lookupAnchorRect(
    text: String,
    lookupStart: Int,
    lookupString: String,
): SubtitleAnchorRect? {
    val matchLength = lookupString.takeWhile { isLookupStartChar(it) }
        .length
        .takeIf { it > 0 }
        ?: 1
    val start = lookupStart.coerceIn(0, text.length)
    val end = (start + matchLength).coerceIn(start + 1, text.length)
    val rect = highlightRects(text, start, end).reduceOrNull { acc, item -> acc.unionWith(item) }
        ?: return null
    return SubtitleAnchorRect(
        left = rect.left,
        top = rect.top,
        width = rect.width.coerceAtLeast(8f),
        height = rect.height.coerceAtLeast(8f),
    )
}

private fun TextLayoutResult.highlightRects(text: String, start: Int, end: Int): List<Rect> {
    val rects = (start until end)
        .filter { it in text.indices && !text[it].isWhitespace() }
        .map { getBoundingBox(it) }
        .filter { it.width > 0f && it.height > 0f }
    if (rects.isEmpty()) return emptyList()

    val merged = mutableListOf<Rect>()
    rects.forEach { rect ->
        val index = merged.indexOfFirst {
            abs(it.top - rect.top) < 2f && abs(it.bottom - rect.bottom) < 2f
        }
        if (index >= 0) {
            merged[index] = merged[index].unionWith(rect)
        } else {
            merged += rect
        }
    }
    return merged
}

private fun TextLayoutResult.subtitleLineBackgroundRects(text: String, fullLineWidth: Boolean): List<Rect> {
    return (0 until lineCount).mapNotNull { lineIndex ->
        val lineStart = getLineStart(lineIndex).coerceIn(0, text.length)
        val lineEnd = getLineEnd(lineIndex, visibleEnd = true).coerceIn(lineStart, text.length)
        if (text.substring(lineStart, lineEnd).isBlank()) return@mapNotNull null

        val rect = if (fullLineWidth) {
            lineBounds(lineIndex)
        } else {
            highlightRects(text, lineStart, lineEnd).reduceOrNull { acc, item -> acc.unionWith(item) }
                ?: return@mapNotNull null
        }

        rect.inflate(horizontal = 10f, vertical = 4f)
    }
}

private fun TextLayoutResult.lineBounds(lineIndex: Int): Rect {
    return Rect(
        left = getLineLeft(lineIndex),
        top = getLineTop(lineIndex),
        right = getLineRight(lineIndex),
        bottom = getLineBottom(lineIndex),
    )
}

private fun Rect.inflate(horizontal: Float, vertical: Float): Rect {
    return Rect(
        left = left - horizontal,
        top = top - vertical,
        right = right + horizontal,
        bottom = bottom + vertical,
    )
}

private fun Rect.unionWith(other: Rect): Rect {
    return Rect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )
}

private fun Char.subtitleDisplayUnits(): Double {
    return when {
        isWhitespace() -> 0.5
        code <= 0x7F -> 0.6
        else -> 1.0
    }
}

private fun Char.isSubtitleLookupChar(): Boolean = isLookupStartChar(this)

private fun String.collapseHorizontalWhitespace(): String {
    var lastWasSpace = false
    return buildString(length) {
        for (char in this@collapseHorizontalWhitespace) {
            if (char == ' ' || char == '\t' || char == '\u3000') {
                if (!lastWasSpace) append(' ')
                lastWasSpace = true
            } else {
                append(char)
                lastWasSpace = false
            }
        }
    }.trim()
}

fun <T> playerControlsExitAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 300,
    easing = FastOutSlowInEasing,
)

fun <T> playerControlsEnterAnimationSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = 100,
    easing = LinearOutSlowInEasing,
)
