package tachiyomi.domain.novel.model

import java.io.Serializable

data class NovelInstalledPlugin(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val codeUrl: String,
    val iconUrl: String,
    val sha256: String,
    val repositoryUrl: String,
    val installedAt: Long,
) : Serializable
