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

`Route` is the single typed navigation model. Repository states map to Unlock, connectivity progress, Offline, WalletPicker, RecoveryPhrase, RestoreBackup, or Home. Root navigation uses single-top replacement so repository emissions do not stack destinations. Unconfirmed wallets cannot navigate around recovery confirmation. A mnemonic-restored wallet offers Google Drive channel recovery before Home; skipping remains explicit. Wallets with an active or transitional channel can open the typed Channel route to inspect the encrypted local lifecycle and pending reconciliation state.

The Refined Ferret theme is light-only: cream canvas, near-white surfaces, charcoal ink, yellow primary, accessible coral secondary, blue tertiary, and dark red errors. Exo 2 headings, Ubuntu Mono body text, rounded outlined surfaces, 48 dp touch targets, edge-to-edge safe drawing insets, bundled ferret art, and bundled Material Symbols are shared across implemented screens.

Home shows the wallet name, network, confirmed on-chain ADA balance, latest immutable activity, refresh timestamp, payment address, and wallet/settings navigation. Pull-to-refresh updates the balance, activity, and encrypted-journal channel lifecycle projection together. Home repeats that coordinated refresh every 20 seconds while an L1, journal-backed payment, or channel record is pending and the Home lifecycle is started. Validated foreground sessions cancel work on background or connectivity loss. Top-up renders a local address QR with owned sensitive-clipboard expiry. Settings connects a selected Google account, initializes or verifies the wallet's encrypted Drive appData backup, identifies a newer remote generation as a stale local writer, and offers an explicit confirmed takeover that installs …
Settings reads channel lifecycle and pending-operation status from the encrypted runtime journal rather than the profile's coarse cached channel field.


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

### 2026-09-09 — Dynamic minimum ADA authorization

- Android now validates every transaction output and collateral return against the live positive `coins_per_utxo_size` and each output's original serializer-stable CBOR size before L1 authorization.
- Transfer and sweep previews validate minimums; submission reloads ledger inputs and parameters before journaling or seed access, then rechecks signed bytes before remote submission. Parameter drift fails closed without rebuilding confirmed previews.
- Pinned-Konduit output boundaries, malformed parameters/CBOR, recipient/change/collateral sizing, local build/sign, pre-side-effect drift, and interrupted signing checks passed with `androidCheck` and the shared iOS compile gate. Funded Mainnet transfer acceptance, complete five-intent conformance, channel controls, release credentials/MASVS, and deployment activation remain outstanding.

### 2026-09-09 — L1 transaction authorization

- Android inspection now requires the modern four-item CBOR envelope, supported Conway body keys, a true validity flag, a consistent explicit network ID, and exposes prohibited raw body and non-key witness entries without normalization.
- Transfer and SweepWallet reject script, datum, redeemer, collateral, required-signer, auxiliary, and other prohibited contamination. Every unsigned boundary requires zero witnesses; every signed boundary requires exactly one valid spending witness matching the vault profile credential.
- Focused host checks, `androidCheck`, `:shared:compileKotlinIosSimulatorArm64`, and Konduit's 28 connector-server tests passed on Linux. Financial actions remain gated; the controlled adaptor validator does not match Ferret's pin, and pinned-Konduit golden equivalence, dynamic min-ADA, funded Mainnet interruption, and release OAuth/MASVS acceptance remain outstanding.

### 2026-09-08 — L1 funding semantic validation

- Transaction inspection now exposes ordered consumed input references. Transfer and SweepWallet construction reject empty, duplicate, unknown, foreign, asset-bearing, datum-bearing, and reference-script inputs and require exact overflow-safe ADA conservation against selected ledger UTxOs.
- Transfer preview independently revalidates the initial body against its connector ledger before authorization; signed bodies retain the validated input identity and existing transaction-body hash binding.
- Focused host regressions, `androidCheck`, and `:shared:compileKotlinIosSimulatorArm64` passed on Linux. Transfer remains disabled pending signer/script/datum/redeemer and dynamic min-ADA conformance, pinned-Konduit equivalence, funded Mainnet interruption acceptance, and release OAuth/MASVS checks.

### 2026-09-05 — Transfer preview integrity

- Transfer previews resolve recipients from the vault, display decoded change rather than total-wallet arithmetic, and retain the unsigned transaction body hash in memory.
- Submission revalidates current wallet identity, preview metadata, unsigned semantics and body identity before journaling or seed access. Signing must preserve that body hash; a mismatch remains PREPARED and rejects through lookup-free restart reconciliation.
- Shared output checks permit exactly one designated output and at most one source change output; L1 outputs are ADA-only. Real Android host construction/signing proves witness-independent body identity and detects input-only mutations.
- Focused regressions, `androidCheck`, and the shared iOS compile gate passed. Transfer remains deployment-gated; channel script/signer conformance, datum-aware input selection, funded Mainnet/device acceptance, and release OAuth/MASVS remain outstanding.

### 2026-09-05 — Durable L1 reconciliation

- L1 submission preserves one preparation timestamp and local transfer details; remote responses must match both the durable operation UUID and expected transaction hash.
- Confirmed operations reconcile through depth-2160 settlement on existing refresh calls and can roll back before settlement. Confirmation still permits a subsequent transfer; the journal remains a single current-operation slot.
- Operation responses require depth and consistent status/transaction identity. Lost-response restart, immutable fallback history, identity rejection, rollback, and finality boundaries have deterministic regression coverage.
- Focused host tests, `androidCheck`, and `:shared:compileKotlinIosSimulatorArm64` passed. iOS simulator execution is unavailable on Linux. Financial actions remain gated; funded Mainnet and release acceptance were not performed.

### 2026-09-04 — Mainnet-only test decision

- Removed `ferret.channel` and Preprod from the deployment and device acceptance-test plan; controlled low-value Mainnet scenarios against `crustypants.com` are the only release evidence.

### 2026-09-04 — Channel mutation lifecycle

- Channel write-ahead now exposes `Opening` and `Closing` from the encrypted journal, so interrupted open/close operations remain reachable and visible while reconciliation is pending.

### 2026-09-04

- Added stable redacted diagnostic identifiers (`FRT-001` through `FRT-006`) and expanded the release logging scan across Android and shared production Kotlin.
- Confirmed `FLAG_SECURE` on the API 36 unlock window; screenshot capture returned black and startup logs contained no wallet data.

- Added a stable non-backed-up Android installation identity and bound writer-lease claims to the exact verified Drive generation, ciphertext hash, adaptor identity, and monotonic timestamp.
- Bounded every connector/adaptor response by its decoded UTF-8 byte count and tightened transaction, operation, session-lease, and adaptor DTO validation before channel adapters are connected.
- Made the validated writer lease mandatory for every channel mutation and reconciliation; missing ownership now fails before journal or remote side effects.
- Replaced raw adaptor quote/payment request bodies with pinned-Konduit typed JSON, duration and keytag validation, strict quote decoding, and canonical JSON/CBOR fixtures.
- Replaced raw adaptor squash/receipt bodies with strict pinned-Konduit response types, bounded cheque/exclusion collections, and fail-closed variant/field decoding.
- Added fail-closed Ed25519 verification for every signed squash and locked/unlocked cheque returned by adaptor receipt, pay, and squash endpoints.
- Channel write-ahead now persists the exact action, reacquires the lease for the newly written Drive checkpoint, and replays an interrupted operation under the same stable operation ID only after remote reconciliation returns no result.
- Connected explicit Drive verification and takeover to the signed, checkpoint-bound session claimant in Android composition, and clear cached leases whenever the validated foreground session ends.

- Connected native Android Google account selection to the settings backup controls.
- Wired first encrypted Drive appData backup creation and later read-back/decrypt verification, with the verified sequence projected in Settings.
- Added post-mnemonic encrypted Drive backup recovery. Verified channel snapshots are installed into the local encrypted journal; missing, conflicting, or modified backups fail closed.
- Added stale Drive writer detection and an explicit Settings takeover flow. Takeover restores the latest verified channel snapshot locally and starts a new backup generation.
- Channel mutations now revalidate the remote encrypted Drive checkpoint immediately before writer-lease resolution, preventing a stale device from journaling or calling the adaptor after takeover.
- Connected safe removal for empty settled wallets with verified Drive backup deletion before local vault deletion.

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
