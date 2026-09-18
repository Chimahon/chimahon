package chimahon.novel.sync.ttu

import chimahon.novel.data.Statistics
import kotlin.math.ceil

/**
 * TTU Drive sync wire rules. Filenames carry the conflict-resolution clock,
 * so naming must stay byte-compatible with the iOS reader.
 */
object TtuSyncRules {

    private const val APPLE_EPOCH_OFFSET = 978307200000L

    fun appleReferenceSecondsToUnixMillis(appleSec: Double): Long {
        return (appleSec * 1000).toLong() + APPLE_EPOCH_OFFSET
    }

    fun unixMillisToAppleReferenceSeconds(unixMs: Long): Double {
        return ((unixMs - APPLE_EPOCH_OFFSET).toDouble() / 1000.0)
    }

    fun sanitizeTtuFilename(name: String): String {
        var result = name
        if (result.endsWith(" ")) {
            result = result.dropLast(1) + "~ttu-spc~"
        }
        if (result.endsWith(".")) {
            result = result.dropLast(1) + "~ttu-dend~"
        }
        result = result.replace("*", "~ttu-star~")
        return buildString(result.length) {
            result.forEach { character ->
                when (character) {
                    '/', '?', '<', '>', '\\', ':', '*', '|', '%', '"' -> {
                        append("%")
                        append(character.code.toString(16).uppercase().padStart(2, '0'))
                    }
                    else -> append(character)
                }
            }
        }
    }

    fun parseProgressTimestampMillis(file: DriveFile?): Long? {
        val name = file?.name ?: return null
        if (!name.startsWith("progress_")) return null
        val parts = name.split("_")
        if (parts.size <= 4) return null
        return parts[3].toLongOrNull()
    }

    fun parseStatisticsTimestampMillis(file: DriveFile?): Long? {
        val name = file?.name ?: return null
        if (!name.startsWith("statistics_")) return null
        val parts = name.split("_")
        if (parts.size <= 3) return null
        return parts[3].toLongOrNull()
    }

    fun determineDirection(localLastModified: Long?, remoteProgressFile: DriveFile?): SyncDirection {
        val localMillis = localLastModified
        val remoteMillis = parseProgressTimestampMillis(remoteProgressFile)
        return when {
            localMillis == null && remoteMillis == null -> SyncDirection.SYNCED
            localMillis == null -> SyncDirection.IMPORT
            remoteMillis == null -> SyncDirection.EXPORT
            localMillis > remoteMillis -> SyncDirection.EXPORT
            remoteMillis > localMillis -> SyncDirection.IMPORT
            else -> SyncDirection.SYNCED
        }
    }

    fun progressFileName(progress: TtuProgress): String {
        return "progress_1_6_${progress.lastBookmarkModified}_${progress.progress}.json"
    }

    fun statisticsFileName(stats: List<Statistics>): String {
        var readingTime = 0.0
        var charactersRead = 0
        var minReadingSpeed = 0
        var altMinReadingSpeed = 0
        var maxReadingSpeed = 0
        var weightedSum = 0L
        var validReadingDays = 0
        var lastStatisticModified = 0L

        for (stat in stats) {
            readingTime += stat.readingTime
            charactersRead += stat.charactersRead
            minReadingSpeed = if (minReadingSpeed > 0) minOf(minReadingSpeed, stat.minReadingSpeed) else stat.minReadingSpeed
            altMinReadingSpeed = if (altMinReadingSpeed > 0) minOf(altMinReadingSpeed, stat.altMinReadingSpeed) else stat.altMinReadingSpeed
            maxReadingSpeed = maxOf(maxReadingSpeed, stat.lastReadingSpeed)
            weightedSum += (stat.readingTime.toInt().toLong() * stat.charactersRead.toLong())
            lastStatisticModified = maxOf(lastStatisticModified, stat.lastStatisticModified)
            if (stat.readingTime > 0) validReadingDays++
        }

        val averageReadingTime = if (validReadingDays > 0) ceil(readingTime / validReadingDays) else 0.0
        val averageWeightedReadingTime = if (charactersRead > 0) ceil(weightedSum.toDouble() / charactersRead) else 0.0
        val averageCharactersRead = if (validReadingDays > 0) ceil(charactersRead.toDouble() / validReadingDays) else 0.0
        val averageWeightedCharactersRead = if (readingTime > 0) ceil(weightedSum.toDouble() / readingTime) else 0.0
        val lastReadingSpeed = if (readingTime > 0) ceil((3600.0 * charactersRead) / readingTime) else 0.0
        val averageReadingSpeed = if (averageReadingTime > 0) ceil((3600.0 * averageCharactersRead) / averageReadingTime) else 0.0
        val averageWeightedReadingSpeed = if (averageWeightedReadingTime > 0) ceil((3600.0 * averageWeightedCharactersRead) / averageWeightedReadingTime) else 0.0

        return "statistics_1_6_${lastStatisticModified}_${charactersRead}_${readingTime}_" +
            "${minReadingSpeed}_${altMinReadingSpeed}_${lastReadingSpeed}_${maxReadingSpeed}_" +
            "${averageReadingTime}_${averageWeightedReadingTime}_${averageCharactersRead}_" +
            "${averageWeightedCharactersRead}_${averageReadingSpeed}_${averageWeightedReadingSpeed}_na.json"
    }

    fun coverFileName(title: String): String {
        return "cover_1_6.jpeg"
    }

    fun coverMetadata(coverData: ByteArray): CoverMetadata {
        val magic = coverData.take(4)
        return when {
            magic.size >= 4 && magic[0] == 0x89.toByte() && magic[1] == 0x50.toByte() &&
                magic[2] == 0x4E.toByte() && magic[3] == 0x47.toByte() -> CoverMetadata("image/png", "png")
            magic.size >= 4 && magic[0] == 0x47.toByte() && magic[1] == 0x49.toByte() &&
                magic[2] == 0x46.toByte() && magic[3] == 0x38.toByte() -> CoverMetadata("image/gif", "gif")
            magic.size >= 2 && magic[0] == 0x42.toByte() && magic[1] == 0x4D.toByte() ->
                CoverMetadata("image/bmp", "bmp")
            magic.size >= 4 && magic[0] == 0x52.toByte() && magic[1] == 0x49.toByte() &&
                magic[2] == 0x46.toByte() && magic[3] == 0x46.toByte() -> CoverMetadata("image/webp", "webp")
            else -> CoverMetadata("image/jpeg", "jpeg")
        }
    }

    fun parseProgressTimestamp(filename: String): Long? {
        val parts = filename.removeSuffix(".json").split("_")
        if (parts.size >= 4 && parts[0] == "progress" && parts[1] == "1" && parts[2] == "6") {
            return parts[3].toLongOrNull()
        }
        return null
    }

    fun parseProgressValue(filename: String): Double? {
        val parts = filename.removeSuffix(".json").split("_")
        if (parts.size >= 5 && parts[0] == "progress" && parts[1] == "1" && parts[2] == "6") {
            return parts[4].toDoubleOrNull()
        }
        return null
    }

    fun isProgressFile(name: String): Boolean = name.startsWith("progress_1_6_") && name.endsWith(".json")
    fun isStatisticsFile(name: String): Boolean = name.startsWith("statistics_1_6_") && name.endsWith(".json")
    fun isCoverFile(name: String): Boolean = name.startsWith("cover_1_6.")
}
