//! Mesh Client
//!
//! Gossip protocol implementation for peer discovery and communication.
//! Uses WebSocket connections for transport.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};
use ed25519_dalek::SigningKey;

use crate::capability::Capability;
use crate::cost::NodeCost;
use crate::error::{AtmosphereError, Result};
use crate::node::{NodeConfig, NodeId};

/// Information about a connected peer
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerInfo {
    /// Peer's node ID
    pub node_id: NodeId,

    /// Peer's public key (hex encoded)
    pub public_key: String,

    /// Peer's display name
    pub name: String,

    /// Connection address
    pub address: String,

    /// When we connected to this peer
    pub connected_at_ms: u64,

    /// Last message received timestamp
    pub last_seen_ms: u64,

    /// Peer's advertised capabilities
    pub capabilities: Vec<Capability>,

    /// Peer's current cost
    pub cost: Option<NodeCost>,
}

/// Types of gossip messages
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum GossipMessage {
    /// Hello message for initial handshake
    Hello {
        node_id: NodeId,
        public_key: String,
        name: String,
        capabilities: Vec<Capability>,
    },

    /// Advertise capabilities
    Capabilities {
        node_id: NodeId,
        capabilities: Vec<Capability>,
    },

    /// Advertise current cost
    Cost {
        node_id: NodeId,
        cost: NodeCost,
    },

    /// Request intent routing
    IntentRequest {
        intent_id: String,
        capability_type: String,
        payload: String,
    },

    /// Response to intent request
    IntentResponse {
        intent_id: String,
        success: bool,
        result: Option<String>,
        error: Option<String>,
    },

    /// Peer list for discovery
    PeerList {
        peers: Vec<String>, // addresses
    },

    /// Ping for keepalive
    Ping {
        timestamp_ms: u64,
    },

    /// Pong response
    Pong {
        timestamp_ms: u64,
    },

    /// Goodbye message before disconnect
    Goodbye {
        node_id: NodeId,
        reason: String,
    },
}

/// Connection state for a peer
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    Connecting,
    Connected,
    Disconnecting,
    Disconnected,
}

/// Internal peer connection tracking
struct PeerConnection {
    info: PeerInfo,
    state: ConnectionState,
}

/// Mesh network client
///
/// Manages connections to peers and gossip protocol communication.
pub struct MeshClient {
    /// Our node ID
    node_id: NodeId,

    /// Our signing key
    #[allow(dead_code)]
    signing_key: SigningKey,

    /// Configuration
    config: NodeConfig,

    /// Connected peers
    peers: Arc<RwLock<HashMap<NodeId, PeerConnection>>>,

    /// Running state
    running: Arc<RwLock<bool>>,
}

impl MeshClient {
    /// Create a new mesh client
    pub fn new(node_id: NodeId, signing_key: SigningKey, config: NodeConfig) -> Self {
        Self {
            node_id,
            signing_key,
            config,
            peers: Arc::new(RwLock::new(HashMap::new())),
            running: Arc::new(RwLock::new(false)),
        }
    }

    /// Connect to a peer at the given address
    pub async fn connect(&self, address: &str) -> Result<NodeId> {
        tracing::info!(address = %address, "Connecting to peer");

        // For now, we create a placeholder connection
        // Real implementation would use tokio-tungstenite
        let peer_id = NodeId::new(); // Would be received from handshake

        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        let peer = PeerConnection {
            info: PeerInfo {
                node_id: peer_id,
                public_key: String::new(),
                name: format!("peer-{}", &peer_id.0.to_string()[..8]),
                address: address.to_string(),
                connected_at_ms: now_ms,
                last_seen_ms: now_ms,
                capabilities: Vec::new(),
                cost: None,
            },
            state: ConnectionState::Connected,
        };

        self.peers.write().await.insert(peer_id, peer);
        tracing::info!(peer_id = %peer_id, "Connected to peer");

        Ok(peer_id)
    }

    /// Disconnect from a specific peer
    pub async fn disconnect(&self, node_id: &NodeId) -> Result<()> {
        if let Some(mut peer) = self.peers.write().await.remove(node_id) {
            peer.state = ConnectionState::Disconnected;
            tracing::info!(peer_id = %node_id, "Disconnected from peer");
        }
        Ok(())
    }

    /// Disconnect from all peers
    pub async fn disconnect_all(&self) {
        let mut peers = self.peers.write().await;
        for (id, _) in peers.drain() {
            tracing::info!(peer_id = %id, "Disconnected from peer");
        }
    }

    /// Get list of connected peers
    pub async fn get_peers(&self) -> Vec<PeerInfo> {
        self.peers
            .read()
            .await
            .values()
            .filter(|p| p.state == ConnectionState::Connected)
            .map(|p| p.info.clone())
            .collect()
    }

    /// Get a specific peer's info
    pub async fn get_peer(&self, node_id: &NodeId) -> Option<PeerInfo> {
        self.peers.read().await.get(node_id).map(|p| p.info.clone())
    }

    /// Get the number of connected peers
    pub async fn peer_count(&self) -> usize {
        self.peers
            .read()
            .await
            .values()
            .filter(|p| p.state == ConnectionState::Connected)
            .count()
    }

    /// Send a gossip message to a specific peer
    pub async fn send_to(&self, node_id: &NodeId, message: GossipMessage) -> Result<()> {
        let peers = self.peers.read().await;
        
        if !peers.contains_key(node_id) {
            return Err(AtmosphereError::Network(format!(
                "Peer not connected: {}",
                node_id
            )));
        }

        // Real implementation would serialize and send via WebSocket
        let _json = serde_json::to_string(&message)?;
        tracing::debug!(peer_id = %node_id, message_type = ?std::mem::discriminant(&message), "Sent message");

        Ok(())
    }

    /// Broadcast a message to all connected peers
    pub async fn broadcast(&self, message: GossipMessage) -> Result<usize> {
        let peers = self.peers.read().await;
        let mut sent = 0;

        let _json = serde_json::to_string(&message)?;

        for (node_id, peer) in peers.iter() {
            if peer.state == ConnectionState::Connected {
                tracing::debug!(peer_id = %node_id, "Broadcasting message");
                sent += 1;
            }
        }

        Ok(sent)
    }

    /// Update a peer's capabilities
    pub async fn update_peer_capabilities(&self, node_id: &NodeId, capabilities: Vec<Capability>) {
        if let Some(peer) = self.peers.write().await.get_mut(node_id) {
            peer.info.capabilities = capabilities;
            peer.info.last_seen_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
        }
    }

    /// Update a peer's cost
    pub async fn update_peer_cost(&self, node_id: &NodeId, cost: NodeCost) {
        if let Some(peer) = self.peers.write().await.get_mut(node_id) {
            peer.info.cost = Some(cost);
            peer.info.last_seen_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
        }
    }

    /// Check if connected to max peers
    pub async fn at_capacity(&self) -> bool {
        self.peer_count().await >= self.config.max_peers
    }
}

impl std::fmt::Debug for MeshClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("MeshClient")
            .field("node_id", &self.node_id)
            .field("config", &self.config)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::rngs::OsRng;

    fn create_test_client() -> MeshClient {
        let node_id = NodeId::new();
        let signing_key = SigningKey::generate(&mut OsRng);
        let config = NodeConfig::default();
        MeshClient::new(node_id, signing_key, config)
    }

    #[tokio::test]
    async fn test_connect_disconnect() {
        let client = create_test_client();

        let peer_id = client.connect("ws://localhost:8765").await.unwrap();
        assert_eq!(client.peer_count().await, 1);

        client.disconnect(&peer_id).await.unwrap();
        assert_eq!(client.peer_count().await, 0);
    }

    #[tokio::test]
    async fn test_disconnect_all() {
        let client = create_test_client();

        client.connect("ws://localhost:8765").await.unwrap();
        client.connect("ws://localhost:8766").await.unwrap();
        assert_eq!(client.peer_count().await, 2);

        client.disconnect_all().await;
        assert_eq!(client.peer_count().await, 0);
    }

    #[tokio::test]
    async fn test_get_peers() {
        let client = create_test_client();

        client.connect("ws://localhost:8765").await.unwrap();
        
        let peers = client.get_peers().await;
        assert_eq!(peers.len(), 1);
        assert_eq!(peers[0].address, "ws://localhost:8765");
    }

    #[tokio::test]
    async fn test_send_message() {
        let client = create_test_client();

        let peer_id = client.connect("ws://localhost:8765").await.unwrap();
        
        let message = GossipMessage::Ping {
            timestamp_ms: 12345,
        };

        client.send_to(&peer_id, message).await.unwrap();
    }

    #[tokio::test]
    async fn test_send_to_unknown_peer() {
        let client = create_test_client();
        let unknown_id = NodeId::new();

        let message = GossipMessage::Ping { timestamp_ms: 0 };
        let result = client.send_to(&unknown_id, message).await;

        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_broadcast() {
        let client = create_test_client();

        client.connect("ws://localhost:8765").await.unwrap();
        client.connect("ws://localhost:8766").await.unwrap();

        let message = GossipMessage::Capabilities {
            node_id: NodeId::new(),
            capabilities: vec![],
        };

        let sent = client.broadcast(message).await.unwrap();
        assert_eq!(sent, 2);
    }

    #[test]
    fn test_gossip_message_serialization() {
        let message = GossipMessage::Hello {
            node_id: NodeId::new(),
            public_key: "abc123".to_string(),
            name: "test-node".to_string(),
            capabilities: vec![],
        };

        let json = serde_json::to_string(&message).unwrap();
        assert!(json.contains("Hello"));
        assert!(json.contains("test-node"));

        let _parsed: GossipMessage = serde_json::from_str(&json).unwrap();
    }

    #[tokio::test]
    async fn test_update_peer_capabilities() {
        let client = create_test_client();

        let peer_id = client.connect("ws://localhost:8765").await.unwrap();
        
        let caps = vec![
            crate::capability::Capability::new("camera", "Test Camera"),
        ];

        client.update_peer_capabilities(&peer_id, caps).await;

        let peer = client.get_peer(&peer_id).await.unwrap();
        assert_eq!(peer.capabilities.len(), 1);
    }
}
