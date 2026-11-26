package eu.kanade.presentation.more.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.ModeEdit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.anki.AnkiProfile
import eu.kanade.presentation.more.stats.data.StatsData
import eu.kanade.presentation.more.stats.data.StatsType
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import eu.kanade.tachiyomi.util.lang.toCountString

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
    onDateScaleSelect: (StatsDateScale) -> Unit,
    onDateOffsetChange: (Int) -> Unit,
    onStatsTypeSelect: (StatsType) -> Unit,
    onProfileSelect: (String?) -> Unit,
    allRead: Boolean = false,
    isSingleTitle: Boolean = false,
    titleName: String? = null,
    onLibraryCardClick: (() -> Unit)? = null,
) {
    LazyColumn(
        contentPadding = paddingValues,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
    ) {
        item {
            FiltersRow(
                statsType = state.statsType,
                onStatsTypeSelect = onStatsTypeSelect,
                dateScale = state.dateScale,
                onDateScaleSelect = onDateScaleSelect,
                activeProfileId = state.activeProfileId,
                profiles = state.profiles,
                onProfileSelect = onProfileSelect,
                isSingleTitle = isSingleTitle,
            )
        }

        item {
            DateNavigation(
                scale = state.dateScale,
                offset = state.dateOffset,
                onOffsetChange = onDateOffsetChange,
            )
        }

        item {
            HeroSection(
                state = state,
                onDateOffsetChange = onDateOffsetChange,
            )
        }

        item {
            StatsGrid(
                state = state,
                allRead = allRead,
                isSingleTitle = isSingleTitle,
                onLibraryCardClick = onLibraryCardClick,
            )
        }
    }
}

@Composable
private fun FiltersRow(
    statsType: StatsType,
    onStatsTypeSelect: (StatsType) -> Unit,
    dateScale: StatsDateScale,
    onDateScaleSelect: (StatsDateScale) -> Unit,
    activeProfileId: String?,
    profiles: List<AnkiProfile>,
    onProfileSelect: (String?) -> Unit,
    isSingleTitle: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isSingleTitle) {
            StatsType.values().forEach { type ->
                val isSelected = statsType == type
                FilterChip(
                    selected = isSelected,
                    onClick = { onStatsTypeSelect(type) },
                    label = { Text(type.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        selectedBorderColor = Color.Transparent,
                        borderWidth = 1.dp,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }

        var showMenu by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = false,
                onClick = { showMenu = true },
                label = { Text(if (dateScale == StatsDateScale.AllTime) "All Time" else dateScale.name) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    borderWidth = 1.dp,
                ),
                shape = RoundedCornerShape(8.dp),
            )
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                StatsDateScale.values().forEach { scale ->
                    DropdownMenuItem(
                        text = { Text(if (scale == StatsDateScale.AllTime) "All Time" else scale.name) },
                        onClick = {
                            onDateScaleSelect(scale)
                            showMenu = false
                        },
                    )
                }
            }
        }

        if (!isSingleTitle) {
            var showProfileMenu by remember { mutableStateOf(false) }
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.find { it.id == activeProfileId }
            }
            val isProfileSelected = activeProfileId != null
            val profileLabel = if (activeProfile != null) {
                stringResource(SYMR.strings.stats_profile_filter_label, activeProfile.name)
            } else {
                stringResource(SYMR.strings.stats_all_profiles)
            }

            Box {
                FilterChip(
                    selected = isProfileSelected,
                    onClick = { showProfileMenu = true },
                    label = { Text(profileLabel) },
                    trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isProfileSelected,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        selectedBorderColor = Color.Transparent,
                        borderWidth = 1.dp,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )
                DropdownMenu(
                    expanded = showProfileMenu,
                    onDismissRequest = { showProfileMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(SYMR.strings.stats_all_profiles)) },
                        onClick = {
                            onProfileSelect(null)
                            showProfileMenu = false
                        },
                    )
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = {
                                onProfileSelect(profile.id)
                                showProfileMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun DateNavigation(
    scale: StatsDateScale,
    offset: Int,
    onOffsetChange: (Int) -> Unit,
) {
    if (scale == StatsDateScale.AllTime) return

    val label = remember(scale, offset) {
        val now = LocalDate.now()
        val dateFormatter = DateTimeFormatter.ofPattern("MMM d")
        
        when (scale) {
            StatsDateScale.Day -> {
                when (offset) {
                    0 -> "Today"
                    -1 -> "Yesterday"
                    else -> now.plusDays(offset.toLong()).format(dateFormatter)
                }
            }
            StatsDateScale.Week -> {
                when (offset) {
                    0 -> "This week"
                    -1 -> "Last week"
                    else -> {
                        val start = now.plusWeeks(offset.toLong()).with(DayOfWeek.MONDAY)
                        val end = start.plusDays(6)
                        "${start.format(dateFormatter)} - ${end.format(dateFormatter)}"
                    }
                }
            }
            StatsDateScale.Month -> {
                when (offset) {
                    0 -> "This month"
                    -1 -> "Last month"
                    else -> now.plusMonths(offset.toLong()).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                }
            }
            StatsDateScale.Year -> {
                when (offset) {
                    0 -> "This year"
                    -1 -> "Last year"
                    else -> now.plusYears(offset.toLong()).year.toString()
                }
            }
            else -> ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onOffsetChange(offset - 1) }) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
            onClick = { onOffsetChange(offset + 1) },
            enabled = offset < 0,
        ) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = if (offset < 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        }
    }
}

@Composable
private fun HeroSection(
    state: StatsScreenState.Success,
    onDateOffsetChange: (Int) -> Unit,
) {
    val durationMs = state.overview.totalReadDuration
    val avgDurationPerDay = state.overview.avgDurationPerDay
    val historyPoints = state.overview.historyPoints
    val hours = durationMs / 3600000
    val minutes = (durationMs % 3600000) / 60000

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            if (hours > 0) {
                Text(
                    text = hours.toString(),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "h",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp, start = 2.dp, end = 4.dp),
                )
            }
            Text(
                text = minutes.toString(),
                fontSize = 56.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "m",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp, start = 2.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (avgDurationPerDay != null) {
                val avgH = avgDurationPerDay / 3600000
                val avgM = (avgDurationPerDay % 3600000) / 60000
                val avgStr = buildString {
                    if (avgH > 0) append("${avgH}h ")
                    append("${avgM}m per day")
                }
                Text(
                    text = avgStr,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 24.dp)
                .pointerInput(historyPoints) {
                    detectTapGestures { offset ->
                        val spacing = size.width / historyPoints.size
                        val index = (offset.x / spacing).toInt().coerceIn(0, historyPoints.size - 1)
                        val point = historyPoints[index]
                        onDateOffsetChange(point.dateOffset)
                    }
                },
        ) {
            val primaryColor = MaterialTheme.colorScheme.primary
            val inactiveColor = primaryColor.copy(alpha = 0.15f)
            val maxVal = historyPoints.maxOfOrNull { it.value } ?: 1L

            Canvas(modifier = Modifier.fillMaxSize()) {
                val spacing = size.width / historyPoints.size
                val barWidth = spacing * 0.5f
                
                historyPoints.forEachIndexed { index, point ->
                    val barHeight = (point.value.toFloat() / maxVal.toFloat().coerceAtLeast(1f)) * size.height
                    val x = index * spacing + spacing / 2 - barWidth / 2
                    val y = size.height - barHeight
                    
                    val isSelected = point.dateOffset == state.dateOffset
                    drawRoundRect(
                        color = if (isSelected) primaryColor else if (point.value > 0) primaryColor.copy(alpha = 0.3f) else inactiveColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight.coerceAtLeast(4.dp.toPx())),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            historyPoints.forEach { point ->
                val isSelected = point.dateOffset == state.dateOffset
                Text(
                    text = point.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatsGrid(
    state: StatsScreenState.Success,
    allRead: Boolean = false,
    isSingleTitle: Boolean = false,
    onLibraryCardClick: (() -> Unit)? = null,
) {
    val iconColor = MaterialTheme.colorScheme.primary
    
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Core Activity
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    MetricCard(MetricData(state.overview.readingStreak.toString(), "Streak", "day", Icons.Outlined.LocalFireDepartment, iconColor))
                }
                Box(modifier = Modifier.weight(1f)) {
                    MetricCard(MetricData(state.overview.ankiCardsAdded.toCountString(), "Added cards", null, Icons.Outlined.Style, iconColor))
                }
            }
            
            if (state.statsType != StatsType.Anime) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.overview.charactersRead.toCountString(), "Characters read", null, Icons.Outlined.TextFields, iconColor))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.overview.charactersPerHour?.toCountString() ?: "0", "Avg speed", "ch/h", Icons.Outlined.Speed, iconColor))
                    }
                }
            }
        }

        // Library Section
        if (!isSingleTitle) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(
                            data = MetricData(state.overview.libraryMangaCount.toCountString(), if (allRead) "All read" else "In library", null, Icons.Outlined.LibraryBooks, iconColor),
                            onClick = onLibraryCardClick,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.titles.localMangaCount.toCountString(), "Local", null, Icons.Outlined.SdCard, iconColor))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.titles.startedMangaCount.toCountString(), "Started", null, Icons.Outlined.PlayArrow, iconColor))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.overview.completedMangaCount.toCountString(), "Completed", null, Icons.Outlined.DoneAll, iconColor))
                    }
                }
            }
        }

        // Chapters / Episodes Section
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (state.statsType == StatsType.Anime) "Episodes" else "Chapters",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    MetricCard(MetricData(state.chapters.totalChapterCount.toCountString(), "Total", null, Icons.Outlined.MenuBook, iconColor))
                }
                Box(modifier = Modifier.weight(1f)) {
                    MetricCard(
                        MetricData(
                            state.chapters.readChapterCount.toCountString(),
                            if (state.statsType == StatsType.Anime) "Watched" else "Read",
                            null,
                            Icons.Outlined.History,
                            iconColor,
                        ),
                    )
                }
            }
            if (state.statsType != StatsType.Novels && state.statsType != StatsType.Anime && !isSingleTitle) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.chapters.downloadCount.toCountString(), "Downloaded", null, Icons.Outlined.Download, iconColor))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // Trackers Section
        if ((state.statsType == StatsType.All || state.statsType == StatsType.Manga) && !isSingleTitle) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Trackers",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.trackers.trackedTitleCount.toCountString(), "Tracked titles", null, Icons.Outlined.CollectionsBookmark, iconColor))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        val meanScore = if (state.trackers.meanScore > 0) java.lang.String.format(java.util.Locale.getDefault(), "%.1f", state.trackers.meanScore) else "0.0"
                        MetricCard(MetricData(meanScore, "Mean score", null, Icons.Outlined.Star, iconColor))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(MetricData(state.trackers.trackerCount.toCountString(), "Trackers", null, Icons.Outlined.Public, iconColor))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

data class MetricData(
    val value: String,
    val label: String,
    val unit: String? = null,
    val icon: ImageVector,
    val accentColor: Color,
)

@Composable
private fun MetricCard(data: MetricData, onClick: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(114.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = data.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = data.accentColor,
                )
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            Column {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = data.value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (data.unit != null) {
                        Text(
                            text = data.unit,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                Text(
                    text = data.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
