//! Intent Router
//!
//! Routes intents to capable nodes based on capability matching and cost.
//! Intents are high-level requests like "take a photo" or "run computation".

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::capability::CapabilityRegistry;
use crate::cost::CostCollector;
use crate::error::{AtmosphereError, Result};
use crate::node::NodeId;

/// An intent to be routed to a capable node
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Intent {
    /// Unique identifier for this intent
    pub id: Uuid,

    /// Required capability type
    pub capability_type: String,

    /// Action to perform
    pub action: String,

    /// Parameters for the action
    pub params: HashMap<String, serde_json::Value>,

    /// Priority (higher = more urgent)
    pub priority: u8,

    /// Maximum cost willing to pay (0.0 - 1.0)
    pub max_cost: f32,

    /// Prefer local execution if available
    pub prefer_local: bool,

    /// Creation timestamp
    pub created_at_ms: u64,

    /// Timeout in milliseconds
    pub timeout_ms: u64,
}

impl Intent {
    /// Create a new intent
    pub fn new(capability_type: impl Into<String>, action: impl Into<String>) -> Self {
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        Self {
            id: Uuid::new_v4(),
            capability_type: capability_type.into(),
            action: action.into(),
            params: HashMap::new(),
            priority: 5,
            max_cost: 1.0,
            prefer_local: true,
            created_at_ms: now_ms,
            timeout_ms: 30000,
        }
    }

    /// Add a parameter
    pub fn with_param(mut self, key: impl Into<String>, value: impl Serialize) -> Self {
        self.params.insert(key.into(), serde_json::to_value(value).unwrap());
        self
    }

    /// Set priority
    pub fn with_priority(mut self, priority: u8) -> Self {
        self.priority = priority;
        self
    }

    /// Set maximum cost
    pub fn with_max_cost(mut self, max_cost: f32) -> Self {
        self.max_cost = max_cost;
        self
    }

    /// Set local preference
    pub fn prefer_remote(mut self) -> Self {
        self.prefer_local = false;
        self
    }

    /// Set timeout
    pub fn with_timeout_ms(mut self, timeout_ms: u64) -> Self {
        self.timeout_ms = timeout_ms;
        self
    }
}

/// Status of an intent
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum IntentStatus {
    /// Waiting to be routed
    Pending,

    /// Routed to a node, awaiting execution
    Routed { target: NodeId },

    /// Currently executing
    Executing { target: NodeId },

    /// Successfully completed
    Completed { target: NodeId },

    /// Failed to execute
    Failed { reason: String },

    /// Timed out
    TimedOut,

    /// Cancelled by user
    Cancelled,
}

/// Result of routing an intent
#[derive(Debug, Clone)]
pub struct RoutingDecision {
    /// Chosen target node
    pub target: NodeId,

    /// Whether target is local
    pub is_local: bool,

    /// Cost of execution on target
    pub cost: f32,

    /// Capability ID on target
    pub capability_id: Uuid,
}

/// Tracked intent with status
struct TrackedIntent {
    intent: Intent,
    status: IntentStatus,
    result: Option<serde_json::Value>,
}

/// Routes intents to capable nodes
pub struct IntentRouter {
    /// Capability registry
    capabilities: Arc<CapabilityRegistry>,

    /// Cost collector
    cost_collector: Arc<CostCollector>,

    /// Active intents
    active_intents: RwLock<HashMap<Uuid, TrackedIntent>>,

    /// Local node ID (set on start)
    local_node_id: RwLock<Option<NodeId>>,
}

impl IntentRouter {
    /// Create a new intent router
    pub fn new(capabilities: Arc<CapabilityRegistry>, cost_collector: Arc<CostCollector>) -> Self {
        Self {
            capabilities,
            cost_collector,
            active_intents: RwLock::new(HashMap::new()),
            local_node_id: RwLock::new(None),
        }
    }

    /// Set the local node ID
    pub async fn set_local_node_id(&self, node_id: NodeId) {
        *self.local_node_id.write().await = Some(node_id);
    }

    /// Route an intent to a capable node
    pub async fn route(&self, intent: Intent) -> Result<RoutingDecision> {
        let intent_id = intent.id;
        let capability_type = &intent.capability_type;

        tracing::debug!(
            intent_id = %intent_id,
            capability = %capability_type,
            "Routing intent"
        );

        // Track the intent
        self.active_intents.write().await.insert(
            intent_id,
            TrackedIntent {
                intent: intent.clone(),
                status: IntentStatus::Pending,
                result: None,
            },
        );

        // First, check for local capability
        let local_caps = self.capabilities.find_local_by_type(capability_type);
        let local_node_id = self.local_node_id.read().await.unwrap_or_else(NodeId::new);

        if !local_caps.is_empty() && intent.prefer_local {
            let cap = &local_caps[0];
            let local_cost = self.cost_collector.calculate_local_cost();

            if local_cost.total_cost <= intent.max_cost {
                self.update_status(intent_id, IntentStatus::Routed { target: local_node_id }).await;

                return Ok(RoutingDecision {
                    target: local_node_id,
                    is_local: true,
                    cost: local_cost.total_cost,
                    capability_id: cap.id,
                });
            }
        }

        // Look for remote capabilities
        let remote_caps = self.capabilities.find_peers_with_capability(capability_type);

        if remote_caps.is_empty() && local_caps.is_empty() {
            self.update_status(
                intent_id,
                IntentStatus::Failed {
                    reason: format!("No capable node found for: {}", capability_type),
                },
            )
            .await;

            return Err(AtmosphereError::NoCapablePeer(capability_type.clone()));
        }

        // Find the best remote peer by cost
        let mut best_peer: Option<(NodeId, Uuid, f32)> = None;

        for (node_id, cap) in remote_caps {
            let cost = self
                .cost_collector
                .get_peer_cost(&node_id)
                .map(|c| c.total_cost * cap.cost_weight)
                .unwrap_or(0.5);

            if cost <= intent.max_cost {
                match &best_peer {
                    None => best_peer = Some((node_id, cap.id, cost)),
                    Some((_, _, best_cost)) if cost < *best_cost => {
                        best_peer = Some((node_id, cap.id, cost));
                    }
                    _ => {}
                }
            }
        }

        // Fall back to local if no good remote option
        if best_peer.is_none() && !local_caps.is_empty() {
            let cap = &local_caps[0];
            let local_cost = self.cost_collector.calculate_local_cost();

            self.update_status(intent_id, IntentStatus::Routed { target: local_node_id }).await;

            return Ok(RoutingDecision {
                target: local_node_id,
                is_local: true,
                cost: local_cost.total_cost,
                capability_id: cap.id,
            });
        }

        match best_peer {
            Some((node_id, cap_id, cost)) => {
                self.update_status(intent_id, IntentStatus::Routed { target: node_id }).await;

                Ok(RoutingDecision {
                    target: node_id,
                    is_local: false,
                    cost,
                    capability_id: cap_id,
                })
            }
            None => {
                self.update_status(
                    intent_id,
                    IntentStatus::Failed {
                        reason: "No peer within cost budget".to_string(),
                    },
                )
                .await;

                Err(AtmosphereError::NoCapablePeer(capability_type.clone()))
            }
        }
    }

    /// Update intent status
    async fn update_status(&self, intent_id: Uuid, status: IntentStatus) {
        if let Some(tracked) = self.active_intents.write().await.get_mut(&intent_id) {
            tracked.status = status;
        }
    }

    /// Get intent status
    pub async fn get_status(&self, intent_id: Uuid) -> Option<IntentStatus> {
        self.active_intents
            .read()
            .await
            .get(&intent_id)
            .map(|t| t.status.clone())
    }

    /// Mark intent as completed with result
    pub async fn complete(&self, intent_id: Uuid, result: serde_json::Value) {
        if let Some(tracked) = self.active_intents.write().await.get_mut(&intent_id) {
            let target = match &tracked.status {
                IntentStatus::Routed { target } | IntentStatus::Executing { target } => *target,
                _ => NodeId::new(),
            };
            tracked.status = IntentStatus::Completed { target };
            tracked.result = Some(result);
        }
    }

    /// Mark intent as failed
    pub async fn fail(&self, intent_id: Uuid, reason: String) {
        self.update_status(intent_id, IntentStatus::Failed { reason }).await;
    }

    /// Cancel an intent
    pub async fn cancel(&self, intent_id: Uuid) {
        self.update_status(intent_id, IntentStatus::Cancelled).await;
    }

    /// Get the result of a completed intent
    pub async fn get_result(&self, intent_id: Uuid) -> Option<serde_json::Value> {
        self.active_intents
            .read()
            .await
            .get(&intent_id)
            .and_then(|t| t.result.clone())
    }

    /// Remove completed/failed intents older than the given age
    pub async fn cleanup(&self, max_age_ms: u64) {
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        self.active_intents.write().await.retain(|_, tracked| {
            let age = now_ms.saturating_sub(tracked.intent.created_at_ms);
            match tracked.status {
                IntentStatus::Completed { .. }
                | IntentStatus::Failed { .. }
                | IntentStatus::Cancelled
                | IntentStatus::TimedOut => age < max_age_ms,
                _ => true,
            }
        });
    }

    /// Get count of active intents
    pub async fn active_count(&self) -> usize {
        self.active_intents
            .read()
            .await
            .values()
            .filter(|t| matches!(t.status, IntentStatus::Pending | IntentStatus::Routed { .. } | IntentStatus::Executing { .. }))
            .count()
    }
}

impl std::fmt::Debug for IntentRouter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("IntentRouter").finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capability::Capability;
    use crate::metrics::MockMetrics;

    fn create_test_router() -> IntentRouter {
        let capabilities = Arc::new(CapabilityRegistry::new());
        let metrics = Arc::new(MockMetrics::default());
        let cost_collector = Arc::new(CostCollector::new(metrics));
        IntentRouter::new(capabilities, cost_collector)
    }

    #[test]
    fn test_intent_creation() {
        let intent = Intent::new("camera", "capture")
            .with_param("resolution", "1080p")
            .with_priority(8)
            .with_max_cost(0.5)
            .with_timeout_ms(5000);

        assert_eq!(intent.capability_type, "camera");
        assert_eq!(intent.action, "capture");
        assert_eq!(intent.priority, 8);
        assert!((intent.max_cost - 0.5).abs() < f32::EPSILON);
        assert_eq!(intent.timeout_ms, 5000);
        assert!(intent.params.contains_key("resolution"));
    }

    #[test]
    fn test_intent_prefer_remote() {
        let intent = Intent::new("compute", "process").prefer_remote();
        assert!(!intent.prefer_local);
    }

    #[tokio::test]
    async fn test_route_local_capability() {
        let capabilities = Arc::new(CapabilityRegistry::new());
        let metrics = Arc::new(MockMetrics {
            battery: Some(80.0),
            on_battery: false,
            cpu: 0.2,
            memory_mb: 2048,
            total_memory_mb: 4096,
        });
        let cost_collector = Arc::new(CostCollector::new(metrics));
        let router = IntentRouter::new(Arc::clone(&capabilities), cost_collector);

        // Register a local capability
        capabilities.register(Capability::new("camera", "Front Camera"));
        router.set_local_node_id(NodeId::new()).await;

        let intent = Intent::new("camera", "capture");
        let decision = router.route(intent).await.unwrap();

        assert!(decision.is_local);
    }

    #[tokio::test]
    async fn test_route_no_capability() {
        let router = create_test_router();

        let intent = Intent::new("nonexistent", "action");
        let result = router.route(intent).await;

        assert!(result.is_err());
        assert!(matches!(
            result.unwrap_err(),
            AtmosphereError::NoCapablePeer(_)
        ));
    }

    #[tokio::test]
    async fn test_intent_status_tracking() {
        let capabilities = Arc::new(CapabilityRegistry::new());
        capabilities.register(Capability::new("test", "Test Cap"));

        let metrics = Arc::new(MockMetrics::default());
        let cost_collector = Arc::new(CostCollector::new(metrics));
        let router = IntentRouter::new(capabilities, cost_collector);
        router.set_local_node_id(NodeId::new()).await;

        let intent = Intent::new("test", "action");
        let intent_id = intent.id;

        router.route(intent).await.unwrap();

        let status = router.get_status(intent_id).await.unwrap();
        assert!(matches!(status, IntentStatus::Routed { .. }));

        router.complete(intent_id, serde_json::json!({"success": true})).await;

        let status = router.get_status(intent_id).await.unwrap();
        assert!(matches!(status, IntentStatus::Completed { .. }));

        let result = router.get_result(intent_id).await.unwrap();
        assert_eq!(result["success"], true);
    }

    #[tokio::test]
    async fn test_intent_cancellation() {
        let router = create_test_router();

        // Create and track an intent manually
        let intent = Intent::new("test", "action");
        let intent_id = intent.id;

        router.active_intents.write().await.insert(
            intent_id,
            TrackedIntent {
                intent,
                status: IntentStatus::Pending,
                result: None,
            },
        );

        router.cancel(intent_id).await;

        let status = router.get_status(intent_id).await.unwrap();
        assert_eq!(status, IntentStatus::Cancelled);
    }

    #[tokio::test]
    async fn test_active_count() {
        let router = create_test_router();

        assert_eq!(router.active_count().await, 0);

        // Add some tracked intents
        let intent1 = Intent::new("a", "1");
        let intent2 = Intent::new("b", "2");

        {
            let mut intents = router.active_intents.write().await;
            intents.insert(intent1.id, TrackedIntent {
                intent: intent1,
                status: IntentStatus::Pending,
                result: None,
            });
            intents.insert(intent2.id, TrackedIntent {
                intent: intent2,
                status: IntentStatus::Cancelled,
                result: None,
            });
        }

        assert_eq!(router.active_count().await, 1); // Only pending counts
    }
}
