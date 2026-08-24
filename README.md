# Ferret

Ferret is an Android-first Kotlin Multiplatform wallet for Cardano and Lightning payments. Shared domain, state, navigation, and Compose UI live in `shared`; Android supplies biometric authentication, encrypted persistence, Cardano derivation, and QR scanning.

Android wallet onboarding is implemented end to end:

- biometric or device-credential unlock;
- encrypted local wallet profiles and 32-byte seed entropy;
- wallet creation or 24-word recovery-phrase restore;
- Preprod and Mainnet selection;
- mandatory, resumable recovery-phrase confirmation for newly created wallets;
- a shared cream, charcoal, yellow, and coral Compose interface.

The iOS shared target compiles and renders an explicit platform-availability gate. iOS wallet support remains disabled until the adapters and Xcode host in [`IOS_FOLLOW_UP.md`](IOS_FOLLOW_UP.md) are complete.

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

`WalletManager` is the create, restore, selection, and recovery-confirmation boundary. `SecureVault` stores the encrypted wallet index separately from each encrypted seed file. New wallets remain marked as unconfirmed until the user verifies recovery words 4, 12, and 21; an interrupted flow resumes after the next unlock. Restored wallets are confirmed because the user supplied the complete phrase.

Mnemonic routes set Android `FLAG_SECURE`. The UI never offers mnemonic copy actions. Do not log recovery phrases, seed entropy, credentials, signed payloads, or encrypted vault keys.
