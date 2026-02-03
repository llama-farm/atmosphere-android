//! Capability Registry
//!
//! Manages the capabilities that a node can provide to the mesh network.
//! Capabilities represent services like camera access, compute resources, etc.

use std::collections::HashMap;
use std::sync::RwLock;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::{AtmosphereError, Result};
use crate::node::NodeId;

/// A capability that a node can provide
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Capability {
    /// Unique identifier for this capability instance
    pub id: Uuid,

    /// Type of capability (e.g., "camera", "compute", "storage")
    pub capability_type: String,

    /// Human-readable name
    pub name: String,

    /// Detailed description
    pub description: String,

    /// Version of this capability
    pub version: String,

    /// Whether this capability is currently available
    pub available: bool,

    /// Cost weight for using this capability (higher = more expensive)
    pub cost_weight: f32,

    /// Custom metadata
    pub metadata: HashMap<String, String>,
}

impl Capability {
    /// Create a new capability
    pub fn new(capability_type: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            id: Uuid::new_v4(),
            capability_type: capability_type.into(),
            name: name.into(),
            description: String::new(),
            version: "1.0.0".to_string(),
            available: true,
            cost_weight: 1.0,
            metadata: HashMap::new(),
        }
    }

    /// Set the description
    pub fn with_description(mut self, description: impl Into<String>) -> Self {
        self.description = description.into();
        self
    }

    /// Set the version
    pub fn with_version(mut self, version: impl Into<String>) -> Self {
        self.version = version.into();
        self
    }

    /// Set the cost weight
    pub fn with_cost_weight(mut self, weight: f32) -> Self {
        self.cost_weight = weight;
        self
    }

    /// Add metadata
    pub fn with_metadata(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.metadata.insert(key.into(), value.into());
        self
    }
}

/// Registry of capabilities for a node
#[derive(Debug, Default)]
pub struct CapabilityRegistry {
    /// Local capabilities (owned by this node)
    local: RwLock<HashMap<Uuid, Capability>>,

    /// Remote capabilities (advertised by peers)
    remote: RwLock<HashMap<NodeId, Vec<Capability>>>,
}

impl CapabilityRegistry {
    /// Create a new empty registry
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a local capability
    pub fn register(&self, capability: Capability) -> Uuid {
        let id = capability.id;
        self.local.write().unwrap().insert(id, capability);
        tracing::debug!(capability_id = %id, "Registered local capability");
        id
    }

    /// Unregister a local capability
    pub fn unregister(&self, id: Uuid) -> Option<Capability> {
        let removed = self.local.write().unwrap().remove(&id);
        if removed.is_some() {
            tracing::debug!(capability_id = %id, "Unregistered local capability");
        }
        removed
    }

    /// Get a local capability by ID
    pub fn get(&self, id: Uuid) -> Option<Capability> {
        self.local.read().unwrap().get(&id).cloned()
    }

    /// Get all local capabilities
    pub fn list_local(&self) -> Vec<Capability> {
        self.local.read().unwrap().values().cloned().collect()
    }

    /// Find local capabilities by type
    pub fn find_local_by_type(&self, capability_type: &str) -> Vec<Capability> {
        self.local
            .read()
            .unwrap()
            .values()
            .filter(|c| c.capability_type == capability_type && c.available)
            .cloned()
            .collect()
    }

    /// Update remote capabilities for a peer
    pub fn update_remote(&self, node_id: NodeId, capabilities: Vec<Capability>) {
        self.remote.write().unwrap().insert(node_id, capabilities);
        tracing::debug!(node_id = %node_id, "Updated remote capabilities");
    }

    /// Remove a peer's capabilities
    pub fn remove_remote(&self, node_id: &NodeId) {
        self.remote.write().unwrap().remove(node_id);
    }

    /// Get capabilities for a specific peer
    pub fn get_remote(&self, node_id: &NodeId) -> Vec<Capability> {
        self.remote
            .read()
            .unwrap()
            .get(node_id)
            .cloned()
            .unwrap_or_default()
    }

    /// Find all peers with a specific capability type
    pub fn find_peers_with_capability(&self, capability_type: &str) -> Vec<(NodeId, Capability)> {
        self.remote
            .read()
            .unwrap()
            .iter()
            .flat_map(|(node_id, caps)| {
                caps.iter()
                    .filter(|c| c.capability_type == capability_type && c.available)
                    .map(|c| (*node_id, c.clone()))
            })
            .collect()
    }

    /// Check if we have a local capability of the given type
    pub fn has_local_capability(&self, capability_type: &str) -> bool {
        self.local
            .read()
            .unwrap()
            .values()
            .any(|c| c.capability_type == capability_type && c.available)
    }

    /// Get the count of all capabilities (local + remote)
    pub fn total_count(&self) -> usize {
        let local_count = self.local.read().unwrap().len();
        let remote_count: usize = self.remote.read().unwrap().values().map(|v| v.len()).sum();
        local_count + remote_count
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_capability_creation() {
        let cap = Capability::new("camera", "Front Camera")
            .with_description("High resolution front-facing camera")
            .with_version("2.0.0")
            .with_cost_weight(1.5)
            .with_metadata("resolution", "4K");

        assert_eq!(cap.capability_type, "camera");
        assert_eq!(cap.name, "Front Camera");
        assert_eq!(cap.version, "2.0.0");
        assert!((cap.cost_weight - 1.5).abs() < f32::EPSILON);
        assert_eq!(cap.metadata.get("resolution"), Some(&"4K".to_string()));
    }

    #[test]
    fn test_registry_register_unregister() {
        let registry = CapabilityRegistry::new();
        
        let cap = Capability::new("compute", "GPU Compute");
        let id = registry.register(cap.clone());
        
        assert!(registry.get(id).is_some());
        assert_eq!(registry.list_local().len(), 1);
        
        registry.unregister(id);
        assert!(registry.get(id).is_none());
        assert_eq!(registry.list_local().len(), 0);
    }

    #[test]
    fn test_registry_find_by_type() {
        let registry = CapabilityRegistry::new();
        
        registry.register(Capability::new("camera", "Front Camera"));
        registry.register(Capability::new("camera", "Back Camera"));
        registry.register(Capability::new("compute", "CPU"));

        let cameras = registry.find_local_by_type("camera");
        assert_eq!(cameras.len(), 2);

        let compute = registry.find_local_by_type("compute");
        assert_eq!(compute.len(), 1);

        let storage = registry.find_local_by_type("storage");
        assert_eq!(storage.len(), 0);
    }

    #[test]
    fn test_registry_remote_capabilities() {
        let registry = CapabilityRegistry::new();
        let peer_id = NodeId::new();

        let caps = vec![
            Capability::new("camera", "Peer Camera"),
            Capability::new("storage", "Peer Storage"),
        ];

        registry.update_remote(peer_id, caps);
        
        let peer_caps = registry.get_remote(&peer_id);
        assert_eq!(peer_caps.len(), 2);

        let camera_peers = registry.find_peers_with_capability("camera");
        assert_eq!(camera_peers.len(), 1);
        assert_eq!(camera_peers[0].0, peer_id);
    }

    #[test]
    fn test_registry_has_capability() {
        let registry = CapabilityRegistry::new();
        
        assert!(!registry.has_local_capability("camera"));
        
        registry.register(Capability::new("camera", "Test Camera"));
        
        assert!(registry.has_local_capability("camera"));
        assert!(!registry.has_local_capability("compute"));
    }
}
