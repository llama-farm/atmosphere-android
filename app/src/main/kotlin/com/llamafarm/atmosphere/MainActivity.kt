package com.llamafarm.atmosphere

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.llamafarm.atmosphere.ui.screens.CapabilitiesScreen
import com.llamafarm.atmosphere.ui.screens.HomeScreen
import com.llamafarm.atmosphere.ui.screens.InferenceScreen
import com.llamafarm.atmosphere.ui.screens.JoinMeshScreen
import com.llamafarm.atmosphere.ui.screens.MeshScreen
import com.llamafarm.atmosphere.ui.screens.SettingsScreen
import com.llamafarm.atmosphere.ui.screens.TestScreen
import com.llamafarm.atmosphere.ui.screens.TransportSettingsScreen
import com.llamafarm.atmosphere.ui.theme.AtmosphereTheme
import com.llamafarm.atmosphere.viewmodel.AtmosphereViewModel

/**
 * Navigation destinations for the app.
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector?) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Inference : Screen("inference", "AI Chat", Icons.Default.Psychology)
    data object Mesh : Screen("mesh", "Mesh", Icons.Default.Hub)
    data object Test : Screen("test", "Test", Icons.Default.Send)
    data object Capabilities : Screen("capabilities", "Capabilities", Icons.Default.Memory)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object JoinMesh : Screen("join_mesh", "Join Mesh", null)
    data object TransportSettings : Screen("transport_settings", "Transport Settings", null)
}

class MainActivity : ComponentActivity() {

    private val screens = listOf(
        Screen.Home,
        Screen.Inference,
        Screen.Mesh,
        Screen.Test,
        Screen.Capabilities,
        Screen.Settings
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Handle permission result if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        setContent {
            AtmosphereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AtmosphereApp(screens = screens)
                }
            }
        }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmosphereApp(screens: List<Screen>) {
    val navController = rememberNavController()
    val viewModel: AtmosphereViewModel = viewModel()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            
            // Hide bottom bar on JoinMesh and TransportSettings screens
            if (currentDestination?.route != Screen.JoinMesh.route && 
                currentDestination?.route != Screen.TransportSettings.route) {
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
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Inference.route) { InferenceScreen() }
            composable(Screen.Mesh.route) { 
                MeshScreen(
                    viewModel = viewModel,
                    onJoinMeshClick = {
                        navController.navigate(Screen.JoinMesh.route)
                    }
                )
            }
            composable(Screen.Test.route) { TestScreen(viewModel = viewModel) }
            composable(Screen.Capabilities.route) { CapabilitiesScreen() }
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
                    onJoinMesh = { endpoint, token ->
                        viewModel.joinMesh(endpoint, token)
                        // Don't navigate immediately - wait for LaunchedEffect above
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
