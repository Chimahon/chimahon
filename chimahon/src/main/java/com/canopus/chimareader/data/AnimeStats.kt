package com.canopus.chimareader.data

import kotlinx.serialization.Serializable

@Serializable
data class AnimeStats(
    val dateKey: String,
    var watchTime: Long = 0, // In ms
    var animeId: Long = 0,
)
