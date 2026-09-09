package com.canopus.chimareader.data

import kotlinx.serialization.Serializable

@Serializable
data class AnkiStats(
    val dateKey: String,
    var mangaCards: Int = 0,
    var novelCards: Int = 0,
    var profileId: String = "",
    var titleId: String? = null,
)
