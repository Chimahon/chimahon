package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.json.JsonObject

/**
 * Mirrors the `EMPTY` helper extensions-lib exposes for `JsonObject`, used as the default
 * value of the `memo` field added in extensions-lib 17.
 *
 * @since extensions-lib 17
 */
val JsonObject.Companion.EMPTY: JsonObject
    get() = JsonObject(emptyMap())
