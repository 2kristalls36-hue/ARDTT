package com.ardtt.lab

import org.json.JSONArray
import org.json.JSONObject

data class LabRequest(
    val id: String,
    val cmd: String,
    val fields: JSONObject,
)

object LabProtocol {
    const val DEFAULT_REMOTE_PORT = 7422
    const val PORT_FILE = "/tmp/ardtt-lab.port"
    const val ARDTT_PACKAGE = "com.ardtt.app"

    fun parseLine(line: String): LabRequest? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = JSONObject(trimmed)
            val cmd = obj.optString("cmd").trim()
            if (cmd.isEmpty()) return null
            LabRequest(
                id = obj.optString("id").ifBlank { "0" },
                cmd = cmd,
                fields = obj,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun reply(id: String, ok: Boolean, extras: Map<String, Any?> = emptyMap()): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("ok", ok)
        extras.forEach { (key, value) -> putValue(obj, key, value) }
        return obj.toString()
    }

    fun event(type: String, extras: Map<String, Any?> = emptyMap()): String {
        val obj = JSONObject()
        obj.put("event", type)
        extras.forEach { (key, value) -> putValue(obj, key, value) }
        return obj.toString()
    }

    fun error(id: String, message: String): String =
        reply(id, false, mapOf("error" to message))

    private fun putValue(obj: JSONObject, key: String, value: Any?) {
        when (value) {
            null -> obj.put(key, JSONObject.NULL)
            is Map<*, *> -> {
                val nested = JSONObject()
                value.forEach { (k, v) ->
                    if (k != null) putValue(nested, k.toString(), v)
                }
                obj.put(key, nested)
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                value.forEach { item -> arr.put(item ?: JSONObject.NULL) }
                obj.put(key, arr)
            }
            else -> obj.put(key, value)
        }
    }
}
