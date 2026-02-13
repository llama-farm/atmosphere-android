// IAtmosphereService.aidl
// Atmosphere mesh service - allows other apps to use the mesh
package com.llamafarm.atmosphere;

import com.llamafarm.atmosphere.IAtmosphereCallback;
import com.llamafarm.atmosphere.AtmosphereCapability;

interface IAtmosphereService {
    /**
     * Get the SDK/service version.
     * @return Version string (e.g., "1.0.0")
     */
    String getVersion();
    
    /**
     * Route an intent to the best capability in the mesh.
     * @param intent Natural language description of what to do
     * @param payload JSON payload with data
     * @return JSON response from the capability
     */
    String route(String intent, String payload);
    
    /**
     * OpenAI-compatible chat completion.
     * @param messagesJson JSON array of messages [{role, content}]
     * @param model Optional model name (null for auto-select)
     * @return JSON response with choices
     */
    String chatCompletion(String messagesJson, String model);
    
    /**
     * Streaming chat completion via callback.
     * Chunks are delivered through IAtmosphereCallback.onStreamChunk().
     * @param requestId Unique ID for this request (to match chunks)
     * @param messagesJson JSON array of messages [{role, content}]
     * @param model Optional model name (null for auto-select)
     * @param callback Callback to receive stream chunks
     */
    void chatCompletionStream(String requestId, String messagesJson, String model, IAtmosphereCallback callback);
    
    /**
     * Get all available capabilities in the mesh.
     * @return List of capabilities
     */
    List<AtmosphereCapability> getCapabilities();
    
    /**
     * Get a specific capability by ID.
     * @param capabilityId The capability ID
     * @return The capability, or null if not found
     */
    AtmosphereCapability getCapability(String capabilityId);
    
    /**
     * Invoke a specific capability directly (bypass routing).
     * @param capabilityId The capability ID to invoke
     * @param payload JSON payload
     * @return JSON response
     */
    String invokeCapability(String capabilityId, String payload);
    
    /**
     * Get current mesh status.
     * @return JSON with node count, connected peers, etc.
     */
    String getMeshStatus();
    
    /**
     * Get current cost metrics.
     * @return JSON with battery, cpu, network costs
     */
    String getCostMetrics();
    
    /**
     * Request to join a mesh network.
     * @param meshId The mesh ID to join (null for default/auto)
     * @param credentialsJson Optional credentials JSON
     * @return JSON result with success/error
     */
    String joinMesh(String meshId, String credentialsJson);
    
    /**
     * Leave the current mesh network.
     * @return JSON result with success/error
     */
    String leaveMesh();
    
    /**
     * Register a capability from a third-party app.
     * Allows other apps to contribute capabilities to the mesh.
     * @param capabilityJson JSON describing the capability
     * @return JSON result with assigned capability ID
     */
    String registerCapability(String capabilityJson);
    
    /**
     * Unregister a previously registered capability.
     * @param capabilityId The capability ID to remove
     */
    void unregisterCapability(String capabilityId);
    
    /**
     * Register for capability events (push notifications from mesh).
     */
    void registerCallback(IAtmosphereCallback callback);
    
    /**
     * Unregister from capability events.
     */
    void unregisterCallback(IAtmosphereCallback callback);
    
    // ========================== RAG API ==========================
    
    /**
     * Create a RAG index from JSON documents.
     * Each document should have "id" and "content" fields.
     * 
     * @param indexId Index ID
     * @param documentsJson JSON string with documents
     * @return JSON result with success/error
     */
    String createRagIndex(String indexId, String documentsJson);
    
    /**
     * Add a document to a RAG index.
     * 
     * @param namespace Namespace of the index
     * @param docId Unique document ID
     * @param content Document content
     * @param metadata Optional metadata JSON string
     * @return true if successful, false otherwise
     */
    boolean addRagDocument(String namespace, String docId, String content, String metadata);
    
    /**
     * Query a RAG index with natural language.
     * Returns relevant context as JSON.
     * 
     * @param indexId The index ID to query
     * @param query Natural language query
     * @param generateAnswer Whether to generate an answer using LLM
     * @return JSON with retrieved documents and optional answer
     */
    String queryRag(String indexId, String query, boolean generateAnswer);
    
    /**
     * Delete a RAG index.
     * 
     * @param indexId The index ID to delete
     * @return JSON result with success/error
     */
    String deleteRagIndex(String indexId);
    
    /**
     * List all RAG indexes.
     * 
     * @return JSON array of index info objects
     */
    String listRagIndexes();
    
    // ========================== Vision API ==========================
    
    /**
     * Detect objects in an image (base64 encoded).
     * 
     * @param imageBase64 Base64-encoded image data
     * @param sourceId Optional source identifier
     * @return JSON result with detections: {class_name, confidence, bbox, inference_time_ms}
     */
    String detectObjects(String imageBase64, String sourceId);
    
    /**
     * Capture from camera and detect objects.
     * 
     * @param facing "front" or "back"
     * @return JSON result with detections
     */
    String captureAndDetect(String facing);
    
    /**
     * Get vision capability status and info.
     * 
     * @return JSON with model info, readiness, supported operations
     */
    String getVisionCapability();
    
    /**
     * Set confidence threshold for vision escalation.
     * 
     * @param threshold Float between 0.0 and 1.0
     */
    void setVisionConfidenceThreshold(float threshold);
    
    /**
     * Send feedback on a vision detection for model training.
     * 
     * @param feedbackJson JSON with detection_id, correct (boolean), corrected_label, image_base64
     * @return JSON result with success/error
     */
    String sendVisionFeedback(String feedbackJson);
    
    // ========================== LlamaFarm Lite API ==========================
    
    /**
     * Get all LlamaFarm Lite capabilities (LLM, RAG, Vision, Prompts).
     * 
     * @return JSON with complete capability info
     */
    String getLlamaFarmCapabilities();
    
    /**
     * Check if LLM is ready for inference.
     * 
     * @return true if model loaded and ready
     */
    boolean isLlmReady();
    
    /**
     * Check if Vision is ready for inference.
     * 
     * @return true if vision model loaded and ready
     */
    boolean isVisionReady();
    
    // ========================== Mesh App / Tool API ==========================
    
    /**
     * Get all available mesh apps.
     * 
     * @return JSON array of app objects: [{name, description, toolCount}]
     */
    String getApps();
    
    /**
     * Get tools exposed by a specific mesh app.
     * 
     * @param appName App name (e.g., "horizon")
     * @return JSON array of tool objects: [{name, description, params, method, endpoint}]
     */
    String getAppTools(String appName);
    
    /**
     * Call a tool on a mesh app.
     * 
     * @param appName App name (e.g., "horizon")
     * @param toolName Tool name (e.g., "get_mission_summary")
     * @param paramsJson JSON object with tool parameters
     * @return JSON response from the tool
     */
    String callTool(String appName, String toolName, String paramsJson);

    // ========================== CRDT Data Sync API ==========================

    /** Insert a document into a CRDT collection. Returns doc ID. */
    String crdtInsert(String collection, String docJson);

    /** Query all documents in a CRDT collection. Returns JSON array. */
    String crdtQuery(String collection);

    /** Get a specific document by ID. Returns JSON or null. */
    String crdtGet(String collection, String docId);

    /** Subscribe to changes on a collection. Changes delivered via onCrdtChange callback. */
    void crdtSubscribe(String collection);

    /** Unsubscribe from collection changes. */
    void crdtUnsubscribe(String collection);

    /** Get connected CRDT mesh peers. Returns JSON array. */
    String crdtPeers();

    /** Get CRDT mesh info (peer_id, app_id, mesh_port, peer_count). Returns JSON. */
    String crdtInfo();
}
