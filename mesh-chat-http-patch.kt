// Simpler HTTP-based mesh chat routing for AtmosphereBinderService.kt
// This directly calls LlamaFarm's HTTP API instead of going through WebSocket

// Replace the chatCompletion TODO section with:

if (isConnectedToMesh && meshConnection != null) {
    Log.d(TAG, "Routing chat through mesh...")
    
    // Get semantic router
    val semanticRouter = SemanticRouter.getInstance(applicationContext)
    
    // Build routing intent from last user message
    val lastUserMessage = findLastUserMessage(messages) ?: "chat"
    
    // Route to find best capability
    val routingDecision = runBlocking {
        semanticRouter.route(lastUserMessage)
    }
    
    if (routingDecision != null && routingDecision.capability.nodeName == "Mac-Rob") {
        // Route to Mac's LlamaFarm via HTTP
        Log.d(TAG, "📍 Routing to Mac LlamaFarm: ${routingDecision.capability.label}")
        
        return runBlocking {
            try {
                // Call LlamaFarm HTTP API directly
                val url = URL("http://192.168.86.237:14345/v1/projects/discoverable/${routingDecision.capability.label}/chat/completions")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                
                // Build OpenAI-compatible request
                val requestBody = JSONObject().apply {
                    put("messages", messages)
                    model?.let { put("model", it) }
                }.toString()
                
                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray())
                }
                
                // Read response
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    // Add routing metadata
                    val responseJson = JSONObject(response)
                    responseJson.put("routed_via", "mesh-http")
                    responseJson.put("routed_to", "Mac-Rob")
                    responseJson.put("capability", routingDecision.capability.label)
                    
                    responseJson.toString()
                } else {
                    val error = try {
                        connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                    } catch (e: Exception) {
                        "HTTP $responseCode"
                    }
                    
                    Log.e(TAG, "LlamaFarm HTTP error: $error")
                    executeLocalInference(app, messages, model)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mesh HTTP request failed", e)
                // Fallback to local
                executeLocalInference(app, messages, model)
            }
        }
    } else {
        // No mesh route or local route
        Log.d(TAG, "Using local inference (no suitable mesh route)")
        return executeLocalInference(app, messages, model)
    }
} else {
    // Not connected to mesh
    Log.d(TAG, "Using local inference (not connected to mesh)")
    return executeLocalInference(app, messages, model)
}

// Helper to find last user message
private fun findLastUserMessage(messages: JSONArray): String? {
    for (i in messages.length() - 1 downTo 0) {
        val msg = messages.getJSONObject(i)
        if (msg.optString("role") == "user") {
            return msg.optString("content")
        }
    }
    return null
}

// Add this import at the top of the file:
// import java.net.URL
// import java.net.HttpURLConnection
