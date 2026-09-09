package com.canopus.chimareader.data

import kotlinx.serialization.Serializable

@Serializable
data class MangaStats(
    val dateKey: String,
    var charactersRead: Int = 0,
    var readingTime: Long = 0, // In ms
    var mangaId: Long = 0,
)
