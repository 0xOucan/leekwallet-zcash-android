#!/usr/bin/env bash
# Build the two LeekWallet Zcash release APKs (mainnet and testnet), name them,
# write SHA256SUMS and BUILDINFO.txt, show the signing certificate, and print
# (never run) the commands that sign the checksums and publish the release.
#
#   scripts/leekwallet-release.sh 3.17.0-leek.1
#
# Signing (nothing secret lives in this repository). Either export:
#   LEEK_RELEASE_KEYSTORE           path to the release keystore, outside the repo
#   LEEK_RELEASE_KEY_ALIAS          key alias inside it
#   LEEK_RELEASE_KEYSTORE_PASSWORD  asked for if unset
#   LEEK_RELEASE_KEY_PASSWORD       asked for if unset (Enter = keystore password)
# or put Zodl's ZCASH_RELEASE_KEYSTORE_PATH, ZCASH_RELEASE_KEYSTORE_PASSWORD,
# ZCASH_RELEASE_KEY_ALIAS and ZCASH_RELEASE_KEY_ALIAS_PASSWORD in
# ~/.gradle/gradle.properties. LEEK_UNSIGNED=1 builds unsigned APKs instead,
# for rebuilding a published release and comparing it (LEEKWALLET-RELEASE.md).
#
# Optional:
#   LEEK_VERSION_CODE   versionCode (default: commit count of HEAD, Zodl's rule)
#   LEEK_DISTRIBUTION   foss (default: no Firebase, no ML Kit) or store
#   LEEK_CERT_SHA256    expected signing certificate SHA-256 (default: the
#                       project release key); abort on mismatch
#   LEEK_HOST_DIR       the main repo's host/ (default: ../host)
#   LEEK_SKIP_NATIVE=1  keep the libleek_android.so already in jniLibs
#   LEEK_ALLOW_SNAPSHOT=1  build even though a dependency is a -SNAPSHOT
#   LEEK_ALLOW_DIRTY=1  build with uncommitted changes (never for a release)
#   LEEK_OUT            output directory (default: build/leekwallet-release/<version>)
#   LEEK_GRADLE_ARGS    extra Gradle arguments (e.g. the low-RAM flags in README)
set -Eeuo pipefail
trap 'echo "error: line $LINENO: $BASH_COMMAND" >&2' ERR

die() { echo "error: $*" >&2; exit 1; }
note() { printf '\n== %s\n' "$*"; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION_NAME="${1:-}"
[[ -n "$VERSION_NAME" ]] || die "usage: $0 <versionName>, e.g. $0 3.17.0-leek.1"
[[ "$VERSION_NAME" =~ ^[0-9A-Za-z.+-]+$ ]] || die "versionName has odd characters: $VERSION_NAME"

prop() { sed -n "s/^$1=//p" "$ROOT/gradle.properties" | tail -n 1; }
UPSTREAM_VERSION="$(prop ZCASH_VERSION_NAME)"
[[ "$VERSION_NAME" == "$UPSTREAM_VERSION"-leek.* ]] ||
  echo "warning: $VERSION_NAME does not start with Zodl's $UPSTREAM_VERSION-leek." >&2

DIST="${LEEK_DISTRIBUTION:-foss}"
case "$DIST" in
  foss) DIST_CAP=Foss ;;
  store) DIST_CAP=Store ;;
  *) die "LEEK_DISTRIBUTION must be foss or store" ;;
esac

# --- tools -------------------------------------------------------------------
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f local.properties ]]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties)"
fi
APKSIGNER="$(ls -d "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
[[ -x "$APKSIGNER" ]] || APKSIGNER="$(command -v apksigner || true)"
[[ -n "$APKSIGNER" ]] || die "apksigner not found (Android SDK build-tools)"
AAPT2="$(ls -d "$SDK_DIR"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1 || true)"
for t in git java sha256sum unzip; do
  command -v "$t" >/dev/null || die "$t not found"
done

# --- source state ------------------------------------------------------------
COMMIT="$(git rev-parse HEAD)"
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  [[ "${LEEK_ALLOW_DIRTY:-}" == 1 ]] || die "uncommitted changes; a release must build from a commit"
  echo "warning: building with uncommitted changes" >&2
fi
if git rev-parse --is-shallow-repository | grep -q true; then
  die "shallow clone: the versionCode and the embedded commit count need full history"
fi
VERSION_CODE="${LEEK_VERSION_CODE:-$(git rev-list --count HEAD)}"
[[ "$VERSION_CODE" =~ ^[0-9]+$ && "$VERSION_CODE" -gt 1 ]] || die "bad versionCode: $VERSION_CODE"

SNAPSHOTS="$(grep -E '^[A-Z0-9_]+_VERSION=.*SNAPSHOT' gradle.properties || true)"
if [[ -n "$SNAPSHOTS" ]]; then
  echo "warning: snapshot dependencies; a rebuild later may resolve different code:" >&2
  echo "$SNAPSHOTS" >&2
  [[ "${LEEK_ALLOW_SNAPSHOT:-}" == 1 ]] || die "set LEEK_ALLOW_SNAPSHOT=1 to build anyway"
fi

# --- signing -----------------------------------------------------------------
GRADLE_SIGN_ARGS=()
USER_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
if [[ "${LEEK_UNSIGNED:-}" == 1 ]]; then
  echo "LEEK_UNSIGNED=1: building unsigned APKs (rebuild check, not for release)"
  # -P wins over ~/.gradle/gradle.properties; a blank path leaves the APKs unsigned.
  GRADLE_SIGN_ARGS=(-PZCASH_RELEASE_KEYSTORE_PATH=)
elif [[ -n "${LEEK_RELEASE_KEYSTORE:-}" ]]; then
  [[ -f "$LEEK_RELEASE_KEYSTORE" ]] || die "keystore not found: $LEEK_RELEASE_KEYSTORE"
  KS="$(realpath "$LEEK_RELEASE_KEYSTORE")"
  [[ "$KS" != "$ROOT"/* ]] || die "keep the keystore outside the repository"
  [[ -n "${LEEK_RELEASE_KEY_ALIAS:-}" ]] || die "LEEK_RELEASE_KEY_ALIAS is not set"
  ! grep -qs '^ZCASH_RELEASE_KEYSTORE_PATH=.\+' "$USER_PROPS" ||
    die "$USER_PROPS also sets ZCASH_RELEASE_*; use one or the other"
  if [[ -z "${LEEK_RELEASE_KEYSTORE_PASSWORD:-}" ]]; then
    read -r -s -p "Keystore password: " LEEK_RELEASE_KEYSTORE_PASSWORD; echo
  fi
  if [[ -z "${LEEK_RELEASE_KEY_PASSWORD:-}" ]]; then
    read -r -s -p "Key password (Enter = same): " LEEK_RELEASE_KEY_PASSWORD; echo
    LEEK_RELEASE_KEY_PASSWORD="${LEEK_RELEASE_KEY_PASSWORD:-$LEEK_RELEASE_KEYSTORE_PASSWORD}"
  fi
  # Environment, not -P, so the passwords stay out of the process list.
  export ORG_GRADLE_PROJECT_ZCASH_RELEASE_KEYSTORE_PATH="$KS"
  export ORG_GRADLE_PROJECT_ZCASH_RELEASE_KEY_ALIAS="$LEEK_RELEASE_KEY_ALIAS"
  export ORG_GRADLE_PROJECT_ZCASH_RELEASE_KEYSTORE_PASSWORD="$LEEK_RELEASE_KEYSTORE_PASSWORD"
  export ORG_GRADLE_PROJECT_ZCASH_RELEASE_KEY_ALIAS_PASSWORD="$LEEK_RELEASE_KEY_PASSWORD"
elif grep -qs '^ZCASH_RELEASE_KEYSTORE_PATH=.\+' "$USER_PROPS"; then
  echo "signing with ZCASH_RELEASE_* from $USER_PROPS"
else
  die "no release signing: set LEEK_RELEASE_KEYSTORE and LEEK_RELEASE_KEY_ALIAS (or LEEK_UNSIGNED=1)"
fi

OUT="${LEEK_OUT:-$ROOT/build/leekwallet-release/$VERSION_NAME}"
mkdir -p "$OUT"
[[ -z "$(ls -A "$OUT")" ]] || die "$OUT is not empty"

# --- native library ----------------------------------------------------------
SO_REL=feature-leekwallet/src/main/jniLibs/arm64-v8a/libleek_android.so
HOST_DIR="$(realpath -m "${LEEK_HOST_DIR:-$ROOT/../host}")"
if [[ "${LEEK_SKIP_NATIVE:-}" != 1 ]]; then
  note "native library (host/leek-android)"
  [[ -f "$HOST_DIR/Cargo.toml" ]] || die "host/ not found at $HOST_DIR; set LEEK_HOST_DIR"
  # Strip local paths from the binary so others can rebuild the same bytes.
  RUSTFLAGS="${RUSTFLAGS:-} --remap-path-prefix=$HOST_DIR=/leek/host"
  RUSTFLAGS+=" --remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo"
  RUSTFLAGS+=" --remap-path-prefix=${RUSTUP_HOME:-$HOME/.rustup}=/rustup"
  export RUSTFLAGS
  LEEK_HOST_DIR="$HOST_DIR" tools/leekwallet/build-native.sh --locked
fi
[[ -f "$SO_REL" ]] || die "$SO_REL missing; run tools/leekwallet/build-native.sh"

# --- build -------------------------------------------------------------------
TASKS=(":app:assembleZcashmainnet${DIST_CAP}Release" ":app:assembleZcashtestnet${DIST_CAP}Release")
note "gradle ${TASKS[*]}"
# shellcheck disable=SC2086
./gradlew --no-daemon --no-build-cache --no-configuration-cache \
  -PZCASH_VERSION_NAME="$VERSION_NAME" \
  -PZCASH_VERSION_CODE="$VERSION_CODE" \
  -PZCASH_FLEXA_KEY= -PZCASH_CMC_KEY= \
  -PIS_SIGN_RELEASE_BUILD_WITH_DEBUG_KEY=false \
  -PIS_RELEASE_BUILD_DEBUGGABLE=false \
  "${GRADLE_SIGN_ARGS[@]}" \
  ${LEEK_GRADLE_ARGS:-} \
  :app:clean "${TASKS[@]}"

# --- collect -----------------------------------------------------------------
declare -A PKG=([mainnet]="" [testnet]="")
CERTS=()
for net in mainnet testnet; do
  dir="app/build/outputs/apk/zcash${net}${DIST_CAP}/release"
  mapfile -t apks < <(ls "$dir"/*.apk 2>/dev/null)
  [[ ${#apks[@]} -eq 1 ]] || die "expected one APK in $dir, found ${#apks[@]}"
  name="leekwallet-zcash-zodl-${VERSION_NAME}-${net}.apk"
  [[ "${LEEK_UNSIGNED:-}" == 1 ]] && name="${name%.apk}-unsigned.apk"
  cp "${apks[0]}" "$OUT/$name"

  unzip -l "$OUT/$name" | grep -q "lib/arm64-v8a/libleek_android.so" ||
    die "$name has no libleek_android.so"
  if [[ -n "$AAPT2" ]]; then
    PKG[$net]="$("$AAPT2" dump packagename "$OUT/$name")"
  fi

  if [[ "${LEEK_UNSIGNED:-}" != 1 ]]; then
    note "apksigner verify --print-certs $name"
    "$APKSIGNER" verify --verbose --print-certs "$OUT/$name" | grep -v '^WARNING' || true
    CERTS+=("$("$APKSIGNER" verify --print-certs "$OUT/$name" |
      sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)")
  fi
done

if [[ "${LEEK_UNSIGNED:-}" != 1 ]]; then
  [[ "${CERTS[0]}" == "${CERTS[1]}" ]] || die "the two APKs are signed by different certificates"
  LEEK_CERT_SHA256="${LEEK_CERT_SHA256:-c59491e663fbb200bee06d28098a7d1f8955d5cf3dcdcd9da20c9ed2ba658eb7}"  # the release key (LEEKWALLET-RELEASE.md)
  if [[ -n "${LEEK_CERT_SHA256:-}" ]]; then
    want="$(tr -d ': ' <<<"${LEEK_CERT_SHA256,,}")"
    [[ "${CERTS[0]}" == "$want" ]] || die "certificate ${CERTS[0]} is not LEEK_CERT_SHA256 $want"
  fi
fi

# --- build info and checksums ------------------------------------------------
{
  echo "LeekWallet Zcash for Android (Zodl fork) $VERSION_NAME"
  echo "repository:      https://github.com/0xOucan/leekwallet-zcash-android"
  echo "commit:          $COMMIT"
  echo "versionName:     $VERSION_NAME"
  echo "versionCode:     $VERSION_CODE"
  echo "distribution:    $DIST (gradle ${TASKS[*]})"
  echo "mainnet package: ${PKG[mainnet]:-?}"
  echo "testnet package: ${PKG[testnet]:-?}"
  echo "zodl base:       $UPSTREAM_VERSION"
  echo "zcash sdk:       $(prop ZCASH_SDK_VERSION)"
  echo "signing cert:    ${CERTS[0]:-unsigned}"
  echo "libleek_android: $(sha256sum "$SO_REL" | cut -d' ' -f1)"
  host_commit="$(git -C "$HOST_DIR" rev-parse HEAD 2>/dev/null || echo '?')"
  [[ -z "$(git -C "$HOST_DIR" status --porcelain -- . 2>/dev/null)" ]] || host_commit+=" (dirty)"
  echo "host commit:     $host_commit (leekwallet-zcash, host/leek-android)"
  echo "rustc:           $(rustc -V 2>/dev/null || echo '?')"
  echo "java:            $(java -version 2>&1 | head -n 1)"
  echo "gradle wrapper:  $(sed -n 's/^distributionUrl=.*gradle-\(.*\)-bin.zip/\1/p' gradle/wrapper/gradle-wrapper.properties)"
  echo "built on:        $(uname -sm)"
} >"$OUT/BUILDINFO.txt"

(cd "$OUT" && sha256sum -- *.apk BUILDINFO.txt >SHA256SUMS)
note "$OUT"
cat "$OUT/BUILDINFO.txt"
echo
cat "$OUT/SHA256SUMS"

[[ "${LEEK_UNSIGNED:-}" == 1 ]] && exit 0

# --- next steps (printed, not run) -------------------------------------------
TAG="v$VERSION_NAME"
SSH_KEY="${LEEK_SSH_KEY:-$HOME/.ssh/id_ed25519}"
cat <<EOF

== next steps (not run by this script)

# 1. Install both APKs on a test phone and check them against the board.

# 2. Sign the checksums with your SSH key (namespace "file"):
ssh-keygen -Y sign -f $SSH_KEY -n file $OUT/SHA256SUMS
echo "0xoucanxxx@gmail.com namespaces=\"file\" \$(cut -d' ' -f1,2 $SSH_KEY.pub)" > $OUT/allowed_signers
ssh-keygen -Y verify -f $OUT/allowed_signers -I 0xoucanxxx@gmail.com -n file \\
  -s $OUT/SHA256SUMS.sig < $OUT/SHA256SUMS

# 3. Put the certificate fingerprint in LEEKWALLET-RELEASE.md if it is the first
#    signed release (it must never change afterwards):
#    ${CERTS[0]}

# 4. Tag the commit that was built and publish (review the draft before publishing):
git tag -a $TAG $COMMIT -m "LeekWallet Zcash for Android $VERSION_NAME"
git push origin $TAG
gh release create $TAG --repo 0xOucan/leekwallet-zcash-android --draft --prerelease \\
  --title "LeekWallet Zcash for Android $VERSION_NAME" \\
  --notes "Zodl $UPSTREAM_VERSION with LeekWallet hardware signing. Verify before installing: see LEEKWALLET-RELEASE.md." \\
  $OUT/leekwallet-zcash-zodl-$VERSION_NAME-mainnet.apk \\
  $OUT/leekwallet-zcash-zodl-$VERSION_NAME-testnet.apk \\
  $OUT/BUILDINFO.txt $OUT/SHA256SUMS $OUT/SHA256SUMS.sig $OUT/allowed_signers
EOF
