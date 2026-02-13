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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.llamafarm.atmosphere.ui.screens.*
import com.llamafarm.atmosphere.ui.theme.AtmosphereTheme
import com.llamafarm.atmosphere.ui.theme.*
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel
import com.llamafarm.atmosphere.viewmodel.MeshDebugViewModel

private const val TAG = "MainActivity"

/**
 * Navigation destinations — 5 bottom tabs + settings.
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector?) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Mesh : Screen("mesh", "Mesh", Icons.Default.Hub)
    data object Models : Screen("models", "Models", Icons.Default.Memory)
    data object Routing : Screen("routing", "Routing", Icons.Default.Route)
    data object Logs : Screen("logs", "Logs", Icons.Default.Terminal)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    // Legacy routes kept for deep link / service compat
    data object JoinMesh : Screen("join_mesh", "Join Mesh", null)
    data object Test : Screen("test", "Test", Icons.Default.Send)
    data object RAG : Screen("rag", "RAG", Icons.Default.Search)
    data object VisionTest : Screen("vision_test", "Vision", Icons.Default.CameraAlt)
    data object ConnectedApps : Screen("connected_apps", "Apps", Icons.Default.Apps)
    data object TransportSettings : Screen("transport_settings", "Transport Settings", null)
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

    private val bottomScreens = listOf(
        Screen.Dashboard,
        Screen.Mesh,
        Screen.Models,
        Screen.Routing,
        Screen.Logs,
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            val intent = Intent(this, com.llamafarm.atmosphere.service.AtmosphereService::class.java)
            intent.action = "ACTION_RETRY_BLE"
            startService(intent)
        }
    }

    private var pendingDeepLink: DeepLinkData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        requestBlePermissionsIfNeeded()
        initializeAtmosphereService()
        handleDeepLink(intent)

        setContent {
            AtmosphereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DashboardBackground
                ) {
                    AtmosphereDebuggerApp(bottomScreens = bottomScreens, initialDeepLink = pendingDeepLink)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        if (pendingDeepLink != null) recreate()
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "atmosphere") return
        try {
            pendingDeepLink = parseDeepLink(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse deep link: $data", e)
        }
    }

    private fun parseDeepLink(uri: Uri): DeepLinkData? {
        val path = uri.path
        if (path != null && path.startsWith("/") && !path.contains("?")) {
            val base64Data = path.removePrefix("/")
            if (base64Data.isNotEmpty()) {
                try {
                    val jsonBytes = android.util.Base64.decode(base64Data, android.util.Base64.URL_SAFE)
                    val jsonStr = String(jsonBytes, Charsets.UTF_8)
                    val invite = org.json.JSONObject(jsonStr)
                    return parseInviteJson(invite)
                } catch (_: Exception) {}
            }
        }
        val token = uri.getQueryParameter("token") ?: return null
        val endpoint = uri.getQueryParameter("endpoint") ?: return null
        val meshName = uri.getQueryParameter("mesh") ?: uri.getQueryParameter("name")
        return DeepLinkData(endpoint = endpoint, token = token, meshName = meshName, endpoints = null, tokenObject = null)
    }

    private fun parseInviteJson(invite: org.json.JSONObject): DeepLinkData? {
        if (invite.has("mesh_id") && !invite.has("mesh") && !invite.has("m")) {
            return parseMacInvite(invite)
        }
        val version = invite.optInt("v", 1)
        val meshObj = if (version >= 2) invite.optJSONObject("m") else invite.optJSONObject("mesh")
        val meshName = meshObj?.optString(if (version >= 2) "n" else "name")?.takeIf { it.isNotEmpty() }
            ?: invite.optString("mesh_name").takeIf { it.isNotEmpty() }

        val tokenStr = when {
            version >= 2 && invite.has("t") -> invite.getJSONObject("t").toString()
            invite.optJSONObject("token") != null -> invite.getJSONObject("token").toString()
            else -> invite.optString("token", "")
        }
        if (tokenStr.isEmpty()) return null

        val endpointsMap = mutableMapOf<String, String>()
        val eKey = if (version >= 2) "e" else "endpoints"
        invite.opt(eKey)?.let { e ->
            when (e) {
                is org.json.JSONObject -> e.keys().forEach { k -> e.optString(k)?.let { endpointsMap[k] = it } }
                is org.json.JSONArray -> (0 until e.length()).forEach { i ->
                    val ep = e.optString(i)
                    val type = if (ep.startsWith("ws")) "relay" else "local"
                    if (!endpointsMap.containsKey(type)) endpointsMap[type] = ep
                }
            }
        }
        if (endpointsMap.isEmpty()) invite.optString("endpoint")?.takeIf { it.isNotEmpty() }?.let { endpointsMap["local"] = it }
        if (endpointsMap.isEmpty()) return null

        val primaryEndpoint = endpointsMap["relay"] ?: endpointsMap["local"] ?: endpointsMap["public"] ?: endpointsMap.values.first()
        return DeepLinkData(endpoint = primaryEndpoint, token = tokenStr, meshName = meshName, endpoints = endpointsMap, tokenObject = null)
    }

    private fun parseMacInvite(invite: org.json.JSONObject): DeepLinkData? {
        val endpoint = invite.optString("endpoint") ?: return null
        val meshName = invite.optString("mesh_name")
        val endpointsMap = mutableMapOf("local" to endpoint)
        invite.optString("public_endpoint")?.takeIf { it.isNotEmpty() }?.let { endpointsMap["public"] = it }
        invite.optJSONArray("relay_urls")?.let { if (it.length() > 0) endpointsMap["relay"] = it.getString(0) }
        return DeepLinkData(endpoint = endpoint, token = invite.toString(), meshName = meshName, endpoints = endpointsMap, tokenObject = invite)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) blePermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun initializeAtmosphereService() {
        com.llamafarm.atmosphere.service.ServiceManager.getConnector().bind()
        com.llamafarm.atmosphere.service.AtmosphereService.start(this)

        lifecycleScope.launch {
            val app = application as AtmosphereApplication
            var attempts = 0
            while (!app.isLocalModelReady && attempts < 30) {
                kotlinx.coroutines.delay(500)
                attempts++
            }
            if (app.isLocalModelReady) {
                val defaultModel = com.llamafarm.atmosphere.inference.ModelManager.DEFAULT_MODEL
                com.llamafarm.atmosphere.service.InferenceService.start(this@MainActivity, defaultModel.id, "ASSISTANT")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmosphereDebuggerApp(
    bottomScreens: List<Screen>,
    initialDeepLink: DeepLinkData? = null
) {
    val navController = rememberNavController()
    val debugViewModel: MeshDebugViewModel = viewModel()
    val atmosphereViewModel: AtmosphereViewModel = viewModel()
    var deepLinkProcessed by remember { mutableStateOf(false) }

    // Handle deep link
    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink != null && !deepLinkProcessed) {
            deepLinkProcessed = true
            atmosphereViewModel.joinMesh(
                initialDeepLink.endpoint,
                initialDeepLink.token,
                initialDeepLink.tokenObject,
                initialDeepLink.endpoints
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DashboardBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "⚡ Atmosphere",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = TextWhite
                    )
                },
                actions = {
                    IconButton(onClick = {
                        navController.navigate(Screen.Settings.route) {
                            launchSingleTop = true
                        }
                    }) {
                        Icon(Icons.Default.Settings, "Settings", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CardBackground,
                    titleContentColor = TextWhite,
                )
            )
        },
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            if (currentDestination?.route != Screen.Settings.route) {
                NavigationBar(
                    containerColor = CardBackground,
                    contentColor = TextSecondary,
                    tonalElevation = 0.dp
                ) {
                    bottomScreens.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    screen.icon!!,
                                    contentDescription = screen.title,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(screen.title, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentBlue,
                                selectedTextColor = AccentBlue,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = AccentBlueDim.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(debugViewModel) }
            composable(Screen.Mesh.route) { MeshPeersScreen(debugViewModel) }
            composable(Screen.Models.route) { ModelsScreenNew(debugViewModel) }
            composable(Screen.Routing.route) { RoutingScreen(debugViewModel) }
            composable(Screen.Logs.route) { LogsScreen(debugViewModel) }
            composable(Screen.Settings.route) { SettingsScreenNew(debugViewModel) }
        }
    }
}
