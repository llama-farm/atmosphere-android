//! Cost Collection
//!
//! Collects and calculates costs for executing tasks on a node.
//! Uses platform metrics to determine current resource costs.

use std::sync::Arc;
use std::collections::HashMap;
use std::sync::RwLock;
use serde::{Deserialize, Serialize};

use crate::metrics::PlatformMetrics;
use crate::node::NodeId;

/// Cost metrics for a node
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeCost {
    /// Battery cost factor (0.0 = no cost, 1.0 = max cost)
    pub battery_cost: f32,

    /// CPU cost factor
    pub cpu_cost: f32,

    /// Memory cost factor
    pub memory_cost: f32,

    /// Network cost factor
    pub network_cost: f32,

    /// Combined weighted cost score
    pub total_cost: f32,

    /// Timestamp when this cost was calculated
    pub timestamp_ms: u64,
}

impl Default for NodeCost {
    fn default() -> Self {
        Self {
            battery_cost: 0.5,
            cpu_cost: 0.5,
            memory_cost: 0.5,
            network_cost: 0.5,
            total_cost: 0.5,
            timestamp_ms: 0,
        }
    }
}

/// Cost weights for different resource types
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CostWeights {
    pub battery: f32,
    pub cpu: f32,
    pub memory: f32,
    pub network: f32,
}

impl Default for CostWeights {
    fn default() -> Self {
        Self {
            battery: 0.4,  // Battery is most important on mobile
            cpu: 0.25,
            memory: 0.2,
            network: 0.15,
        }
    }
}

/// Collects and calculates node costs based on platform metrics
pub struct CostCollector {
    /// Platform metrics provider
    metrics: Arc<dyn PlatformMetrics>,

    /// Cost calculation weights
    weights: RwLock<CostWeights>,

    /// Cached peer costs
    peer_costs: RwLock<HashMap<NodeId, NodeCost>>,
}

impl CostCollector {
    /// Create a new cost collector with the given metrics provider
    pub fn new(metrics: Arc<dyn PlatformMetrics>) -> Self {
        Self {
            metrics,
            weights: RwLock::new(CostWeights::default()),
            peer_costs: RwLock::new(HashMap::new()),
        }
    }

    /// Calculate the current cost for this node
    pub fn calculate_local_cost(&self) -> NodeCost {
        let weights = self.weights.read().unwrap();

        // Calculate battery cost
        let battery_cost = self.calculate_battery_cost();

        // Calculate CPU cost (higher load = higher cost)
        let cpu_cost = self.metrics.cpu_load();

        // Calculate memory cost
        let memory_cost = self.calculate_memory_cost();

        // Network cost (simplified - could be enhanced)
        let network_cost = 0.2; // Base network cost

        // Calculate weighted total
        let total_cost = (battery_cost * weights.battery)
            + (cpu_cost * weights.cpu)
            + (memory_cost * weights.memory)
            + (network_cost * weights.network);

        let timestamp_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        NodeCost {
            battery_cost,
            cpu_cost,
            memory_cost,
            network_cost,
            total_cost,
            timestamp_ms,
        }
    }

    /// Calculate battery cost factor
    fn calculate_battery_cost(&self) -> f32 {
        if !self.metrics.is_on_battery() {
            // Plugged in - low battery cost
            return 0.1;
        }

        match self.metrics.battery_percent() {
            Some(percent) => {
                // Higher cost when battery is lower
                // At 100%: cost = 0.2
                // At 50%: cost = 0.5
                // At 20%: cost = 0.8
                // At 10%: cost = 1.0
                if percent <= 10.0 {
                    1.0
                } else if percent <= 20.0 {
                    0.8
                } else {
                    1.0 - (percent / 100.0) * 0.8
                }
            }
            None => 0.5, // Unknown - use middle value
        }
    }

    /// Calculate memory cost factor
    fn calculate_memory_cost(&self) -> f32 {
        let available = self.metrics.available_memory_mb() as f32;
        let total = self.metrics.total_memory_mb() as f32;

        if total == 0.0 {
            return 0.5; // Unknown
        }

        let usage_ratio = 1.0 - (available / total);
        usage_ratio.clamp(0.0, 1.0)
    }

    /// Update the cost weights
    pub fn set_weights(&self, weights: CostWeights) {
        *self.weights.write().unwrap() = weights;
    }

    /// Get current weights
    pub fn get_weights(&self) -> CostWeights {
        self.weights.read().unwrap().clone()
    }

    /// Store a peer's cost information
    pub fn update_peer_cost(&self, node_id: NodeId, cost: NodeCost) {
        self.peer_costs.write().unwrap().insert(node_id, cost);
    }

    /// Get a peer's cost information
    pub fn get_peer_cost(&self, node_id: &NodeId) -> Option<NodeCost> {
        self.peer_costs.read().unwrap().get(node_id).cloned()
    }

    /// Remove a peer's cost information
    pub fn remove_peer_cost(&self, node_id: &NodeId) {
        self.peer_costs.write().unwrap().remove(node_id);
    }

    /// Get all peer costs, sorted by total cost (lowest first)
    pub fn get_sorted_peer_costs(&self) -> Vec<(NodeId, NodeCost)> {
        let mut costs: Vec<_> = self.peer_costs.read().unwrap().clone().into_iter().collect();
        costs.sort_by(|a, b| a.1.total_cost.partial_cmp(&b.1.total_cost).unwrap());
        costs
    }

    /// Find the peer with the lowest cost
    pub fn find_lowest_cost_peer(&self) -> Option<NodeId> {
        self.peer_costs
            .read()
            .unwrap()
            .iter()
            .min_by(|a, b| a.1.total_cost.partial_cmp(&b.1.total_cost).unwrap())
            .map(|(id, _)| *id)
    }
}

impl std::fmt::Debug for CostCollector {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CostCollector")
            .field("weights", &self.weights)
            .field("peer_count", &self.peer_costs.read().unwrap().len())
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metrics::MockMetrics;

    #[test]
    fn test_cost_calculation_plugged_in() {
        let metrics = Arc::new(MockMetrics {
            battery: Some(80.0),
            on_battery: false, // Plugged in
            cpu: 0.3,
            memory_mb: 2048,
            total_memory_mb: 4096,
        });

        let collector = CostCollector::new(metrics);
        let cost = collector.calculate_local_cost();

        // Battery cost should be low when plugged in
        assert!(cost.battery_cost < 0.2);
        assert!((cost.cpu_cost - 0.3).abs() < f32::EPSILON);
    }

    #[test]
    fn test_cost_calculation_low_battery() {
        let metrics = Arc::new(MockMetrics {
            battery: Some(15.0),
            on_battery: true,
            cpu: 0.5,
            memory_mb: 1024,
            total_memory_mb: 4096,
        });

        let collector = CostCollector::new(metrics);
        let cost = collector.calculate_local_cost();

        // Battery cost should be high at low battery
        assert!(cost.battery_cost >= 0.8);
    }

    #[test]
    fn test_memory_cost() {
        let metrics = Arc::new(MockMetrics {
            battery: Some(100.0),
            on_battery: false,
            cpu: 0.0,
            memory_mb: 1024, // 25% available
            total_memory_mb: 4096,
        });

        let collector = CostCollector::new(metrics);
        let cost = collector.calculate_local_cost();

        // Memory cost should be ~0.75 (75% used)
        assert!((cost.memory_cost - 0.75).abs() < 0.01);
    }

    #[test]
    fn test_peer_costs() {
        let metrics = Arc::new(MockMetrics::default());
        let collector = CostCollector::new(metrics);

        let peer1 = NodeId::new();
        let peer2 = NodeId::new();

        collector.update_peer_cost(peer1, NodeCost { total_cost: 0.8, ..Default::default() });
        collector.update_peer_cost(peer2, NodeCost { total_cost: 0.3, ..Default::default() });

        // Should find peer2 (lower cost)
        assert_eq!(collector.find_lowest_cost_peer(), Some(peer2));

        // Sorted should have peer2 first
        let sorted = collector.get_sorted_peer_costs();
        assert_eq!(sorted.len(), 2);
        assert_eq!(sorted[0].0, peer2);
    }

    #[test]
    fn test_custom_weights() {
        let metrics = Arc::new(MockMetrics::default());
        let collector = CostCollector::new(metrics);

        let new_weights = CostWeights {
            battery: 0.1,
            cpu: 0.5,
            memory: 0.3,
            network: 0.1,
        };

        collector.set_weights(new_weights.clone());
        let weights = collector.get_weights();

        assert!((weights.battery - 0.1).abs() < f32::EPSILON);
        assert!((weights.cpu - 0.5).abs() < f32::EPSILON);
    }
}
