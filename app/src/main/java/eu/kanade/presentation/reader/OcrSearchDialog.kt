package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel

private const val MAX_OCR_SEARCH_RESULTS = 200
private const val SNIPPET_RADIUS_CHARS = 24

private data class OcrSearchMatch(
    val pageIndex: Int,
    val snippet: String,
    val matchStart: Int,
    val matchLength: Int,
)

@Composable
fun OcrSearchDialog(
    onDismissRequest: () -> Unit,
    totalPages: Int,
    loadPages: suspend () -> List<ReaderViewModel.OcrSearchPage>,
    onSelectPage: (Int) -> Unit,
) {
    var pages by remember { mutableStateOf<List<ReaderViewModel.OcrSearchPage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        pages = loadPages()
        isLoading = false
        runCatching { focusRequester.requestFocus() }
    }

    val trimmedQuery = query.trim()
    val matches = remember(trimmedQuery, pages) {
        if (trimmedQuery.isEmpty()) {
            emptyList()
        } else {
            buildList {
                for (page in pages) {
                    for (text in page.texts) {
                        var startIndex = 0
                        while (size < MAX_OCR_SEARCH_RESULTS) {
                            val idx = text.indexOf(trimmedQuery, startIndex = startIndex, ignoreCase = true)
                            if (idx < 0) break
                            val snippetStart = (idx - SNIPPET_RADIUS_CHARS).coerceAtLeast(0)
                            val snippetEnd = (idx + trimmedQuery.length + SNIPPET_RADIUS_CHARS).coerceAtMost(text.length)
                            val prefix = if (snippetStart > 0) "…" else ""
                            val suffix = if (snippetEnd < text.length) "…" else ""
                            val snippet = prefix + text.substring(snippetStart, snippetEnd) + suffix
                            add(
                                OcrSearchMatch(
                                    pageIndex = page.pageIndex,
                                    snippet = snippet,
                                    matchStart = prefix.length + (idx - snippetStart),
                                    matchLength = trimmedQuery.length,
                                ),
                            )
                            startIndex = idx + trimmedQuery.length.coerceAtLeast(1)
                        }
                        if (size >= MAX_OCR_SEARCH_RESULTS) break
                    }
                    if (size >= MAX_OCR_SEARCH_RESULTS) break
                }
            }
        }
    }

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 500.dp)
                .padding(top = 12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Search OCR text…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        }
                    }
                },
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                trimmedQuery.isEmpty() -> {
                    val status = if (pages.isEmpty()) {
                        "No OCR text loaded for this chapter yet"
                    } else {
                        "${pages.size}/$totalPages pages have OCR text"
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                matches.isEmpty() -> {
                    Text(
                        text = "No matches",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
                else -> {
                    val highlightColor = MaterialTheme.colorScheme.primary
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(matches) { match ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPage(match.pageIndex) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = "Page ${match.pageIndex + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = highlightColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = buildAnnotatedString {
                                        val end = (match.matchStart + match.matchLength)
                                            .coerceAtMost(match.snippet.length)
                                        val start = match.matchStart.coerceIn(0, end)
                                        append(match.snippet.substring(0, start))
                                        withStyle(
                                            SpanStyle(
                                                color = highlightColor,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                        ) {
                                            append(match.snippet.substring(start, end))
                                        }
                                        append(match.snippet.substring(end))
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}
