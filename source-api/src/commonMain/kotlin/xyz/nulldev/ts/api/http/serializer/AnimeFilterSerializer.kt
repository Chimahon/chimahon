package xyz.nulldev.ts.api.http.serializer

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import logcat.asLog
import tachiyomi.core.common.util.system.logcat
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.isSubclassOf

class AnimeFilterSerializer {
    private val serializers = listOf<AnimeSerializer<*>>(
        AnimeHeaderSerializer(this),
        AnimeSeparatorSerializer(this),
        AnimeSelectSerializer(this),
        AnimeTextSerializer(this),
        AnimeCheckboxSerializer(this),
        AnimeTriStateSerializer(this),
        AnimeGroupSerializer(this),
        AnimeSortSerializer(this),
    )

    fun serialize(filters: AnimeFilterList) = buildJsonArray {
        filters.filterIsInstance<AnimeFilter<Any?>>().forEach {
            add(serialize(it))
        }
    }

    fun serialize(filter: AnimeFilter<Any?>): JsonObject {
        return serializers
            .filterIsInstance<AnimeSerializer<AnimeFilter<Any?>>>()
            .firstOrNull {
                filter::class.isSubclassOf(it.clazz)
            }?.let { serializer ->
                buildJsonObject {
                    with(serializer) { serialize(filter) }

                    val classMappings = mutableListOf<Pair<String, Any>>()

                    serializer.mappings().forEach {
                        val res = it.second.get(filter)
                        put(it.first, res.toString())
                        classMappings += it.first to (res?.javaClass?.name ?: "null")
                    }

                    putJsonObject(CLASS_MAPPINGS) {
                        classMappings.forEach { (t, u) ->
                            put(t, u.toString())
                        }
                    }

                    put(TYPE, serializer.type)
                }
            } ?: throw IllegalArgumentException("Cannot serialize this Filter object!")
    }

    fun deserialize(filters: AnimeFilterList, json: JsonArray) {
        filters.filterIsInstance<AnimeFilter<Any?>>().zip(json).forEach { (filter, obj) ->
            try {
                deserialize(filter, obj.jsonObject)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { e.asLog() }
            }
        }
    }

    fun deserialize(filter: AnimeFilter<Any?>, json: JsonObject) {
        val serializer = serializers
            .filterIsInstance<AnimeSerializer<AnimeFilter<Any?>>>()
            .firstOrNull {
                it.type == json[TYPE]!!.jsonPrimitive.content
            } ?: throw IllegalArgumentException("Cannot deserialize this type!")

        serializer.deserialize(json, filter)

        serializer.mappings().forEach {
            if (it.second is KMutableProperty1) {
                val obj = json[it.first]!!.jsonPrimitive
                val res: Any? = when (json[CLASS_MAPPINGS]!!.jsonObject[it.first]!!.jsonPrimitive.content) {
                    java.lang.Integer::class.java.name -> obj.int
                    java.lang.Long::class.java.name -> obj.long
                    java.lang.Float::class.java.name -> obj.float
                    java.lang.Double::class.java.name -> obj.double
                    java.lang.String::class.java.name -> obj.content
                    java.lang.Boolean::class.java.name -> obj.boolean
                    java.lang.Byte::class.java.name -> obj.content.toByte()
                    java.lang.Short::class.java.name -> obj.content.toShort()
                    java.lang.Character::class.java.name -> obj.content[0]
                    "null" -> null
                    else -> throw IllegalArgumentException("Cannot deserialize this type!")
                }
                @Suppress("UNCHECKED_CAST")
                (it.second as KMutableProperty1<in AnimeFilter<Any?>, in Any?>).set(filter, res)
            }
        }
    }

    companion object {
        const val TYPE = "_type"
        const val CLASS_MAPPINGS = "_cmaps"
    }
}
