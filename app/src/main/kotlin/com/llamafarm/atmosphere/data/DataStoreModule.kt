package com.llamafarm.atmosphere.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Extension property for Context to access DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "atmosphere_settings")

/**
 * Preference keys for Atmosphere settings.
 */
object PreferenceKeys {
    // Capabilities
    val CAMERA_ENABLED = booleanPreferencesKey("camera_enabled")
    val MIC_ENABLED = booleanPreferencesKey("mic_enabled")
    val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
    val STORAGE_ENABLED = booleanPreferencesKey("storage_enabled")
    val COMPUTE_ENABLED = booleanPreferencesKey("compute_enabled")
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    
    // Node settings
    val NODE_NAME = stringPreferencesKey("node_name")
    val NODE_ID = stringPreferencesKey("node_id")
    
    // Behavior settings
    val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
    val BATTERY_OPTIMIZATION_DISMISSED = booleanPreferencesKey("battery_optimization_dismissed")
    
    // Mesh connection
    val LAST_MESH_ENDPOINT = stringPreferencesKey("last_mesh_endpoint")
    val LAST_MESH_TOKEN = stringPreferencesKey("last_mesh_token")
    val LAST_MESH_NAME = stringPreferencesKey("last_mesh_name")
    val AUTO_RECONNECT_MESH = booleanPreferencesKey("auto_reconnect_mesh")
    
    // Transport settings
    val TRANSPORT_LAN_ENABLED = booleanPreferencesKey("transport_lan_enabled")
    val TRANSPORT_WIFI_DIRECT_ENABLED = booleanPreferencesKey("transport_wifi_direct_enabled")
    val TRANSPORT_BLE_MESH_ENABLED = booleanPreferencesKey("transport_ble_mesh_enabled")
    val TRANSPORT_MATTER_ENABLED = booleanPreferencesKey("transport_matter_enabled")
    val TRANSPORT_RELAY_ENABLED = booleanPreferencesKey("transport_relay_enabled")
    val TRANSPORT_PREFER_LOCAL_ONLY = booleanPreferencesKey("transport_prefer_local_only")
}

/**
 * Repository for managing Atmosphere preferences.
 */
class AtmospherePreferences(private val context: Context) {
    
    private val dataStore = context.dataStore
    
    // ========================================================================
    // Capabilities
    // ========================================================================
    
    val cameraEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.CAMERA_ENABLED] ?: false
    }
    
    val micEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.MIC_ENABLED] ?: false
    }
    
    val locationEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.LOCATION_ENABLED] ?: false
    }
    
    val storageEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.STORAGE_ENABLED] ?: false
    }
    
    val computeEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.COMPUTE_ENABLED] ?: false
    }
    
    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.NOTIFICATIONS_ENABLED] ?: true
    }
    
    suspend fun setCameraEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.CAMERA_ENABLED] = enabled
        }
    }
    
    suspend fun setMicEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.MIC_ENABLED] = enabled
        }
    }
    
    suspend fun setLocationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.LOCATION_ENABLED] = enabled
        }
    }
    
    suspend fun setStorageEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.STORAGE_ENABLED] = enabled
        }
    }
    
    suspend fun setComputeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.COMPUTE_ENABLED] = enabled
        }
    }
    
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.NOTIFICATIONS_ENABLED] = enabled
        }
    }
    
    // ========================================================================
    // Node Settings
    // ========================================================================
    
    val nodeName: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.NODE_NAME] ?: "My Android Node"
    }
    
    val nodeId: Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.NODE_ID]
    }
    
    suspend fun setNodeName(name: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.NODE_NAME] = name
        }
    }
    
    suspend fun setNodeId(id: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.NODE_ID] = id
        }
    }
    
    // ========================================================================
    // Behavior Settings
    // ========================================================================
    
    val autoStartOnBoot: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.AUTO_START_ON_BOOT] ?: false
    }
    
    val batteryOptimizationDismissed: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.BATTERY_OPTIMIZATION_DISMISSED] ?: false
    }
    
    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.AUTO_START_ON_BOOT] = enabled
        }
    }
    
    suspend fun setBatteryOptimizationDismissed(dismissed: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.BATTERY_OPTIMIZATION_DISMISSED] = dismissed
        }
    }
    
    // ========================================================================
    // Mesh Connection Settings
    // ========================================================================
    
    val lastMeshEndpoint: Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.LAST_MESH_ENDPOINT]
    }
    
    val lastMeshToken: Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.LAST_MESH_TOKEN]
    }
    
    val lastMeshName: Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.LAST_MESH_NAME]
    }
    
    val autoReconnectMesh: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.AUTO_RECONNECT_MESH] ?: false
    }
    
    suspend fun saveMeshConnection(endpoint: String, token: String, meshName: String?) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.LAST_MESH_ENDPOINT] = endpoint
            preferences[PreferenceKeys.LAST_MESH_TOKEN] = token
            meshName?.let { preferences[PreferenceKeys.LAST_MESH_NAME] = it }
        }
    }
    
    suspend fun clearMeshConnection() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.LAST_MESH_ENDPOINT)
            preferences.remove(PreferenceKeys.LAST_MESH_TOKEN)
            preferences.remove(PreferenceKeys.LAST_MESH_NAME)
        }
    }
    
    suspend fun setAutoReconnectMesh(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.AUTO_RECONNECT_MESH] = enabled
        }
    }
    
    // ========================================================================
    // Utility Functions
    // ========================================================================
    
    /**
     * Get all capability states as a map.
     */
    fun getAllCapabilities(): Flow<Map<String, Boolean>> = dataStore.data.map { preferences ->
        mapOf(
            "camera" to (preferences[PreferenceKeys.CAMERA_ENABLED] ?: false),
            "microphone" to (preferences[PreferenceKeys.MIC_ENABLED] ?: false),
            "location" to (preferences[PreferenceKeys.LOCATION_ENABLED] ?: false),
            "storage" to (preferences[PreferenceKeys.STORAGE_ENABLED] ?: false),
            "compute" to (preferences[PreferenceKeys.COMPUTE_ENABLED] ?: false),
            "notifications" to (preferences[PreferenceKeys.NOTIFICATIONS_ENABLED] ?: true)
        )
    }
    
    /**
     * Clear all preferences.
     */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
    
    // ========================================================================
    // Transport Settings
    // ========================================================================
    
    val transportLanEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.TRANSPORT_LAN_ENABLED] ?: true
    }
    
    val transportWifiDirectEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.TRANSPORT_WIFI_DIRECT_ENABLED] ?: true
    }
    
    val transportBleMeshEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.TRANSPORT_BLE_MESH_ENABLED] ?: true
    }
    
    val transportMatterEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.TRANSPORT_MATTER_ENABLED] ?: true
    }
    
    val transportRelayEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.TRANSPORT_RELAY_ENABLED] ?: true
    }
    
    val transportPreferLocalOnly: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceKeys.TRANSPORT_PREFER_LOCAL_ONLY] ?: false
    }
    
    suspend fun setTransportLanEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.TRANSPORT_LAN_ENABLED] = enabled
        }
    }
    
    suspend fun setTransportWifiDirectEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.TRANSPORT_WIFI_DIRECT_ENABLED] = enabled
        }
    }
    
    suspend fun setTransportBleMeshEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.TRANSPORT_BLE_MESH_ENABLED] = enabled
        }
    }
    
    suspend fun setTransportMatterEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.TRANSPORT_MATTER_ENABLED] = enabled
        }
    }
    
    suspend fun setTransportRelayEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.TRANSPORT_RELAY_ENABLED] = enabled
        }
    }
    
    suspend fun setTransportPreferLocalOnly(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.TRANSPORT_PREFER_LOCAL_ONLY] = enabled
            // If prefer local only is enabled, automatically disable relay
            if (enabled) {
                preferences[PreferenceKeys.TRANSPORT_RELAY_ENABLED] = false
            }
        }
    }
    
    /**
     * Get all transport settings as a map.
     */
    fun getAllTransportSettings(): Flow<Map<String, Boolean>> = dataStore.data.map { preferences ->
        mapOf(
            "lan" to (preferences[PreferenceKeys.TRANSPORT_LAN_ENABLED] ?: true),
            "wifi_direct" to (preferences[PreferenceKeys.TRANSPORT_WIFI_DIRECT_ENABLED] ?: true),
            "ble_mesh" to (preferences[PreferenceKeys.TRANSPORT_BLE_MESH_ENABLED] ?: true),
            "matter" to (preferences[PreferenceKeys.TRANSPORT_MATTER_ENABLED] ?: true),
            "relay" to (preferences[PreferenceKeys.TRANSPORT_RELAY_ENABLED] ?: true),
            "prefer_local_only" to (preferences[PreferenceKeys.TRANSPORT_PREFER_LOCAL_ONLY] ?: false)
        )
    }
}
