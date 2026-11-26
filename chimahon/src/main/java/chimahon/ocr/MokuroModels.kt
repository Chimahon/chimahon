package chimahon.ocr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MokuroVolume(
    val version: String,
    val title: String,
    @SerialName("title_uuid") val titleUuid: String,
    val volume: String,
    @SerialName("volume_uuid") val volumeUuid: String,
    val pages: List<MokuroPage>,
)

@Serializable
data class MokuroPage(
    val version: String = "0.2.0",
    @SerialName("img_path") val imgPath: String,
    @SerialName("img_width") val imgWidth: Int,
    @SerialName("img_height") val imgHeight: Int,
    val blocks: List<MokuroBlock>,
)

@Serializable
data class MokuroBlock(
    val box: List<Float>,
    val vertical: Boolean,
    @SerialName("font_size") val fontSize: Int = 16,
    val lines: List<String>,
    @SerialName("lines_coords") val linesCoords: List<List<List<Float>>> = emptyList(),
)
