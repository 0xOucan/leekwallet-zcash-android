# LeekWallet Zcash for Android: releases

Releases of this fork are published on its
[GitHub releases page](https://github.com/0xOucan/leekwallet-zcash-android/releases).
Each one has two APKs, built from the same commit and signed with the same key:

| File | App name | Package | Network |
|---|---|---|---|
| `leekwallet-zcash-zodl-<version>-mainnet.apk` | LeekWallet Zcash | `io.leekwallet.zcash.foss` | Zcash mainnet (ZEC) |
| `leekwallet-zcash-zodl-<version>-testnet.apk` | LeekWallet Zcash (testnet) | `io.leekwallet.zcash.testnet.foss` | Zcash testnet (TAZ) |

and next to them `SHA256SUMS`, its signature `SHA256SUMS.sig`, the
`allowed_signers` file to check it with, and `BUILDINFO.txt` (the commits,
versions and toolchain the APKs were built from).

> **Experimental.** The LeekWallet board is not audited and has no flash
> encryption or secure boot. Use the testnet app, or small amounts.

## Which one

- **Mainnet** for real ZEC. **Testnet** for trying the board with TAZ, which
  has no value. Pick the one that matches the network you want; the board
  serves either, with different keys and addresses for each.
- Both apps install side by side, and next to Zodl itself: each has its own
  package name, storage and wallet. Nothing is shared between them.
- The APKs are the FOSS build of Zodl (no Firebase, no Google ML Kit; QR
  scanning uses zxing-cpp), for arm64 phones (the LeekWallet native library
  is built for `arm64-v8a` only).

## Prebuilt APKs are a trust decision

A prebuilt APK from a release page asks you to trust whoever built it, the
machine it was built on, and the account that uploaded it. The checks below
tell you the file is the one the maintainer signed. They do not tell you
the maintainer's machine was clean, or that the APK matches the source code.
For a wallet, **building it yourself from source is the recommended way**
(see [Rebuilding](#rebuilding)); use the prebuilt APKs only if you accept
that trust.

## Verifying a download

Download the APK(s) you want together with `SHA256SUMS`, `SHA256SUMS.sig`
and `allowed_signers` into one folder.

**1. The signature on the checksums.** `allowed_signers` holds the
maintainer's SSH public key. Do not just trust the copy next to the APKs:
compare its key with the one at <https://github.com/0xOucan.keys> (and any
other place the maintainer publishes it), then:

```sh
ssh-keygen -Y verify -f allowed_signers -I 0xoucanxxx@gmail.com -n file \
  -s SHA256SUMS.sig < SHA256SUMS
```

It must print `Good "file" signature for 0xoucanxxx@gmail.com`.

**2. The checksums.**

```sh
sha256sum --check --ignore-missing SHA256SUMS
```

Every file you downloaded must say `OK`.

**3. The APK signing certificate.** Android only installs an update when it
is signed by the same certificate as the installed app, so pinning it once
protects every later update. Check it with `apksigner` (Android SDK
build-tools):

```sh
apksigner verify --print-certs leekwallet-zcash-zodl-<version>-mainnet.apk
```

`Signer #1 certificate SHA-256 digest` must be:

```
TODO: placeholder until the first signed release. Do not install an APK
      that claims to be a release while this line is a placeholder.
```

Both APKs are signed with the same certificate. On the phone, apps such as
AppVerifier show the same fingerprint for an installed app.

## Rebuilding

The release is built by [`scripts/leekwallet-release.sh`](scripts/leekwallet-release.sh).
`BUILDINFO.txt` in the release lists what it used: this repository's commit,
the commit of the main repository
([leekwallet-zcash](https://github.com/0xOucan/leekwallet-zcash)) whose
`host/leek-android` crate is the native library, the versionCode, the Zcash
SDK version and the Rust, Java and Gradle versions.

```sh
git clone https://github.com/0xOucan/leekwallet-zcash.git      # full clone, not --depth
cd leekwallet-zcash && git checkout <host commit from BUILDINFO.txt>
git clone https://github.com/0xOucan/leekwallet-zcash-android.git android
cd android && git checkout <commit from BUILDINFO.txt>

# JDK 21, Android SDK, NDK 27.2.12479018, Rust with aarch64-linux-android
# (the version in BUILDINFO.txt): see README.md, Build.
LEEK_UNSIGNED=1 LEEK_ALLOW_SNAPSHOT=1 LEEK_VERSION_CODE=<versionCode> \
  scripts/leekwallet-release.sh <version>
```

This writes unsigned APKs under `build/leekwallet-release/<version>/`. To
compare one with the published, signed APK, copy the published signature
onto your build and check that it still verifies, with
[apksigcopier](https://github.com/obfusk/apksigcopier):

```sh
apksigcopier compare leekwallet-zcash-zodl-<version>-mainnet.apk \
  --unsigned build/leekwallet-release/<version>/leekwallet-zcash-zodl-<version>-mainnet-unsigned.apk \
  && echo identical
```

If it is not identical, `diffoscope` shows where the two differ.

**What to expect.** Reproducible builds are not a goal Zodl claims today.
Its [Build integrity](docs/Build%20integrity.md) notes cover the supply
chain: pinned dependency versions, Gradle dependency locks, the Gradle
distribution checked by SHA-256, GitHub Actions pinned by commit. They also
say dependency hash verification is *not* enabled, and
[CI.md](docs/CI.md) says plainly that its CI builds are not byte-reproducible.
On top of that:

- the Zcash SDK is a `-SNAPSHOT` version at the moment, which can resolve to
  different code on a later day (the script refuses to build unless you
  accept it with `LEEK_ALLOW_SNAPSHOT=1`);
- the native library is built by Rust and the NDK; the script strips local
  paths (`--remap-path-prefix`) and uses the locked `Cargo.lock`, but a
  different Rust version gives different bytes;
- the commit hash and commit count are compiled in, so the build must come
  from the exact commit with full history.

So a rebuild may differ from the release. Treat an identical rebuild as a
strong check, and a different one as a reason to look closer, not as proof
of tampering. Either way, the APK you build yourself is one you can trust as
far as you trust the source.

## For the maintainer: making a release

1. Keep the release keystore outside the repository (and backed up: losing
   it means users must uninstall to update). Never commit it or its
   passwords.
2. On a clean checkout of the commit to release, with the main repository's
   `host/` next to it (or `LEEK_HOST_DIR` set):

   ```sh
   export LEEK_RELEASE_KEYSTORE=~/keys/leekwallet-release.jks
   export LEEK_RELEASE_KEY_ALIAS=leekwallet
   LEEK_ALLOW_SNAPSHOT=1 scripts/leekwallet-release.sh 3.17.0-leek.1
   ```

   The passwords are asked for (or read from `LEEK_RELEASE_KEYSTORE_PASSWORD`
   and `LEEK_RELEASE_KEY_PASSWORD`) and handed to Gradle through the
   environment as Zodl's `ZCASH_RELEASE_*` properties. Instead of the
   `LEEK_*` variables you can put the four `ZCASH_RELEASE_*` properties in
   `~/.gradle/gradle.properties`. After the first release, set
   `LEEK_CERT_SHA256` so the script refuses any other key.
3. The script builds `:app:assembleZcashmainnetFossRelease` and
   `:app:assembleZcashtestnetFossRelease`, checks both APKs, writes
   `SHA256SUMS` and `BUILDINFO.txt`, prints `apksigner verify --print-certs`,
   and prints (does not run) the `ssh-keygen -Y sign`, `git tag` and
   `gh release create --draft` commands. Test both APKs with the board,
   then run those commands.
4. On the first signed release, replace the placeholder fingerprint above.

**Versions.** versionName is Zodl's version plus a LeekWallet counter, e.g.
`3.17.0-leek.1`, `3.17.0-leek.2`, then `3.18.0-leek.1` after rebasing on
Zodl 3.18.0; the tag is `v<versionName>`. versionCode defaults to the commit
count of the built commit (Zodl's own rule); it must grow with every
release, or phones refuse the update.

**GitHub Actions.** Zodl's workflows in `.github/workflows` need Zodl's
secrets and infrastructure. In this fork their automatic triggers (push,
tag, schedule, pull request, release published) are commented out, so a
push or a release here starts nothing; each can still be run by hand.
Releases are built locally with the script above, not in CI.
