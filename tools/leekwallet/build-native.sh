#!/usr/bin/env bash
# Build the LeekWallet native library (host/leek-android in the parent repo)
# for the phone's ABI and place it where Gradle packages it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
HOST="$HERE/../host"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.2.12479018}/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$NDK/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$NDK/llvm-ar"
(cd "$HOST" && cargo build --release -p leek-android --lib --target aarch64-linux-android)
mkdir -p "$HERE/feature-leekwallet/src/main/jniLibs/arm64-v8a"
cp "$HOST/target/aarch64-linux-android/release/libleek_android.so" "$HERE/feature-leekwallet/src/main/jniLibs/arm64-v8a/"
echo "libleek_android.so -> feature-leekwallet/src/main/jniLibs/arm64-v8a/"
