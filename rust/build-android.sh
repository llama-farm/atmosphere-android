#!/bin/bash
set -e

# Atmosphere Android - Rust Cross-Compilation Script
# ===================================================
# Builds the Rust core library for all Android targets

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Configuration
MIN_SDK_VERSION="${RUST_MIN_SDK:-24}"
ANDROID_TARGETS="aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android"

# Target to Android ABI mapping (using case statement for bash 3 compatibility)
get_abi() {
    case "$1" in
        "aarch64-linux-android") echo "arm64-v8a" ;;
        "armv7-linux-androideabi") echo "armeabi-v7a" ;;
        "x86_64-linux-android") echo "x86_64" ;;
        "i686-linux-android") echo "x86" ;;
        *) echo "unknown" ;;
    esac
}

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

# Find Android NDK
find_ndk() {
    if [[ -n "$ANDROID_NDK_HOME" ]]; then
        echo "$ANDROID_NDK_HOME"
        return
    fi
    
    # Check common locations
    local possible_paths=(
        "$HOME/Library/Android/sdk/ndk"
        "$HOME/Android/Sdk/ndk"
        "/usr/local/share/android-ndk"
    )
    
    for base in "${possible_paths[@]}"; do
        if [[ -d "$base" ]]; then
            # Find latest NDK version
            local latest=$(ls -1 "$base" 2>/dev/null | sort -V | tail -1)
            if [[ -n "$latest" ]]; then
                echo "$base/$latest"
                return
            fi
        fi
    done
    
    return 1
}

# Setup environment for cross-compilation
setup_env() {
    local target=$1
    local ndk=$2
    
    # Determine host platform
    local host_tag
    case "$(uname -s)" in
        Darwin) host_tag="darwin-x86_64" ;;
        Linux) host_tag="linux-x86_64" ;;
        *) log_error "Unsupported host OS"; exit 1 ;;
    esac
    
    local toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag"
    
    # Get clang prefix based on target
    local clang_prefix
    case "$target" in
        "aarch64-linux-android") clang_prefix="aarch64-linux-android$MIN_SDK_VERSION" ;;
        "armv7-linux-androideabi") clang_prefix="armv7a-linux-androideabi$MIN_SDK_VERSION" ;;
        "x86_64-linux-android") clang_prefix="x86_64-linux-android$MIN_SDK_VERSION" ;;
        "i686-linux-android") clang_prefix="i686-linux-android$MIN_SDK_VERSION" ;;
    esac
    
    export CC="$toolchain/bin/${clang_prefix}-clang"
    export CXX="$toolchain/bin/${clang_prefix}-clang++"
    export AR="$toolchain/bin/llvm-ar"
    export RANLIB="$toolchain/bin/llvm-ranlib"
    
    # Rust-specific linker settings
    local rust_target_upper=$(echo "$target" | tr '[:lower:]-' '[:upper:]_')
    export "CARGO_TARGET_${rust_target_upper}_LINKER=$CC"
}

# Build for a single target
build_target() {
    local target=$1
    local ndk=$2
    local release=$3
    
    log_info "Building for $target..."
    
    setup_env "$target" "$ndk"
    
    local build_cmd="cargo build --target $target"
    if [[ "$release" == "true" ]]; then
        build_cmd="$build_cmd --release"
    fi
    
    cd "$PROJECT_DIR"
    $build_cmd
    
    # Copy output
    local profile="debug"
    if [[ "$release" == "true" ]]; then
        profile="release"
    fi
    
    local abi=$(get_abi "$target")
    local output_dir="$PROJECT_DIR/app/src/main/jniLibs/$abi"
    mkdir -p "$output_dir"
    
    local lib_name="libatmosphere_android.so"
    cp "$PROJECT_DIR/target/$target/$profile/$lib_name" "$output_dir/" 2>/dev/null || log_warn "Library not found for $target"
    
    log_info "Built $target -> $output_dir"
}

# Main
main() {
    local release=false
    local targets=""
    
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --release) release=true; shift ;;
            --target) targets="$2"; shift 2 ;;
            *) shift ;;
        esac
    done
    
    # Default to all targets if none specified
    if [[ -z "$targets" ]]; then
        targets="$ANDROID_TARGETS"
    fi
    
    # Find NDK
    local ndk=$(find_ndk)
    if [[ -z "$ndk" ]]; then
        log_error "Android NDK not found. Set ANDROID_NDK_HOME or install via Android Studio."
        exit 1
    fi
    log_info "Using NDK: $ndk"
    
    # Check Rust targets are installed
    for target in $targets; do
        if ! rustup target list --installed | grep -q "$target"; then
            log_info "Installing Rust target: $target"
            rustup target add "$target"
        fi
    done
    
    # Build each target
    for target in $targets; do
        build_target "$target" "$ndk" "$release"
    done
    
    log_info "Build complete!"
}

main "$@"
