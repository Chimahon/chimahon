package eu.kanade.tachiyomi.ui.download

import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import eu.davidea.viewholders.FlexibleViewHolder
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme.Companion.setColors
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.animedownload.model.AnimeDownload
import eu.kanade.tachiyomi.databinding.DownloadAnimeItemBinding
import eu.kanade.tachiyomi.util.view.popupMenu
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.ank.AMR

class AnimeDownloadHolder(view: View, val adapter: DownloadAdapter) :
    FlexibleViewHolder(view, adapter) {

    private val binding = DownloadAnimeItemBinding.bind(view)

    init {
        setDragHandleView(binding.reorder)
        binding.menu.setOnClickListener { it.post { showPopupMenu(it) } }
    }

    fun bind(download: AnimeDownload) {
        binding.chapterTitle.text = download.episode.name
        binding.mangaFullTitle.text = download.anime.title
        binding.reorder.isVisible = true
        binding.menu.isVisible = true
        binding.downloadProgress.setColors(adapter.colorScheme)
        updateProgress(download)
    }

    fun updateProgress(download: AnimeDownload) {
        when (download.status) {
            AnimeDownload.State.DOWNLOADING -> {
                binding.downloadProgress.max = 100
                binding.downloadProgress.isIndeterminate = false
                binding.downloadProgress.setProgressCompat(download.progress.coerceIn(0, 100), true)
                binding.downloadProgressText.text = itemView.context
                    .stringResource(AMR.strings.episode_download_progress, download.progress)
                binding.downloadProgress.isVisible = true
                binding.downloadProgressText.isVisible = true
            }
            AnimeDownload.State.QUEUE -> {
                // Indeterminate instead of a flat 0/1 bar, so a queued row does not look stuck.
                binding.downloadProgress.max = 100
                binding.downloadProgress.isIndeterminate = true
                binding.downloadProgressText.text = itemView.context
                    .stringResource(AMR.strings.download_queued_indeterminate)
                binding.downloadProgress.isVisible = true
                binding.downloadProgressText.isVisible = true
            }
            AnimeDownload.State.DOWNLOADED -> {
                binding.downloadProgress.isIndeterminate = false
                binding.downloadProgress.max = 1
                binding.downloadProgress.progress = 0
                binding.downloadProgressText.text = ""
                binding.downloadProgress.isVisible = false
                binding.downloadProgressText.isVisible = false
            }
            AnimeDownload.State.ERROR -> {
                // Surfaced instead of an empty row that is indistinguishable from DOWNLOADED.
                binding.downloadProgress.isIndeterminate = false
                binding.downloadProgress.max = 1
                binding.downloadProgress.progress = 0
                binding.downloadProgressText.text = itemView.context
                    .stringResource(AMR.strings.download_retry)
                binding.downloadProgress.isVisible = false
                binding.downloadProgressText.isVisible = true
            }
            else -> {
                binding.downloadProgress.isIndeterminate = false
                binding.downloadProgress.isVisible = false
                binding.downloadProgressText.isVisible = false
            }
        }
    }

    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.downloadItemListener.onItemReleased(position)
        binding.container.isDragged = false
    }

    override fun onActionStateChanged(position: Int, actionState: Int) {
        super.onActionStateChanged(position, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            binding.container.isDragged = true
        }
    }

    private fun showPopupMenu(view: View) {
        view.popupMenu(
            menuRes = R.menu.download_single,
            initMenu = {
                findItem(R.id.move_to_top).isVisible = bindingAdapterPosition > 1
                findItem(R.id.move_to_bottom).isVisible =
                    bindingAdapterPosition != adapter.itemCount - 1
                findItem(R.id.show_manga).isVisible = false
                findItem(R.id.show_anime).isVisible = true
            },
            onMenuItemClick = {
                adapter.downloadItemListener.onMenuItemClick(bindingAdapterPosition, this)
            },
        )
    }
}
