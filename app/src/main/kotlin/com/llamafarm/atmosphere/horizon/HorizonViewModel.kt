package com.llamafarm.atmosphere.horizon

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * ViewModel for HORIZON app integration.
 * 
 * Communicates with HORIZON backend through Atmosphere mesh routing.
 * No direct HTTP calls - everything goes through the mesh.
 */
class HorizonViewModel : ViewModel() {
    
    private val _missionSummary = MutableStateFlow(MissionSummary())
    val missionSummary: StateFlow<MissionSummary> = _missionSummary.asStateFlow()
    
    private val _anomalies = MutableStateFlow<List<Anomaly>>(emptyList())
    val anomalies: StateFlow<List<Anomaly>> = _anomalies.asStateFlow()
    
    private val _agentActions = MutableStateFlow<List<AgentAction>>(emptyList())
    val agentActions: StateFlow<List<AgentAction>> = _agentActions.asStateFlow()
    
    private val _agentStatus = MutableStateFlow(AgentStatus())
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()
    
    private val _latestBrief = MutableStateFlow<IntelBrief?>(null)
    val latestBrief: StateFlow<IntelBrief?> = _latestBrief.asStateFlow()
    
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Mesh connection reference (set by MainActivity or MeshService)
    var meshConnection: MeshConnection? = null
    
    companion object {
        private const val TAG = "HorizonViewModel"
    }
    
    data class ChatMessage(
        val id: String = UUID.randomUUID().toString(),
        val role: String,  // "user" or "assistant"
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Load mission summary from HORIZON.
     */
    fun loadMissionSummary() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val response = sendAppRequest(
                    capabilityId = "app/horizon/mission",
                    endpoint = "summary"
                )
                
                // Parse response
                response?.let { body ->
                    _missionSummary.value = MissionSummary(
                        callsign = body["callsign"] as? String ?: "",
                        phase = body["phase"] as? String ?: "",
                        route = body["route"] as? String ?: "",
                        connectivity = body["connectivity"] as? String ?: "",
                        anomalyCount = (body["anomaly_count"] as? Number)?.toInt() ?: 0,
                        pendingActions = (body["pending_actions"] as? Number)?.toInt() ?: 0
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load mission summary", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Load active anomalies.
     */
    fun loadAnomalies() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val response = sendAppRequest(
                    capabilityId = "app/horizon/anomaly",
                    endpoint = "list_active"
                )
                
                response?.let { body ->
                    val bySeverity = body["by_severity"] as? Map<String, List<Map<String, Any>>>
                    val anomalies = mutableListOf<Anomaly>()
                    
                    bySeverity?.forEach { (severity, items) ->
                        items.forEach { item ->
                            anomalies.add(Anomaly(
                                id = item["id"] as? String ?: "",
                                title = item["title"] as? String ?: "",
                                description = item["description"] as? String ?: "",
                                severity = severity,
                                category = item["category"] as? String ?: "",
                                timestamp = item["timestamp"] as? String ?: "",
                                acknowledged = item["acknowledged"] as? Boolean ?: false,
                                resolved = item["resolved"] as? Boolean ?: false
                            ))
                        }
                    }
                    
                    _anomalies.value = anomalies
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load anomalies", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Acknowledge an anomaly.
     */
    fun acknowledgeAnomaly(anomalyId: String) {
        viewModelScope.launch {
            try {
                sendAppRequest(
                    capabilityId = "app/horizon/anomaly",
                    endpoint = "acknowledge",
                    params = mapOf("anomaly_id" to anomalyId)
                )
                loadAnomalies()  // Refresh
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acknowledge anomaly", e)
            }
        }
    }
    
    /**
     * Resolve an anomaly.
     */
    fun resolveAnomaly(anomalyId: String) {
        viewModelScope.launch {
            try {
                sendAppRequest(
                    capabilityId = "app/horizon/anomaly",
                    endpoint = "resolve",
                    params = mapOf("anomaly_id" to anomalyId)
                )
                loadAnomalies()  // Refresh
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve anomaly", e)
            }
        }
    }
    
    /**
     * Load agent actions needing approval.
     */
    fun loadAgentActions() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                
                // Load status
                val statusResp = sendAppRequest(
                    capabilityId = "app/horizon/agent",
                    endpoint = "status"
                )
                statusResp?.let { body ->
                    _agentStatus.value = AgentStatus(
                        monitoring = body["monitoring"] as? Boolean ?: false,
                        totalMessages = (body["total_messages"] as? Number)?.toInt() ?: 0,
                        needsInputCount = (body["needs_input_count"] as? Number)?.toInt() ?: 0,
                        hilCriticalCount = (body["hil_critical_count"] as? Number)?.toInt() ?: 0
                    )
                }
                
                // Load actions
                val actionsResp = sendAppRequest(
                    capabilityId = "app/horizon/agent",
                    endpoint = "needs_input"
                )
                actionsResp?.let { body ->
                    val items = body["items"] as? List<Map<String, Any>>
                    val actions = items?.mapNotNull { item ->
                        val action = item["action"] as? Map<String, Any>
                        val message = item["message"] as? Map<String, Any>
                        
                        if (action != null && message != null) {
                            AgentAction(
                                id = action["id"] as? String ?: "",
                                sender = message["sender"] as? String ?: "",
                                channel = message["channel"] as? String ?: "",
                                content = message["content"] as? String ?: "",
                                draftedResponse = action["drafted_response"] as? String ?: "",
                                reasoning = action["reasoning"] as? String ?: "",
                                status = action["status"] as? String ?: "",
                                hilPriority = action["hil_priority"] as? String ?: "medium",
                                timestamp = message["timestamp"] as? String ?: ""
                            )
                        } else null
                    } ?: emptyList()
                    
                    _agentActions.value = actions
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load agent actions", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Approve an agent action.
     */
    fun approveAction(actionId: String) {
        viewModelScope.launch {
            try {
                sendAppRequest(
                    capabilityId = "app/horizon/agent",
                    endpoint = "approve",
                    params = mapOf("action_id" to actionId)
                )
                loadAgentActions()  // Refresh
            } catch (e: Exception) {
                Log.e(TAG, "Failed to approve action", e)
            }
        }
    }
    
    /**
     * Reject an agent action.
     */
    fun rejectAction(actionId: String) {
        viewModelScope.launch {
            try {
                sendAppRequest(
                    capabilityId = "app/horizon/agent",
                    endpoint = "reject",
                    params = mapOf("action_id" to actionId)
                )
                loadAgentActions()  // Refresh
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject action", e)
            }
        }
    }
    
    /**
     * Query HORIZON knowledge brain.
     */
    fun sendIntelQuery(question: String) {
        viewModelScope.launch {
            try {
                // Add user message
                _chatMessages.value += ChatMessage(role = "user", content = question)
                _isLoading.value = true
                
                val response = sendAppRequest(
                    capabilityId = "app/horizon/knowledge",
                    endpoint = "query",
                    params = mapOf(
                        "question" to question,
                        "include_context" to "true"
                    )
                )
                
                response?.let { body ->
                    val answer = body["answer"] as? String ?: "No response"
                    _chatMessages.value += ChatMessage(role = "assistant", content = answer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send intel query", e)
                _chatMessages.value += ChatMessage(
                    role = "assistant",
                    content = "Error: ${e.message}"
                )
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Load latest intelligence brief.
     */
    fun loadLatestBrief() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val response = sendAppRequest(
                    capabilityId = "app/horizon/osint",
                    endpoint = "brief_latest"
                )
                
                response?.let { body ->
                    val brief = body["brief"] as? Map<String, Any>
                    brief?.let {
                        _latestBrief.value = IntelBrief(
                            mission = it["mission"] as? String ?: "",
                            route = it["route"] as? String ?: "",
                            generatedAt = it["generated_at"] as? String ?: "",
                            triggeredBy = it["triggered_by"] as? String ?: "",
                            summary = it["summary"] as? String ?: "",
                            threats = (it["threats"] as? List<String>) ?: emptyList(),
                            weather = (it["weather"] as? List<String>) ?: emptyList(),
                            recommendations = (it["recommendations"] as? List<String>) ?: emptyList()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load brief", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Send an app request through the mesh.
     * 
     * Returns the response body or null on error.
     */
    private suspend fun sendAppRequest(
        capabilityId: String,
        endpoint: String,
        params: Map<String, String> = emptyMap()
    ): Map<String, Any>? {
        val connection = meshConnection ?: run {
            Log.w(TAG, "No mesh connection available")
            return null
        }
        
        val requestId = UUID.randomUUID().toString()
        
        val request = buildJsonObject {
            put("type", "app_request")
            put("request_id", requestId)
            put("capability_id", capabilityId)
            put("endpoint", endpoint)
            put("params", buildJsonObject {
                params.forEach { (k, v) -> put(k, v) }
            })
        }
        
        // TODO: Implement actual mesh send/receive
        // For now, this is a placeholder
        Log.d(TAG, "Sending app request: $request")
        
        // In real implementation:
        // connection.sendMessage(request)
        // val response = connection.awaitResponse(requestId, timeout = 30000)
        // return response.body
        
        return null  // Placeholder
    }
    
    /**
     * Handle incoming push events.
     */
    fun onPushEvent(event: String, data: Map<String, Any>) {
        when {
            event.startsWith("anomaly.") -> {
                // Refresh anomalies
                loadAnomalies()
            }
            event.startsWith("action.needs_approval") -> {
                // Refresh agent actions
                loadAgentActions()
            }
            event.startsWith("osint.") -> {
                // Refresh brief
                loadLatestBrief()
            }
        }
    }
    
    /**
     * Placeholder for mesh connection interface.
     */
    interface MeshConnection {
        suspend fun sendMessage(message: JsonObject)
        suspend fun awaitResponse(requestId: String, timeout: Long): AppResponse
    }
}
