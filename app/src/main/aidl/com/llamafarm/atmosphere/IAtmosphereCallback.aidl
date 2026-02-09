// IAtmosphereCallback.aidl
// Callback interface for receiving mesh events
package com.llamafarm.atmosphere;

interface IAtmosphereCallback {
    /**
     * Called when a capability emits an event.
     * @param capabilityId The capability that emitted
     * @param eventJson JSON payload of the event
     */
    void onCapabilityEvent(String capabilityId, String eventJson);
    
    /**
     * Called when mesh topology changes.
     * @param statusJson JSON with new mesh status
     */
    void onMeshUpdate(String statusJson);
    
    /**
     * Called when cost metrics change significantly.
     * @param costsJson JSON with new cost values
     */
    void onCostUpdate(String costsJson);
    
    /**
     * Called when a streaming response chunk is available.
     * For streaming chat completions and long-running operations.
     * @param requestId The original request ID
     * @param chunkJson JSON with the chunk data
     * @param isFinal True if this is the final chunk
     */
    void onStreamChunk(String requestId, String chunkJson, boolean isFinal);
    
    /**
     * Called when an error occurs asynchronously.
     * @param errorCode Error code string
     * @param errorMessage Human-readable error message
     */
    void onError(String errorCode, String errorMessage);
}
