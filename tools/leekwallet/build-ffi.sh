#!/usr/bin/env bash
# Build the LeekWallet SDK (host/leek-zcash-ffi in the main repo) for Android
# and lay it out for the :leek-zcash-sdk Gradle module:
#
#   leek-zcash-sdk/sdk/jniLibs/<abi>/libleek_zcash_ffi.so
#   leek-zcash-sdk/sdk/kotlin/io/leekwallet/zcash/sdk/leek_zcash_ffi.kt
#   leek-zcash-sdk/sdk/consumer-rules.pro
#   leek-zcash-sdk/sdk/SDK_COMMIT
#
# The same steps as the SDK's own scripts/build-android.sh, but nothing is
# written into the SDK tree: cargo builds into this checkout's
# build/leek-ffi-cargo (CARGO_TARGET_DIR), --locked.
#
# 64-bit only (arm64-v8a, x86_64 for the emulator), API 27 (the app's
# minSdk), 16 KB ELF page alignment (Android 15+), local paths remapped so
# others can rebuild the same bytes. The Kotlin bindings are regenerated from
# the arm64 library just built (UniFFI library mode) and must match the SDK's
# committed bindings byte for byte; then the libraries are stripped.
#
# Env: LEEK_HOST_DIR (the main repo's host/, default ../host),
# ANDROID_NDK_HOME (default ~/Android/Sdk/ndk/27.2.12479018), ANDROID_API
# (default 27), ABIS (default "arm64-v8a x86_64"). Extra arguments go to
# cargo build (for example --offline).
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
host="$(realpath -m "${LEEK_HOST_DIR:-$here/../host}")"
crate="$host/leek-zcash-ffi"
[[ -f "$crate/Cargo.toml" ]] || { echo "no leek-zcash-ffi in $host (set LEEK_HOST_DIR)" >&2; exit 1; }
out="$here/leek-zcash-sdk/sdk"
export CARGO_TARGET_DIR="$here/build/leek-ffi-cargo"

ndk="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.2.12479018}"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
api="${ANDROID_API:-27}"
abis="${ABIS:-arm64-v8a x86_64}"
[[ -x "$bin/clang" ]] || { echo "no NDK clang in $bin (set ANDROID_NDK_HOME)" >&2; exit 1; }

triple() {
    case "$1" in
        arm64-v8a) echo aarch64-linux-android ;;
        x86_64)    echo x86_64-linux-android ;;
        *) echo "unsupported ABI $1: 64-bit only (arm64-v8a, x86_64)" >&2; exit 1 ;;
    esac
}

remap="--remap-path-prefix=$host=/leek/host"
remap+=" --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo"
remap+=" --remap-path-prefix=${RUSTUP_HOME:-$HOME/.rustup}=/rustup"

cd "$host"
for abi in $abis; do
    t="$(triple "$abi")"
    T="$(echo "$t" | tr 'a-z-' 'A-Z_')"
    u="$(echo "$t" | tr '-' '_')"
    # Per-target variables only, so host build scripts are not rebuilt with them.
    export "CARGO_TARGET_${T}_LINKER=$bin/$t$api-clang"
    export "CARGO_TARGET_${T}_RUSTFLAGS=$remap -C link-arg=-Wl,-z,max-page-size=16384"
    export "CC_$u=$bin/$t$api-clang"
    export "CXX_$u=$bin/$t$api-clang++"
    export "AR_$u=$bin/llvm-ar"
    export "RANLIB_$u=$bin/llvm-ranlib"
    cargo build --release --locked -p leek-zcash-ffi --lib --target "$t" "$@"
done

first="$(triple "$(set -- $abis; echo "$1")")"
gen="$(mktemp -d)"
trap 'rm -rf "$gen"' EXIT
cargo run -q --release --locked -p leek-zcash-ffi --features bindgen --bin uniffi-bindgen -- \
    generate --library "$CARGO_TARGET_DIR/$first/release/libleek_zcash_ffi.so" --language kotlin \
    --config "$crate/uniffi.toml" --out-dir "$gen" --no-format
[[ -f "$gen/io/leekwallet/zcash/sdk/leek_zcash_ffi.kt" ]] \
    || { echo "uniffi-bindgen generated nothing from the Android library" >&2; exit 1; }
if ! diff -r "$gen" "$crate/bindings/kotlin" >/dev/null; then
    diff -r "$gen" "$crate/bindings/kotlin" | head -20 >&2 || true
    echo "the SDK's bindings/kotlin does not match its library: regenerate them in the SDK" >&2
    exit 1
fi

rm -rf "$out"
mkdir -p "$out"
readelf="$bin/llvm-readelf"
for abi in $abis; do
    mkdir -p "$out/jniLibs/$abi"
    so="$out/jniLibs/$abi/libleek_zcash_ffi.so"
    "$bin/llvm-strip" --strip-all -o "$so" "$CARGO_TARGET_DIR/$(triple "$abi")/release/libleek_zcash_ffi.so"
    bad="$("$readelf" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' \
        | grep -v -x -E 'libc\.so|libm\.so|libdl\.so|liblog\.so' || true)"
    [[ -z "$bad" ]] || { echo "$abi: unexpected NEEDED: $bad" >&2; exit 1; }
    if ! "$readelf" -lW "$so" | awk '$1 == "LOAD" { n++; a = strtonum($NF); if (a < 16384 || a % 16384) bad = 1 }
            END { exit (n == 0 || bad) }'; then
        echo "$abi: a LOAD segment is not 16 KB aligned" >&2; exit 1
    fi
    printf '%-10s %9d bytes  %s\n' "$abi" "$(stat -c %s "$so")" "${so#"$here"/}"
done
cp -r "$gen" "$out/kotlin"
cp "$crate/android/consumer-rules.pro" "$out/consumer-rules.pro"

commit="$(git -C "$host" rev-parse HEAD 2>/dev/null || echo '?')"
[[ -z "$(git -C "$host" status --porcelain -- . 2>/dev/null)" ]] || commit+=" (dirty)"
echo "$commit" >"$out/SDK_COMMIT"
echo "LeekWallet SDK $commit -> ${out#"$here"/}"
