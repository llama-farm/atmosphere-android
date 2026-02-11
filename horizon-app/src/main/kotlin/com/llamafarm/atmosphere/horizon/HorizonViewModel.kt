package com.llamafarm.atmosphere.horizon

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llamafarm.atmosphere.sdk.AtmosphereClient
import com.llamafarm.atmosphere.sdk.MeshStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "HorizonVM"

class HorizonViewModel : ViewModel() {

    private var repo: HorizonRepository? = null
    private var client: AtmosphereClient? = null
    private var refreshJobs = mutableListOf<Job>()

    // ── UI State ─────────────────────────────────────────────────────────────

    private val _selectedTab = MutableStateFlow(HorizonTab.ANOMALY)
    val selectedTab: StateFlow<HorizonTab> = _selectedTab.asStateFlow()

    private val _connectivity = MutableStateFlow(HorizonConnectivity.OFFLINE)
    val connectivity: StateFlow<HorizonConnectivity> = _connectivity.asStateFlow()

    // Mission
    private val _mission = MutableStateFlow(MissionSummary())
    val mission: StateFlow<MissionSummary> = _mission.asStateFlow()

    // Anomalies
    private val _anomalies = MutableStateFlow<List<Anomaly>>(emptyList())
    val anomalies: StateFlow<List<Anomaly>> = _anomalies.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Knowledge
    private val _knowledgeResult = MutableStateFlow<KnowledgeResult?>(null)
    val knowledgeResult: StateFlow<KnowledgeResult?> = _knowledgeResult.asStateFlow()
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()
    private val _queryLoading = MutableStateFlow(false)
    val queryLoading: StateFlow<Boolean> = _queryLoading.asStateFlow()

    // Voice
    private val _transcripts = MutableStateFlow<List<VoiceTranscript>>(emptyList())
    val transcripts: StateFlow<List<VoiceTranscript>> = _transcripts.asStateFlow()
    private val _voiceMonitoring = MutableStateFlow(false)
    val voiceMonitoring: StateFlow<Boolean> = _voiceMonitoring.asStateFlow()

    // Agent
    private val _hilItems = MutableStateFlow<List<HilItem>>(emptyList())
    val hilItems: StateFlow<List<HilItem>> = _hilItems.asStateFlow()
    private val _handledItems = MutableStateFlow<List<HandledItem>>(emptyList())
    val handledItems: StateFlow<List<HandledItem>> = _handledItems.asStateFlow()

    // OSINT
    private val _intelItems = MutableStateFlow<List<IntelItem>>(emptyList())
    val intelItems: StateFlow<List<IntelItem>> = _intelItems.asStateFlow()
    private val _latestBrief = MutableStateFlow<IntelBrief?>(null)
    val latestBrief: StateFlow<IntelBrief?> = _latestBrief.asStateFlow()
    private val _briefGenerating = MutableStateFlow(false)
    val briefGenerating: StateFlow<Boolean> = _briefGenerating.asStateFlow()

    // Stale data indicator
    private val _lastUpdate = MutableStateFlow(0L)
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()
    val isStale: StateFlow<Boolean> = _lastUpdate.map {
        it > 0 && System.currentTimeMillis() - it > 15_000
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── Init ─────────────────────────────────────────────────────────────────

    fun initialize(atmosphereClient: AtmosphereClient) {
        if (repo != null) return
        client = atmosphereClient
        repo = HorizonRepository(atmosphereClient)

        // Watch mesh connection state
        viewModelScope.launch {
            atmosphereClient.meshStatusFlow().collect { status ->
                _connectivity.value = when {
                    status.connected -> HorizonConnectivity.CONNECTED
                    status.relayConnected -> HorizonConnectivity.DEGRADED
                    else -> HorizonConnectivity.OFFLINE
                }
            }
        }

        startAutoRefresh()
    }

    fun selectTab(tab: HorizonTab) { _selectedTab.value = tab }

    // ── Auto-refresh ─────────────────────────────────────────────────────────

    private fun startAutoRefresh() {
        // Mission status every 3s
        refreshJobs += viewModelScope.launch {
            while (isActive) {
                refreshMission()
                delay(3_000)
            }
        }
        // Tab-specific refresh every 2-5s
        refreshJobs += viewModelScope.launch {
            while (isActive) {
                when (_selectedTab.value) {
                    HorizonTab.ANOMALY -> refreshAnomalies()
                    HorizonTab.VOICE -> refreshTranscripts()
                    HorizonTab.AGENT -> refreshAgent()
                    else -> {} // Knowledge & OSINT are on-demand
                }
                delay(2_000)
            }
        }
    }

    // ── Refresh helpers ──────────────────────────────────────────────────────

    private suspend fun refreshMission() {
        repo?.getMissionSummary()?.onSuccess {
            _mission.value = it
            _lastUpdate.value = System.currentTimeMillis()
        }?.onFailure {
            // Use cached if available
            repo?.cachedMission?.let { _mission.value = it }
        }
    }

    private suspend fun refreshAnomalies() {
        repo?.getActiveAnomalies()?.onSuccess { _anomalies.value = it }
    }

    private suspend fun refreshTranscripts() {
        repo?.getTranscripts(30)?.onSuccess { _transcripts.value = it }
    }

    private suspend fun refreshAgent() {
        repo?.getHilItems()?.onSuccess { _hilItems.value = it }
        repo?.getHandledItems()?.onSuccess { _handledItems.value = it }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    fun runScan() {
        viewModelScope.launch {
            _scanning.value = true
            repo?.runScan()
            delay(500)
            refreshAnomalies()
            _scanning.value = false
        }
    }

    fun acknowledgeAnomaly(id: String) {
        viewModelScope.launch {
            repo?.acknowledgeAnomaly(id)
            refreshAnomalies()
        }
    }

    fun resolveAnomaly(id: String) {
        viewModelScope.launch {
            repo?.resolveAnomaly(id)
            refreshAnomalies()
        }
    }

    fun queryKnowledge(query: String) {
        viewModelScope.launch {
            _queryLoading.value = true
            repo?.queryKnowledge(query)?.onSuccess { _knowledgeResult.value = it }
            _queryLoading.value = false
        }
    }

    fun loadSuggestions() {
        viewModelScope.launch {
            repo?.getSuggestions()?.onSuccess { _suggestions.value = it }
        }
    }

    fun toggleVoiceMonitoring() {
        viewModelScope.launch {
            if (_voiceMonitoring.value) {
                repo?.stopVoiceMonitoring()
                _voiceMonitoring.value = false
            } else {
                repo?.startVoiceMonitoring()
                _voiceMonitoring.value = true
            }
        }
    }

    fun approveHil(id: String) {
        viewModelScope.launch {
            repo?.approveAction(id)
            refreshAgent()
        }
    }

    fun rejectHil(id: String) {
        viewModelScope.launch {
            repo?.rejectAction(id)
            refreshAgent()
        }
    }

    fun searchIntel(query: String, category: String = "") {
        viewModelScope.launch {
            repo?.searchIntel(query, category)?.onSuccess { _intelItems.value = it }
        }
    }

    fun generateBrief() {
        viewModelScope.launch {
            _briefGenerating.value = true
            repo?.generateBrief()?.onSuccess { _latestBrief.value = it }
            _briefGenerating.value = false
        }
    }

    fun loadLatestBrief() {
        viewModelScope.launch {
            repo?.getLatestBrief()?.onSuccess { _latestBrief.value = it }
        }
    }

    override fun onCleared() {
        refreshJobs.forEach { it.cancel() }
        client?.disconnect()
        super.onCleared()
    }
}

enum class HorizonTab(val label: String) {
    ANOMALY("Anomaly"),
    KNOWLEDGE("Knowledge"),
    VOICE("Voice"),
    AGENT("Agent"),
    OSINT("OSINT")
}
