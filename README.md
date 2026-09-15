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

Controlled Mainnet ADA channel opening and Lightning payment have passed on an Android device against the owned `crustypants.com` services. The current app supports one ADA channel per wallet; native-asset channel funding, USDM/USDCx payments, multiple channels, and explicit payment-channel selection are required next steps in [`NATIVE_MIGRATION_PLAN.md`](NATIVE_MIGRATION_PLAN.md), not current capabilities. ADA transfer, ADA channel opening, payment, and removal are enabled in debug and release builds; their foreground-session, deployment-validation, encrypted-backup, balance, and reconciliation checks remain mandatory. Add/close/squash channel controls, funded L1 transfer/finality acceptance, the two-device Drive matrix, the complete removal scenario, and production release credential/device review remain. Preprod and `ferret.channel` are unsupported test or release targets. The iOS shared target compiles and renders an explicit platform-availability gate; iOS wallet support remains disabled until [`IOS_FOLLOW_UP.md`](IOS_FOLLOW_UP.md) is complete.

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

## Repository map

- `shared/src/commonMain`: shared domain models, repositories, state, typed navigation, and Compose screens.
- `shared/src/androidMain`: Android secure vault, biometric authentication support, Cardano implementation, and camera scanner.
- `shared/src/iosMain`: thin shared Compose entry point with the intentional wallet-availability gate.
- `androidApp`: Android application host, dependency construction, system-bar configuration, and sensitive-screen protection.
- `iosApp`: thin Swift host source; no Xcode project is checked in yet.
- `native/cardano-ios-bridge`: reserved iOS Cardano bridge surface; implementation remains part of the iOS follow-up.

## Security model

`WalletManager` is the create, restore, selection, rename, and recovery-confirmation boundary. `SecureVault` stores the encrypted wallet index separately from each encrypted seed and journal file. New wallets remain unconfirmed until the user verifies three distinct random recovery words; an interrupted flow resumes after the next unlock. Restored wallets are confirmed because the user supplied the complete phrase.

Mnemonic, transfer-confirmation, payment-receipt, and removal routes set Android `FLAG_SECURE`. The UI never offers mnemonic or invoice copy actions. Do not log recovery phrases, seed entropy, credentials, signed payloads, decrypted channel state, or encrypted vault keys.
