package dev.yatori.mobile.api.internal

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dev.yatori.mobile.api.CoreException

internal object EnvelopeParser {
    fun <T> parse(raw: String, extract: (JsonObject) -> T): T {
        val j = parse(raw)
        if (!j.get("ok").asBoolean) throw CoreException(j.optString("error"))
        val data = j.getAsJsonObject("data")
        return extract(data)
    }

    fun parseVoid(raw: String) {
        val j = parse(raw)
        if (!j.get("ok").asBoolean) throw CoreException(j.optString("error"))
    }

    private fun parse(raw: String): JsonObject {
        return try {
            val el = JsonParser.parseString(raw)
            if (!el.isJsonObject) throw CoreException("envelope is not a JSON object: $raw")
            val obj = el.asJsonObject
            if (!obj.has("ok")) throw CoreException("envelope missing 'ok' field: $raw")
            obj
        } catch (e: JsonSyntaxException) {
            throw CoreException("invalid JSON from core: ${e.message}")
        }
    }

    private fun JsonObject.optString(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else "unknown error"
}
