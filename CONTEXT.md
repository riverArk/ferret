# Project Context

## Snapshot

- Date: 2026-08-25
- Current focus: repository-complete Android financial orchestration with deployment-gated mutation reachability.
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
- Wallet creation and restore still expose the existing Preprod/Mainnet model, but deployment and device acceptance testing use Mainnet only. Preprod is unsupported because its `ferret.channel` services are not controlled by this project.
- Create generates 32-byte entropy, derives a wallet without constructing a transaction engine, persists `recoveryPhraseConfirmed = false`, and publishes it as active.
- Recovery displays the 24 words without clipboard support, then verifies words 4, 12, and 21.
- Confirmation atomically updates only the encrypted wallet index and republishes the confirmed wallet.
- If the process stops before confirmation, the next unlock returns to recovery-word display.
- Restore requires exactly 24 normalized words and persists the resulting profile as confirmed.
- Duplicate payment credentials are rejected.

## Navigation and UI

`Route` is the single typed navigation model. Repository states map to Unlock, connectivity progress, Offline, WalletPicker, RecoveryPhrase, RestoreBackup, or Home. Root navigation uses single-top replacement so repository emissions do not stack destinations. Unconfirmed wallets cannot navigate around recovery confirmation. A mnemonic-restored wallet offers Google Drive channel recovery before Home; skipping remains explicit.

The Refined Ferret theme is light-only: cream canvas, near-white surfaces, charcoal ink, yellow primary, accessible coral secondary, blue tertiary, and dark red errors. Exo 2 headings, Ubuntu Mono body text, rounded outlined surfaces, 48 dp touch targets, edge-to-edge safe drawing insets, bundled ferret art, and bundled Material Symbols are shared across implemented screens.

Home shows the wallet name, network, confirmed on-chain ADA balance, latest immutable activity, refresh timestamp, payment address, and wallet/settings navigation. Pull-to-refresh updates the balance and activity projection together. Home repeats that coordinated refresh every 20 seconds only while an L1 or journal-backed payment record is pending and the Home lifecycle is started. Validated foreground sessions cancel work on background or connectivity loss. Top-up renders a local address QR with owned sensitive-clipboard expiry. Settings connects a selected Google account, initializes or verifies the wallet's encrypted Drive appData backup, identifies a newer remote generation as a stale local writer, and offers an explicit confirmed takeover that installs the latest verified channel snapshot. Transfer, channel recovery, BOLT11 payment, and removal use encrypted durable journals and reconciliation boundaries. Channel mutation and reconciliation interfaces require a validated Drive-backed writer lease before journaling or remote execution; financial mutation entry points remain disabled until the pinned server operation and writer-lease contracts are verified.

## Known platform gaps

- iOS wallet setup and runtime integrations are unavailable; see `IOS_FOLLOW_UP.md`.
- Only the Mainnet connector/adaptor services under `crustypants.com` are supported for deployment and device acceptance testing. Do not gate work on or test against `ferret.channel`.
- Live Google Drive verification requires a Google account and OAuth authorization on the Android device; release checks remain credential-gated.
- No dark theme is implemented.

## Decisions

- Android is the functional delivery target for this pass.
- Shared Compose owns platform-independent UI and navigation.
- `WalletManager`, `WalletRepository`, `SecureVault`, and `Route` remain the existing boundaries.
- Newly created wallets must confirm backup before Home; restored wallets are already confirmed.
- Mainnet is the sole deployment and device acceptance-test target. Preprod remains in the current model but is unsupported and must not be used as release evidence.
- Do not expose unfinished transaction or channel actions.

## Verification

Primary commands:

```sh
./gradlew :shared:allTests
./gradlew androidCheck
./gradlew :shared:compileKotlinIosSimulatorArm64
```

Android installation and manual onboarding verification require an API 36 emulator or device. Networked scenarios use low-value Mainnet wallets against the controlled `crustypants.com` services only. Release checks require `FERRET_GOOGLE_SERVER_CLIENT_ID`.

## Session log

### 2026-09-04 — Mainnet-only test decision

- Removed `ferret.channel` and Preprod from the deployment and device acceptance-test plan; controlled low-value Mainnet scenarios against `crustypants.com` are the only release evidence.

### 2026-09-04

- Added stable redacted diagnostic identifiers (`FRT-001` through `FRT-006`) and expanded the release logging scan across Android and shared production Kotlin.
- Confirmed `FLAG_SECURE` on the API 36 unlock window; screenshot capture returned black and startup logs contained no wallet data.

- Added a stable non-backed-up Android installation identity and bound writer-lease claims to the exact verified Drive generation, ciphertext hash, adaptor identity, and monotonic timestamp.
- Bounded every connector/adaptor response by its decoded UTF-8 byte count and tightened transaction, operation, session-lease, and adaptor DTO validation before channel adapters are connected.
- Made the validated writer lease mandatory for every channel mutation and reconciliation; missing ownership now fails before journal or remote side effects.

- Connected native Android Google account selection to the settings backup controls.
- Wired first encrypted Drive appData backup creation and later read-back/decrypt verification, with the verified sequence projected in Settings.
- Added post-mnemonic encrypted Drive backup recovery. Verified channel snapshots are installed into the local encrypted journal; missing, conflicting, or modified backups fail closed.
- Added stale Drive writer detection and an explicit Settings takeover flow. Takeover restores the latest verified channel snapshot locally and starts a new backup generation.
- Channel mutations now revalidate the remote encrypted Drive checkpoint immediately before writer-lease resolution, preventing a stale device from journaling or calling the adaptor after takeover.

### 2026-09-02

- Connected Home refresh to balance and immutable history together, with the latest activity and refresh timestamp visible on the dashboard.
- Confirmed the dashboard’s initial load and pull-to-refresh behavior on an API 34 emulator.

### 2026-08-25

- Completed the five-minute foreground lock/deployment gate by cancelling in-flight balance and history refreshes whenever the validated session backgrounds or goes offline.
- Protected Unlock with `FLAG_SECURE` and kept protection active across overlapping sensitive-route transitions.
- Packaged the third-party notice, completed release dependency locks, and preserved ML Kit component registrars through R8.
- Verified `androidCheck`, `androidReleaseCheck`, the merged release manifest, non-debuggable installation, clean release startup, and the protected unlock window on an API 34 emulator.

### 2026-08-24

- Cut over the repository handoff from the removed frontend to the live Kotlin Multiplatform project.
- Added safe, resumable create/restore recovery confirmation and Android dependency construction.
- Wired state-driven shared onboarding navigation and an explicit iOS unavailable state.
- Applied the Refined Ferret theme, bundled fonts/icons, Android launcher, and shared component styling.
- Replaced contributor and project documentation with Gradle/KMP instructions and retained deferred iOS work in `IOS_FOLLOW_UP.md`.
- Connected the L1 top-up route, local address QR, and owned sensitive-clipboard expiry behavior.
- Connected immutable L1 history parsing, finality labels, pull-to-refresh, and typed History navigation.
- Verified the rendered Mainnet top-up QR and 60-second owned clipboard expiry on a Pixel 8a.
