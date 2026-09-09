package eu.kanade.presentation.browse.novel.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun BaseNovelSourceItem(
    source: NovelsPageSource,
    modifier: Modifier = Modifier,
    showLanguageInContent: Boolean = true,
    iconUrl: String? = null,
    isStub: Boolean = false,
    onClickItem: () -> Unit = {},
    onLongClickItem: () -> Unit = {},
    action: @Composable RowScope.() -> Unit = {},
) {
    val sourceLangString = LocaleHelper.getSourceDisplayName(source.lang, LocalContext.current)
        .takeIf { showLanguageInContent }
        ?.let { "${FlagEmoji.getEmojiLangFlag(source.lang)} $it" }
    BaseBrowseItem(
        modifier = modifier,
        onClickItem = onClickItem,
        onLongClickItem = onLongClickItem,
        icon = { NovelSourceIcon(iconUrl = iconUrl, isStub = isStub) },
        action = { action() },
        content = {
            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                Text(
                    text = source.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (sourceLangString != null) {
                    Text(
                        modifier = Modifier.secondaryItemAlpha(),
                        text = sourceLangString,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}
