package com.llamafarm.atmosphere.apps

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents an endpoint exposed by a mesh app capability.
 */
data class AppEndpoint(
    val name: String,
    val method: String = "GET",
    val path: String = "",
    val description: String = ""
) {
    companion object {
        fun fromJson(name: String, json: JSONObject): AppEndpoint = AppEndpoint(
            name = name,
            method = json.optString("method", "GET"),
            path = json.optString("path", ""),
            description = json.optString("description", "")
        )
    }
}

/**
 * Parameter specification for a tool.
 */
data class ToolParam(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
    val default: String? = null,
    val enum: List<String>? = null
) {
    companion object {
        fun fromJson(json: JSONObject): ToolParam = ToolParam(
            name = json.optString("name", ""),
            type = json.optString("type", "string"),
            description = json.optString("description", ""),
            required = json.optBoolean("required", true),
            default = json.optString("default", null),
            enum = json.optJSONArray("enum")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        )
    }
}

/**
 * Tool/function spec — like OpenAI function calling but for mesh apps.
 */
data class AppTool(
    val name: String,
    val description: String = "",
    val parameters: List<ToolParam> = emptyList(),
    val returns: String = "",
    val endpoint: AppEndpoint,
    val tags: List<String> = emptyList()
) {
    companion object {
        fun fromJson(name: String, json: JSONObject): AppTool {
            val params = mutableListOf<ToolParam>()
            json.optJSONArray("parameters")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    params.add(ToolParam.fromJson(p))
                }
            }

            val epJson = json.optJSONObject("endpoint") ?: JSONObject()
            val endpoint = AppEndpoint.fromJson(name, epJson)

            val tags = mutableListOf<String>()
            json.optJSONArray("tags")?.let { arr ->
                for (i in 0 until arr.length()) tags.add(arr.getString(i))
            }

            return AppTool(
                name = name,
                description = json.optString("description", ""),
                parameters = params,
                returns = json.optString("returns", ""),
                endpoint = endpoint,
                tags = tags
            )
        }
    }
}

/**
 * A capability discovered from a mesh app (e.g., HORIZON).
 * Parsed from gossip capability_announce messages with app types.
 */
data class AppCapability(
    val id: String,                          // e.g., "app/horizon/anomaly"
    val type: String,                        // e.g., "app/query", "app/action", "app/stream", "app/chat"
    val nodeId: String,                      // Source node
    val nodeName: String = "",
    val appName: String = "",                // e.g., "horizon"
    val description: String = "",
    val keywords: List<String> = emptyList(),
    val endpoints: Map<String, AppEndpoint> = emptyMap(),
    val tools: Map<String, AppTool> = emptyMap(),
    val pushEvents: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 300_000
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt

    companion object {
        /**
         * Try to parse an AppCapability from a gossip capability JSON object.
         * Returns null if this isn't an app capability.
         */
        fun fromCapabilityJson(json: JSONObject, nodeId: String, nodeName: String): AppCapability? {
            val capType = json.optString("capability_type", "")
            if (!capType.startsWith("app/")) return null

            val capId = json.optString("capability_id", json.optString("id", ""))
            if (capId.isEmpty()) return null

            // Parse endpoints map
            val endpoints = mutableMapOf<String, AppEndpoint>()
            json.optJSONObject("endpoints")?.let { epJson ->
                epJson.keys().forEach { key ->
                    val epObj = epJson.optJSONObject(key)
                    if (epObj != null) {
                        endpoints[key] = AppEndpoint.fromJson(key, epObj)
                    }
                }
            }
            // Also handle endpoints as array of objects
            json.optJSONArray("endpoints")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val epObj = arr.optJSONObject(i) ?: continue
                    val name = epObj.optString("name", "endpoint_$i")
                    endpoints[name] = AppEndpoint(
                        name = name,
                        method = epObj.optString("method", "GET"),
                        path = epObj.optString("path", ""),
                        description = epObj.optString("description", "")
                    )
                }
            }

            // Parse tools map
            val tools = mutableMapOf<String, AppTool>()
            json.optJSONObject("tools")?.let { toolsJson ->
                toolsJson.keys().forEach { key ->
                    val toolObj = toolsJson.optJSONObject(key)
                    if (toolObj != null) {
                        tools[key] = AppTool.fromJson(key, toolObj)
                    }
                }
            }

            // Parse push events
            val pushEvents = mutableListOf<String>()
            json.optJSONArray("push_events")?.let { arr ->
                for (i in 0 until arr.length()) pushEvents.add(arr.getString(i))
            }

            // Parse keywords
            val keywords = mutableListOf<String>()
            json.optJSONArray("keywords")?.let { arr ->
                for (i in 0 until arr.length()) keywords.add(arr.getString(i))
            }

            // Extract app name from capability ID (e.g., "app/horizon/anomaly" -> "horizon")
            val parts = capId.split("/")
            val appName = if (parts.size >= 2) parts[1] else json.optString("app_name", capId)

            return AppCapability(
                id = capId,
                type = capType,
                nodeId = nodeId,
                nodeName = nodeName,
                appName = appName,
                description = json.optString("description", json.optString("label", "")),
                keywords = keywords,
                endpoints = endpoints,
                tools = tools,
                pushEvents = pushEvents,
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                expiresAt = json.optLong("expires_at", System.currentTimeMillis() + 300_000)
            )
        }
    }
}
