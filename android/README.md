# Atmosphere Android - JNI Bindings

This crate provides JNI bindings for the Atmosphere mesh network core library using [UniFFI](https://mozilla.github.io/uniffi-rs/).

## Overview

The bindings expose the following to Kotlin/Java:

- `AtmosphereNode` - A mesh network node that can register capabilities and route intents
- `AtmosphereError` - Error types for Atmosphere operations
- `create_node()` - Factory function to create nodes
- `generate_node_id()` - Generate random UUIDs for node IDs

## Prerequisites

1. **Rust** (1.70+)
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   ```

2. **Android NDK**
   - Install via Android Studio's SDK Manager, or
   - Download from https://developer.android.com/ndk/downloads
   - Set `ANDROID_NDK_HOME` environment variable

3. **Rust Android targets**
   ```bash
   make setup
   # or manually:
   rustup target add aarch64-linux-android
   rustup target add armv7-linux-androideabi
   rustup target add x86_64-linux-android
   ```

4. **uniffi-bindgen** (optional, for manual binding generation)
   ```bash
   cargo install uniffi_bindgen
   ```

## Building

### Quick Build (ARM64 only)
```bash
make build
```

### Build All Targets
```bash
make build-all
```

### Build Specific Targets
```bash
make build-arm64   # ARM64 devices
make build-arm32   # Older ARM devices
make build-x86_64  # Emulators
```

### Generate Kotlin Bindings Only
```bash
make bindings
```

### Using the Build Script Directly
```bash
./build-android.sh --help

# Build ARM64 with bindings
./build-android.sh --arm64

# Build all without bindings
./build-android.sh --all --skip-bindings
```

## Output Files

After building:

- **Native Libraries**: `../app/src/main/jniLibs/`
  - `arm64-v8a/libatmosphere_android.so`
  - `armeabi-v7a/libatmosphere_android.so` (if built)
  - `x86_64/libatmosphere_android.so` (if built)

- **Kotlin Bindings**: `../app/src/main/kotlin/com/llamafarm/atmosphere/bindings/`
  - `atmosphere.kt` - Generated Kotlin code

## Usage in Kotlin

```kotlin
import com.llamafarm.atmosphere.bindings.*

// Create a node
val nodeId = generateNodeId()
val node = createNode(nodeId, "/data/data/com.llamafarm.atmosphere/files")

// Start the node
try {
    node.start()
} catch (e: AtmosphereException) {
    when (e) {
        is AtmosphereException.NetworkError -> handleNetworkError(e.msg)
        is AtmosphereException.ConfigError -> handleConfigError(e.msg)
        else -> throw e
    }
}

// Check status
val status = node.statusJson()
println("Node status: $status")

// Register a capability
val capability = """
{
    "id": "my-capability",
    "name": "My Capability",
    "description": "Does something useful",
    "version": "1.0.0"
}
"""
node.registerCapability(capability)

// Route an intent
val intent = """
{
    "id": "${generateNodeId()}",
    "capability": "my-capability",
    "action": "do-something",
    "params": {"key": "value"}
}
"""
val result = node.routeIntent(intent)

// Stop the node
node.stop()
```

## Interface Definition (UDL)

The interface is defined in `src/atmosphere.udl`:

```
namespace atmosphere {
    [Throws=AtmosphereError]
    AtmosphereNode create_node(string node_id, string data_dir);
    
    string generate_node_id();
};

interface AtmosphereNode {
    [Throws=AtmosphereError]
    void start();
    void stop();
    boolean is_running();
    string node_id();
    string data_dir();
    string status_json();
    [Throws=AtmosphereError]
    void register_capability(string capability_json);
    [Throws=AtmosphereError]
    string route_intent(string intent_json);
};

[Error]
enum AtmosphereError {
    "NetworkError",
    "ConfigError",
    "CapabilityNotFound",
    "NodeNotRunning",
    "SerializationError",
};
```

## Troubleshooting

### NDK Not Found
```
Error: ANDROID_NDK_HOME not set
```
Solution: Set the environment variable:
```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/26.1.10909125"
```

### Linker Errors
If you get linker errors, make sure:
1. NDK path is correct
2. API level matches your minSdk (default: 21)
3. Target architecture is installed

### Missing .so File
Ensure the build succeeded without errors. Check:
```bash
ls -la ../target/aarch64-linux-android/release/
```

## Development

### Running Tests
```bash
make test
# or
cargo test
```

### Building for Host (macOS/Linux)
For development and testing without Android:
```bash
make host
# or
cargo build --release
```

## Architecture

```
atmosphere-android/
├── Cargo.toml           # Workspace root
├── core/                # Platform-agnostic core library
│   ├── Cargo.toml
│   └── src/lib.rs
├── android/             # This crate - JNI bindings
│   ├── Cargo.toml
│   ├── build.rs         # UniFFI scaffolding generation
│   ├── src/
│   │   ├── lib.rs       # Rust bindings implementation
│   │   └── atmosphere.udl  # UniFFI interface definition
│   ├── build-android.sh # Build script
│   └── Makefile
└── app/                 # Android app
    └── src/main/
        ├── jniLibs/     # Generated .so files
        └── kotlin/      # Generated Kotlin bindings
```
