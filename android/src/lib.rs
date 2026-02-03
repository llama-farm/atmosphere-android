//! Atmosphere Android JNI Bindings
//!
//! Native JNI interface for the Atmosphere mesh network core library.
//! Provides the bridge between Kotlin and the Rust implementation.

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_long};
use std::sync::{Arc, RwLock};
use std::ptr;

use atmosphere_core::{NodeId, Capability, CapabilityRegistry};
use std::collections::HashMap;
use uuid::Uuid;

// Re-export core types for external use
pub use atmosphere_core;

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
}

impl AndroidNode {
    pub fn new(node_id: String, data_dir: String) -> Self {
        Self {
            node_id,
            data_dir,
            running: RwLock::new(false),
            capabilities: Arc::new(CapabilityRegistry::new()),
            cap_name_to_id: RwLock::new(HashMap::new()),
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
        let mut running = self.running.write().unwrap();
        *running = false;
    }
    
    pub fn status_json(&self) -> String {
        let running = self.is_running();
        let cap_count = self.cap_name_to_id.read().unwrap().len();
        format!(
            r#"{{"node_id":"{}","is_running":{},"capabilities_count":{},"connected_peers":0}}"#,
            self.node_id, running, cap_count
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
}
