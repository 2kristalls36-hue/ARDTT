package com.ardtt.app.deploy

import org.json.JSONArray
import org.json.JSONObject

/** Shared JSON encode/decode for [DeployTarget] (local store + export links). */
object DeployTargetJson {
    fun encode(target: DeployTarget): JSONObject =
        JSONObject()
            .put("id", target.id)
            .put("name", target.name)
            .put("host", target.host)
            .put("sshPort", target.sshPort)
            .put("sshUser", target.sshUser)
            .put("password", target.password)
            .put("privateKeyPem", target.privateKeyPem)
            .put("keyPassphrase", target.keyPassphrase)
            .put("sudoPassword", target.sudoPassword)
            .put("publicHost", target.publicHost.ifBlank { target.host })
            .put("autoPorts", target.autoPorts)
            .put("directPort", target.directPort)
            .put("bypassPort", target.bypassPort)
            .put("cascadeEnabled", target.cascadeEnabled)
            .put("cascadeHost", target.cascadeHost)
            .put("cascadePort", target.cascadePort)
            .put("cascadeUser", target.cascadeUser)
            .put("cascadePassword", target.cascadePassword)
            .put("cascadePrivateKeyPem", target.cascadePrivateKeyPem)
            .put("cascadeKeyPassphrase", target.cascadeKeyPassphrase)
            .put("osId", target.osId)
            .put("osVersion", target.osVersion)
            .put("lastDeployedAtMs", target.lastDeployedAtMs)

    fun parse(o: JSONObject): DeployTarget =
        DeployTarget(
            id = o.getString("id"),
            name = o.optString("name", o.optString("host")),
            host = o.getString("host"),
            sshPort = o.optInt("sshPort", 22),
            sshUser = o.optString("sshUser", "root"),
            password = o.optString("password", ""),
            privateKeyPem = o.optString("privateKeyPem", ""),
            keyPassphrase = o.optString("keyPassphrase", ""),
            sudoPassword = o.optString("sudoPassword", ""),
            publicHost = o.optString("publicHost", ""),
            autoPorts = o.optBoolean("autoPorts", true),
            directPort = o.optInt("directPort", 51820),
            bypassPort = o.optInt("bypassPort", 56003),
            cascadeEnabled = o.optBoolean("cascadeEnabled", false),
            cascadeHost = o.optString("cascadeHost", ""),
            cascadePort = o.optInt("cascadePort", 22),
            cascadeUser = o.optString("cascadeUser", "root").ifBlank { "root" },
            cascadePassword = o.optString("cascadePassword", ""),
            cascadePrivateKeyPem = o.optString("cascadePrivateKeyPem", ""),
            cascadeKeyPassphrase = o.optString("cascadeKeyPassphrase", ""),
            osId = o.optString("osId", ""),
            osVersion = o.optString("osVersion", ""),
            lastDeployedAtMs = o.optLong("lastDeployedAtMs", 0L),
        )

    fun encodeList(targets: List<DeployTarget>): String {
        val arr = JSONArray()
        targets.forEach { arr.put(encode(it)) }
        return arr.toString()
    }

    fun parseList(json: String): List<DeployTarget> {
        val trimmed = json.trim()
        require(trimmed.isNotEmpty()) { "Пустой JSON серверов" }
        return when {
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        add(parse(arr.getJSONObject(i)))
                    }
                }
            }
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                val arr = when {
                    root.has("servers") -> root.getJSONArray("servers")
                    root.has("id") && root.has("host") -> JSONArray().put(root)
                    else -> error("Ожидался массив servers или объект сервера")
                }
                buildList {
                    for (i in 0 until arr.length()) {
                        add(parse(arr.getJSONObject(i)))
                    }
                }
            }
            else -> error("Неверный JSON серверов")
        }
    }
}
