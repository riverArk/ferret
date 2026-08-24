# iOS Follow-up

The shared Kotlin Multiplatform theme, typed onboarding navigation, and explicit iOS wallet-unavailable state compile with `:shared:compileKotlinIosSimulatorArm64`. Completing and validating an iOS wallet requires macOS, Xcode, Apple signing, and the platform-specific integrations below.

## Current iOS Surface

- `shared/build.gradle.kts` declares `iosArm64` and `iosSimulatorArm64` static frameworks named `Shared`.
- `shared/src/iosMain/kotlin/io/riverark/ferret/MainViewController.kt` exposes the shared Compose root with `walletManager = null`.
- `iosApp/iosApp/FerretApp.swift` is the thin Swift host source, but no Xcode project is checked in.
- Shared theme, navigation, wallet, channel, backup, payment, and ViewModel contracts compile for the iOS simulator target.
- The current UI intentionally displays `Wallet setup is not available in this iOS build.` No secure-vault, authentication, mnemonic, Cardano bridge, Drive, camera, or iOS TLS adapter exists yet.

## Required macOS Tooling and Credentials

1. Install the current Xcode version supporting iOS 17 and accept its license.
2. Select the Xcode toolchain with `xcode-select`.
3. Install Rust and the pinned toolchain declared by `native/cardano-ios-bridge/rust-toolchain.toml` once that file is added.
4. Configure an Apple development team and signing identity.
5. Create the iOS Google OAuth client and URL scheme. Keep client IDs and signing data outside source control.
6. Provision a dedicated Google Drive test account and funded preprod wallets for recovery and payment tests.

## 1. Create the Xcode Host

Create `iosApp/iosApp.xcodeproj` with:

- Bundle ID `io.riverark.ferret`.
- Deployment target iOS 17.0.
- `FerretApp.swift` as the only application/UI host source.
- A build phase that invokes the Gradle task producing the `Shared` framework for the active SDK and architecture.
- Google Sign-In URL scheme and required app entitlements.
- Camera usage text describing BOLT11 QR scanning.
- No SwiftUI duplicate of the Compose screen tree.

Confirm both simulator and physical-device framework linking before adding platform services.

## 2. Implement the Cardano CSL Bridge

Add `native/cardano-ios-bridge/` as a narrow Rust C ABI wrapper pinned to `cardano-serialization-lib = 17.0.0`.

Expose only:

- Wallet derivation from 32-byte entropy and network.
- Transaction building for `Transfer`, `OpenChannel`, `AddChannelFunds`, `CloseChannel`, and `SweepWallet`.
- Transaction signing.
- Signed-CBOR inspection.
- Explicit result-buffer and error-buffer release functions.

Requirements:

- Build an XCFramework for device ARM64 and simulator ARM64.
- Keep ownership explicit across the C boundary; every allocated output must have one matching free function.
- Never pass mnemonic strings, arbitrary callbacks, or untyped transaction-builder input through the ABI.
- Implement `IosCardanoTransactionEngine` against the existing common `CardanoTransactionEngine` interface.
- Run the same semantic fixtures used by `AndroidCardanoTransactionEngine`: inputs, outputs, value conservation, datum/redeemer/script hashes, signer set, validity bounds, and fee bounds must match. CBOR byte ordering and transaction IDs may differ.
- Require controlled-node ledger evaluation for all five intents before enabling mainnet.

## 3. Implement iOS Secret Custody and App Lock

Implement the common security contracts in `shared/src/iosMain`:

- Wrap a random vault data-encryption key with a Keychain key stored as `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`.
- Use LocalAuthentication to unwrap once per foreground session.
- Keep the unwrapped key only in mutable process memory and wipe it after five continuous background minutes.
- Treat Keychain invalidation or authentication failure as mnemonic-restore conditions; never reset to an empty wallet.
- Store encrypted `wallet-index.v1` and `wallet-<walletId>.v1` files using temp write, `fsync`, and atomic rename.
- Wipe entropy and derived private-key buffers in `finally`/`defer` paths.
- Exclude wallet files from iCloud device backup.
- Redact mnemonics, addresses, invoices, signed payloads, URLs, and server bodies from logs and crash metadata.

Add lifecycle hooks from the Swift host for foreground/background transitions and app-switcher obscuring on sensitive routes.

## 4. Implement Backup Cryptography and Google Drive

Implement `BackupCrypto` with CryptoKit:

- HKDF-SHA-256.
- SHA-256.
- AES-256-GCM.
- Cryptographically secure salt and nonce generation.
- Base64url without padding.

Verify byte-for-byte compatibility with Android `FerretChannelBackupV1` fixtures, including authenticated header encoding.

Implement Google Sign-In and Drive REST access:

- Request only `https://www.googleapis.com/auth/drive.appdata`.
- Store immutable snapshots in `appDataFolder`.
- Preserve generation, sequence, previous-ciphertext hash, read-back verification, conflict handling, and takeover behavior.
- Never put `WalletId`, addresses, network names, or payment credentials in Drive filenames or metadata.

## 5. Implement iOS Networking and TLS Pinning

Provide the Darwin Ktor engine and enforce:

- HTTPS only.
- No cross-origin redirects.
- Existing request/response size and timeout limits.
- Current and backup SPKI pins for the four Ferret connector/adaptor hosts.
- Normal platform trust in addition to pin validation.
- No pinning for Google hosts.

Use the same signed writer lease and operation-ID behavior already enforced by the updated Konduit connector and adaptor servers.

## 6. Implement QR Input and Address QR Output

Implement the iOS scanner with AVFoundation:

- Request camera access only on `ScanInvoice`.
- Restrict metadata detection to QR codes.
- Accept one decoded value, then stop the capture session.
- Stop camera work when the screen backgrounds or leaves composition.
- On denial, expose only retry and system-settings actions; do not add paste or manual invoice entry.

Generate Cardano address QR images locally with Core Image. Copying an address must be explicit and should set UIPasteboard expiry where supported. Never copy mnemonics, invoices, or signed transactions.

## 7. Protect Sensitive UI

For unlock, mnemonic create/restore/verification, payment confirmation, backup recovery, and wallet removal:

- Obscure the app-switcher snapshot.
- Prevent screen capture where supported and react to capture-state changes.
- Restore the normal preview when leaving the sensitive route.

Keep ordinary address and deposit QR screens shareable.

## 8. Complete Platform Verification

Current Linux compile gate:

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64
```

After the platform adapters exist, run the shared/native tests on macOS:

```sh
./gradlew :shared:iosSimulatorArm64Test
```

Run the Xcode host:

```sh
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

Then verify on an iOS 17 physical device:

1. Create and restore independent preprod and mainnet wallets.
2. Confirm unlock remains active at 4:59 background time and locks at 5:00.
3. Inspect app files, pasteboard, unified logs, and app-switcher snapshots for secret leakage.
4. Compare CSL and Bloxbean semantics for all five Cardano intents.
5. Exercise Drive initial backup, tamper rejection, restore, takeover, stale writer rejection, and same-generation conflict.
6. Kill the process before submission, after submission, and after adaptor acceptance for channel open/add/pay/close; confirm one reconciled operation and no duplicate authorization.
7. Verify camera permission denial, lifecycle stop, valid BOLT11 payment, malformed/expired/wrong-network rejection, and durable receipt behavior.
8. Verify offline lockout and cancellation of refresh/camera work.
9. Verify sweep, 2160-block finality gate, local deletion, Drive deletion, and mnemonic restoration after wallet removal.
10. Repeat the controlled release scenario with low-value mainnet fixtures before App Store submission.

## Completion Gate

The compiling shared theme/navigation and unavailable state are not iOS wallet support. iOS is complete only when an Xcode host and its platform adapters pass the Android behavioral contracts and shared semantic fixtures on simulator and physical hardware. Any difference in decoded transaction intent, ledger validity, fee bounds, writer-lease behavior, backup recovery, interruption reconciliation, or sensitive-content protection blocks release.
