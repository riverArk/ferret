# Project Context

## Snapshot

- Date: 2026-08-24
- Current focus: Android wallet onboarding, shared Compose navigation, and the Refined Ferret visual cutover.
- The live repository is a Kotlin Multiplatform project. This file describes current code only.

## Product and platforms

Ferret is an Android-first Cardano and Lightning wallet. Android has real biometric/device-credential authentication, encrypted wallet persistence, Cardano wallet derivation, recovery-phrase create/restore flows, and QR scanning. Shared Kotlin code supplies domain state, typed navigation, ViewModels, and Compose screens.

iOS compiles the shared Compose root and displays `Wallet setup is not available in this iOS build.` It does not provide wallet operations. `IOS_FOLLOW_UP.md` owns the missing Xcode project, secure vault, authentication, mnemonic, Cardano, Drive, TLS, QR, signing, and physical-device work.

## Module map

- `shared/src/commonMain`: domain models, `WalletManager`, `WalletRepository`, `SecureVault` contract, typed `Route`, ViewModels, shared Compose UI, and the Ferret theme.
- `shared/src/androidMain`: Android vault/authentication, recovery codec, Cardano engine, protocol signer, and camera scanner.
- `shared/src/iosMain`: Compose view-controller entry point and explicit platform gate.
- `androidApp`: Android host, dependency construction, edge-to-edge setup, and screenshot protection.
- `iosApp`: Swift host source only; no checked-in Xcode project.
- `native/cardano-ios-bridge`: deferred iOS Cardano bridge surface.

## Runtime and security

`MainActivity` creates one `WalletRepository`, `AndroidSecureVault`, `AndroidUserAuthenticator`, and `WalletManager`. Unlock publishes encrypted vault profiles into `WalletRepository`. The Android vault stores an encrypted `wallet-index.v1` and one encrypted `wallet-<walletId>.v1` seed file per wallet using `AtomicFile`.

Seed entropy is cleared after derivation and vault callbacks. Mnemonic restore/display/verification routes toggle Android `FLAG_SECURE` and never expose a copy action. A null `WalletManager` is the platform-availability gate; no demo wallet fallback exists.

## Wallet onboarding

- Empty state offers Create wallet and Restore wallet.
- Both flows require a name and support Preprod or Mainnet; Preprod is the default and Mainnet shows `Mainnet uses real ADA.`
- Create generates 32-byte entropy, derives a wallet without constructing a transaction engine, persists `recoveryPhraseConfirmed = false`, and publishes it as active.
- Recovery displays the 24 words without clipboard support, then verifies words 4, 12, and 21.
- Confirmation atomically updates only the encrypted wallet index and republishes the confirmed wallet.
- If the process stops before confirmation, the next unlock returns to recovery-word display.
- Restore requires exactly 24 normalized words and persists the resulting profile as confirmed.
- Duplicate payment credentials are rejected.

## Navigation and UI

`Route` is the single typed navigation model. Repository states map to Unlock, connectivity progress, Offline, WalletPicker, RecoveryPhrase, or Home. Root navigation uses single-top replacement so repository emissions do not stack destinations. Unconfirmed wallets cannot navigate around recovery confirmation.

The Refined Ferret theme is light-only: cream canvas, near-white surfaces, charcoal ink, yellow primary, accessible coral secondary, blue tertiary, and dark red errors. Exo 2 headings, Ubuntu Mono body text, rounded outlined surfaces, 48 dp touch targets, edge-to-edge safe drawing insets, bundled ferret art, and bundled Material Symbols are shared across implemented screens.

Home shows the wallet name, network, confirmed on-chain ADA balance, payment address, and Wallets navigation. The balance loads from the wallet network's connector and supports pull-to-refresh. Add ADA opens a shared top-up destination with a locally generated address QR and explicit Android sensitive-clipboard copy that clears after 60 seconds while Ferret still owns the clip. History is reachable from Home, parses the pinned connector response into immutable L1 records, supports pull-to-refresh, and applies 5-block confirmed/2160-block settled finality. Payment, transfer, and channel actions remain unavailable until their repositories are wired.

## Known platform gaps

- iOS wallet setup and runtime integrations are unavailable; see `IOS_FOLLOW_UP.md`.
- Existing transfer, channel, payment, settings, and scanner composables are styled, but only wallet lifecycle, L1 top-up, and L1 history routes are connected to the real root graph.
- No dark theme is implemented.

## Decisions

- Android is the functional delivery target for this pass.
- Shared Compose owns platform-independent UI and navigation.
- `WalletManager`, `WalletRepository`, `SecureVault`, and `Route` remain the existing boundaries.
- Newly created wallets must confirm backup before Home; restored wallets are already confirmed.
- Preprod is the default, but Mainnet remains an explicit user choice with a warning.
- Do not expose unfinished transaction or channel actions.

## Verification

Primary commands:

```sh
./gradlew :shared:allTests
./gradlew androidCheck
./gradlew :shared:compileKotlinIosSimulatorArm64
```

Android installation and manual onboarding verification require an API 36 emulator or device. Release checks require `FERRET_GOOGLE_SERVER_CLIENT_ID`.

## Session log

### 2026-08-24

- Cut over the repository handoff from the removed frontend to the live Kotlin Multiplatform project.
- Added safe, resumable create/restore recovery confirmation and Android dependency construction.
- Wired state-driven shared onboarding navigation and an explicit iOS unavailable state.
- Applied the Refined Ferret theme, bundled fonts/icons, Android launcher, and shared component styling.
- Replaced contributor and project documentation with Gradle/KMP instructions and retained deferred iOS work in `IOS_FOLLOW_UP.md`.
- Connected the L1 top-up route, local address QR, and owned sensitive-clipboard expiry behavior.
- Connected immutable L1 history parsing, finality labels, pull-to-refresh, and typed History navigation.
- Verified the rendered Mainnet top-up QR and 60-second owned clipboard expiry on a Pixel 8a.
