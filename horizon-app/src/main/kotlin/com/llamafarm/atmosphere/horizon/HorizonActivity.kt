package com.llamafarm.atmosphere.horizon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llamafarm.atmosphere.sdk.AtmosphereClient
import com.llamafarm.atmosphere.sdk.AtmosphereNotInstalledException
import kotlinx.coroutines.delay

class HorizonActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HorizonApp()
        }
    }
}

@Composable
fun HorizonApp(
    horizonViewModel: HorizonViewModel = viewModel()
) {
    val context = LocalContext.current
    var connectionState by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Connecting) }

    // Connect to Atmosphere service
    LaunchedEffect(Unit) {
        connectionState = ConnectionStatus.Connecting
        
        // Retry loop for connection
        var retries = 0
        while (retries < 3) {
            try {
                if (!AtmosphereClient.isInstalled(context)) {
                    connectionState = ConnectionStatus.NotInstalled
                    return@LaunchedEffect
                }
                
                val client = AtmosphereClient.connect(context)
                horizonViewModel.initialize(client)
                connectionState = ConnectionStatus.Connected
                return@LaunchedEffect
            } catch (e: AtmosphereNotInstalledException) {
                connectionState = ConnectionStatus.NotInstalled
                return@LaunchedEffect
            } catch (e: Exception) {
                retries++
                if (retries >= 3) {
                    connectionState = ConnectionStatus.Error(e.message ?: "Unknown error")
                } else {
                    delay(1000L * retries)
                }
            }
        }
    }

    when (val state = connectionState) {
        is ConnectionStatus.Connecting -> ConnectingScreen()
        is ConnectionStatus.NotInstalled -> NotInstalledScreen()
        is ConnectionStatus.Error -> ErrorScreen(state.message) {
            connectionState = ConnectionStatus.Connecting
        }
        is ConnectionStatus.Connected -> HorizonScreen(horizonViewModel)
    }
}

sealed class ConnectionStatus {
    data object Connecting : ConnectionStatus()
    data object NotInstalled : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

@Composable
private fun ConnectingScreen() {
    Box(
        Modifier.fillMaxSize().background(HorizonColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "HORIZON",
                color = HorizonColors.Accent,
                fontSize = 28.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "TACTICAL OPERATIONS INTERFACE",
                color = HorizonColors.TextMuted,
                fontSize = 10.sp,
                fontFamily = MonoFont,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(
                color = HorizonColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Connecting to Atmosphere…",
                color = HorizonColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = MonoFont
            )
        }
    }
}

@Composable
private fun NotInstalledScreen() {
    Box(
        Modifier.fillMaxSize().background(HorizonColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = HorizonColors.AnomalyRed,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "ATMOSPHERE NOT FOUND",
                color = HorizonColors.AnomalyRed,
                fontSize = 16.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "HORIZON requires the Atmosphere app to connect to the mesh network. Install Atmosphere to continue.",
                color = HorizonColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { /* TODO: Open Play Store */ },
                colors = ButtonDefaults.buttonColors(containerColor = HorizonColors.Accent)
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Install Atmosphere", fontFamily = MonoFont)
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(HorizonColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = HorizonColors.AnomalyOrange,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "CONNECTION ERROR",
                color = HorizonColors.AnomalyOrange,
                fontSize = 14.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = HorizonColors.TextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(HorizonColors.CardBg)
                    .padding(12.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = HorizonColors.Accent)
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Retry", fontFamily = MonoFont)
            }
        }
    }
}
