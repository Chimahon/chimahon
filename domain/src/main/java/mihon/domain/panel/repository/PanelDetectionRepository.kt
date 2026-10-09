package mihon.domain.panel.repository

import android.graphics.Bitmap
import mihon.domain.panel.model.PanelDetectionResult
import tachiyomi.core.common.util.system.ReadingDirection

interface PanelDetectionRepository {
    suspend fun detectPanels(
        cacheKey: String,
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
    ): PanelDetectionResult

    suspend fun invalidateModels()

    fun cleanup()
}