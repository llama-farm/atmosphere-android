//! Atmosphere Android JNI Bindings
//!
//! Native JNI interface for the Atmosphere mesh network core library.
//! Provides the bridge between Kotlin and the Rust implementation.

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_long};
use std::sync::{Arc, RwLock};
use std::ptr;
use std::collections::HashMap;
use std::net::TcpStream;
use atmosphere_core::{NodeId, Capability, CapabilityRegistry};
use tungstenite::{connect, Message, WebSocket};
use tungstenite::stream::MaybeTlsStream;
use url::Url;
use parking_lot::Mutex;

// Re-export core types for external use
pub use atmosphere_core;

// ============================================================================
// Peer Structure
// ============================================================================

#[derive(Clone, Debug)]
pub struct Peer {
    pub node_id: String,
    pub name: String,
    pub address: String,
    pub connected: bool,
    pub latency_ms: Option<u32>,
    pub capabilities: Vec<String>,
}

impl Peer {
    fn to_json(&self) -> String {
        let caps_json: Vec<String> = self.capabilities.iter()
            .map(|c| format!("\"{}\"", c))
            .collect();
        format!(
            r#"{{"node_id":"{}","name":"{}","address":"{}","connected":{},"latency_ms":{},"capabilities":[{}]}}"#,
            self.node_id,
            self.name,
            self.address,
            self.connected,
            self.latency_ms.map(|l| l.to_string()).unwrap_or("null".to_string()),
            caps_json.join(",")
        )
    }
}

// ============================================================================
// Mesh Connection State
// ============================================================================

pub struct MeshConnection {
    endpoint: String,
    token: String,
    mesh_id: Option<String>,
    mesh_name: Option<String>,
    ws: Option<WebSocket<MaybeTlsStream<TcpStream>>>,
    connected: bool,
}

impl MeshConnection {
    fn new() -> Self {
        Self {
            endpoint: String::new(),
            token: String::new(),
            mesh_id: None,
            mesh_name: None,
            ws: None,
            connected: false,
        }
    }
}

// ============================================================================
// Android Node Wrapper
// ============================================================================

/// Simplified node wrapper for Android
/// Wraps the core functionality in a sync-friendly way
pub struct AndroidNode {
    node_id: String,
    data_dir: String,
    running: RwLock<bool>,
    capabilities: Arc<CapabilityRegistry>,
    // Map capability names to UUIDs for lookup
    cap_name_to_id: RwLock<HashMap<String, uuid::Uuid>>,
    // Mesh connection state
    mesh: Mutex<MeshConnection>,
    // Discovered/connected peers
    peers: RwLock<Vec<Peer>>,
}

impl AndroidNode {
    pub fn new(node_id: String, data_dir: String) -> Self {
        Self {
            node_id,
            data_dir,
            running: RwLock::new(false),
            capabilities: Arc::new(CapabilityRegistry::new()),
            cap_name_to_id: RwLock::new(HashMap::new()),
            mesh: Mutex::new(MeshConnection::new()),
            peers: RwLock::new(Vec::new()),
        }
    }
    
    pub fn node_id(&self) -> &str {
        &self.node_id
    }
    
    pub fn data_dir(&self) -> &str {
        &self.data_dir
    }
    
    pub fn is_running(&self) -> bool {
        *self.running.read().unwrap()
    }
    
    pub fn start(&self) -> Result<(), String> {
        let mut running = self.running.write().unwrap();
        if *running {
            return Err("Already running".to_string());
        }
        *running = true;
        Ok(())
    }
    
    pub fn stop(&self) {
        // Disconnect from mesh first
        self.disconnect_mesh();
        let mut running = self.running.write().unwrap();
        *running = false;
    }
    
    pub fn status_json(&self) -> String {
        let running = self.is_running();
        let cap_count = self.cap_name_to_id.read().unwrap().len();
        let mesh = self.mesh.lock();
        let peer_count = self.peers.read().unwrap().len();
        format!(
            r#"{{"node_id":"{}","is_running":{},"capabilities_count":{},"connected_peers":{},"mesh_connected":{},"mesh_id":{},"mesh_name":{}}}"#,
            self.node_id, running, cap_count, peer_count,
            mesh.connected,
            mesh.mesh_id.as_ref().map(|s| format!("\"{}\"", s)).unwrap_or("null".to_string()),
            mesh.mesh_name.as_ref().map(|s| format!("\"{}\"", s)).unwrap_or("null".to_string())
        )
    }
    
    pub fn register_capability_json(&self, json: &str) -> Result<(), String> {
        // Parse capability from JSON
        let cap: serde_json::Value = serde_json::from_str(json)
            .map_err(|e| format!("Invalid JSON: {}", e))?;
        
        let name = cap["name"].as_str().ok_or("Missing name")?;
        let _description = cap.get("description").and_then(|v| v.as_str());
        
        // Create capability with auto-generated UUID
        let capability = Capability::new(name, name);
        let uuid = self.capabilities.register(capability);
        
        // Store name -> uuid mapping for lookup
        self.cap_name_to_id.write().unwrap().insert(name.to_string(), uuid);
        
        Ok(())
    }
    
    pub fn route_intent_json(&self, json: &str) -> Result<String, String> {
        // Parse intent
        let intent: serde_json::Value = serde_json::from_str(json)
            .map_err(|e| format!("Invalid JSON: {}", e))?;
        
        let capability_name = intent["capability"].as_str().ok_or("Missing capability")?;
        
        // Check if we have the capability locally by name
        let cap_map = self.cap_name_to_id.read().unwrap();
        if let Some(uuid) = cap_map.get(capability_name) {
            if self.capabilities.get(*uuid).is_some() {
                return Ok(format!(
                    r#"{{"status":"routed_local","node":"{}","capability":"{}"}}"#,
                    self.node_id, capability_name
                ));
            }
        }
        
        Err(format!("Capability not found: {}", capability_name))
    }
    
    /// Join a mesh network via WebSocket
    pub fn join_mesh(&self, endpoint: &str, token: &str) -> Result<(), String> {
        let mut mesh = self.mesh.lock();
        
        // Parse the endpoint URL
        let ws_url = if endpoint.starts_with("ws://") || endpoint.starts_with("wss://") {
            endpoint.to_string()
        } else if endpoint.starts_with("http://") {
            endpoint.replace("http://", "ws://")
        } else if endpoint.starts_with("https://") {
            endpoint.replace("https://", "wss://")
        } else {
            format!("ws://{}", endpoint)
        };
        
        // Add /api/ws if not present
        let ws_url = if ws_url.contains("/api/ws") {
            ws_url
        } else {
            format!("{}/api/ws", ws_url.trim_end_matches('/'))
        };
        
        // Parse URL
        let url = Url::parse(&ws_url)
            .map_err(|e| format!("Invalid URL: {}", e))?;
        
        // Connect with timeout
        let (mut socket, _response): (WebSocket<MaybeTlsStream<TcpStream>>, _) = connect(url)
            .map_err(|e| format!("WebSocket connection failed: {}", e))?;
        
        // Send join message
        let join_msg = serde_json::json!({
            "type": "join",
            "token": token,
            "node_id": self.node_id,
            "capabilities": self.get_capability_names()
        });
        
        socket.send(Message::Text(join_msg.to_string()))
            .map_err(|e| format!("Failed to send join message: {}", e))?;
        
        // Wait for response
        let response = socket.read()
            .map_err(|e| format!("Failed to read response: {}", e))?;
        
        if let Message::Text(text) = response {
            let resp: serde_json::Value = serde_json::from_str(&text)
                .map_err(|e| format!("Invalid response JSON: {}", e))?;
            
            if resp["type"].as_str() == Some("joined") || resp["type"].as_str() == Some("welcome") {
                mesh.endpoint = endpoint.to_string();
                mesh.token = token.to_string();
                mesh.mesh_id = resp["mesh_id"].as_str().map(|s| s.to_string());
                mesh.mesh_name = resp["mesh_name"].as_str().map(|s| s.to_string());
                mesh.ws = Some(socket);
                mesh.connected = true;
                
                return Ok(());
            } else if resp["type"].as_str() == Some("error") {
                return Err(resp["message"].as_str().unwrap_or("Unknown error").to_string());
            }
        }
        
        Err("Unexpected response from server".to_string())
    }
    
    /// Disconnect from mesh
    pub fn disconnect_mesh(&self) {
        let mut mesh = self.mesh.lock();
        if let Some(mut ws) = mesh.ws.take() {
            let _ = ws.close(None);
        }
        mesh.connected = false;
        mesh.mesh_id = None;
        mesh.mesh_name = None;
        
        // Clear peers
        self.peers.write().unwrap().clear();
    }
    
    /// Discover peers on the mesh
    pub fn discover_peers(&self) -> Result<(), String> {
        let mut mesh = self.mesh.lock();
        
        if !mesh.connected {
            return Err("Not connected to mesh".to_string());
        }
        
        let ws = mesh.ws.as_mut().ok_or("WebSocket not available")?;
        
        // Send discover message
        let discover_msg = serde_json::json!({
            "type": "discover",
            "node_id": self.node_id
        });
        
        ws.send(Message::Text(discover_msg.to_string()))
            .map_err(|e| format!("Failed to send discover message: {}", e))?;
        
        // Read response (with timeout)
        let response = ws.read()
            .map_err(|e| format!("Failed to read discover response: {}", e))?;
        
        if let Message::Text(text) = response {
            let resp: serde_json::Value = serde_json::from_str(&text)
                .map_err(|e| format!("Invalid response JSON: {}", e))?;
            
            if resp["type"].as_str() == Some("peers") {
                if let Some(peers_arr) = resp["peers"].as_array() {
                    let mut peers = self.peers.write().unwrap();
                    peers.clear();
                    
                    for p in peers_arr {
                        peers.push(Peer {
                            node_id: p["node_id"].as_str().unwrap_or("unknown").to_string(),
                            name: p["name"].as_str().unwrap_or("Unknown Node").to_string(),
                            address: p["address"].as_str().unwrap_or("").to_string(),
                            connected: p["connected"].as_bool().unwrap_or(false),
                            latency_ms: p["latency_ms"].as_u64().map(|l| l as u32),
                            capabilities: p["capabilities"].as_array()
                                .map(|arr| arr.iter()
                                    .filter_map(|v| v.as_str().map(|s| s.to_string()))
                                    .collect())
                                .unwrap_or_default(),
                        });
                    }
                }
            }
        }
        
        Ok(())
    }
    
    /// Get peers as JSON array
    pub fn get_peers_json(&self) -> String {
        let peers = self.peers.read().unwrap();
        let peers_json: Vec<String> = peers.iter().map(|p| p.to_json()).collect();
        format!("[{}]", peers_json.join(","))
    }
    
    /// Connect to a specific peer by address
    pub fn connect_to_peer(&self, address: &str) -> Result<(), String> {
        let mut peers = self.peers.write().unwrap();
        
        // Find and update peer status
        for peer in peers.iter_mut() {
            if peer.address == address {
                peer.connected = true;
                return Ok(());
            }
        }
        
        // If not found, add as new peer
        peers.push(Peer {
            node_id: format!("peer_{}", address.replace(".", "_").replace(":", "_")),
            name: format!("Peer at {}", address),
            address: address.to_string(),
            connected: true,
            latency_ms: None,
            capabilities: vec![],
        });
        
        Ok(())
    }
    
    /// Send a gossip message to the mesh
    pub fn send_gossip(&self, message: &str) -> Result<(), String> {
        let mut mesh = self.mesh.lock();
        
        if !mesh.connected {
            return Err("Not connected to mesh".to_string());
        }
        
        let ws = mesh.ws.as_mut().ok_or("WebSocket not available")?;
        
        let gossip_msg = serde_json::json!({
            "type": "gossip",
            "from": self.node_id,
            "payload": message
        });
        
        ws.send(Message::Text(gossip_msg.to_string()))
            .map_err(|e| format!("Failed to send gossip: {}", e))?;
        
        Ok(())
    }
    
    fn get_capability_names(&self) -> Vec<String> {
        self.cap_name_to_id.read().unwrap().keys().cloned().collect()
    }
}

/// Opaque handle to an AndroidNode
type NodeHandle = *mut AndroidNode;

// ============================================================================
// Static Functions (called from Kotlin companion object)
// ============================================================================

/// Create a new Atmosphere node
/// Returns a handle (pointer) to the node, or 0 on failure
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_00024Companion_nativeCreateNode(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
    node_id: *const c_char,
    data_dir: *const c_char,
) -> c_long {
    // Safety: Convert C strings to Rust strings
    let node_id = unsafe {
        if node_id.is_null() {
            return 0;
        }
        match CStr::from_ptr(node_id).to_str() {
            Ok(s) => s.to_string(),
            Err(_) => return 0,
        }
    };
    
    let data_dir = unsafe {
        if data_dir.is_null() {
            return 0;
        }
        match CStr::from_ptr(data_dir).to_str() {
            Ok(s) => s.to_string(),
            Err(_) => return 0,
        }
    };
    
    // Create the node
    let node = AndroidNode::new(node_id, data_dir);
    let ptr = Box::into_raw(Box::new(node));
    
    ptr as c_long
}

/// Generate a new random node ID
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_00024Companion_nativeGenerateNodeId(
    _env: *mut std::ffi::c_void,
    _class: *mut std::ffi::c_void,
) -> *mut c_char {
    let id = NodeId::new().to_string();
    match CString::new(id) {
        Ok(cstr) => cstr.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

// ============================================================================
// Instance Methods (called on node handle)
// ============================================================================

/// Start the node
/// Returns 0 on success, non-zero on error
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeStart(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) -> i32 {
    let node = unsafe {
        if handle == 0 {
            return -1;
        }
        &*(handle as NodeHandle)
    };
    
    match node.start() {
        Ok(_) => 0,
        Err(_) => -2,
    }
}

/// Stop the node
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeStop(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) {
    let node = unsafe {
        if handle == 0 {
            return;
        }
        &*(handle as NodeHandle)
    };
    
    node.stop();
}

/// Check if node is running
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeIsRunning(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) -> bool {
    let node = unsafe {
        if handle == 0 {
            return false;
        }
        &*(handle as NodeHandle)
    };
    
    node.is_running()
}

/// Get node ID
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeNodeId(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) -> *mut c_char {
    let node = unsafe {
        if handle == 0 {
            return ptr::null_mut();
        }
        &*(handle as NodeHandle)
    };
    
    match CString::new(node.node_id()) {
        Ok(cstr) => cstr.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Get data directory
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeDataDir(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) -> *mut c_char {
    let node = unsafe {
        if handle == 0 {
            return ptr::null_mut();
        }
        &*(handle as NodeHandle)
    };
    
    match CString::new(node.data_dir()) {
        Ok(cstr) => cstr.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Get status as JSON
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeStatusJson(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) -> *mut c_char {
    let node = unsafe {
        if handle == 0 {
            return ptr::null_mut();
        }
        &*(handle as NodeHandle)
    };
    
    let status = node.status_json();
    match CString::new(status) {
        Ok(cstr) => cstr.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Register a capability
/// Returns 0 on success, non-zero on error
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeRegisterCapability(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
    json: *const c_char,
) -> i32 {
    let node = unsafe {
        if handle == 0 {
            return -1;
        }
        &*(handle as NodeHandle)
    };
    
    let json_str = unsafe {
        if json.is_null() {
            return -2;
        }
        match CStr::from_ptr(json).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        }
    };
    
    match node.register_capability_json(json_str) {
        Ok(_) => 0,
        Err(_) => -4,
    }
}

/// Route an intent
/// Returns JSON result or "ERROR:message" on failure
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeRouteIntent(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
    json: *const c_char,
) -> *mut c_char {
    let node = unsafe {
        if handle == 0 {
            return CString::new("ERROR:Invalid handle").unwrap().into_raw();
        }
        &*(handle as NodeHandle)
    };
    
    let json_str = unsafe {
        if json.is_null() {
            return CString::new("ERROR:Null JSON").unwrap().into_raw();
        }
        match CStr::from_ptr(json).to_str() {
            Ok(s) => s,
            Err(_) => return CString::new("ERROR:Invalid UTF-8").unwrap().into_raw(),
        }
    };
    
    match node.route_intent_json(json_str) {
        Ok(result) => {
            match CString::new(result) {
                Ok(cstr) => cstr.into_raw(),
                Err(_) => CString::new("ERROR:Encoding error").unwrap().into_raw(),
            }
        }
        Err(e) => {
            let msg = format!("ERROR:{}", e);
            CString::new(msg).unwrap().into_raw()
        }
    }
}

// ============================================================================
// NEW: Networking Functions
// ============================================================================

/// Join a mesh network
/// Returns 0 on success, non-zero on error
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeJoinMesh(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
    endpoint: *const c_char,
    token: *const c_char,
) -> i32 {
    let node = unsafe {
        if handle == 0 {
            return -1;
        }
        &*(handle as NodeHandle)
    };
    
    let endpoint_str = unsafe {
        if endpoint.is_null() {
            return -2;
        }
        match CStr::from_ptr(endpoint).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        }
    };
    
    let token_str = unsafe {
        if token.is_null() {
            return -4;
        }
        match CStr::from_ptr(token).to_str() {
            Ok(s) => s,
            Err(_) => return -5,
        }
    };
    
    match node.join_mesh(endpoint_str, token_str) {
        Ok(_) => 0,
        Err(_) => -6,
    }
}

/// Discover peers on the mesh
/// Returns JSON array of peers
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeDiscoverPeers(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) -> *mut c_char {
    let node = unsafe {
        if handle == 0 {
            return CString::new("[]").unwrap().into_raw();
        }
        &*(handle as NodeHandle)
    };
    
    // Try to discover, then return current peers
    let _ = node.discover_peers();
    let peers_json = node.get_peers_json();
    
    match CString::new(peers_json) {
        Ok(cstr) => cstr.into_raw(),
        Err(_) => CString::new("[]").unwrap().into_raw(),
    }
}

/// Connect to a specific peer
/// Returns 0 on success, non-zero on error
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeConnectToPeer(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
    address: *const c_char,
) -> i32 {
    let node = unsafe {
        if handle == 0 {
            return -1;
        }
        &*(handle as NodeHandle)
    };
    
    let address_str = unsafe {
        if address.is_null() {
            return -2;
        }
        match CStr::from_ptr(address).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        }
    };
    
    match node.connect_to_peer(address_str) {
        Ok(_) => 0,
        Err(_) => -4,
    }
}

/// Get connected peers as JSON array
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeGetPeers(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) -> *mut c_char {
    let node = unsafe {
        if handle == 0 {
            return CString::new("[]").unwrap().into_raw();
        }
        &*(handle as NodeHandle)
    };
    
    let peers_json = node.get_peers_json();
    
    match CString::new(peers_json) {
        Ok(cstr) => cstr.into_raw(),
        Err(_) => CString::new("[]").unwrap().into_raw(),
    }
}

/// Send a gossip message to the mesh
/// Returns 0 on success, non-zero on error
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeSendGossip(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
    message: *const c_char,
) -> i32 {
    let node = unsafe {
        if handle == 0 {
            return -1;
        }
        &*(handle as NodeHandle)
    };
    
    let message_str = unsafe {
        if message.is_null() {
            return -2;
        }
        match CStr::from_ptr(message).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        }
    };
    
    match node.send_gossip(message_str) {
        Ok(_) => 0,
        Err(_) => -4,
    }
}

/// Disconnect from mesh
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeDisconnectMesh(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) {
    let node = unsafe {
        if handle == 0 {
            return;
        }
        &*(handle as NodeHandle)
    };
    
    node.disconnect_mesh();
}

/// Destroy/free a node handle
#[no_mangle]
pub extern "C" fn Java_com_llamafarm_atmosphere_bindings_AtmosphereNode_nativeDestroy(
    _env: *mut std::ffi::c_void,
    _obj: *mut std::ffi::c_void,
    handle: c_long,
) {
    if handle != 0 {
        unsafe {
            // Reconstruct and drop the Box to free memory
            let _ = Box::from_raw(handle as NodeHandle);
        }
    }
}

// ============================================================================
// JNI Lifecycle
// ============================================================================

/// Called when the library is loaded
#[no_mangle]
pub extern "C" fn JNI_OnLoad(
    _vm: *mut std::ffi::c_void,
    _reserved: *mut std::ffi::c_void,
) -> i32 {
    // JNI version 1.6
    0x00010006
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_android_node() {
        let node = AndroidNode::new("test-node".to_string(), "/tmp".to_string());
        assert!(!node.is_running());
        
        node.start().unwrap();
        assert!(node.is_running());
        
        let status = node.status_json();
        assert!(status.contains("\"is_running\":true"));
        
        node.stop();
        assert!(!node.is_running());
    }
    
    #[test]
    fn test_generate_node_id() {
        let id = NodeId::new();
        assert!(!id.to_string().is_empty());
    }
    
    #[test]
    fn test_peers_json() {
        let node = AndroidNode::new("test-node".to_string(), "/tmp".to_string());
        
        // Empty peers
        let json = node.get_peers_json();
        assert_eq!(json, "[]");
        
        // Add a peer
        node.connect_to_peer("192.168.1.1:11451").unwrap();
        let json = node.get_peers_json();
        assert!(json.contains("192.168.1.1:11451"));
    }
}
