# Ferret

Ferret is an Android-first Kotlin Multiplatform wallet for Cardano and Lightning payments. Shared domain, state, navigation, and Compose UI live in `shared`; Android supplies biometric authentication, encrypted persistence, Cardano derivation, and QR scanning.

Android repository-contained wallet flows include:

- biometric or device-credential unlock and an exact five-minute background lock;
- encrypted local wallet profiles, seed entropy, operation journals, and channel recovery state;
- wallet creation or 24-word recovery-phrase restore with random three-word confirmation;
- immutable Preprod and Mainnet wallet profiles, with deployment and device acceptance testing restricted to Mainnet;
- validated online-session gating, L1 balance/history, local top-up QR, and durable L1 transfer orchestration;
- native Google account selection with encrypted Drive appData backup initialization/read-back verification, single-writer channel journaling, QR-only BOLT11 reconciliation, settings, diagnostics, and safe wallet removal boundaries;
- a shared cream, charcoal, yellow, and coral Compose interface.

Financial mutations remain unreachable until the controlled Mainnet connector/adaptor deployment under `crustypants.com` exposes and passes the required operation lookup, protocol-parameter, writer-lease, and reconciliation contracts. Preprod and `ferret.channel` are unsupported and are not test or release targets. The iOS shared target compiles and renders an explicit platform-availability gate; iOS wallet support remains disabled until the adapters and Xcode host in [`IOS_FOLLOW_UP.md`](IOS_FOLLOW_UP.md) are complete.

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
