package com.llamafarm.atmosphere.core

/**
 * JNI wrapper for the Rust Atmosphere core.
 * 
 * This is a thin Kotlin layer that calls into the native Rust implementation
 * of the Atmosphere mesh platform via JNI.
 */
object AtmosphereNative {
    
    init {
        try {
            System.loadLibrary("atmo_jni")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("AtmosphereNative", "Failed to load atmo_jni library", e)
            throw e
        }
    }

    /**
     * Initialize Atmosphere core.
     * 
     * @param appId Application ID for the mesh
     * @param peerId Unique peer ID for this node
     * @param deviceName Human-readable name for this device
     * @return Handle ID for subsequent operations (0 if failed)
     */
    external fun init(appId: String, peerId: String, deviceName: String): Long

    /**
     * Start mesh networking.
     * 
     * @param handle Handle returned from init()
     * @return Mesh TCP port (0 if failed or OS-assigned)
     */
    external fun startMesh(handle: Long): Int

    /**
     * Stop Atmosphere and release resources.
     * 
     * @param handle Handle returned from init()
     */
    external fun stop(handle: Long)

    /**
     * Insert a document into a CRDT collection.
     * 
     * @param handle Handle returned from init()
     * @param collection Collection name (e.g., "_capabilities", "_requests")
     * @param docId Document ID
     * @param dataJson Document data as JSON string
     */
    external fun insert(handle: Long, collection: String, docId: String, dataJson: String)

    /**
     * Query all documents in a CRDT collection.
     * 
     * @param handle Handle returned from init()
     * @param collection Collection name
     * @return JSON array of documents as string
     */
    external fun query(handle: Long, collection: String): String

    /**
     * Get a specific document from a CRDT collection.
     * 
     * @param handle Handle returned from init()
     * @param collection Collection name
     * @param docId Document ID
     * @return JSON object as string (or "null" if not found)
     */
    external fun get(handle: Long, collection: String, docId: String): String

    /**
     * Get list of connected peers.
     * 
     * @param handle Handle returned from init()
     * @return JSON array of peer info as string
     */
    external fun peers(handle: Long): String

    /**
     * Get capabilities from the gradient table (_capabilities collection).
     * 
     * @param handle Handle returned from init()
     * @return JSON array of capabilities as string
     */
    external fun capabilities(handle: Long): String

    /**
     * Get health/status information.
     * 
     * @param handle Handle returned from init()
     * @return JSON object with status info as string
     */
    external fun health(handle: Long): String

    /**
     * Get transport statuses from Rust core.
     * Returns JSON object with status of each transport (lan, websocket, ble, p2p_wifi).
     *
     * @param handle Handle returned from init()
     * @return JSON string with transport statuses
     */
    external fun getTransportStatuses(handle: Long): String

    /**
     * Initialize the text embedder with model files.
     * 
     * @param modelsDir Path to directory containing model.onnx and tokenizer.json
     * @return true if initialization succeeded, false otherwise
     */
    external fun initEmbedder(modelsDir: String, ortLibPath: String = ""): Boolean

    /**
     * Embed a text string into a 384-dimensional vector.
     * Uses all-MiniLM-L6-v2 ONNX model for sentence embeddings.
     * 
     * @param text Text to embed
     * @return FloatArray of 384 dimensions, or null if failed
     */
    external fun nativeEmbed(text: String): FloatArray?

    /**
     * Calculate cosine similarity between two embedding vectors.
     * Returns a value between -1.0 (opposite) and 1.0 (identical).
     * 
     * @param a First embedding vector (384 dimensions)
     * @param b Second embedding vector (384 dimensions)
     * @return Similarity score, or 0.0 if failed
     */
    external fun nativeCosineSimilarity(a: FloatArray, b: FloatArray): Float

    // ========================================================================
    // Wi-Fi Aware Transport
    // ========================================================================

    /**
     * Notify Rust core that a Wi-Fi Aware peer was discovered.
     * 
     * @param handle Handle returned from init()
     * @param peerId Peer handle/ID as string
     * @param serviceInfo Service-specific info from discovery
     */
    external fun wifiAwarePeerDiscovered(handle: Long, peerId: String, serviceInfo: String)

    /**
     * Notify Rust core that a Wi-Fi Aware peer was lost.
     * 
     * @param handle Handle returned from init()
     * @param peerId Peer handle/ID as string
     */
    external fun wifiAwarePeerLost(handle: Long, peerId: String)

    /**
     * Notify Rust core that data was received via Wi-Fi Aware.
     * 
     * @param handle Handle returned from init()
     * @param peerId Peer handle/ID as string
     * @param data Data bytes received
     */
    external fun wifiAwareDataReceived(handle: Long, peerId: String, data: ByteArray)

    /**
     * Poll outgoing Wi-Fi Aware data for a peer.
     * Returns null if no data is pending.
     */
    external fun wifiAwarePollOutgoing(handle: Long, peerId: String): ByteArray?

    /**
     * Notify Rust that a Wi-Fi Aware peer completed hello handshake (accepted into multiplexer).
     */
    external fun wifiAwarePeerAccepted(handle: Long, peerId: String, peerHandle: String)

    // ========================================================================
    // BLE Transport
    // ========================================================================

    /**
     * Notify Rust core that BLE data was received.
     * 
     * @param handle Handle returned from init()
     * @param peerId Peer ID
     * @param data Data bytes received
     */
    external fun bleDataReceived(handle: Long, peerId: String, data: ByteArray)

    /**
     * Send data to a BLE peer.
     * 
     * @param handle Handle returned from init()
     * @param peerId Peer ID
     * @param data Data bytes to send
     * @return true if send was queued successfully
     */
    external fun bleSendData(handle: Long, peerId: String, data: ByteArray): Boolean

    /**
     * Poll for outgoing BLE data to send to a peer.
     * Kotlin calls this periodically to get data the Rust side wants to send over BLE.
     * 
     * @param handle Handle returned from init()
     * @param peerId Peer ID to poll for
     * @return Data bytes to send, or null if nothing pending
     */
    external fun blePollOutgoing(handle: Long, peerId: String): ByteArray?

    /**
     * Notify Rust that a BLE peer was discovered via scanning.
     * 
     * @param handle Handle returned from init()
     * @param peerId Atmosphere peer ID
     * @param deviceId BLE device address
     */
    external fun blePeerDiscovered(handle: Long, peerId: String, deviceId: String)

    /**
     * Notify Rust that a BLE peer was lost/disconnected.
     * 
     * @param handle Handle returned from init()
     * @param peerId Atmosphere peer ID
     */
    external fun blePeerLost(handle: Long, peerId: String)

    /**
     * Notify Rust that a BLE peer completed hello handshake (accepted).
     * 
     * @param handle Handle returned from init()
     * @param peerId Atmosphere peer ID
     * @param deviceId BLE device address
     */
    external fun blePeerAccepted(handle: Long, peerId: String, deviceId: String)
}
