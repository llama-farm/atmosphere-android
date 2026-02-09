// Patch for AtmosphereBinderService.kt chatCompletion() method
// Replace the TODO section (lines ~134-144) with this implementation:

if (isConnectedToMesh && meshConnection != null) {
    Log.d(TAG, "Routing chat through mesh...")
    
    // Get the semantic router
    val semanticRouter = SemanticRouter.getInstance(applicationContext)
    
    // Build a routing intent from the last user message
    val lastUserMessage = findLastUserMessage(messages) ?: "chat"
    
    // Route to find best capability
    val routingDecision = runBlocking {
        semanticRouter.route(lastUserMessage)
    }
    
    if (routingDecision != null && routingDecision.capability.nodeName != "local") {
        Log.d(TAG, "📍 Routed to ${routingDecision.capability.nodeName}: ${routingDecision.capability.label}")
        
        // Forward chat to mesh node
        return runBlocking {
            executeMeshChat(
                meshConnection,
                routingDecision.capability.nodeId,
                messageList,
                model,
                timeout = 30_000L  // 30s timeout
            )
        }
    } else {
        // No mesh route found or routed to local
        Log.d(TAG, "No suitable mesh route, using local inference")
        return executeLocalInference(app, messages, model)
    }
} else {
    // Use local inference
    Log.d(TAG, "Using local inference (not connected to mesh)")
    return executeLocalInference(app, messages, model)
}

// Helper function to find last user message for routing
private fun findLastUserMessage(messages: JSONArray): String? {
    for (i in messages.length() - 1 downTo 0) {
        val msg = messages.getJSONObject(i)
        if (msg.optString("role") == "user") {
            return msg.optString("content")
        }
    }
    return null
}

// Suspend function to execute chat via mesh with timeout
private suspend fun executeMeshChat(
    connection: com.llamafarm.atmosphere.network.MeshConnection,
    targetNodeId: String,
    messages: List<Map<String, String>>,
    model: String?,
    timeout: Long
): String = withTimeout(timeout) {
    val requestId = "chat-${System.currentTimeMillis()}"
    
    // Build chat request payload
    val payload = JSONObject().apply {
        put("type", "chat_request")
        put("request_id", requestId)
        put("messages", JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg["role"])
                    put("content", msg["content"])
                })
            }
        })
        model?.let { put("model", it) }
    }
    
    // Send to mesh
    connection.sendInferenceRequest(
        targetNodeId = targetNodeId,
        capabilityId = "chat",
        requestId = requestId,
        payload = payload
    )
    
    // Wait for response (collect from connection.messages flow)
    val response = CompletableDeferred<String>()
    
    val job = GlobalScope.launch {
        connection.messages.collect { message ->
            when (message) {
                is com.llamafarm.atmosphere.network.MeshMessage.ChatResponse -> {
                    if (message.requestId == requestId) {
                        // Format as OpenAI-compatible response
                        val result = JSONObject().apply {
                            put("id", requestId)
                            put("object", "chat.completion")
                            put("model", model ?: "mesh")
                            put("routed_via", "mesh")
                            put("routed_to", targetNodeId.take(8))
                            put("choices", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("index", 0)
                                    put("message", JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", message.response)
                                    })
                                    put("finish_reason", "stop")
                                })
                            })
                            put("usage", JSONObject().apply {
                                put("total_tokens", 0) // Unknown from mesh
                            })
                        }.toString()
                        response.complete(result)
                    }
                }
                is com.llamafarm.atmosphere.network.MeshMessage.Error -> {
                    response.completeExceptionally(Exception("Mesh error: ${message.message}"))
                }
                else -> { /* Ignore other message types */ }
            }
        }
    }
    
    try {
        response.await()
    } catch (e: TimeoutCancellationException) {
        job.cancel()
        // Timeout - return error response
        JSONObject().apply {
            put("id", requestId)
            put("object", "chat.completion")
            put("error", "Mesh request timed out after ${timeout}ms")
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", "Request timed out. The mesh node may be offline or overloaded.")
                    })
                })
            })
        }.toString()
    } finally {
        job.cancel()
    }
}
