package com.canopus.chimareader.data

import android.content.Context
import chimahon.widget.ImmersionWidgetSignals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

object AnimeStatsStorage {

    private const val FILE_NAME = "anime_stats.json"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private fun getAnimeStatsFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readStats(file: File): List<AnimeStats>? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<List<AnimeStats>>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun writeStats(file: File, value: List<AnimeStats>) {
        file.writeText(json.encodeToString(value))
    }

    @Synchronized
    fun loadAll(context: Context): List<AnimeStats> {
        return readStats(getAnimeStatsFile(context)) ?: emptyList()
    }

    @Synchronized
    fun saveAll(context: Context, stats: List<AnimeStats>) {
        writeStats(getAnimeStatsFile(context), stats)
        ImmersionWidgetSignals.notifyStatsChanged()
    }

    @Synchronized
    fun addStats(context: Context, timeMs: Long, animeId: Long = 0, date: LocalDate = LocalDate.now()) {
        if (timeMs <= 0) return

        val dateKey = date.toString()
        val allStats = loadAll(context).toMutableList()
        val existing = allStats.find { it.dateKey == dateKey && it.animeId == animeId }
        if (existing != null) {
            existing.watchTime += timeMs
        } else {
            allStats.add(AnimeStats(dateKey, timeMs, animeId))
        }
        saveAll(context, allStats)
    }

    @Synchronized
    fun merge(context: Context, incoming: List<AnimeStats>) {
        if (incoming.isEmpty()) return
        val local = loadAll(context).toMutableList()
        var changed = false
        incoming.forEach { remote ->
            val existing = local.find { it.dateKey == remote.dateKey && it.animeId == remote.animeId }
            if (existing != null) {
                if (remote.watchTime > existing.watchTime) {
                    existing.watchTime = remote.watchTime
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
