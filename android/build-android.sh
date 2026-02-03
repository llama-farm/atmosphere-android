#!/bin/bash
# Build Atmosphere Android native libraries
# 
# This script builds the Rust library for Android targets and generates
# Kotlin bindings using UniFFI.
#
# Prerequisites:
#   - Rust with cross-compilation targets installed
#   - Android NDK installed (set ANDROID_NDK_HOME)
#   - uniffi-bindgen-cli installed: cargo install uniffi_bindgen

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="${PROJECT_ROOT}/app/src/main/jniLibs"
KOTLIN_OUTPUT_DIR="${PROJECT_ROOT}/app/src/main/kotlin/com/llamafarm/atmosphere/bindings"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check for Android NDK
check_ndk() {
    if [ -z "${ANDROID_NDK_HOME:-}" ]; then
        # Try common locations
        if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
            ANDROID_NDK_HOME=$(ls -d "$HOME/Library/Android/sdk/ndk"/*/ 2>/dev/null | head -1 | sed 's/\/$//')
        elif [ -d "$HOME/Android/Sdk/ndk" ]; then
            ANDROID_NDK_HOME=$(ls -d "$HOME/Android/Sdk/ndk"/*/ 2>/dev/null | head -1 | sed 's/\/$//')
        fi
        
        if [ -z "${ANDROID_NDK_HOME:-}" ]; then
            log_error "ANDROID_NDK_HOME not set and NDK not found in default locations"
            log_error "Please set ANDROID_NDK_HOME to your NDK installation path"
            exit 1
        fi
        
        log_info "Found NDK at: $ANDROID_NDK_HOME"
        export ANDROID_NDK_HOME
    fi
}

# Set up cross-compilation environment for a target
setup_target_env() {
    local target=$1
    local api_level=${ANDROID_API_LEVEL:-21}
    local toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
    
    # Find the prebuilt directory
    if [ -d "$toolchain/darwin-x86_64" ]; then
        toolchain="$toolchain/darwin-x86_64"
    elif [ -d "$toolchain/linux-x86_64" ]; then
        toolchain="$toolchain/linux-x86_64"
    else
        log_error "Could not find NDK toolchain"
        exit 1
    fi
    
    case $target in
        aarch64-linux-android)
            export CC_aarch64_linux_android="$toolchain/bin/aarch64-linux-android${api_level}-clang"
            export AR_aarch64_linux_android="$toolchain/bin/llvm-ar"
            export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
            ;;
        armv7-linux-androideabi)
            export CC_armv7_linux_androideabi="$toolchain/bin/armv7a-linux-androideabi${api_level}-clang"
            export AR_armv7_linux_androideabi="$toolchain/bin/llvm-ar"
            export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$CC_armv7_linux_androideabi"
            ;;
        x86_64-linux-android)
            export CC_x86_64_linux_android="$toolchain/bin/x86_64-linux-android${api_level}-clang"
            export AR_x86_64_linux_android="$toolchain/bin/llvm-ar"
            export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CC_x86_64_linux_android"
            ;;
        i686-linux-android)
            export CC_i686_linux_android="$toolchain/bin/i686-linux-android${api_level}-clang"
            export AR_i686_linux_android="$toolchain/bin/llvm-ar"
            export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$CC_i686_linux_android"
            ;;
    esac
}

# Install Rust targets if not present
install_targets() {
    log_info "Checking Rust targets..."
    
    local targets=(
        "aarch64-linux-android"
        "armv7-linux-androideabi"
        "x86_64-linux-android"
    )
    
    for target in "${targets[@]}"; do
        if ! rustup target list --installed | grep -q "$target"; then
            log_info "Installing target: $target"
            rustup target add "$target"
        fi
    done
}

# Build for a specific target
build_target() {
    local target=$1
    local jni_dir
    
    case $target in
        aarch64-linux-android)
            jni_dir="arm64-v8a"
            ;;
        armv7-linux-androideabi)
            jni_dir="armeabi-v7a"
            ;;
        x86_64-linux-android)
            jni_dir="x86_64"
            ;;
        i686-linux-android)
            jni_dir="x86"
            ;;
        *)
            log_error "Unknown target: $target"
            return 1
            ;;
    esac
    
    log_info "Building for $target..."
    
    setup_target_env "$target"
    
    cd "$SCRIPT_DIR"
    cargo build --release --target "$target"
    
    # Copy the .so file to jniLibs
    local lib_path="$PROJECT_ROOT/target/$target/release/libatmosphere_android.so"
    local dest_dir="$OUTPUT_DIR/$jni_dir"
    
    mkdir -p "$dest_dir"
    
    if [ -f "$lib_path" ]; then
        cp "$lib_path" "$dest_dir/"
        log_info "Copied library to $dest_dir/libatmosphere_android.so"
    else
        log_error "Library not found at $lib_path"
        return 1
    fi
}

# Generate Kotlin bindings
generate_kotlin_bindings() {
    log_info "Generating Kotlin bindings..."
    
    # Check if uniffi-bindgen is available
    if ! command -v uniffi-bindgen &> /dev/null; then
        log_warn "uniffi-bindgen not found, installing..."
        cargo install uniffi_bindgen
    fi
    
    mkdir -p "$KOTLIN_OUTPUT_DIR"
    
    cd "$SCRIPT_DIR"
    
    # Generate the bindings
    uniffi-bindgen generate \
        --library "$PROJECT_ROOT/target/release/libatmosphere_android.dylib" \
        --language kotlin \
        --out-dir "$KOTLIN_OUTPUT_DIR" \
        2>/dev/null || \
    uniffi-bindgen generate \
        --library "$PROJECT_ROOT/target/release/libatmosphere_android.so" \
        --language kotlin \
        --out-dir "$KOTLIN_OUTPUT_DIR" \
        2>/dev/null || \
    uniffi-bindgen generate \
        src/atmosphere.udl \
        --language kotlin \
        --out-dir "$KOTLIN_OUTPUT_DIR"
    
    log_info "Kotlin bindings generated at $KOTLIN_OUTPUT_DIR"
}

# Build host library (for binding generation)
build_host() {
    log_info "Building host library for binding generation..."
    cd "$SCRIPT_DIR"
    cargo build --release
}

# Main build process
main() {
    local targets=()
    local skip_bindings=false
    local only_bindings=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --arm64)
                targets+=("aarch64-linux-android")
                shift
                ;;
            --arm32)
                targets+=("armv7-linux-androideabi")
                shift
                ;;
            --x86_64)
                targets+=("x86_64-linux-android")
                shift
                ;;
            --all)
                targets=(
                    "aarch64-linux-android"
                    "armv7-linux-androideabi"
                    "x86_64-linux-android"
                )
                shift
                ;;
            --skip-bindings)
                skip_bindings=true
                shift
                ;;
            --bindings-only)
                only_bindings=true
                shift
                ;;
            --help|-h)
                echo "Usage: $0 [OPTIONS]"
                echo ""
                echo "Options:"
                echo "  --arm64          Build for ARM64 (aarch64-linux-android)"
                echo "  --arm32          Build for ARM32 (armv7-linux-androideabi)"
                echo "  --x86_64         Build for x86_64 emulator"
                echo "  --all            Build for all targets"
                echo "  --skip-bindings  Skip Kotlin binding generation"
                echo "  --bindings-only  Only generate Kotlin bindings"
                echo "  --help, -h       Show this help"
                echo ""
                echo "Environment variables:"
                echo "  ANDROID_NDK_HOME    Path to Android NDK"
                echo "  ANDROID_API_LEVEL   Target API level (default: 21)"
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                exit 1
                ;;
        esac
    done
    
    # Default to ARM64 only if no targets specified
    if [ ${#targets[@]} -eq 0 ] && [ "$only_bindings" = false ]; then
        targets=("aarch64-linux-android")
    fi
    
    # Build host first for binding generation
    build_host
    
    if [ "$only_bindings" = true ]; then
        generate_kotlin_bindings
        exit 0
    fi
    
    check_ndk
    install_targets
    
    # Build each target
    for target in "${targets[@]}"; do
        build_target "$target"
    done
    
    # Generate Kotlin bindings
    if [ "$skip_bindings" = false ]; then
        generate_kotlin_bindings
    fi
    
    log_info "Build complete!"
    log_info ""
    log_info "Native libraries: $OUTPUT_DIR"
    if [ "$skip_bindings" = false ]; then
        log_info "Kotlin bindings: $KOTLIN_OUTPUT_DIR"
    fi
}

main "$@"
