package io.opengraph.syncfield.writers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON encoder shared by every writer in syncfield. `prettyPrint` and
 * sorted keys mirror the Swift SDK's `[.prettyPrinted, .sortedKeys]`
 * formatting so the diff between iOS and Android episode directories
 * stays empty.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val SyncFieldJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
}

/** Compact (single-line, sorted-keys) encoder used for JSONL rows. */
internal val SyncFieldJsonCompact: Json = Json {
    prettyPrint = false
    encodeDefaults = true
    explicitNulls = false
}

/** Recursively encode a `Map<String, Any?>` into a [JsonElement] tree. */
internal fun anyToJson(value: Any?): JsonElement = when (value) {
    null            -> kotlinx.serialization.json.JsonNull
    is JsonElement  -> value
    is Boolean      -> kotlinx.serialization.json.JsonPrimitive(value)
    is Number       -> kotlinx.serialization.json.JsonPrimitive(value)
    is String       -> kotlinx.serialization.json.JsonPrimitive(value)
    is Map<*, *>    -> kotlinx.serialization.json.JsonObject(
        value.entries
            .map { it.key.toString() to anyToJson(it.value) }
            .sortedBy { it.first }
            .toMap()
    )
    is List<*>      -> kotlinx.serialization.json.JsonArray(value.map(::anyToJson))
    is Iterable<*>  -> kotlinx.serialization.json.JsonArray(value.map(::anyToJson))
    else            -> kotlinx.serialization.json.JsonPrimitive(value.toString())
}
