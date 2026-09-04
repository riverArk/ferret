# Agent Guide

Read `CONTEXT.md` first, then verify statements against the live repository.

## Project facts

- Stack: Kotlin Multiplatform, shared Compose Material 3 UI, Android application host.
- Build system: checked-in Gradle wrapper. Keep dependency locks authoritative.
- Android: minSdk 28, compile/target SDK 36, Java 17.
- Primary verification: `./gradlew androidCheck`.
- Release verification: `FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck`.
- Deployment and device acceptance testing use only the controlled Mainnet services under `crustypants.com`. Do not depend on or test against `ferret.channel`.
- iOS remains compile-gated until `IOS_FOLLOW_UP.md` is complete.

## Architecture boundaries

- Keep domain models, state, typed navigation, and shared Compose UI in `shared/src/commonMain`.
- Keep biometric authentication, encrypted vault files, Cardano/Bloxbean integration, and camera behavior in platform source sets.
- `WalletManager` owns create, restore, selection, recovery words, and recovery confirmation.
- `WalletRepository` publishes the root `AppState`; do not add a second active-wallet store.
- `SecureVault` is the encrypted persistence boundary. Never bypass it for wallet profiles or seed entropy.
- `Route` is the single navigation model. Do not add a second route or coordinator abstraction.
- `FerretTheme.kt` and `FerretComponents.kt` own shared visual tokens and primitives. Reuse them before adding screen-local styling.
- Platform availability is explicit: Android provides a real `WalletManager`; iOS passes `null` until its secure-vault, authentication, mnemonic, Cardano, and host integrations exist.

## Security rules

- Never log, copy, or expose mnemonics or seed entropy.
- Never add a mnemonic clipboard action.
- Preserve encrypted, atomic profile and seed persistence.
- Preserve recovery confirmation across process death; an unconfirmed wallet must return to phrase recording after unlock.
- Keep sensitive Android routes protected with `FLAG_SECURE` and clear the flag when they leave composition.
- Do not add in-memory or demo wallet fallbacks for unavailable platform integrations.

## Change guidelines

- Keep changes mobile-first and shared where behavior is platform-independent.
- Use platform source sets only for platform APIs.
- Preserve the light cream/charcoal/yellow/coral Ferret visual system unless a task explicitly changes it.
- Keep transaction, channel, and payment actions disabled or unreachable until real repositories are wired.
- The worktree may contain user changes. Inspect before editing and never revert unrelated work.

## Verification

Run `./gradlew androidCheck` after code changes. For shared platform API changes, also run `./gradlew :shared:compileKotlinIosSimulatorArm64`. Install and exercise the Android app for UI or lifecycle changes; do not claim an iOS app run because no Xcode project or iOS wallet adapters are checked in.
