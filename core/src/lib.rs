//! Atmosphere Core Library
//!
//! Platform-agnostic core for the Atmosphere mesh network.
//! Provides node management, gossip protocol, capability registry,
//! cost collection, and intent routing.

pub mod capability;
pub mod cost;
pub mod error;
pub mod intent;
pub mod mesh;
pub mod metrics;
pub mod node;

pub use capability::{Capability, CapabilityRegistry};
pub use cost::{CostCollector, NodeCost};
pub use error::{AtmosphereError, Result};
pub use intent::{Intent, IntentRouter, IntentStatus};
pub use mesh::{GossipMessage, MeshClient, PeerInfo};
pub use metrics::PlatformMetrics;
pub use node::{AtmosphereNode, NodeConfig, NodeId};

/// Library version
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version() {
        assert!(!VERSION.is_empty());
    }
}
