package ai.atmosphere.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

data class ChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: String = "",
    val isLocal: Boolean = false,
    val isStreaming: Boolean = false,
    val peerId: String? = null, // Which peer handled the request
)

class ChatActivity : ComponentActivity() {

    private val client = AtmoClient()
    private val deviceName = "Pixel"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7C4DFF),
                    secondary = Color(0xFF03DAC6),
                    tertiary = Color(0xFFFFB74D),
                    surface = Color(0xFF1E1E1E),
                    background = Color(0xFF121212),
                    onBackground = Color(0xFFE0E0E0),
                    onSurface = Color(0xFFE0E0E0),
                )
            ) {
                ChatScreen()
            }
        }
    }

    @Composable
    fun ChatScreen() {
        val messages = remember { mutableStateListOf<ChatMessage>() }
        var inputText by remember { mutableStateOf("") }
        var statusText by remember { mutableStateOf("Connecting to daemon...") }
        var peerCount by remember { mutableIntStateOf(0) }
        var isConnected by remember { mutableStateOf(false) }
        var bigLlamaStatus by remember { mutableStateOf("Unknown") }
        var bigLlamaColor by remember { mutableStateOf(Color.Gray) }
        var isSending by remember { mutableStateOf(false) }
        
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        // Connect to daemon on launch
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    client.connect()
                    isConnected = true

                    val info = client.info()
                    val peerId = info["peer_id"]?.toString() ?: "?"
                    statusText = "Connected • Peer: ${peerId.take(8)}"

                    // Subscribe to _responses for streaming chat
                    client.subscribe("_responses")

                    // Get peer count
                    val peers = client.peers()
                    peerCount = peers.size

                    // Get BigLlama status
                    val blStatus = client.getBigLlamaStatus()
                    val connected = blStatus["connected"] as? Boolean ?: false
                    val mode = blStatus["mode"]?.toString() ?: "offline"
                    
                    withContext(Dispatchers.Main) {
                        when {
                            connected && mode == "cloud" -> {
                                bigLlamaStatus = "Cloud"
                                bigLlamaColor = Color(0xFF03DAC6)
                            }
                            connected && mode == "lan" -> {
                                bigLlamaStatus = "LAN"
                                bigLlamaColor = Color(0xFF4CAF50)
                            }
                            else -> {
                                bigLlamaStatus = "Offline"
                                bigLlamaColor = Color(0xFF9E9E9E)
                            }
                        }
                    }

                    // Listen for events (_responses for streaming)
                    client.events.collect { event ->
                        if (event.collection == "_responses" && event.doc != null) {
                            val doc = event.doc
                            val requestId = doc["request_id"]?.toString()
                            val token = doc["token"]?.toString()
                            val status = doc["status"]?.toString()
                            val handledBy = doc["handled_by"]?.toString()
                            
                            withContext(Dispatchers.Main) {
                                // Find the message being streamed
                                val idx = messages.indexOfLast { it.id == requestId }
                                if (idx >= 0) {
                                    val msg = messages[idx]
                                    if (token != null) {
                                        // Append token
                                        messages[idx] = msg.copy(
                                            text = msg.text + token,
                                        )
                                    }
                                    if (status == "complete") {
                                        // Streaming complete
                                        messages[idx] = msg.copy(
                                            isStreaming = false,
                                            peerId = handledBy,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    statusText = "Error: ${e.message}"
                    isConnected = false
                }
            }
        }

        // Periodic status refresh
        LaunchedEffect(isConnected) {
            if (!isConnected) return@LaunchedEffect
            while (true) {
                delay(5000)
                withContext(Dispatchers.IO) {
                    try {
                        peerCount = client.peers().size
                        
                        // Refresh BigLlama status
                        val blStatus = client.getBigLlamaStatus()
                        val connected = blStatus["connected"] as? Boolean ?: false
                        val mode = blStatus["mode"]?.toString() ?: "offline"
                        
                        withContext(Dispatchers.Main) {
                            when {
                                connected && mode == "cloud" -> {
                                    bigLlamaStatus = "Cloud"
                                    bigLlamaColor = Color(0xFF03DAC6)
                                }
                                connected && mode == "lan" -> {
                                    bigLlamaStatus = "LAN"
                                    bigLlamaColor = Color(0xFF4CAF50)
                                }
                                else -> {
                                    bigLlamaStatus = "Offline"
                                    bigLlamaColor = Color(0xFF9E9E9E)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .statusBarsPadding()
        ) {
            // Header with status
            Surface(
                color = Color(0xFF1E1E1E),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚡ Atmosphere Chat",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // BigLlama status badge
                        Surface(
                            color = bigLlamaColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(bigLlamaColor, shape = RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "BigLlama: $bigLlamaStatus",
                                    color = bigLlamaColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Text(
                        "$statusText • $peerCount mesh peer${if (peerCount != 1) "s" else ""}",
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                    )
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages.distinctBy { it.id }, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            // Auto-scroll on new message
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    delay(100)
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            // Input area
            Surface(
                color = Color(0xFF1E1E1E),
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask something...", color = Color(0xFF666666)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF7C4DFF),
                            focusedBorderColor = Color(0xFF7C4DFF),
                            unfocusedBorderColor = Color(0xFF333333),
                        ),
                        singleLine = true,
                        enabled = !isSending,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val text = inputText.trim()
                            if (text.isNotEmpty() && isConnected && !isSending) {
                                inputText = ""
                                isSending = true
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val requestId = java.util.UUID.randomUUID().toString()
                                        
                                        // Add user message
                                        withContext(Dispatchers.Main) {
                                            messages.add(ChatMessage(
                                                id = "user-$requestId",
                                                sender = "You",
                                                text = text,
                                                timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                                    .format(java.util.Date()),
                                                isLocal = true,
                                            ))
                                            
                                            // Add placeholder for assistant response
                                            messages.add(ChatMessage(
                                                id = requestId,
                                                sender = "Assistant",
                                                text = "",
                                                timestamp = "",
                                                isLocal = false,
                                                isStreaming = true,
                                            ))
                                        }
                                        
                                        // Send request to _requests collection
                                        client.insert("_requests", mapOf(
                                            "request_id" to requestId,
                                            "prompt" to text,
                                            "timestamp" to System.currentTimeMillis(),
                                        ))
                                        
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            statusText = "Send failed: ${e.message}"
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            isSending = false
                                        }
                                    }
                                }
                            }
                        },
                        enabled = isConnected && inputText.isNotBlank() && !isSending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7C4DFF),
                            disabledContainerColor = Color(0xFF333333),
                        ),
                    ) {
                        Text(if (isSending) "..." else "Send")
                    }
                }
            }
        }
    }

    @Composable
    fun MessageBubble(msg: ChatMessage) {
        val bgColor = if (msg.isLocal) Color(0xFF7C4DFF) else Color(0xFF2A2A2A)
        val alignment = if (msg.isLocal) Arrangement.End else Arrangement.Start

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = alignment,
        ) {
            Surface(
                color = bgColor,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .animateContentSize(),
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (!msg.isLocal) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                msg.sender,
                                color = Color(0xFF7C4DFF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (msg.peerId != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = Color(0xFF03DAC6).copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        msg.peerId.take(8),
                                        color = Color(0xFF03DAC6),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    
                    Text(
                        text = if (msg.isStreaming && msg.text.isEmpty()) "Thinking..." else msg.text,
                        color = if (msg.isStreaming && msg.text.isEmpty()) Color(0xFF888888) else Color.White,
                        fontSize = 15.sp,
                    )
                    
                    if (msg.isStreaming && msg.text.isNotEmpty()) {
                        Text(
                            "▋",
                            color = Color.White,
                            fontSize = 15.sp,
                        )
                    }
                    
                    if (msg.timestamp.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            msg.timestamp,
                            color = Color(0xFF666666),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.disconnect()
    }
}
