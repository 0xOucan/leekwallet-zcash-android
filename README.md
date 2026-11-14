# LeekWallet Zcash for Android

The Android companion for [LeekWallet Zcash](https://github.com/0xOucan/leekwallet-zcash),
a DIY Zcash hardware wallet built on an ESP32-S3 board. It is a fork of
[Zodl](https://github.com/zodl-inc/zodl-android), the Zcash wallet for Android:
everything Zodl does, plus a LeekWallet account whose keys stay on the board.

The phone syncs, builds and proves transactions. The board holds the seed,
checks each transaction against its own keys, shows you the outputs and memo,
and signs. Only the signatures come back.

> **Not audited.** Signing over USB, Bluetooth and QR is tested on testnet,
> and on mainnet with small amounts (a shielded send signed on the board). The
> board is not audited and has no flash encryption or secure boot (no eFuses
> burned): use testnet, or small amounts only.

Website: [zcash.leekwallet.xyz](https://zcash.leekwallet.xyz) · Demo:
[2-minute video](https://www.youtube.com/watch?v=8F6_8AhsnAA) · X:
[@gmLeekWallet](https://x.com/gmLeekWallet) ·
[@0xOucan](https://x.com/0xOucan)

## What it adds to Zodl

| | |
|---|---|
| **Connect a LeekWallet** | Pair over USB-OTG or Bluetooth LE; you compare a six-digit code on the phone and the board, on every connection (no host is remembered). The app imports the board's account as watch-only, with its birthday, and syncs it |
| **Import from the View key QR** | No cable, no radio: on the board, Main menu → View key; the phone's camera reads the animated `ur:leek-zcash-accounts` and imports the account (UFVK, seed fingerprint, account index, birthday). A one-way export, not pairing |
| **Sign on the board** | Any send from the LeekWallet account goes to the board for review and signature, over **USB**, **Bluetooth**, or **QR** (air-gapped: animated `ur:leek-pczt` to the board's camera, `ur:leek-zsig` back; presets *Big codes* 25 B / 350 ms, the phone default, 40 B / 350 ms and 50 B / 500 ms; a brightness button (system, 60 %, 80 %, 100 %) and the screen kept on while the code shows). All three go through the LeekWallet SDK |
| **Compact PCZT** | The board gets the compact signer view (~1.4 KB instead of ~2.8 KB): half the QR frames, faster links. A switch on the sign screen falls back to the full view |
| **Shield on the board** | Transparent funds of the LeekWallet account (its t-address) are shielded like any other send: Zodl's Shield prompt builds the proposal, the board reviews one "From k of n" page per transparent input and "Shield ... into this wallet", and signs the inputs (a version 2 `.zsig`) |
| **Verify on device** | On the Receive screen, the board checks that the address the phone shows, even a freshly rotated one, is its own, and shows it for you to compare. An address that is not the board's is refused, with a warning that the app may be compromised. With no link, **Check on board (QR)** shows the address as a still QR for the board's Main menu → Check address, which says whether it is its own and at which index |
| **Mainnet and testnet** | Two apps that install side by side: **LeekWallet Zcash** (`io.leekwallet.zcash.foss`, mainnet) and **LeekWallet Zcash (testnet)** (`io.leekwallet.zcash.testnet.foss`). Each tells the board its network (`main` / `test`), and the board derives keys and addresses for it |
| **The LeekWallet SDK** | Pairing, the encrypted session, preflight checks, signing, signature checks and the QR codes come from the [LeekWallet SDK](https://github.com/0xOucan/leekwallet-zcash/tree/main/host/leek-zcash-ffi) (`leek-zcash-ffi`, Kotlin bindings over JNA); the app keeps only its USB and Bluetooth links |
| **No voting** | The board does not sign governance ballots, so coinholder voting is refused for a LeekWallet account (select a Zodl account to vote) |
| **QR is never pairing** | The QR codes carry a transaction, its signatures, an address to check or the View key export. Pairing (the six-digit code and the encrypted session) needs USB or Bluetooth |
| **64-bit phones only** | Android 64-bit (arm64-v8a) phones. The SDK's native library is built for 64-bit only, so release APKs leave out 32-bit (armeabi-v7a) |
| **Branding** | App name LeekWallet Zcash, package `io.leekwallet.zcash` (`.foss` in the released FOSS build), its own icons and file-provider authorities (so it installs next to Zodl) |

Which link to use: **USB or Bluetooth** in a place you trust, for everyday
use; they take seconds. **QR** keeps the board off every cable and radio but
takes about 28 frames per send; use it when that isolation matters.

## Zodl base

The fork is on **Zodl 3.20.0** (3807); its version names are Zodl's plus a
LeekWallet counter (`3.20.0-leek.1`). Everything Zodl 3.20.0 added comes
with it, with Zodl's defaults:

- **Private servers (OHTTP).** Syncing, the price feed, swaps, voting and
  Flexa go through Zodl's OHTTP gateways by default; Settings → servers also
  offers Public and Manual. The LeekWallet account syncs and broadcasts the
  same way: a transaction signed on the board is broadcast once, to the
  current server, like Zodl's own.
- **Zcash Android SDK 8.0.0**, which is not on Maven Central yet. The build
  pins the exact snapshot Zodl 3.20.0 shipped with, `8.0.0-20261109.071156-1`
  (in `gradle.properties`, from the `com.zodl.android` group on
  `central.sonatype.com/repository/maven-snapshots`), so every build gets
  the same SDK; it moves to `8.0.0` once that is released.
- **Flexa built from source** (`third-party/flexa`). Flexa pays from a Zodl
  account only, not from a LeekWallet one.

## Using it with the board

1. Flash and wire the board: see the main repository's
   [README](https://github.com/0xOucan/leekwallet-zcash#readme) and
   [wiring guide](https://github.com/0xOucan/leekwallet-zcash/blob/main/firmware/docs/WIRING-S3CAM.md).
   Create or restore the wallet on the board, and unlock it with your PIN.
2. In the app: add a hardware wallet → **LeekWallet**. Pick USB (OTG cable) or
   Bluetooth (on the board: Settings → Link → Bluetooth), press Start, compare
   the six digits, press ALLOW on the board. Or, with no cable: **No cable:
   import from the View key QR**, then on the board Main menu → View key.
3. Send as usual from the LeekWallet account. On the sign screen pick the
   link, press Start, review every page on the board and ACCEPT. Over QR,
   the board scans the phone (3x zoom, about 30 cm away), then the phone
   scans the board's signature (Scan signature). If the board finds the code
   but doesn't read it, try a lower brightness with the brightness button
   first, then a higher one. On an OLED phone turn on anti-flicker / DC
   dimming, keep the board's exposure at 0 and use Big codes.
4. Receive → LeekWallet Shielded Address → **Verify on device** to check the
   address on the board's screen, or **Check on board (QR)** with no link
   (on the board: Main menu → Check address, then scan the code).
5. Funds on the LeekWallet transparent address show as transparent balance.
   Press **Shield** (on Home, or in the balance details): the LeekWallet sign
   screen opens; pick the link, press Start, check each "From" page (your
   t-address and its path) and the "Shield ... into this wallet" page on the
   board, and ACCEPT. Leaving the sign screen drops the shield; press Shield
   again to retry. A transparent balance that would all go to the fee is not
   shielded.

Use the app that matches the network: the mainnet app for ZEC, the testnet
app for TAZ. The board serves either; the account (and its addresses) differ
per network. When the board's stored birthday belongs to the other network's
chain (a testnet birthday is past mainnet's tip), the mainnet app ignores it
and scans the account from the start.

## Releases

Signed APKs of both apps (mainnet and testnet) are on the
[releases page](https://github.com/0xOucan/leekwallet-zcash-android/releases).
[LEEKWALLET-RELEASE.md](LEEKWALLET-RELEASE.md) says how to verify them
(checksums, SSH signature, signing certificate) and how to rebuild them.
Building it yourself, below, is still the safer way to get a wallet.

## Build

This repository is meant to sit inside the main one, as `android/`, because the
LeekWallet SDK is built from the main repository's `host/leek-zcash-ffi` crate
(`LEEK_HOST_DIR` points elsewhere).

```sh
git clone git@github.com:0xOucan/leekwallet-zcash.git
cd leekwallet-zcash
git clone -b leekwallet git@github.com:0xOucan/leekwallet-zcash-android.git android
cd android
```

You need what Zodl needs ([docs/Setup.md](docs/Setup.md): JDK, Android SDK),
plus Rust with the `aarch64-linux-android` and `x86_64-linux-android` targets
and the Android NDK (27.2.12479018 by default, or set `ANDROID_NDK_HOME`).

```sh
rustup target add aarch64-linux-android x86_64-linux-android
tools/leekwallet/build-ffi.sh   # the SDK: .so (arm64-v8a, x86_64) + Kotlin bindings + R8 rules

./gradlew :app:assembleZcashtestnetFossDebug      # LeekWallet Zcash (testnet)
./gradlew :app:assembleZcashmainnetFossDebug      # LeekWallet Zcash (mainnet)
adb install -r app/build/outputs/apk/zcashtestnetFoss/debug/app-zcashtestnet-foss-debug.apk
adb install -r app/build/outputs/apk/zcashmainnetFoss/debug/app-zcashmainnet-foss-debug.apk
```

Tests: `./gradlew :ui-lib:testZcashtestnetFossDebugUnitTest` (the LeekWallet
hooks in ui-lib, such as shield routing, are covered there),
`:feature-leekwallet:testZcashtestnetFossDebugUnitTest` and
`:feature-voting:testZcashtestnetFossDebugUnitTest` (the voting guard).

On a machine with little RAM, Gradle needs a lighter configuration:

```sh
./gradlew --no-daemon --no-parallel --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g" \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleZcashtestnetFossDebug
```

## How the fork is organised

The LeekWallet code is kept apart so the fork stays easy to rebase and the
hardware support can be offered to Zodl upstream.

| Where | What |
|---|---|
| `feature-leekwallet/` | The module: connect, View key import, sign and verify screens and view models, the USB-OTG and Bluetooth LE links (the SDK's `FrameTransport`), the big QR and camera views |
| `leek-zcash-sdk/` | The LeekWallet SDK packaged for Android: `libleek_zcash_ffi.so`, its UniFFI Kotlin bindings (`io.leekwallet.zcash.sdk`), JNA and the R8 rules; built into `sdk/` by `tools/leekwallet/build-ffi.sh` |
| `ui-lib/.../common/leekwallet/LeekWalletContracts.kt` | The seam between Zodl's UI and the module (navigator and nav-graph contributor, wired through Koin) |
| `ui-lib` (small hooks) | A LeekWallet account is a Keystone-shaped account with key source `leekwallet`, so it uses Zodl's existing hardware-wallet proposal pipeline (`createSignerPczt` → board → signatures applied → submit). Hooks in the account type labels, choose-hardware-wallet, Receive, Request and the review screen |
| `tools/leekwallet/` | The SDK build (`build-ffi.sh`), icon and logo generators |
| `host/leek-zcash-ffi` (main repo) | The Rust side: device protocol (pairing, encrypted session, signing), preflight, compact view, applying and checking the board's signatures with the `pczt` crate, the QR codes |

Branches: `leekwallet` is the app (default branch); `main` mirrors Zodl's
`main`; the remote `upstream` is
[zodl-inc/zodl-android](https://github.com/zodl-inc/zodl-android), and
each Zodl release is merged into `leekwallet` (`--no-ff`, history kept).

## Roadmap

- [x] Import the board's account, sign over USB, Bluetooth and QR
- [x] Compact PCZT; verify address on device (with rotating addresses)
- [x] Shield transparent funds, signed on the board
- [x] Mainnet app beside the testnet one
- [x] On the LeekWallet SDK; import from the board's View key QR
- [x] QR signing and Check on board (QR) out of experimental (tested on testnet from this app and Zingo)
- [ ] A small mainnet send and shield over each link (ROADMAP 10.3)
- [ ] NEAR Intents swaps signed on the board (needs mainnet support on the board)
- [ ] A proper `LeekWalletAccount` type instead of the Keystone-shaped one
- [ ] Offer the hardware module to Zodl upstream
- [ ] Signed release APK

The full plan is in the main repository's
[ROADMAP](https://github.com/0xOucan/leekwallet-zcash/blob/main/docs/zcash/ROADMAP.md).

## Credits and license

Built on [Zodl](https://github.com/zodl-inc/zodl-android) by the Zcash Open
Development Lab and the Zcash community; their original README is in
[docs/ZODL-README.md](docs/ZODL-README.md). Please report LeekWallet issues
here, not to Zodl.

MIT, as Zodl: see [LICENSE](LICENSE). The LeekWallet additions are by
0xoucan under the same license.
