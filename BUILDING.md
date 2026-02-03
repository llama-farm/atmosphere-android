# Building Atmosphere Android

This document provides detailed instructions for building the Atmosphere Android app from source.

## Prerequisites

### Required Software

| Software | Version | Notes |
|----------|---------|-------|
| Android Studio | 2023.1.1+ (Hedgehog) | Or use command line tools |
| JDK | 17+ | Temurin/Adoptium recommended |
| Android SDK | 34 | API level 34 |
| Android NDK | 26.1.10909125 | For native compilation |
| Rust | stable | Latest stable toolchain |
| Git | 2.x | For cloning |

### System Requirements

- **macOS**: 10.15 Catalina or later (Intel or Apple Silicon)
- **Linux**: Ubuntu 20.04+ or equivalent
- **Windows**: Windows 10+ with WSL2 (for Rust builds)
- **RAM**: 8GB minimum, 16GB recommended
- **Disk**: 10GB free space for SDK + NDK + builds

## Installation

### 1. Install Android Studio

Download from: https://developer.android.com/studio

During installation, ensure these components are selected:
- Android SDK
- Android SDK Platform
- Android Virtual Device

### 2. Install Android NDK

Via Android Studio:
1. Open Settings → Languages & Frameworks → Android SDK
2. Go to SDK Tools tab
3. Check "Show Package Details"
4. Select NDK (Side by side) → 26.1.10909125
5. Click Apply

Via command line:
```bash
sdkmanager --install "ndk;26.1.10909125"
```

### 3. Install Rust

```bash
# Install rustup
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Source the environment
source ~/.cargo/env

# Install stable toolchain
rustup default stable

# Add Android targets
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
rustup target add i686-linux-android
```

### 4. Configure Environment

Create or edit `local.properties` in the project root:

```properties
# macOS
sdk.dir=/Users/YOUR_USERNAME/Library/Android/sdk

# Linux
# sdk.dir=/home/YOUR_USERNAME/Android/Sdk

# Windows
# sdk.dir=C\:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
```

Set environment variables (add to `~/.bashrc` or `~/.zshrc`):

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk  # Adjust for your OS
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
```

## Building

### Debug Build

```bash
# From project root
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build (Unsigned)

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Release Build (Signed)

1. Generate a keystore (first time only):
   ```bash
   keytool -genkey -v -keystore atmosphere.jks \
     -alias atmosphere \
     -keyalg RSA \
     -keysize 2048 \
     -validity 10000
   ```

2. Configure signing in `local.properties`:
   ```properties
   ATMOSPHERE_KEYSTORE_FILE=/path/to/atmosphere.jks
   ATMOSPHERE_KEYSTORE_PASSWORD=your_keystore_password
   ATMOSPHERE_KEY_ALIAS=atmosphere
   ATMOSPHERE_KEY_PASSWORD=your_key_password
   ```

3. Build:
   ```bash
   ./gradlew assembleRelease
   ```

Output: `app/build/outputs/apk/release/app-release.apk`

## Building Rust Libraries

The Rust libraries are automatically built when you run `./gradlew assembleDebug` or `assembleRelease`. To build them manually:

```bash
cd rust
./build-android.sh
```

This script:
1. Detects your NDK installation
2. Configures cross-compilation toolchains
3. Builds for all Android architectures
4. Copies `.so` files to `rust/target/jniLibs/`

### Build Options

```bash
# Build only release libraries
BUILD_TYPE=release ./build-android.sh

# Build only debug libraries
BUILD_TYPE=debug ./build-android.sh

# Use specific NDK
ANDROID_NDK_HOME=/path/to/ndk ./build-android.sh

# Set minimum SDK version
RUST_MIN_SDK=26 ./build-android.sh
```

## Troubleshooting

### NDK Not Found

```
Error: Android NDK not found
```

Solution:
1. Verify NDK is installed: `ls $ANDROID_HOME/ndk/`
2. Set `ANDROID_NDK_HOME` explicitly
3. Check `ndk.dir` in `local.properties`

### Rust Target Not Installed

```
error: could not find `aarch64-linux-android` in the `platforms` directory
```

Solution:
```bash
rustup target add aarch64-linux-android
# Repeat for other targets
```

### JNI Library Not Loaded

```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libatmosphere_core.so" not found
```

Solution:
1. Check that Rust build completed: `ls rust/target/jniLibs/`
2. Verify library name matches `System.loadLibrary()` call
3. Ensure correct ABI is built for your device

### Gradle Sync Failed

```
Could not determine the dependencies of task ':app:compileDebugKotlin'
```

Solution:
1. File → Invalidate Caches and Restart
2. Delete `.gradle/` and `build/` directories
3. Run `./gradlew clean` and retry

### Out of Memory

```
GC overhead limit exceeded
```

Solution: Increase Gradle heap in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx8192m -Dfile.encoding=UTF-8
```

## CI/CD

The project includes a GitHub Actions workflow (`.github/workflows/android.yml`) that:

1. Builds debug and release APKs on every push
2. Runs lint and unit tests
3. Uploads APKs as artifacts
4. Creates signed releases on main branch (when secrets are configured)

### Required Secrets

For signed release builds, configure these GitHub secrets:

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias in keystore |
| `KEY_PASSWORD` | Key password |

To encode your keystore:
```bash
base64 -i atmosphere.jks | pbcopy  # macOS
base64 atmosphere.jks | xclip -selection clipboard  # Linux
```

## Performance Tips

### Faster Builds

1. Enable Gradle configuration cache (already in `gradle.properties`)
2. Use parallel builds: `org.gradle.parallel=true`
3. Increase workers: `org.gradle.workers.max=4`

### Smaller APKs

1. Enable R8/ProGuard (already enabled for release)
2. Build only for specific ABIs:
   ```kotlin
   // In app/build.gradle.kts
   ndk {
       abiFilters += listOf("arm64-v8a")  // ARM64 only
   }
   ```

### Debug Builds

For faster iteration, build only for your device's architecture:
```bash
./gradlew assembleDebug -Pabi=arm64-v8a
```

## Next Steps

After building successfully:
1. Install on device: `adb install app/build/outputs/apk/debug/app-debug.apk`
2. View logs: `adb logcat -s Atmosphere`
3. Profile: Use Android Studio Profiler for CPU/memory analysis
