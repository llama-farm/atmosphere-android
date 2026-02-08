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
        router.registerCapability(Capability(
            id = "camera-$nodeId",
            name = "camera",
            description = "Take photos and capture images using the device camera",
            keywords = listOf("photo", "picture", "image", "camera", "capture", "snap", "shoot", "photograph"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "video-$nodeId",
            name = "video",
            description = "Record video using the device camera",
            keywords = listOf("video", "record", "film", "movie", "clip", "recording"),
            nodeId = nodeId
        ))
        
        // Location capabilities
        router.registerCapability(Capability(
            id = "location-$nodeId",
            name = "location",
            description = "Get current GPS location and coordinates",
            keywords = listOf("location", "gps", "coordinates", "where", "position", "place", "latitude", "longitude"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "navigation-$nodeId",
            name = "navigation",
            description = "Navigate and get directions to a destination",
            keywords = listOf("navigate", "directions", "route", "map", "driving", "walking", "transit"),
            nodeId = nodeId
        ))
        
        // Audio capabilities
        router.registerCapability(Capability(
            id = "microphone-$nodeId",
            name = "microphone",
            description = "Record audio using the device microphone",
            keywords = listOf("microphone", "audio", "record", "sound", "voice", "recording", "mic"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "speech_to_text-$nodeId",
            name = "speech_to_text",
            description = "Convert speech to text, transcribe audio",
            keywords = listOf("transcribe", "speech", "voice", "dictate", "stt", "listen"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "text_to_speech-$nodeId",
            name = "text_to_speech",
            description = "Convert text to speech, read text aloud",
            keywords = listOf("speak", "say", "read", "tts", "voice", "aloud"),
            nodeId = nodeId
        ))
        
        // Sensors
        router.registerCapability(Capability(
            id = "accelerometer-$nodeId",
            name = "accelerometer",
            description = "Get device acceleration and motion data",
            keywords = listOf("accelerometer", "motion", "shake", "movement", "acceleration"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "gyroscope-$nodeId",
            name = "gyroscope",
            description = "Get device orientation and rotation data",
            keywords = listOf("gyroscope", "rotation", "orientation", "tilt", "angle"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "light_sensor-$nodeId",
            name = "light_sensor",
            description = "Measure ambient light level",
            keywords = listOf("light", "brightness", "ambient", "lux", "illumination"),
            nodeId = nodeId
        ))
        
        // Communication
        router.registerCapability(Capability(
            id = "notification-$nodeId",
            name = "notification",
            description = "Send notifications to the user",
            keywords = listOf("notify", "notification", "alert", "remind", "message"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "sms-$nodeId",
            name = "sms",
            description = "Send SMS text messages",
            keywords = listOf("sms", "text", "message", "send"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "call-$nodeId",
            name = "call",
            description = "Make phone calls",
            keywords = listOf("call", "phone", "dial", "ring"),
            nodeId = nodeId
        ))
        
        // Storage
        router.registerCapability(Capability(
            id = "file_read-$nodeId",
            name = "file_read",
            description = "Read files from device storage",
            keywords = listOf("read", "file", "open", "load", "document"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "file_write-$nodeId",
            name = "file_write",
            description = "Write files to device storage",
            keywords = listOf("write", "save", "store", "file", "export"),
            nodeId = nodeId
        ))
        
        // Device info
        router.registerCapability(Capability(
            id = "battery-$nodeId",
            name = "battery",
            description = "Get device battery level and charging status",
            keywords = listOf("battery", "charge", "power", "level"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "device_info-$nodeId",
            name = "device_info",
            description = "Get device information like model, OS version",
            keywords = listOf("device", "model", "version", "info", "system"),
            nodeId = nodeId
        ))
        
        // Network
        router.registerCapability(Capability(
            id = "wifi_scan-$nodeId",
            name = "wifi_scan",
            description = "Scan for available WiFi networks",
            keywords = listOf("wifi", "network", "scan", "ssid", "wireless"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "bluetooth_scan-$nodeId",
            name = "bluetooth_scan",
            description = "Scan for Bluetooth devices",
            keywords = listOf("bluetooth", "scan", "device", "pair", "ble"),
            nodeId = nodeId
        ))
    }
    
    /**
     * Register LLM capabilities (for nodes with inference).
     */
    fun registerLlmCapabilities(router: SemanticRouter, nodeId: String = "local") {
        router.registerCapability(Capability(
            id = "chat-$nodeId",
            name = "chat",
            description = "Chat with an AI language model, have a conversation",
            keywords = listOf("chat", "talk", "conversation", "ask", "question", "ai", "llm"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "summarize-$nodeId",
            name = "summarize",
            description = "Summarize text, documents, or content",
            keywords = listOf("summarize", "summary", "tldr", "brief", "condense"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "translate-$nodeId",
            name = "translate",
            description = "Translate text between languages",
            keywords = listOf("translate", "translation", "language", "convert"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "analyze-$nodeId",
            name = "analyze",
            description = "Analyze text for sentiment, entities, or meaning",
            keywords = listOf("analyze", "analysis", "sentiment", "understand", "interpret"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "generate-$nodeId",
            name = "generate",
            description = "Generate text, stories, or creative content",
            keywords = listOf("generate", "create", "write", "compose", "draft"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "code-$nodeId",
            name = "code",
            description = "Generate or explain code",
            keywords = listOf("code", "program", "script", "function", "programming"),
            nodeId = nodeId
        ))
    }
    
    /**
     * Register vision capabilities (for nodes with vision models).
     */
    fun registerVisionCapabilities(router: SemanticRouter, nodeId: String = "local") {
        router.registerCapability(Capability(
            id = "image_analyze-$nodeId",
            name = "image_analyze",
            description = "Analyze images, detect objects, describe scenes",
            keywords = listOf("analyze", "image", "detect", "recognize", "identify", "describe", "vision"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "ocr-$nodeId",
            name = "ocr",
            description = "Extract text from images (OCR)",
            keywords = listOf("ocr", "text", "extract", "read", "scan", "document"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "face_detect-$nodeId",
            name = "face_detect",
            description = "Detect faces in images",
            keywords = listOf("face", "detect", "person", "people", "recognize"),
            nodeId = nodeId
        ))
    }
    
    /**
     * Register smart home / Matter capabilities.
     */
    fun registerSmartHomeCapabilities(router: SemanticRouter, nodeId: String = "local") {
        router.registerCapability(Capability(
            id = "lights-$nodeId",
            name = "lights",
            description = "Control smart lights - turn on, off, dim, change color",
            keywords = listOf("light", "lights", "lamp", "on", "off", "dim", "bright", "color"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "thermostat-$nodeId",
            name = "thermostat",
            description = "Control thermostat - temperature, heating, cooling",
            keywords = listOf("thermostat", "temperature", "heat", "cool", "ac", "hvac", "warm", "cold"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "lock-$nodeId",
            name = "lock",
            description = "Control smart locks - lock, unlock doors",
            keywords = listOf("lock", "unlock", "door", "secure", "security"),
            nodeId = nodeId
        ))
        
        router.registerCapability(Capability(
            id = "speaker-$nodeId",
            name = "speaker",
            description = "Control smart speakers - play music, volume",
            keywords = listOf("speaker", "music", "play", "volume", "audio", "sound", "sonos"),
            nodeId = nodeId
        ))
    }
}
