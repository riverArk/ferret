# Ferret

Ferret is an Android-first Kotlin Multiplatform wallet for Cardano and Lightning payments. Shared domain, repositories, state, typed navigation, and Compose UI live in `shared`; Android supplies biometric authentication, encrypted persistence, Cardano transaction execution, Google Drive recovery, and QR scanning.

Android repository-contained wallet flows include:

- biometric or device-credential unlock and an exact five-minute background lock;
- encrypted local wallet profiles, seed entropy, operation journals, and channel recovery state;
- wallet creation or 24-word recovery-phrase restore with random three-word confirmation;
- immutable Preprod and Mainnet wallet profiles, with supported deployment and device acceptance restricted to Mainnet;
- validated foreground sessions, ADA L1/L2 balances, merged activity, local top-up QR, production-enabled ADA transfer/send-all, and safe wallet-removal orchestration;
- encrypted Drive appData backup verification, missing-backup replacement, restore, stale-writer detection and confirmed takeover, checkpoint-bound single-writer channel journaling, controlled Mainnet ADA channel opening, and QR-only BOLT11 payment with durable reconciliation;
- a shared cream, charcoal, yellow, and coral Compose interface.

Controlled Mainnet ADA channel opening and Lightning payment have passed on an Android device against the owned `crustypants.com` services. The shared app exposes reviewed ADA/USDA/USDCx/USDM transfer, channel Open/Add, eligible-channel BOLT11 payment, and guarded wallet removal on Android and iOS. Channel close/squash controls remain unexposed until their complete flows exist. Native-asset Add and settlement, funded L1 finality, Drive takeover, and removal require additional controlled Mainnet acceptance; iOS physical-device and ledger parity checks are manual through TestFlight. Preprod and `ferret.channel` are unsupported test or release targets. See [`NATIVE_MIGRATION_PLAN.md`](NATIVE_MIGRATION_PLAN.md) and [`IOS_FOLLOW_UP.md`](IOS_FOLLOW_UP.md) for outstanding acceptance.

## Prerequisites

- JDK 17
- Android SDK with API 36
- An API 36 Android device or emulator for installation and manual verification

Use the checked-in Gradle wrapper. Dependency locks are authoritative.

## Build and verify

Run the Android aggregate check:

```sh
./gradlew androidCheck
```

Build and install a debug application:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

No feature property is required to expose the Android financial routes.

Release verification requires the Google OAuth server client ID:

```sh
FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck
```

Tagged `v*` releases build signed Android APKs and iOS IPAs, upload the IPA to
internal TestFlight, and publish the verified APK/SBOM after both platforms
succeed. Configure the protected `mobile-release` environment first; see
[`IOS_FOLLOW_UP.md`](IOS_FOLLOW_UP.md) for credentials, upload, and physical
device acceptance. `bash scripts/test-ferret-version.sh` checks version metadata.

On macOS with Xcode 26.6, iOS 26.5 simulator, JDK 17, and Android SDK 36,
`scripts/check-ios.sh` runs shared iOS tests and the Xcode unit/UI tests,
then installs and launches the app on an iPhone 17 simulator. The iOS check
workflow also links the iPhone framework and assembles an XCFramework.
Physical-device and controlled Mainnet financial acceptance remain manual.

## Repository map

- `shared/src/commonMain`: shared domain models, repositories, state, typed navigation, and Compose screens.
- `shared/src/androidMain`: Android secure vault, biometric authentication support, Cardano implementation, and camera scanner.
- `shared/src/iosMain`: Compose runtime, Keychain vault, CryptoKit backup, pinned Darwin networking, Cardano and Drive adapters, and local QR services.
- `androidApp`: Android application host, dependency construction, system-bar configuration, and sensitive-screen protection.
- `iosApp`: SwiftUI/Xcode host, Google Sign-In, camera, sensitive-content cover, and simulator checks.
- `native/cardano-ios-bridge`: pinned Rust/CSL C ABI for iOS derivation, transaction construction, signing, inspection, and channel codecs; full semantic and controlled-node parity remains unverified.

## Security model

`WalletManager` is the create, restore, selection, rename, and recovery-confirmation boundary. `SecureVault` stores the encrypted wallet index separately from each encrypted seed and journal file. New wallets remain unconfirmed until the user verifies three distinct random recovery words; an interrupted flow resumes after the next unlock. Restored wallets are confirmed because the user supplied the complete phrase.

Mnemonic, transfer-confirmation, payment-receipt, and removal routes set Android `FLAG_SECURE`. The UI never offers mnemonic or invoice copy actions. Do not log recovery phrases, seed entropy, credentials, signed payloads, decrypted channel state, or encrypted vault keys.
