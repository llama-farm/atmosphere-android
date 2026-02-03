//! Platform metrics abstraction
//!
//! Defines traits for collecting platform-specific metrics like battery,
//! CPU, and memory usage. Platform implementations (Android, iOS, desktop)
//! provide concrete implementations.

use async_trait::async_trait;

/// Platform metrics provider trait
///
/// Implement this trait for each platform to provide device metrics.
/// These metrics are used for cost calculation and peer selection.
#[async_trait]
pub trait PlatformMetrics: Send + Sync {
    /// Get current battery percentage (0.0 - 100.0)
    /// Returns None if battery status is unavailable (e.g., desktop without battery)
    fn battery_percent(&self) -> Option<f32>;

    /// Check if device is running on battery power
    /// Returns false if plugged in or no battery present
    fn is_on_battery(&self) -> bool;

    /// Get current CPU load (0.0 - 1.0 representing utilization)
    fn cpu_load(&self) -> f32;

    /// Get available memory in megabytes
    fn available_memory_mb(&self) -> u64;

    /// Get total memory in megabytes
    fn total_memory_mb(&self) -> u64 {
        // Default implementation - platforms can override
        0
    }

    /// Check if device is in power saving mode
    fn is_power_saving(&self) -> bool {
        false
    }

    /// Get network type (wifi, cellular, ethernet, unknown)
    fn network_type(&self) -> NetworkType {
        NetworkType::Unknown
    }

    /// Get estimated network bandwidth in Mbps (0 if unknown)
    fn estimated_bandwidth_mbps(&self) -> u32 {
        0
    }
}

/// Network connection type
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum NetworkType {
    Wifi,
    Cellular,
    Ethernet,
    #[default]
    Unknown,
}

/// Mock metrics implementation for testing
#[derive(Debug, Clone)]
pub struct MockMetrics {
    pub battery: Option<f32>,
    pub on_battery: bool,
    pub cpu: f32,
    pub memory_mb: u64,
    pub total_memory_mb: u64,
}

impl Default for MockMetrics {
    fn default() -> Self {
        Self {
            battery: Some(80.0),
            on_battery: true,
            cpu: 0.3,
            memory_mb: 2048,
            total_memory_mb: 4096,
        }
    }
}

#[async_trait]
impl PlatformMetrics for MockMetrics {
    fn battery_percent(&self) -> Option<f32> {
        self.battery
    }

    fn is_on_battery(&self) -> bool {
        self.on_battery
    }

    fn cpu_load(&self) -> f32 {
        self.cpu
    }

    fn available_memory_mb(&self) -> u64 {
        self.memory_mb
    }

    fn total_memory_mb(&self) -> u64 {
        self.total_memory_mb
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mock_metrics_default() {
        let metrics = MockMetrics::default();
        assert_eq!(metrics.battery_percent(), Some(80.0));
        assert!(metrics.is_on_battery());
        assert!((metrics.cpu_load() - 0.3).abs() < f32::EPSILON);
        assert_eq!(metrics.available_memory_mb(), 2048);
    }

    #[test]
    fn test_mock_metrics_custom() {
        let metrics = MockMetrics {
            battery: None,
            on_battery: false,
            cpu: 0.8,
            memory_mb: 1024,
            total_memory_mb: 8192,
        };
        assert_eq!(metrics.battery_percent(), None);
        assert!(!metrics.is_on_battery());
        assert!((metrics.cpu_load() - 0.8).abs() < f32::EPSILON);
        assert_eq!(metrics.available_memory_mb(), 1024);
        assert_eq!(metrics.total_memory_mb(), 8192);
    }

    #[test]
    fn test_network_type_default() {
        let net_type = NetworkType::default();
        assert_eq!(net_type, NetworkType::Unknown);
    }
}
