package chimahon.ocr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.roundToInt

data class MokuroExportPageInput(
    val imgPath: String,
    val imgWidth: Int,
    val imgHeight: Int,
    val blocks: List<OcrTextBlock>,
)

object MokuroExporter {
    private const val MOKURO_VERSION = "0.2.0"

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    fun buildMokuroVolume(
        title: String,
        volumeName: String,
        pages: List<MokuroExportPageInput>,
    ): MokuroVolume {
        val safeTitle = title.ifBlank { "Manga" }
        val safeVolume = volumeName.ifBlank { "Chapter" }
        val titleUuid = UUID.nameUUIDFromBytes(safeTitle.toByteArray(Charsets.UTF_8)).toString()
        val volumeUuid = UUID.nameUUIDFromBytes("$safeTitle/$safeVolume".toByteArray(Charsets.UTF_8)).toString()

        return MokuroVolume(
            version = MOKURO_VERSION,
            title = safeTitle,
            titleUuid = titleUuid,
            volume = safeVolume,
            volumeUuid = volumeUuid,
            pages = pages.map { page -> toMokuroPage(page) },
        )
    }

    fun encode(volume: MokuroVolume): String = json.encodeToString(volume)

    private fun toMokuroPage(input: MokuroExportPageInput): MokuroPage {
        val w = input.imgWidth.coerceAtLeast(1)
        val h = input.imgHeight.coerceAtLeast(1)
        val wf = w.toFloat()
        val hf = h.toFloat()

        val mokuroBlocks = input.blocks.mapNotNull { block ->
            val x1 = (block.xmin * wf).coerceIn(0f, wf)
            val y1 = (block.ymin * hf).coerceIn(0f, hf)
            val x2 = (block.xmax * wf).coerceIn(x1, wf)
            val y2 = (block.ymax * hf).coerceIn(y1, hf)
            if (x2 <= x1 || y2 <= y1) return@mapNotNull null

            val pairs = block.lines.mapIndexedNotNull { idx, line ->
                if (line.isBlank()) {
                    null
                } else {
                    line to block.lineGeometries?.getOrNull(idx)
                }
            }
            if (pairs.isEmpty()) return@mapNotNull null

            val linesCoords = buildLinesCoords(
                pairs = pairs,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                wf = wf,
                hf = hf,
                vertical = block.vertical,
            )

            val avgThickness = linesCoords.map { poly ->
                val lx1 = poly[0][0]
                val ly1 = poly[0][1]
                val lx2 = poly[2][0]
                val ly2 = poly[2][1]
                if (block.vertical) (lx2 - lx1) else (ly2 - ly1)
            }.average().toFloat()

            MokuroBlock(
                box = listOf(x1, y1, x2, y2),
                vertical = block.vertical,
                fontSize = avgThickness.roundToInt().coerceIn(8, 120),
                lines = pairs.map { it.first },
                linesCoords = linesCoords,
            )
        }

        return MokuroPage(
            version = MOKURO_VERSION,
            imgPath = input.imgPath.replace('\\', '/'),
            imgWidth = w,
            imgHeight = h,
            blocks = mokuroBlocks,
        )
    }

    private fun buildLinesCoords(
        pairs: List<Pair<String, OcrLineGeometry?>>,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        wf: Float,
        hf: Float,
        vertical: Boolean,
    ): List<List<List<Float>>> {
        val count = pairs.size
        return pairs.mapIndexed { idx, (_, geo) ->
            val lx1: Float
            val ly1: Float
            val lx2: Float
            val ly2: Float
            if (geo != null) {
                lx1 = (geo.xmin * wf).coerceIn(0f, wf)
                ly1 = (geo.ymin * hf).coerceIn(0f, hf)
                lx2 = (geo.xmax * wf).coerceIn(lx1, wf)
                ly2 = (geo.ymax * hf).coerceIn(ly1, hf)
            } else if (vertical) {
                val colW = (x2 - x1) / count
                lx2 = x2 - idx * colW
                lx1 = lx2 - colW
                ly1 = y1
                ly2 = y2
            } else {
                val rowH = (y2 - y1) / count
                lx1 = x1
                lx2 = x2
                ly1 = y1 + idx * rowH
                ly2 = ly1 + rowH
            }
            listOf(
                listOf(lx1, ly1),
                listOf(lx2, ly1),
                listOf(lx2, ly2),
                listOf(lx1, ly2),
            )
        }
    }
}
