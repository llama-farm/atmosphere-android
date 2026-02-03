//! Atmosphere Node
//!
//! The main entry point for the Atmosphere mesh network.
//! AtmosphereNode manages the node's identity, connections, and services.

use std::sync::Arc;
use tokio::sync::RwLock;
use uuid::Uuid;
use ed25519_dalek::{SigningKey, VerifyingKey};
use serde::{Deserialize, Serialize};
use rand::rngs::OsRng;

use crate::capability::CapabilityRegistry;
use crate::cost::CostCollector;
use crate::error::{AtmosphereError, Result};
use crate::intent::IntentRouter;
use crate::mesh::MeshClient;
use crate::metrics::PlatformMetrics;

/// Unique identifier for a node in the mesh network
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct NodeId(pub Uuid);

impl NodeId {
    /// Generate a new random node ID
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }

    /// Create from an existing UUID
    pub fn from_uuid(uuid: Uuid) -> Self {
        Self(uuid)
    }

    /// Get the inner UUID
    pub fn as_uuid(&self) -> &Uuid {
        &self.0
    }
}

impl Default for NodeId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for NodeId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

/// Configuration for an Atmosphere node
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeConfig {
    /// Human-readable name for this node
    pub name: String,

    /// Bootstrap peers to connect to on startup
    pub bootstrap_peers: Vec<String>,

    /// Port to listen on for incoming connections
    pub listen_port: u16,

    /// Maximum number of peer connections
    pub max_peers: usize,

    /// Interval in seconds between gossip rounds
    pub gossip_interval_secs: u64,

    /// Enable cost-based routing
    pub cost_aware_routing: bool,
}

impl Default for NodeConfig {
    fn default() -> Self {
        Self {
            name: "atmosphere-node".to_string(),
            bootstrap_peers: Vec::new(),
            listen_port: 8765,
            max_peers: 50,
            gossip_interval_secs: 30,
            cost_aware_routing: true,
        }
    }
}

/// Internal state of the node
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NodeState {
    Stopped,
    Starting,
    Running,
    Stopping,
}

/// Main Atmosphere node
///
/// This is the primary interface for participating in the Atmosphere mesh network.
pub struct AtmosphereNode {
    /// Unique node identifier
    id: NodeId,

    /// Node configuration
    config: NodeConfig,

    /// Ed25519 signing key for this node
    signing_key: SigningKey,

    /// Current state
    state: Arc<RwLock<NodeState>>,

    /// Capability registry
    capabilities: Arc<CapabilityRegistry>,

    /// Cost collector
    cost_collector: Arc<CostCollector>,

    /// Intent router
    intent_router: Arc<IntentRouter>,

    /// Mesh client (optional, created on start)
    mesh_client: Arc<RwLock<Option<MeshClient>>>,
}

impl AtmosphereNode {
    /// Create a new Atmosphere node with the given configuration
    pub fn new(config: NodeConfig, metrics: Arc<dyn PlatformMetrics>) -> Self {
        let id = NodeId::new();
        let signing_key = SigningKey::generate(&mut OsRng);
        let capabilities = Arc::new(CapabilityRegistry::new());
        let cost_collector = Arc::new(CostCollector::new(metrics));
        let intent_router = Arc::new(IntentRouter::new(
            Arc::clone(&capabilities),
            Arc::clone(&cost_collector),
        ));

        Self {
            id,
            config,
            signing_key,
            state: Arc::new(RwLock::new(NodeState::Stopped)),
            capabilities,
            cost_collector,
            intent_router,
            mesh_client: Arc::new(RwLock::new(None)),
        }
    }

    /// Get the node's unique identifier
    pub fn id(&self) -> NodeId {
        self.id
    }

    /// Get the node's public key
    pub fn public_key(&self) -> VerifyingKey {
        self.signing_key.verifying_key()
    }

    /// Get the node's configuration
    pub fn config(&self) -> &NodeConfig {
        &self.config
    }

    /// Get the capability registry
    pub fn capabilities(&self) -> &Arc<CapabilityRegistry> {
        &self.capabilities
    }

    /// Get the cost collector
    pub fn cost_collector(&self) -> &Arc<CostCollector> {
        &self.cost_collector
    }

    /// Get the intent router
    pub fn intent_router(&self) -> &Arc<IntentRouter> {
        &self.intent_router
    }

    /// Get the current node state
    pub async fn state(&self) -> NodeState {
        *self.state.read().await
    }

    /// Start the node and begin participating in the mesh
    pub async fn start(&self) -> Result<()> {
        let mut state = self.state.write().await;
        
        if *state != NodeState::Stopped {
            return Err(AtmosphereError::InvalidConfig(
                "Node is already running or starting".to_string(),
            ));
        }

        *state = NodeState::Starting;
        tracing::info!(node_id = %self.id, "Starting Atmosphere node");

        // Create mesh client
        let mesh_client = MeshClient::new(
            self.id,
            self.signing_key.clone(),
            self.config.clone(),
        );

        // Store mesh client
        *self.mesh_client.write().await = Some(mesh_client);

        // Connect to bootstrap peers
        if let Some(ref client) = *self.mesh_client.read().await {
            for peer_addr in &self.config.bootstrap_peers {
                if let Err(e) = client.connect(peer_addr).await {
                    tracing::warn!(peer = %peer_addr, error = %e, "Failed to connect to bootstrap peer");
                }
            }
        }

        *state = NodeState::Running;
        tracing::info!(node_id = %self.id, "Atmosphere node started");
        
        Ok(())
    }

    /// Stop the node and disconnect from the mesh
    pub async fn stop(&self) -> Result<()> {
        let mut state = self.state.write().await;

        if *state != NodeState::Running {
            return Err(AtmosphereError::NotInitialized);
        }

        *state = NodeState::Stopping;
        tracing::info!(node_id = %self.id, "Stopping Atmosphere node");

        // Disconnect mesh client
        if let Some(ref client) = *self.mesh_client.read().await {
            client.disconnect_all().await;
        }

        *self.mesh_client.write().await = None;
        *state = NodeState::Stopped;
        
        tracing::info!(node_id = %self.id, "Atmosphere node stopped");
        Ok(())
    }

    /// Sign data with this node's private key
    pub fn sign(&self, data: &[u8]) -> ed25519_dalek::Signature {
        use ed25519_dalek::Signer;
        self.signing_key.sign(data)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metrics::MockMetrics;

    #[test]
    fn test_node_id_generation() {
        let id1 = NodeId::new();
        let id2 = NodeId::new();
        assert_ne!(id1, id2);
    }

    #[test]
    fn test_node_id_display() {
        let id = NodeId::from_uuid(Uuid::nil());
        assert_eq!(id.to_string(), "00000000-0000-0000-0000-000000000000");
    }

    #[test]
    fn test_node_config_default() {
        let config = NodeConfig::default();
        assert_eq!(config.listen_port, 8765);
        assert_eq!(config.max_peers, 50);
        assert!(config.cost_aware_routing);
    }

    #[tokio::test]
    async fn test_node_creation() {
        let config = NodeConfig::default();
        let metrics = Arc::new(MockMetrics::default());
        let node = AtmosphereNode::new(config, metrics);
        
        assert_eq!(node.state().await, NodeState::Stopped);
        assert!(!node.id().0.is_nil());
    }

    #[tokio::test]
    async fn test_node_start_stop() {
        let config = NodeConfig::default();
        let metrics = Arc::new(MockMetrics::default());
        let node = AtmosphereNode::new(config, metrics);

        // Start the node
        node.start().await.unwrap();
        assert_eq!(node.state().await, NodeState::Running);

        // Can't start again while running
        assert!(node.start().await.is_err());

        // Stop the node
        node.stop().await.unwrap();
        assert_eq!(node.state().await, NodeState::Stopped);
    }

    #[test]
    fn test_node_signing() {
        let config = NodeConfig::default();
        let metrics = Arc::new(MockMetrics::default());
        let node = AtmosphereNode::new(config, metrics);

        let data = b"test message";
        let signature = node.sign(data);

        // Verify signature
        use ed25519_dalek::Verifier;
        assert!(node.public_key().verify(data, &signature).is_ok());
    }
}
