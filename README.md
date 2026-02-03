# Atmosphere Android

Rust core library for the Atmosphere mesh network, designed for Android deployment.

## Architecture

```
atmosphere-android/
├── core/                    # Platform-agnostic Rust library
│   └── src/
│       ├── lib.rs          # Main entry point and re-exports
│       ├── node.rs         # AtmosphereNode - main node management
│       ├── mesh.rs         # MeshClient - gossip protocol
│       ├── capability.rs   # CapabilityRegistry - service registration
│       ├── cost.rs         # CostCollector - resource metrics
│       ├── intent.rs       # IntentRouter - intent-based routing
│       ├── metrics.rs      # PlatformMetrics trait
│       └── error.rs        # Error types
├── android/                 # Android JNI bindings (placeholder)
└── README.md
```

## Core Components

### AtmosphereNode
The main entry point for participating in the mesh network. Manages:
- Node identity (Ed25519 keypair)
- Lifecycle (start/stop)
- Component orchestration

```rust
use atmosphere_core::{AtmosphereNode, NodeConfig};

let config = NodeConfig {
    name: "my-phone".to_string(),
    bootstrap_peers: vec!["ws://peer1.example.com:8765".to_string()],
    ..Default::default()
};

let metrics = Arc::new(MyPlatformMetrics::new());
let node = AtmosphereNode::new(config, metrics);
node.start().await?;
```

### MeshClient
Gossip protocol implementation for peer discovery and communication:
- WebSocket-based transport
- Peer management
- Message broadcasting

### CapabilityRegistry
Register and discover capabilities across the mesh:
- Local capability registration
- Remote capability tracking
- Capability queries

```rust
use atmosphere_core::{Capability, CapabilityRegistry};

let registry = CapabilityRegistry::new();

// Register a local capability
registry.register(
    Capability::new("camera", "Front Camera")
        .with_description("12MP front-facing camera")
        .with_cost_weight(1.5)
);

// Find remote peers with cameras
let camera_peers = registry.find_peers_with_capability("camera");
```

### CostCollector
Collects device metrics for cost-aware routing:
- Battery status
- CPU load
- Memory usage
- Network conditions

```rust
use atmosphere_core::CostCollector;

let collector = CostCollector::new(metrics);
let cost = collector.calculate_local_cost();

println!("Battery cost: {}", cost.battery_cost);
println!("Total cost: {}", cost.total_cost);
```

### IntentRouter
Routes intents to capable nodes based on capability and cost:
- Capability matching
- Cost-aware selection
- Local preference option

```rust
use atmosphere_core::{Intent, IntentRouter};

let intent = Intent::new("camera", "capture")
    .with_param("resolution", "1080p")
    .with_max_cost(0.5);

let decision = router.route(intent).await?;
println!("Routed to: {}, cost: {}", decision.target, decision.cost);
```

### PlatformMetrics Trait
Implement this trait for your platform to provide device metrics:

```rust
use atmosphere_core::PlatformMetrics;

pub struct AndroidMetrics { /* ... */ }

impl PlatformMetrics for AndroidMetrics {
    fn battery_percent(&self) -> Option<f32> {
        // Query Android BatteryManager
    }
    
    fn is_on_battery(&self) -> bool {
        // Check power source
    }
    
    fn cpu_load(&self) -> f32 {
        // Read /proc/stat or use Android APIs
    }
    
    fn available_memory_mb(&self) -> u64 {
        // Query ActivityManager.MemoryInfo
    }
}
```

## Building

### Prerequisites
- Rust 1.75+ with `cargo`
- For Android: NDK and `cargo-ndk`

### Build Library
```bash
# Debug build
cargo build

# Release build
cargo build --release

# Run tests
cargo test
```

### Build for Android (future)
```bash
# Install Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi

# Build with cargo-ndk
cargo ndk -t arm64-v8a -t armeabi-v7a build --release
```

## Testing

```bash
# Run all tests
cargo test

# Run with logging
RUST_LOG=debug cargo test -- --nocapture

# Run specific test
cargo test test_node_creation
```

## Design Principles

1. **Platform Agnostic Core**: All business logic is in `core/`, with platform-specific code isolated to binding crates.

2. **Cost-Aware Routing**: Every routing decision considers the cost (battery, CPU, memory) on both local and remote nodes.

3. **Capability-Based**: Nodes advertise capabilities, and intents are routed to capable nodes.

4. **Gossip Protocol**: Decentralized peer discovery and state propagation.

5. **Async-First**: Built on Tokio for efficient async I/O.

## License

MIT
