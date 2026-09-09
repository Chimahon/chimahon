package com.canopus.chimareader.data

import android.content.Context
import java.io.File
import java.time.LocalDate

object AnkiStatsStorage {

    private const val FILE_NAME = "anki_stats.json"

    private fun getAnkiStatsFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    private inline fun <reified T> readList(file: File): List<T>? where T : Any {
        if (!file.exists()) return null
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.decodeFromString(kotlinx.serialization.serializer(), file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <reified T> writeList(file: File, value: List<T>) where T : Any {
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        file.writeText(json.encodeToString(kotlinx.serialization.serializer(), value))
    }

    fun loadAll(context: Context): List<AnkiStats> {
        return readList(getAnkiStatsFile(context)) ?: emptyList()
    }

    fun saveAll(context: Context, stats: List<AnkiStats>) {
        writeList(getAnkiStatsFile(context), stats)
        chimahon.widget.ImmersionWidgetSignals.notifyStatsChanged()
    }

    fun addCard(context: Context, type: String? = null, date: LocalDate = LocalDate.now(), profileId: String = "", titleId: String? = null) {
        val dateKey = date.toString()
        val allStats = loadAll(context).toMutableList()
        val existing = allStats.find { it.dateKey == dateKey && it.profileId == profileId && it.titleId == titleId }
        android.util.Log.d("AnkiStatsStorage", "addCard: type=$type, date=$dateKey, profile=$profileId, titleId=$titleId")
        if (existing != null) {
            if (type == "manga") {
                existing.mangaCards++
            } else {
                existing.novelCards++
            }
            android.util.Log.d("AnkiStatsStorage", "Updated existing: manga=${existing.mangaCards}, novel=${existing.novelCards}")
        } else {
            val stats = AnkiStats(dateKey, profileId = profileId, titleId = titleId)
            if (type == "manga") {
                stats.mangaCards = 1
            } else {
                stats.novelCards = 1
            }
            allStats.add(stats)
            android.util.Log.d("AnkiStatsStorage", "Created new: manga=${stats.mangaCards}, novel=${stats.novelCards}")
        }
        saveAll(context, allStats)
    }

    fun merge(context: Context, incoming: List<AnkiStats>) {
        if (incoming.isEmpty()) return
        val local = loadAll(context).toMutableList()
        var changed = false
        incoming.forEach { remote ->
            val existing = local.find { it.dateKey == remote.dateKey && it.profileId == remote.profileId && it.titleId == remote.titleId }
            if (existing != null) {
                if (remote.mangaCards > existing.mangaCards) {
                    existing.mangaCards = remote.mangaCards
                    changed = true
                }
                if (remote.novelCards > existing.novelCards) {
                    existing.novelCards = remote.novelCards
                    changed = true
                }
            } else {
                local.add(remote)
                changed = true
            }
        }
        if (changed) saveAll(context, local)
    }
}
