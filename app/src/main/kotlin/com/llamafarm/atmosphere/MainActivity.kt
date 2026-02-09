package com.llamafarm.atmosphere

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.llamafarm.atmosphere.ui.screens.CapabilitiesScreen
import com.llamafarm.atmosphere.ui.screens.ConnectedAppsScreen
import com.llamafarm.atmosphere.ui.screens.HomeScreen
import com.llamafarm.atmosphere.ui.screens.InferenceScreen
import com.llamafarm.atmosphere.ui.screens.JoinMeshScreen
import com.llamafarm.atmosphere.ui.screens.MeshAppsScreen
import com.llamafarm.atmosphere.ui.screens.MeshScreen
import com.llamafarm.atmosphere.ui.screens.PairingScreen
import com.llamafarm.atmosphere.ui.screens.SettingsScreen
import com.llamafarm.atmosphere.ui.screens.TestScreen
import com.llamafarm.atmosphere.ui.screens.TransportSettingsScreen
import com.llamafarm.atmosphere.ui.theme.AtmosphereTheme
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel

import com.llamafarm.atmosphere.ui.screens.ModelsScreen
import com.llamafarm.atmosphere.viewmodel.ModelsViewModel

private const val TAG = "MainActivity"

/**
 * Navigation destinations for the app.
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector?) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Inference : Screen("inference", "AI Chat", Icons.Default.Psychology)
    data object Mesh : Screen("mesh", "Mesh", Icons.Default.Hub)
    data object Models : Screen("models", "Models", Icons.Default.Memory)
    data object ConnectedApps : Screen("connected_apps", "Apps", Icons.Default.Apps)
    data object MeshApps : Screen("mesh_apps", "Mesh Apps", Icons.Default.Widgets)
    data object Test : Screen("test", "Test", Icons.Default.Send)
    data object Capabilities : Screen("capabilities", "Capabilities", Icons.Default.Memory)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object JoinMesh : Screen("join_mesh", "Join Mesh", null)
    data object TransportSettings : Screen("transport_settings", "Transport Settings", null)
    data object Pairing : Screen("pairing", "Pairing", null)
    data object RAG : Screen("rag", "RAG", Icons.Default.Search)
    data object VisionTest : Screen("vision_test", "Vision", Icons.Default.CameraAlt)
    data object Horizon : Screen("horizon", "HORIZON", Icons.Default.Flight)
}

/**
 * Data class to hold parsed deep link information for mesh joining.
 */
data class DeepLinkData(
    val endpoint: String,
    val token: String,
    val meshName: String?,
    val endpoints: Map<String, String>?,
    val tokenObject: org.json.JSONObject?
)

class MainActivity : ComponentActivity() {

    private val screens = listOf(
        Screen.Home,
        Screen.Horizon,
        Screen.Inference,
        Screen.RAG,
        Screen.VisionTest,
        Screen.Mesh,
        Screen.MeshApps,
        Screen.ConnectedApps,
        Screen.Test,
        Screen.Settings
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Handle permission result if needed
    }
    
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            android.util.Log.i(TAG, "BLE permissions granted — restarting BLE transport")
            // Notify service to retry BLE
            val intent = android.content.Intent(this, com.llamafarm.atmosphere.service.AtmosphereService::class.java)
            intent.action = "ACTION_RETRY_BLE"
            startService(intent)
        }
    }
    
    // Deep link data to be consumed by Compose
    private var pendingDeepLink: DeepLinkData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()
        
        // Request BLE permissions on Android 12+
        requestBlePermissionsIfNeeded()

        // Start the Atmosphere service from Activity (not Application) to avoid
        // ForegroundServiceStartNotAllowedException on Android 12+
        initializeAtmosphereService()
        
        // Handle deep link if present
        handleDeepLink(intent)

        setContent {
            AtmosphereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AtmosphereApp(screens = screens, initialDeepLink = pendingDeepLink)
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        // For new intents, we need to restart the activity to pick up the deep link
        // Or use a ViewModel to communicate - for simplicity, we'll recreate
        if (pendingDeepLink != null) {
            recreate()
        }
    }
    
    /**
     * Parse atmosphere:// deep links for mesh joining.
     * 
     * Supported formats:
     * - atmosphere://join/{base64_encoded_invite}
     * - atmosphere://join?endpoint=...&token=...
     */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data
        Log.e(TAG, "🔥 handleDeepLink called! data=$data")
        
        if (data == null) return
        
        if (data.scheme != "atmosphere") {
            Log.d(TAG, "Ignoring non-atmosphere URI: $data")
            return
        }
        
        Log.i(TAG, "🔗 Handling deep link: $data")
        
        try {
            pendingDeepLink = parseDeepLink(data)
            if (pendingDeepLink != null) {
                Log.e(TAG, "🔥 Deep link parsed successfully: mesh=${pendingDeepLink?.meshName}")
                Log.i(TAG, "✅ Deep link parsed: mesh=${pendingDeepLink?.meshName}, endpoint=${pendingDeepLink?.endpoint}")
            } else {
                Log.e(TAG, "🔥 Failed to parse deep link (returned null)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse deep link: $data", e)
        }
    }
    
    /**
     * Parse the deep link URI into DeepLinkData.
     */
    private fun parseDeepLink(uri: Uri): DeepLinkData? {
        // Handle base64-encoded invite: atmosphere://join/{base64}
        val path = uri.path
        if (path != null && path.startsWith("/") && !path.contains("?")) {
            val base64Data = path.removePrefix("/")
            if (base64Data.isNotEmpty()) {
                try {
                    val jsonBytes = android.util.Base64.decode(base64Data, android.util.Base64.URL_SAFE)
                    val jsonStr = String(jsonBytes, Charsets.UTF_8)
                    val invite = org.json.JSONObject(jsonStr)
                    
                    return parseInviteJson(invite)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse base64 invite, trying query params", e)
                }
            }
        }
        
        // Handle query param format: atmosphere://join?endpoint=...&token=...
        val token = uri.getQueryParameter("token") ?: return null
        val endpoint = uri.getQueryParameter("endpoint") ?: return null
        val meshName = uri.getQueryParameter("mesh") ?: uri.getQueryParameter("name")
        
        // Try to parse multi-path endpoints
        val endpointsParam = uri.getQueryParameter("endpoints")
        val endpointsMap = if (endpointsParam != null) {
            try {
                val endpointsJson = org.json.JSONObject(
                    java.net.URLDecoder.decode(endpointsParam, "UTF-8")
                )
                val map = mutableMapOf<String, String>()
                endpointsJson.keys().forEach { key ->
                    endpointsJson.optString(key, null)?.let { map[key] = it }
                }
                map
            } catch (e: Exception) {
                null
            }
        } else null
        
        return DeepLinkData(
            endpoint = endpoint,
            token = token,
            meshName = meshName,
            endpoints = endpointsMap,
            tokenObject = null
        )
    }
    
    /**
     * Parse a comprehensive invite JSON object.
     * 
     * Supports multiple formats:
     * - Mac CLI format: {mesh_id, mesh_name, endpoint, code, mesh_public_key, ...}
     * - Android v1 format: {mesh, token, endpoints, ...}
     * - Android v2 format: {v, m, t, e, ...}
     */
    private fun parseInviteJson(invite: org.json.JSONObject): DeepLinkData? {
        val version = invite.optInt("v", 1)
        Log.i(TAG, "Parsing invite v$version")
        
        // Detect Mac CLI format (has mesh_id at root, not nested)
        val isMacFormat = invite.has("mesh_id") && !invite.has("mesh") && !invite.has("m")
        
        if (isMacFormat) {
            return parseMacInvite(invite)
        }
        
        // V2 uses short keys for smaller QR codes
        val meshObj = if (version >= 2) invite.optJSONObject("m") else invite.optJSONObject("mesh")
        val meshName = if (meshObj != null) {
            if (version >= 2) meshObj.optString("n", null) else meshObj.optString("name", null)
        } else invite.optString("mesh_name", null)
        
        // Parse token
        val tokenObj: org.json.JSONObject?
        val tokenStr: String
        if (version >= 2 && invite.has("t")) {
            tokenObj = invite.getJSONObject("t")
            tokenStr = tokenObj.toString()
        } else {
            // Check if token is an object (v1 format)
            val innerTokenObj = invite.optJSONObject("token")
            if (innerTokenObj != null) {
                tokenObj = innerTokenObj
                tokenStr = innerTokenObj.toString()
            } else {
                tokenObj = null
                tokenStr = invite.optString("token", "")
            }
        }
        
        if (tokenStr.isEmpty()) {
            Log.w(TAG, "No token found in invite")
            return null
        }
        
        // Parse endpoints
        val endpointsMap = mutableMapOf<String, String>()
        
        try {
            if (version >= 2 && invite.has("e")) {
                val e = invite.get("e")
                if (e is org.json.JSONObject) {
                    e.keys().forEach { key -> e.optString(key)?.let { endpointsMap[key] = it } }
                } else if (e is org.json.JSONArray) {
                    for (i in 0 until e.length()) {
                        val ep = e.optString(i)
                        val type = if (ep.startsWith("ws")) "relay" else "local"
                        if (!endpointsMap.containsKey(type)) endpointsMap[type] = ep
                    }
                }
            } else if (invite.has("endpoints")) {
                val e = invite.get("endpoints")
                if (e is org.json.JSONObject) {
                    e.keys().forEach { key -> e.optString(key)?.let { endpointsMap[key] = it } }
                } else if (e is org.json.JSONArray) {
                    for (i in 0 until e.length()) {
                        val ep = e.optString(i)
                        val type = if (ep.startsWith("ws")) "relay" else "local"
                        if (!endpointsMap.containsKey(type)) endpointsMap[type] = ep
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing endpoints", e)
        }
        
        // Legacy single endpoint
        if (endpointsMap.isEmpty()) {
            invite.optString("endpoint", null)?.takeIf { it.isNotEmpty() }?.let {
                endpointsMap["local"] = it
            }
        }
        
        if (endpointsMap.isEmpty()) {
            Log.w(TAG, "No endpoints found in invite")
            return null
        }
        
        // Select primary endpoint (prefer relay for mobile, then local)
        val primaryEndpoint = endpointsMap["relay"]
            ?: endpointsMap["local"]
            ?: endpointsMap["public"]
            ?: endpointsMap.values.first()
        
        return DeepLinkData(
            endpoint = primaryEndpoint,
            token = tokenStr,
            meshName = meshName,
            endpoints = endpointsMap,
            tokenObject = tokenObj
        )
    }
    
    /**
     * Parse Mac CLI invite format.
     * Format: {mesh_id, mesh_name, endpoint, code, mesh_public_key, created_at, expires_at, ...}
     */
    private fun parseMacInvite(invite: org.json.JSONObject): DeepLinkData? {
        Log.i(TAG, "Parsing Mac CLI invite format")
        
        val meshId = invite.optString("mesh_id", null) ?: return null
        val meshName = invite.optString("mesh_name", null)
        val endpoint = invite.optString("endpoint", null) ?: return null
        val meshPublicKey = invite.optString("mesh_public_key", null)
        
        // For Mac invites, the entire invite JSON IS the token
        // The server will validate using mesh_public_key and timestamps
        val tokenStr = invite.toString()
        
        // Build endpoints map
        val endpointsMap = mutableMapOf<String, String>()
        endpointsMap["local"] = endpoint
        
        // Add public endpoint if available
        invite.optString("public_endpoint", null)?.takeIf { it.isNotEmpty() }?.let {
            endpointsMap["public"] = it
        }
        
        // Add relay URLs if available
        invite.optJSONArray("relay_urls")?.let { relayUrls ->
            if (relayUrls.length() > 0) {
                endpointsMap["relay"] = relayUrls.getString(0)
            }
        }
        
        Log.i(TAG, "✅ Mac invite parsed: mesh=$meshName, id=$meshId, endpoint=$endpoint")
        
        return DeepLinkData(
            endpoint = endpoint,
            token = tokenStr,
            meshName = meshName,
            endpoints = endpointsMap,
            tokenObject = invite // Crucial: Store the whole object as the tokenObject
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                blePermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }
    
    private fun initializeAtmosphereService() {
        // Bind to service and start it
        // Safe to call from Activity.onCreate() on Android 12+ (activities have foreground permission)
        com.llamafarm.atmosphere.service.ServiceManager.getConnector().bind()
        com.llamafarm.atmosphere.service.AtmosphereService.start(this)
        
        // Auto-start inference service once local model is ready
        val activity = this
        lifecycleScope.launch {
            val app = application as AtmosphereApplication
            var attempts = 0
            while (!app.isLocalModelReady && attempts < 30) {
                kotlinx.coroutines.delay(500)
                attempts++
            }
            if (app.isLocalModelReady) {
                val defaultModel = com.llamafarm.atmosphere.inference.ModelManager.DEFAULT_MODEL
                android.util.Log.i("MainActivity", "Auto-starting InferenceService with ${defaultModel.id}")
                com.llamafarm.atmosphere.service.InferenceService.start(activity, defaultModel.id, "ASSISTANT")
            } else {
                android.util.Log.w("MainActivity", "Local model not ready after 15s, skipping InferenceService auto-start")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmosphereApp(screens: List<Screen>, initialDeepLink: DeepLinkData? = null) {
    val navController = rememberNavController()
    val viewModel: AtmosphereViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Track if we've processed the deep link to avoid duplicate joins
    var deepLinkProcessed by remember { mutableStateOf(false) }

    // Handle app lifecycle - reconnect to mesh when app resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Handle deep link on first composition
    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink != null && !deepLinkProcessed) {
            deepLinkProcessed = true
            Log.i(TAG, "🔗 Processing deep link: joining mesh ${initialDeepLink.meshName}")
            
            // Navigate to mesh screen and trigger join
            navController.navigate(Screen.Mesh.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
            }
            
            // Trigger the mesh join
            viewModel.joinMesh(
                initialDeepLink.endpoint,
                initialDeepLink.token,
                initialDeepLink.tokenObject,
                initialDeepLink.endpoints
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            // Hide bottom bar on JoinMesh, TransportSettings, and Pairing screens
            if (currentDestination?.route != Screen.JoinMesh.route &&
                currentDestination?.route != Screen.TransportSettings.route &&
                currentDestination?.route != Screen.Pairing.route) {
                NavigationBar {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon!!, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen(viewModel) }
            composable(Screen.Inference.route) { 
                val isConnected by viewModel.isConnectedToMesh.collectAsState()
                InferenceScreen(
                    isMeshConnected = isConnected,
                    onMeshInference = { prompt, callback ->
                        viewModel.sendUserMessage(prompt) { response, error ->
                            callback(response, error)
                        }
                    }
                )
            }
            composable(Screen.Mesh.route) { 
                MeshScreen(
                    viewModel = viewModel,
                    onJoinMeshClick = {
                        navController.navigate(Screen.JoinMesh.route)
                    }
                )
            }
            composable(Screen.Models.route) {
                val modelsViewModel: ModelsViewModel = viewModel()
                ModelsScreen(modelsViewModel)
            }
            composable(Screen.Test.route) {  
                TestScreen(
                    viewModel = viewModel,
                    onNavigateToPairing = {
                        navController.navigate(Screen.Pairing.route)
                    }
                )
            }
            composable(Screen.Capabilities.route) { CapabilitiesScreen() }
            composable(Screen.ConnectedApps.route) { ConnectedAppsScreen() }
            composable(Screen.MeshApps.route) { MeshAppsScreen(viewModel) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToTransportSettings = {
                        navController.navigate(Screen.TransportSettings.route)
                    }
                )
            }
            composable(Screen.TransportSettings.route) {
                TransportSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Pairing.route) {
                PairingScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.JoinMesh.route) {
                val connectionState by viewModel.meshConnectionState.collectAsState()
                val meshConnected by viewModel.isConnectedToMesh.collectAsState()

                // Navigate back when successfully connected
                LaunchedEffect(meshConnected) {
                    if (meshConnected) {
                        kotlinx.coroutines.delay(500) // Brief delay to show success
                        navController.popBackStack()
                    }
                }

                JoinMeshScreen(
                    onJoinMesh = { endpoint, token, tokenObject, endpoints ->
                        viewModel.joinMesh(endpoint, token, tokenObject, endpoints)
                        // Don't navigate immediately - wait for LaunchedEffect above
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(Screen.RAG.route) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val cameraCapability = com.llamafarm.atmosphere.capabilities.CameraCapability(context.applicationContext)
                val llamaFarmLite = com.llamafarm.atmosphere.llamafarm.LlamaFarmLite.getInstance(context.applicationContext, cameraCapability)
                com.llamafarm.atmosphere.ui.screens.RagScreen(
                    llamaFarmLite = llamaFarmLite,
                    appId = context.packageName
                )
            }
            composable(Screen.Horizon.route) {
                com.llamafarm.atmosphere.horizon.HorizonScreen(
                    atmosphereViewModel = viewModel
                )
            }
            composable(Screen.VisionTest.route) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val visionModelManager = remember { com.llamafarm.atmosphere.vision.VisionModelManager(context.applicationContext) }
                val cameraCapability = com.llamafarm.atmosphere.capabilities.CameraCapability(context.applicationContext)
                val visionCapability = remember { com.llamafarm.atmosphere.vision.VisionCapability(context.applicationContext, "local", cameraCapability, visionModelManager) }
                com.llamafarm.atmosphere.ui.screens.VisionScreen(
                    visionCapability = visionCapability,
                    modelManager = visionModelManager,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
