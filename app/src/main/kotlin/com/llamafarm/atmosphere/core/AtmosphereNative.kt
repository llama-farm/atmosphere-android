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
     * Initialize the text embedder with model files.
     * 
     * @param modelsDir Path to directory containing model.onnx and tokenizer.json
     * @return true if initialization succeeded, false otherwise
     */
    external fun initEmbedder(modelsDir: String): Boolean

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
}
