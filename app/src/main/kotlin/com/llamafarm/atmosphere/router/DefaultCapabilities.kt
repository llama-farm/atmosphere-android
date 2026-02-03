package com.llamafarm.atmosphere.router

/**
 * Default capabilities that can be registered with the SemanticRouter.
 * 
 * Each capability has:
 * - name: Unique identifier
 * - description: Natural language description for embedding matching
 * - keywords: Fast-match keywords for offline routing
 */
object DefaultCapabilities {
    
    /**
     * Register all default Android capabilities.
     */
    fun registerAll(router: SemanticRouter, nodeId: String = "local") {
        // Camera capabilities
        router.registerCapability(
            name = "camera",
            description = "Take photos and capture images using the device camera",
            nodeId = nodeId,
            keywords = listOf("photo", "picture", "image", "camera", "capture", "snap", "shoot", "photograph")
        )
        
        router.registerCapability(
            name = "video",
            description = "Record video using the device camera",
            nodeId = nodeId,
            keywords = listOf("video", "record", "film", "movie", "clip", "recording")
        )
        
        // Location capabilities
        router.registerCapability(
            name = "location",
            description = "Get current GPS location and coordinates",
            nodeId = nodeId,
            keywords = listOf("location", "gps", "coordinates", "where", "position", "place", "latitude", "longitude")
        )
        
        router.registerCapability(
            name = "navigation",
            description = "Navigate and get directions to a destination",
            nodeId = nodeId,
            keywords = listOf("navigate", "directions", "route", "map", "driving", "walking", "transit")
        )
        
        // Audio capabilities
        router.registerCapability(
            name = "microphone",
            description = "Record audio using the device microphone",
            nodeId = nodeId,
            keywords = listOf("microphone", "audio", "record", "sound", "voice", "recording", "mic")
        )
        
        router.registerCapability(
            name = "speech_to_text",
            description = "Convert speech to text, transcribe audio",
            nodeId = nodeId,
            keywords = listOf("transcribe", "speech", "voice", "dictate", "stt", "listen")
        )
        
        router.registerCapability(
            name = "text_to_speech",
            description = "Convert text to speech, read text aloud",
            nodeId = nodeId,
            keywords = listOf("speak", "say", "read", "tts", "voice", "aloud")
        )
        
        // Sensors
        router.registerCapability(
            name = "accelerometer",
            description = "Get device acceleration and motion data",
            nodeId = nodeId,
            keywords = listOf("accelerometer", "motion", "shake", "movement", "acceleration")
        )
        
        router.registerCapability(
            name = "gyroscope",
            description = "Get device orientation and rotation data",
            nodeId = nodeId,
            keywords = listOf("gyroscope", "rotation", "orientation", "tilt", "angle")
        )
        
        router.registerCapability(
            name = "light_sensor",
            description = "Measure ambient light level",
            nodeId = nodeId,
            keywords = listOf("light", "brightness", "ambient", "lux", "illumination")
        )
        
        // Communication
        router.registerCapability(
            name = "notification",
            description = "Send notifications to the user",
            nodeId = nodeId,
            keywords = listOf("notify", "notification", "alert", "remind", "message")
        )
        
        router.registerCapability(
            name = "sms",
            description = "Send SMS text messages",
            nodeId = nodeId,
            keywords = listOf("sms", "text", "message", "send")
        )
        
        router.registerCapability(
            name = "call",
            description = "Make phone calls",
            nodeId = nodeId,
            keywords = listOf("call", "phone", "dial", "ring")
        )
        
        // Storage
        router.registerCapability(
            name = "file_read",
            description = "Read files from device storage",
            nodeId = nodeId,
            keywords = listOf("read", "file", "open", "load", "document")
        )
        
        router.registerCapability(
            name = "file_write",
            description = "Write files to device storage",
            nodeId = nodeId,
            keywords = listOf("write", "save", "store", "file", "export")
        )
        
        // Device info
        router.registerCapability(
            name = "battery",
            description = "Get device battery level and charging status",
            nodeId = nodeId,
            keywords = listOf("battery", "charge", "power", "level")
        )
        
        router.registerCapability(
            name = "device_info",
            description = "Get device information like model, OS version",
            nodeId = nodeId,
            keywords = listOf("device", "model", "version", "info", "system")
        )
        
        // Network
        router.registerCapability(
            name = "wifi_scan",
            description = "Scan for available WiFi networks",
            nodeId = nodeId,
            keywords = listOf("wifi", "network", "scan", "ssid", "wireless")
        )
        
        router.registerCapability(
            name = "bluetooth_scan",
            description = "Scan for Bluetooth devices",
            nodeId = nodeId,
            keywords = listOf("bluetooth", "scan", "device", "pair", "ble")
        )
    }
    
    /**
     * Register LLM capabilities (for nodes with inference).
     */
    fun registerLlmCapabilities(router: SemanticRouter, nodeId: String = "local") {
        router.registerCapability(
            name = "chat",
            description = "Chat with an AI language model, have a conversation",
            nodeId = nodeId,
            keywords = listOf("chat", "talk", "conversation", "ask", "question", "ai", "llm")
        )
        
        router.registerCapability(
            name = "summarize",
            description = "Summarize text, documents, or content",
            nodeId = nodeId,
            keywords = listOf("summarize", "summary", "tldr", "brief", "condense")
        )
        
        router.registerCapability(
            name = "translate",
            description = "Translate text between languages",
            nodeId = nodeId,
            keywords = listOf("translate", "translation", "language", "convert")
        )
        
        router.registerCapability(
            name = "analyze",
            description = "Analyze text for sentiment, entities, or meaning",
            nodeId = nodeId,
            keywords = listOf("analyze", "analysis", "sentiment", "understand", "interpret")
        )
        
        router.registerCapability(
            name = "generate",
            description = "Generate text, stories, or creative content",
            nodeId = nodeId,
            keywords = listOf("generate", "create", "write", "compose", "draft")
        )
        
        router.registerCapability(
            name = "code",
            description = "Generate or explain code",
            nodeId = nodeId,
            keywords = listOf("code", "program", "script", "function", "programming")
        )
    }
    
    /**
     * Register vision capabilities (for nodes with vision models).
     */
    fun registerVisionCapabilities(router: SemanticRouter, nodeId: String = "local") {
        router.registerCapability(
            name = "image_analyze",
            description = "Analyze images, detect objects, describe scenes",
            nodeId = nodeId,
            keywords = listOf("analyze", "image", "detect", "recognize", "identify", "describe", "vision")
        )
        
        router.registerCapability(
            name = "ocr",
            description = "Extract text from images (OCR)",
            nodeId = nodeId,
            keywords = listOf("ocr", "text", "extract", "read", "scan", "document")
        )
        
        router.registerCapability(
            name = "face_detect",
            description = "Detect faces in images",
            nodeId = nodeId,
            keywords = listOf("face", "detect", "person", "people", "recognize")
        )
    }
    
    /**
     * Register smart home / Matter capabilities.
     */
    fun registerSmartHomeCapabilities(router: SemanticRouter, nodeId: String = "local") {
        router.registerCapability(
            name = "lights",
            description = "Control smart lights - turn on, off, dim, change color",
            nodeId = nodeId,
            keywords = listOf("light", "lights", "lamp", "on", "off", "dim", "bright", "color")
        )
        
        router.registerCapability(
            name = "thermostat",
            description = "Control thermostat - temperature, heating, cooling",
            nodeId = nodeId,
            keywords = listOf("thermostat", "temperature", "heat", "cool", "ac", "hvac", "warm", "cold")
        )
        
        router.registerCapability(
            name = "lock",
            description = "Control smart locks - lock, unlock doors",
            nodeId = nodeId,
            keywords = listOf("lock", "unlock", "door", "secure", "security")
        )
        
        router.registerCapability(
            name = "speaker",
            description = "Control smart speakers - play music, volume",
            nodeId = nodeId,
            keywords = listOf("speaker", "music", "play", "volume", "audio", "sound", "sonos")
        )
    }
}
