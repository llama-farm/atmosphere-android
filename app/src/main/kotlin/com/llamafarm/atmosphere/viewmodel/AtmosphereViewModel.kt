package com.llamafarm.atmosphere.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.service.AtmosphereService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Atmosphere app.
 * Manages UI state and coordinates with the background service.
 */
class AtmosphereViewModel(application: Application) : AndroidViewModel(application) {

    // Node state
    data class NodeState(
        val isRunning: Boolean = false,
        val nodeId: String? = null,
        val status: String = "Offline",
        val connectedPeers: Int = 0,
        val capabilities: List<String> = emptyList()
    )

    private val _nodeState = MutableStateFlow(NodeState())
    val nodeState: StateFlow<NodeState> = _nodeState.asStateFlow()

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Initialize any necessary state
        loadSavedState()
    }

    private fun loadSavedState() {
        viewModelScope.launch {
            // TODO: Load persisted state from DataStore
        }
    }

    /**
     * Start the Atmosphere service.
     */
    fun startService() {
        val context = getApplication<Application>()
        AtmosphereService.start(context)
        _nodeState.value = _nodeState.value.copy(
            status = "Connecting"
        )
    }

    /**
     * Stop the Atmosphere service.
     */
    fun stopService() {
        val context = getApplication<Application>()
        AtmosphereService.stop(context)
        _nodeState.value = _nodeState.value.copy(
            isRunning = false,
            status = "Offline",
            connectedPeers = 0
        )
    }

    /**
     * Update node state from service.
     */
    fun updateFromService(stats: AtmosphereService.ServiceStats) {
        _nodeState.value = _nodeState.value.copy(
            isRunning = stats.state == AtmosphereService.ServiceState.RUNNING,
            nodeId = stats.nodeId,
            status = when (stats.state) {
                AtmosphereService.ServiceState.RUNNING -> "Online"
                AtmosphereService.ServiceState.STARTING -> "Connecting"
                AtmosphereService.ServiceState.STOPPING -> "Disconnecting"
                AtmosphereService.ServiceState.STOPPED -> "Offline"
            },
            connectedPeers = stats.connectedPeers
        )
    }

    /**
     * Clear any error state.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Set node name.
     */
    fun setNodeName(name: String) {
        viewModelScope.launch {
            // TODO: Persist to DataStore and update native config
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up any resources
    }
}
