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

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.player.components.LeftSideOvalShape
import eu.kanade.presentation.player.components.RightSideOvalShape
import eu.kanade.presentation.theme.playerRippleConfiguration
import eu.kanade.tachiyomi.ui.player.Panels
import eu.kanade.tachiyomi.ui.player.PlayerUpdates
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.Sheets
import eu.kanade.tachiyomi.ui.player.controls.components.DoubleTapSeekTriangles
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs

private const val SUBTITLE_SWIPE_TIME_LIMIT_MILLIS = 500L
private const val SUBTITLE_SWIPE_DOMINANCE_RATIO = 1.2f

internal enum class SubtitleSwipeAction {
    ToggleVisibility,
    ReplayCurrent,
    Previous,
    Next,
}

internal fun resolveSubtitleSwipeAction(
    totalDragX: Float,
    totalDragY: Float,
    minimumDistance: Float,
): SubtitleSwipeAction? {
    val horizontalDistance = abs(totalDragX)
    val verticalDistance = abs(totalDragY)
    return when {
        horizontalDistance >= minimumDistance &&
            horizontalDistance > verticalDistance * SUBTITLE_SWIPE_DOMINANCE_RATIO -> {
            if (totalDragX > 0f) SubtitleSwipeAction.Next else SubtitleSwipeAction.Previous
        }
        verticalDistance >= minimumDistance &&
            verticalDistance > horizontalDistance * SUBTITLE_SWIPE_DOMINANCE_RATIO -> {
            if (totalDragY > 0f) SubtitleSwipeAction.ToggleVisibility else SubtitleSwipeAction.ReplayCurrent
        }
        else -> null
    }
}

@Composable
fun GestureHandler(
    viewModel: PlayerViewModel,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    onSubtitleTap: (x: Float, y: Float, width: Int, height: Int) -> Boolean = { _, _, _, _ -> false },
    onSubtitleLongPress: (x: Float, y: Float, width: Int, height: Int) -> Boolean = { _, _, _, _ -> false },
) {
    val playerPreferences = remember { Injekt.get<PlayerPreferences>() }
    val gesturePreferences = remember { Injekt.get<GesturePreferences>() }
    val audioPreferences = remember { Injekt.get<AudioPreferences>() }

    val panelShown by viewModel.panelShown.collectAsState()
    val allowGesturesInPanels by playerPreferences.allowGestures().collectAsState()
    val duration by viewModel.duration.collectAsState()
    val position by viewModel.pos.collectAsState()
    val controlsShown by viewModel.controlsShown.collectAsState()
    val areControlsLocked by viewModel.areControlsLocked.collectAsState()
    val disableLongPressScr by playerPreferences.disableLongPressScreenshot().collectAsState()
    val singleTapToPause by playerPreferences.singleTapToPause().collectAsState()
    val seekAmount by viewModel.doubleTapSeekAmount.collectAsState()
    val isSeekingForwards by viewModel.isSeekingForwards.collectAsState()
    var isDoubleTapSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(seekAmount) {
        delay(800)
        isDoubleTapSeeking = false
        viewModel.updateSeekAmount(0)
        viewModel.updateSeekText(null)
        delay(100)
        viewModel.hideSeekBar()
    }

    val gestureVolumeBrightness = gesturePreferences.gestureVolumeBrightness().get()
    val subtitleSwipeControls by gesturePreferences.subtitleSwipeControls().collectAsState()
    val swapVolumeBrightness by gesturePreferences.swapVolumeBrightness().collectAsState()
    val seekGesture by gesturePreferences.gestureHorizontalSeek().collectAsState()
    val preciseSeeking by gesturePreferences.playerSmoothSeek().collectAsState()
    val showSeekbar by gesturePreferences.showSeekBar().collectAsState()
    var isLongPressing by remember { mutableStateOf(false) }
    val currentVolume by viewModel.currentVolume.collectAsState()
    val currentMPVVolume by viewModel.currentMPVVolume.collectAsState()
    val currentBrightness by viewModel.currentBrightness.collectAsState()
    val volumeBoostingCap = audioPreferences.volumeBoostCap().get()
    val haptics = LocalHapticFeedback.current
    val subtitleSwipeDistance = with(LocalDensity.current) { 30.dp.toPx() }
    var subtitleViewport by remember { mutableStateOf(IntSize.Zero) }
    var subtitleOrigin by remember { mutableStateOf(Offset.Zero) }
    val subtitleTap by rememberUpdatedState(onSubtitleTap)
    val subtitleLongPress by rememberUpdatedState(onSubtitleLongPress)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { subtitleViewport = it }
            .windowInsetsPadding(WindowInsets.safeGestures)
            .onGloballyPositioned { subtitleOrigin = it.positionInRoot() }
            .pointerInput(Unit) {
                coroutineScope {
                    val originalSpeed = viewModel.playbackSpeed.value
                    var pendingSingleTap: Job? = null
                    var lastTapAt = 0L
                    var lastTapX = 0f
                    var lastTapY = 0f
                    val doubleTapWindowMillis = 220L
                    val doubleTapDistance = viewConfiguration.touchSlop * 4

                    detectTapGestures(
                        onTap = { position ->
                            if (subtitleTap(
                                    position.x + subtitleOrigin.x,
                                    position.y + subtitleOrigin.y,
                                    subtitleViewport.width,
                                    subtitleViewport.height,
                                )
                            ) {
                                pendingSingleTap?.cancel()
                                lastTapAt = 0L
                                return@detectTapGestures
                            }
                            if (viewModel.suppressTap.value) return@detectTapGestures

                            val now = SystemClock.uptimeMillis()
                            val isNearby = kotlin.math.abs(position.x - lastTapX) <= doubleTapDistance &&
                                kotlin.math.abs(position.y - lastTapY) <= doubleTapDistance
                            val isDoubleTap = lastTapAt > 0L &&
                                now - lastTapAt <= doubleTapWindowMillis &&
                                isNearby

                            if (isDoubleTap) {
                                pendingSingleTap?.cancel()
                                lastTapAt = 0L
                                if (areControlsLocked || isDoubleTapSeeking) return@detectTapGestures
                                if (position.x > size.width * 3 / 5) {
                                    if (!isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleRightDoubleTap()
                                    isDoubleTapSeeking = true
                                } else if (position.x < size.width * 2 / 5) {
                                    if (isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleLeftDoubleTap()
                                    isDoubleTapSeeking = true
                                } else {
                                    viewModel.handleCenterDoubleTap()
                                }
                            } else {
                                lastTapAt = now
                                lastTapX = position.x
                                lastTapY = position.y
                                pendingSingleTap = launch {
                                    delay(doubleTapWindowMillis)
                                    if (lastTapAt == now) {
                                        lastTapAt = 0L
                                        if (singleTapToPause) {
                                            if (viewModel.paused.value) {
                                                viewModel.unpause()
                                                viewModel.hideControls()
                                            } else {
                                                viewModel.pause()
                                                viewModel.showControls()
                                            }
                                        } else {
                                            if (viewModel.controlsShown.value) {
                                                viewModel.hideControls()
                                            } else {
                                                viewModel.showControls()
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onPress = {
                            if (viewModel.suppressTap.value) return@detectTapGestures
                            if (panelShown != Panels.None && !allowGesturesInPanels) {
                                viewModel.panelShown.update { Panels.None }
                            }
                            val press = PressInteraction.Press(
                                it.copy(x = if (it.x > size.width * 3 / 5) it.x - size.width * 0.6f else it.x),
                            )
                            if (!areControlsLocked && isDoubleTapSeeking && seekAmount != 0) {
                                if (it.x > size.width * 3 / 5) {
                                    if (!isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleRightDoubleTap()
                                } else if (it.x < size.width * 2 / 5) {
                                    if (isSeekingForwards) viewModel.updateSeekAmount(0)
                                    viewModel.handleLeftDoubleTap()
                                } else {
                                    viewModel.handleCenterDoubleTap()
                                }
                            } else {
                                isDoubleTapSeeking = false
                            }
                            interactionSource.emit(press)
                            tryAwaitRelease()
                            if (isLongPressing) {
                                isLongPressing = false
                                MPVLib.setPropertyDouble("speed", originalSpeed.toDouble())
                                viewModel.playerUpdate.update { PlayerUpdates.None }
                            }
                            interactionSource.emit(PressInteraction.Release(press))
                        },
                        onLongPress = {
                            pendingSingleTap?.cancel()
                            lastTapAt = 0L
                            if (areControlsLocked) return@detectTapGestures
                            if (!isLongPressing) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (subtitleLongPress(
                                        it.x + subtitleOrigin.x,
                                        it.y + subtitleOrigin.y,
                                        subtitleViewport.width,
                                        subtitleViewport.height,
                                    )
                                ) {
                                    return@detectTapGestures
                                }
                                if (disableLongPressScr) return@detectTapGestures
                                isLongPressing = true
                                viewModel.pause()
                                viewModel.sheetShown.update { Sheets.Screenshot }
                            }
                        },
                    )
                }
            }
            .pointerInput(areControlsLocked, subtitleSwipeControls, subtitleSwipeDistance) {
                if (!subtitleSwipeControls || areControlsLocked) return@pointerInput
                var startedAt = 0L
                var totalDragX = 0f
                var totalDragY = 0f
                detectDragGestures(
                    onDragStart = {
                        startedAt = SystemClock.uptimeMillis()
                        totalDragX = 0f
                        totalDragY = 0f
                    },
                    onDragEnd = {
                        if (SystemClock.uptimeMillis() - startedAt > SUBTITLE_SWIPE_TIME_LIMIT_MILLIS) {
                            return@detectDragGestures
                        }

                        when (resolveSubtitleSwipeAction(totalDragX, totalDragY, subtitleSwipeDistance)) {
                            SubtitleSwipeAction.ToggleVisibility -> {
                                viewModel.setSubtitlesVisible(!viewModel.subtitlesVisible.value)
                            }
                            SubtitleSwipeAction.ReplayCurrent -> viewModel.replayCurrentSubtitle()
                            SubtitleSwipeAction.Previous -> viewModel.seekToAdjacentSubtitle(forward = false)
                            SubtitleSwipeAction.Next -> viewModel.seekToAdjacentSubtitle(forward = true)
                            null -> Unit
                        }
                    },
                    onDragCancel = {
                        totalDragX = 0f
                        totalDragY = 0f
                    },
                ) { change, dragAmount ->
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y
                    change.consume()
                }
            }
            .pointerInput(areControlsLocked, subtitleSwipeControls) {
                if (subtitleSwipeControls || !seekGesture || areControlsLocked) return@pointerInput
                var startingPosition = position.toInt()
                var startingX = 0f
                var wasPlayerAlreadyPause = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        startingPosition = position.toInt()
                        startingX = it.x
                        wasPlayerAlreadyPause = viewModel.paused.value
                        viewModel.pause()
                    },
                    onDragEnd = {
                        viewModel.gestureSeekAmount.update { null }
                        viewModel.hideSeekBar()
                        if (!wasPlayerAlreadyPause) viewModel.unpause()
                    },
                ) { change, dragAmount ->
                    if (position <= 0f && dragAmount < 0) return@detectHorizontalDragGestures
                    if (position >= duration && dragAmount > 0) return@detectHorizontalDragGestures
                    calculateNewHorizontalGestureValue(startingPosition, startingX, change.position.x, 0.15f).let {
                        viewModel.gestureSeekAmount.update { _ ->
                            Pair(
                                startingPosition,
                                (it - startingPosition)
                                    .coerceIn(0 - startingPosition, (duration - startingPosition).toInt()),
                            )
                        }
                        viewModel.seekTo(it.coerceIn(0, duration.toInt()), preciseSeeking)
                    }

                    if (showSeekbar) viewModel.showSeekBar()
                }
            }
            .pointerInput(areControlsLocked, subtitleSwipeControls) {
                if (subtitleSwipeControls || !gestureVolumeBrightness || areControlsLocked) return@pointerInput
                var startingY = 0f
                var mpvVolumeStartingY = 0f
                var originalVolume = currentVolume
                var originalMPVVolume = currentMPVVolume
                var originalBrightness = currentBrightness
                val brightnessGestureSens = 0.001f
                val volumeGestureSens = 0.03f
                val mpvVolumeGestureSens = 0.02f
                val isIncreasingVolumeBoost: (Float) -> Boolean = {
                    volumeBoostingCap > 0 &&
                        currentVolume == viewModel.maxVolume &&
                        currentMPVVolume - 100 < volumeBoostingCap &&
                        it < 0
                }
                val isDecreasingVolumeBoost: (Float) -> Boolean = {
                    volumeBoostingCap > 0 &&
                        currentVolume == viewModel.maxVolume &&
                        currentMPVVolume - 100 in 1..volumeBoostingCap &&
                        it > 0
                }
                detectVerticalDragGestures(
                    onDragEnd = { startingY = 0f },
                    onDragStart = {
                        startingY = 0f
                        mpvVolumeStartingY = 0f
                        originalVolume = currentVolume
                        originalMPVVolume = currentMPVVolume
                        originalBrightness = currentBrightness
                    },
                ) { change, amount ->
                    val changeVolume: () -> Unit = {
                        if (isIncreasingVolumeBoost(amount) || isDecreasingVolumeBoost(amount)) {
                            if (mpvVolumeStartingY == 0f) {
                                startingY = 0f
                                originalVolume = currentVolume
                                mpvVolumeStartingY = change.position.y
                            }
                            viewModel.changeMPVVolumeTo(
                                calculateNewVerticalGestureValue(
                                    originalMPVVolume,
                                    mpvVolumeStartingY,
                                    change.position.y,
                                    mpvVolumeGestureSens,
                                )
                                    .coerceIn(100..volumeBoostingCap + 100),
                            )
                        } else {
                            if (startingY == 0f) {
                                mpvVolumeStartingY = 0f
                                originalMPVVolume = currentMPVVolume
                                startingY = change.position.y
                            }
                            viewModel.changeVolumeTo(
                                calculateNewVerticalGestureValue(
                                    originalVolume,
                                    startingY,
                                    change.position.y,
                                    volumeGestureSens,
                                ),
                            )
                        }
                        viewModel.displayVolumeSlider()
                    }
                    val changeBrightness: () -> Unit = {
                        if (startingY == 0f) startingY = change.position.y
                        viewModel.changeBrightnessTo(
                            calculateNewVerticalGestureValue(
                                originalBrightness,
                                startingY,
                                change.position.y,
                                brightnessGestureSens,
                            ),
                        )
                        viewModel.displayBrightnessSlider()
                    }
                    if (swapVolumeBrightness) {
                        if (change.position.x > size.width / 2) changeBrightness() else changeVolume()
                    } else {
                        if (change.position.x < size.width / 2) changeBrightness() else changeVolume()
                    }
                }
            },
    )
}

@Composable
fun DoubleTapToSeekOvals(
    amount: Int,
    text: String?,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(if (amount == 0) 0f else 0.2f, label = "double_tap_animation_alpha")
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (amount > 0) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides playerRippleConfiguration,
        ) {
            if (amount != 0 || text != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.4f), // 2 fifths
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(if (amount > 0) RightSideOvalShape else LeftSideOvalShape)
                            .background(Color.White.copy(alpha))
                            .indication(interactionSource, ripple()),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DoubleTapSeekTriangles(isForward = amount > 0)
                        Text(
                            text = text ?: pluralStringResource(MR.plurals.seconds, amount, amount),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

fun calculateNewVerticalGestureValue(originalValue: Int, startingY: Float, newY: Float, sensitivity: Float): Int {
    return originalValue + ((startingY - newY) * sensitivity).toInt()
}

fun calculateNewVerticalGestureValue(originalValue: Float, startingY: Float, newY: Float, sensitivity: Float): Float {
    return originalValue + ((startingY - newY) * sensitivity)
}

fun calculateNewHorizontalGestureValue(originalValue: Int, startingX: Float, newX: Float, sensitivity: Float): Int {
    return originalValue + ((newX - startingX) * sensitivity).toInt()
}

fun calculateNewHorizontalGestureValue(originalValue: Float, startingX: Float, newX: Float, sensitivity: Float): Float {
    return originalValue + ((newX - startingX) * sensitivity)
}
