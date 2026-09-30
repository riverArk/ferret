# iOS Follow-up

The checked-in iOS host now constructs the real shared wallet and exposes implemented financial actions. The contracts below describe the platform integrations; signing, physical custody, controlled Mainnet ledger parity, and financial acceptance remain unverified.

## Current iOS Surface

- `shared/build.gradle.kts` declares `iosArm64` and `iosSimulatorArm64` static frameworks named `Shared`.
- macOS CI links the iPhone release framework and assembles a device/simulator XCFramework; no app has been installed on a physical iPhone.
- `MainViewController.kt` receives the real `IosWalletRuntime` from the Swift scene host. No nullable wallet manager or demo fallback remains.
- `iosApp/iosApp.xcodeproj` builds and launches the SwiftUI host and runs simulator UI and CryptoKit tests.
- iOS custody includes a Keychain-protected data key with LocalAuthentication, encrypted atomic Application Support vault, installation identity, BIP-39 recovery codec, and backup-compatible CryptoKit bridge. The simulator vault record/tamper and public crypto-vector tests pass; Face ID/passcode behavior on a physical iPhone remains unverified.
- The Rust/CSL bridge and Kotlin adapter compile into the simulator app. Offline simulator/Rust fixtures cover identity, synthetic transaction intents, conservation, signing, and evaluation-response binding; they do not prove Android semantic parity or controlled Mainnet ledger evaluation. Drive, pinned Darwin networking, QR scanning/output, and sensitive route obscuring are connected but have not been accepted on a physical iPhone.

## Required macOS Tooling and Credentials

1. Install the current Xcode version supporting iOS 17 and accept its license.
2. Select the Xcode toolchain with `xcode-select`.
3. Install Rust and the pinned toolchain declared by `native/cardano-ios-bridge/rust-toolchain.toml`.
4. Configure an Apple development team and signing identity.
5. Create the iOS Google OAuth client and URL scheme. Keep client IDs and signing data outside source control.
6. Provision a dedicated Google Drive test account and funded low-value Mainnet wallets for recovery and payment tests.

## Tagged TestFlight Delivery

1. In Apple Developer, register `io.riverark.ferret`, enable the entitlements required by `iosApp/iosApp.xcodeproj`, and create an App Store distribution profile and matching Apple Distribution `.p12`. In App Store Connect, register the app and an API key with upload permission. Create an iOS Google OAuth client for the same bundle ID and its reversed URL scheme; configure its consent screen and authorized testers.
2. Store these **environment secrets** in GitHub `mobile-release`: `FERRET_APPLE_DISTRIBUTION_P12_BASE64`, `FERRET_APPLE_DISTRIBUTION_P12_PASSWORD`, `FERRET_IOS_PROFILE_BASE64`, `FERRET_ASC_KEY_ID`, `FERRET_ASC_ISSUER_ID`, `FERRET_ASC_PRIVATE_KEY_BASE64`, plus Android `FERRET_RELEASE_KEYSTORE_BASE64`, `FERRET_RELEASE_STORE_PASSWORD`, `FERRET_RELEASE_KEY_ALIAS`, `FERRET_RELEASE_KEY_PASSWORD`, and `FERRET_GOOGLE_SERVER_CLIENT_ID`. Base64-encode the binary `.p12`, `.mobileprovision`, and `.p8` without line wraps. Never commit them.
3. Set **environment variables** `FERRET_APPLE_TEAM_ID`, `FERRET_GOOGLE_IOS_CLIENT_ID`, and `FERRET_GOOGLE_IOS_REVERSED_CLIENT_ID`. Restrict `mobile-release` to `v*` tags and restrict who may create release tags; configure the App Store Connect internal tester group for automatic build distribution. The GitHub environment tag policy alone does not authenticate the tag creator.
4. On the candidate commit, run `./gradlew androidCheck`, `bash scripts/test-ferret-version.sh`, and the `ios-check` workflow (`scripts/check-ios.sh` on a configured Mac). After both checks pass and credentials exist, push a unique `v<major>.<minor>.<patch>[-prerelease]` tag at that commit. `.github/workflows/release.yml` runs both platforms, verifies signatures, package identifiers and version metadata, uploads the signed IPA to App Store Connect, and publishes the Android APK/SBOM only after both jobs succeed. Never tag just to test missing signing material.
5. Inspect the release run, then confirm Apple's processing/export-compliance state and assign the build to the internal group if auto-distribution is not configured. Upload success is not proof of TestFlight availability or physical-device acceptance. Install from TestFlight on a real iPhone and complete the matrix below. On failure, fix the cause and issue a **new** version tag; reruns use a distinct build number.


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
- Obtain controlled Mainnet-node ledger evaluation for all five intents before claiming financial parity or an externally accepted release; internal TestFlight is for manual verification.

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

Run the full simulator/native host smoke on macOS with the pinned Xcode and iPhone 17 simulator:

```sh
scripts/check-ios.sh
```

Then verify on an iOS 17 physical device:

1. Create and restore independent Mainnet wallets against the controlled `crustypants.com` services; do not use Preprod or `ferret.channel` as test evidence.
2. Confirm unlock remains active at 4:59 background time and locks at 5:00.
3. Inspect app files, pasteboard, unified logs, and app-switcher snapshots for secret leakage.
4. Compare CSL and Bloxbean semantics for all five Cardano intents.
5. Exercise Drive initial backup, tamper rejection, restore, takeover, stale writer rejection, and same-generation conflict.
6. Kill the process before submission, after submission, and after adaptor acceptance for the exposed channel Open/Add/payment flows; confirm one reconciled operation and no duplicate authorization. Close is not exposed until its complete flow exists.
7. Verify camera permission denial, lifecycle stop, valid BOLT11 payment, malformed/expired/wrong-network rejection, and durable receipt behavior.
8. Verify offline lockout and cancellation of refresh/camera work.
9. Verify sweep, 2160-block finality gate, local deletion, Drive deletion, and mnemonic restoration after wallet removal.
10. Repeat the complete controlled scenario with low-value Mainnet fixtures before App Store submission.

## Completion Gate

The internal TestFlight build enables implemented financial routes for manual acceptance; it is not evidence of financial parity or a production rollout. Physical custody, controlled-node intent evaluation, signing, Drive takeover, interruption recovery, and sensitive-content behavior must be verified before external distribution. Any mismatch in decoded intent, ledger validity, fee bounds, writer lease, backup recovery, reconciliation, or sensitive-content protection blocks external release.
