package chimahon.novel.sync.ttu

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

object LenientLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element.jsonPrimitive
        return primitive.content.toLongOrNull()
            ?: primitive.content.toDoubleOrNull()?.toLong()
            ?: decoder.decodeLong()
    }
}

@Serializable
data class TtuProgress(
    val dataId: Int = 0,
    val exploredCharCount: Int = 0,
    val progress: Double = 0.0,
    @Serializable(with = LenientLongSerializer::class)
    val lastBookmarkModified: Long = 0L,
)

enum class SyncDirection {
    IMPORT, EXPORT, AUTO, SYNCED
}

enum class SyncMode(val rawValue: String) {
    Auto("Auto"),
    Manual("Manual");

    companion object {
        fun fromRawValue(rawValue: String?): SyncMode =
            entries.firstOrNull { it.rawValue == rawValue } ?: Auto
    }
}

enum class StatisticsSyncMode(val rawValue: String) {
    Merge("Merge"),
    Replace("Replace");

    companion object {
        fun fromRawValue(rawValue: String?): StatisticsSyncMode =
            entries.firstOrNull { it.rawValue == rawValue } ?: Merge
    }
}

data class SyncSettings(
    val enabled: Boolean = false,
    val mode: SyncMode = SyncMode.Auto,
    val autoSyncEnabled: Boolean = false,
    val statisticsSyncEnabled: Boolean = false,
    val statisticsSyncMode: StatisticsSyncMode = StatisticsSyncMode.Merge,
    val autoSyncOnOpen: Boolean = false,
    val autoSyncOnClose: Boolean = false,
    val autoSyncPeriodic: Boolean = false,
    val autoSyncIntervalMins: Int = 10,
)

sealed interface SyncResult {
    data object Skipped : SyncResult
    data class Synced(val title: String) : SyncResult
    data class Imported(val title: String, val characterCount: Int) : SyncResult
    data class Exported(val title: String, val characterCount: Int) : SyncResult
    data class Failed(val title: String, val error: String) : SyncResult
}

data class DriveFile(
    val id: String,
    val name: String,
)

data class DriveSyncFiles(
    val progress: DriveFile? = null,
    val statistics: DriveFile? = null,
)

sealed interface DriveAuthStatus {
    data object Connected : DriveAuthStatus
    data object NotConnected : DriveAuthStatus
    data object MissingConfiguration : DriveAuthStatus
    data class Failed(val message: String) : DriveAuthStatus
}

data class DeviceCodePrompt(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

sealed interface DriveAuthorizationPollResult {
    data class Authorized(val accessToken: String) : DriveAuthorizationPollResult
    data object Pending : DriveAuthorizationPollResult
    data object SlowDown : DriveAuthorizationPollResult
    data object TransientNetworkFailure : DriveAuthorizationPollResult
    data class Failed(val message: String) : DriveAuthorizationPollResult
}

sealed class DriveAuthException(message: String) : Exception(message) {
    data object AccessDenied : DriveAuthException("Access denied by user")
    data object ExpiredToken : DriveAuthException("Device code expired")
    data class Unknown(val detail: String) : DriveAuthException(detail)
}

data class CoverMetadata(
    val mimeType: String,
    val extension: String,
)
