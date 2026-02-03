# Atmosphere Android - Build Makefile
#
# Quick commands:
#   make build       - Build for ARM64 (default)
#   make build-all   - Build for all Android targets
#   make bindings    - Generate Kotlin bindings only
#   make clean       - Clean build artifacts
#   make test        - Run tests

.PHONY: all build build-all build-arm64 build-arm32 build-x86_64 bindings clean test setup help

# Default target
all: build bindings

# Build for ARM64 (most common Android target)
build: build-arm64

# Build for all Android targets
build-all:
	./build-android.sh --all

# Individual target builds
build-arm64:
	./build-android.sh --arm64

build-arm32:
	./build-android.sh --arm32

build-x86_64:
	./build-android.sh --x86_64

# Generate Kotlin bindings only
bindings:
	./build-android.sh --bindings-only

# Build host library (for development/testing)
host:
	cargo build --release

# Run tests
test:
	cargo test

# Clean all build artifacts
clean:
	cargo clean
	rm -rf ../target
	rm -rf ../app/src/main/jniLibs/*/*.so
	rm -rf ../app/src/main/kotlin/com/llamafarm/atmosphere/bindings/*.kt

# Install required Rust targets
setup:
	rustup target add aarch64-linux-android
	rustup target add armv7-linux-androideabi
	rustup target add x86_64-linux-android
	@echo ""
	@echo "Targets installed. Make sure you have Android NDK installed."
	@echo "Set ANDROID_NDK_HOME if not using default location."

# Show help
help:
	@echo "Atmosphere Android Build System"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@echo "Build Targets:"
	@echo "  build        Build for ARM64 (default)"
	@echo "  build-all    Build for all Android architectures"
	@echo "  build-arm64  Build for ARM64 only"
	@echo "  build-arm32  Build for ARM32 only"
	@echo "  build-x86_64 Build for x86_64 emulator"
	@echo "  bindings     Generate Kotlin bindings only"
	@echo "  host         Build host library (for testing)"
	@echo ""
	@echo "Other Targets:"
	@echo "  test         Run Rust tests"
	@echo "  clean        Clean all build artifacts"
	@echo "  setup        Install required Rust targets"
	@echo "  help         Show this help"
	@echo ""
	@echo "Environment Variables:"
	@echo "  ANDROID_NDK_HOME    Path to Android NDK"
	@echo "  ANDROID_API_LEVEL   Target API level (default: 21)"
