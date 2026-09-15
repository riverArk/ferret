# Project Context

## Snapshot

- Date: 2026-09-15
- Current focus: extend the production-enabled single ADA channel path to verified stablecoin assets and explicit multi-channel payment selection.
- The live repository is a Kotlin Multiplatform project. This file describes current code only.

## Product and platforms

Ferret is an Android-first Cardano and Lightning wallet. Android has real biometric/device-credential authentication, encrypted persistence, Cardano wallet derivation and transaction authorization, Google Drive channel recovery, one Mainnet ADA channel, QR-only BOLT11 payment from that channel, ADA transfer/sweep, and safe wallet-removal orchestration. Shared Kotlin code supplies domain state, typed navigation, repositories, ViewModels, and Compose UI.

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

`Route` is the single typed navigation model. Repository states map to Unlock, connectivity progress, Offline, WalletPicker, RecoveryPhrase, RestoreBackup, or Home. Root navigation uses single-top replacement so repository emissions do not stack destinations. Unconfirmed wallets cannot navigate around recovery confirmation. A mnemonic-restored wallet offers Google Drive channel recovery before Home; skipping remains explicit. Mainnet Home exposes top-up, transfer, channel opening/status, QR payment, history, settings, and removal when their existing runtime invariants permit them. Add/close/squash channel controls remain unavailable.

The Refined Ferret theme is light-only: cream canvas, near-white surfaces, charcoal ink, yellow primary, accessible coral secondary, blue tertiary, and dark red errors. Exo 2 headings, Ubuntu Mono body text, rounded outlined surfaces, 48 dp touch targets, edge-to-edge safe drawing insets, bundled ferret art, and bundled Material Symbols are shared across implemented screens.

Home currently shows the wallet name, network, ADA L1 available balance, the single ADA channel's L2 spendable balance, latest merged activity, refresh timestamp, payment address, and wallet/settings navigation. Pull-to-refresh reconciles L1, channel, and payment state before updating the dashboard; Home repeats that refresh every 20 seconds while a durable record remains pending and the lifecycle is started. The open ADA channel exposes a QR payment action that resets stale scanner state before entering the camera.

Top-up renders a local address QR with owned sensitive-clipboard expiry. Transfer accepts validated same-network Cardano addresses, including saved Ferret wallets, and supports exact send-all previews. Settings connects a selected Google account, initializes or verifies the encrypted Drive appData backup, detects stale writers, and performs explicit takeover. Settings and Channel read lifecycle and pending-operation status from the encrypted runtime journal.


## Known platform gaps

- Stablecoin payments are not implemented in Ferret. Reviewed Konduit catalog definitions and Koios presentation metadata/logos are now bundled in shared resources, refreshed only by an explicit maintainer task, and verified offline by Android checks. The app still does not expose native-asset balances, fund USDM/USDCx channel outputs, persist multiple channels per wallet, select a source channel for payment, or enforce the catalog at runtime; `NATIVE_MIGRATION_PLAN.md` owns the remaining P0–P2 work.
- iOS wallet setup and runtime integrations are unavailable; see `IOS_FOLLOW_UP.md`.
- Add, close, elapse, end, and squash channel controls are not connected to UI or controlled lifecycle acceptance yet.
- Funded Mainnet ADA transfer/process-kill/finality acceptance, the two-device Drive takeover matrix, and the close/sweep/removal scenario remain.
- Production release verification still requires the real Google OAuth client ID, signing material, and device security review.
- Only Mainnet services under `crustypants.com` are supported. Preprod and `ferret.channel` do not count as deployment or release evidence.
- No dark theme is implemented.

## Decisions

- Android is the functional delivery target for this pass.
- Shared Compose owns platform-independent UI and navigation.
- `WalletManager`, `WalletRepository`, `SecureVault`, and `Route` remain the existing boundaries.
- Newly created wallets must confirm backup before Home; restored wallets are already confirmed.
- Mainnet is the sole deployment and device acceptance-test target. Preprod remains in the current model but is unsupported and must not be used as release evidence.
- Do not expose unfinished transaction or channel actions.

## Verification

Primary Ferret commands:

```sh
./gradlew :shared:allTests
./gradlew androidCheck
./gradlew :shared:compileKotlinIosSimulatorArm64
FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck
```

Android installation and manual verification require an API 36 device or emulator. The default debug build contains the same production-enabled financial routes; no acceptance feature property is required. Networked scenarios use low-value Mainnet wallets against the controlled `crustypants.com` services only.

## Session log

Entries below are chronological evidence. Older “remaining” or “gated” statements describe their date and are superseded by the Snapshot, Known platform gaps, and newest entry above.

### 2026-09-15 — Embedded asset presentation metadata

- Bundled the canonical four-entry Konduit catalog and reviewed Koios registry metadata/logos for USDA, USDCx, and USDM in shared Compose resources. The explicit updater validates the controlled deployment digest and performs one bounded bulk Mainnet Koios request; normal builds and runtime never invoke it.
- Added strict canonical catalog/manifest, normalized text, identity/decimal, hash, path, PNG structure/CRC/dimension/decode, deterministic generation, failure-safety, and tamper validation using Gradle's bundled Groovy JSON plus JDK facilities.
- The focused offline checks, no-external-connect `strace` run, `androidCheck`, local `androidReleaseCheck` with a non-production OAuth placeholder, both dry-run task graphs, configuration-cache reuse, visual logo inspection, and byte-for-byte debug APK resource comparison passed. No app/device payment acceptance was performed because financial and UI behavior did not change.

### 2026-09-15 — Controlled Mainnet channel and payment acceptance

- A funded Mainnet channel opening completed against the controlled connector/adaptor deployment using pinned Konduit transaction semantics, encrypted Drive write-ahead, a checkpoint-bound writer lease, and durable reconciliation.
- QR-only BOLT11 payment completed on the Android device. The app now rechecks invoice expiry after quote and before payment, distinguishes quote/payment failure reasons, displays backup and confirmation progress, and clears retained scanner errors when the Home QR action is used.
- Konduit quote routing moved from graph-only `QueryRoutes` to invoice-aware `EstimateRouteFee`, uses mission control, rounds millisatoshis exactly, normalizes absolute or relative LND timelocks, and applies explicit payment timeout, fee, and CLTV limits.
- Successful Konduit payments now atomically persist the returned preimage into the channel receipt. Ferret safely replays a stable submitted authorization to recover an already-paid preimage, then persists the verified receipt, updates L2 spendable balance, and changes payment activity from pending to settled.
- Drive writer verification now lists backup metadata and decrypts only the latest matching object instead of downloading the full chain on every payment.
- Removed the debug-only Mainnet acceptance flag. Transfer, channel opening, QR payment, and wallet removal are enabled in default debug and release builds while their foreground, Mainnet, validated-service, encrypted-backup, balance, and unresolved-operation checks remain mandatory.

### 2026-09-09 — Missing Drive backup recovery

- Pixel acceptance confirmed the `da3a472` Home sheet and Google account connection, then found a local generation-1/sequence-1 checkpoint whose encrypted object is absent from the selected Drive account; Open preview correctly remained blocked.
- Backup verification now distinguishes authoritative absence from conflicts, exposes account switching, and permits an explicitly confirmed replacement only when the unchanged local generation-1 snapshot matches and Drive remains empty. Conflicting, advanced, pending, or changed state still fails closed.
- The focused Android backup regression, `androidCheck`, the shared iOS compile gate, and the API 36 replacement-warning surface passed. Creating the replacement on the Pixel and retrying the funded preview remain outstanding.

### 2026-09-09 — Gated Android Open-channel lifecycle

- The shared Open-channel flow now previews a real Mainnet Konduit transaction, shows actual fee/change/minimum/reserve/capacity, requires explicit confirmation, and validates typed intent, live ledger policy, exact datum/output minimum, transaction identity, and signed witness bytes around vault signing.
- Channel submission durably writes unsigned state and Drive backup before seed access, then signed state and Drive backup before adaptor mutation. Unsigned interrupted operations fail terminally without signing; signed absent operations replay exact stored bytes without re-signing; unsupported legacy records remain visible and fail closed.
- Android wires the feature behind the existing build flag plus started lifecycle, validated Mainnet connectivity, selected-wallet readiness, and no unresolved L1/channel operation. Transfers and sweeps also reject pending channels before journal or seed access.
- Focused host regressions, `androidCheck`, the shared iOS compile gate, and an API 36 Open-screen pass at 200% font with TalkBack passed. Funded controlled-Mainnet submission/recovery, Google OAuth/Drive two-device takeover, release credentials, and release-device acceptance remain outstanding.

### 2026-09-09 — Channel interruption recovery

- Authenticated adaptor operation lookup now treats only a bounded HTTP 404 as authoritative absence; every transport failure, unexpected status, redirect, oversized body, malformed result, or identity mismatch remains fail-closed without replay.
- Terminal channel evidence and completed-payment recovery are persisted before the final encrypted Drive snapshot, and restart reconciliation finishes matching saved results without another adaptor lookup or mutation.
- Focused real-client, repository, encrypted-backup and fresh-store recovery regressions plus `androidCheck` passed. Funded controlled-Mainnet lifecycle, Google account/two-device takeover, transaction controls, controlled-node evaluation, release OAuth and device acceptance remain outstanding.

### 2026-09-09 — Channel transaction authorization

- Android now authorizes pinned-Konduit Open, Add, CLOSE, ELAPSE, and END transactions against exact wallet ownership, ledger inputs, datum stages, slot-time boundaries, witness sets, redeemer indices, collateral, fees, script-data hashes, and dynamic minimum ADA before seed access and again after signing.
- Channel evaluation now rejects failed, missing, duplicate, unknown, negative, or aggregate-over-limit execution budgets and verifies the final transaction bytes without accepting evaluation fallback.
- Focused host regressions, `androidCheck`, the shared iOS compile gate, and a GET-only controlled Mainnet live-parameter fixture smoke passed. Controlled-node evaluation, funded lifecycle submission/recovery, device OAuth, and release acceptance remain outstanding.

### 2026-09-09 — Current-Konduit channel encoding conformance

- Android channel datums now encode current Konduit's five-field ADA constants and strictly reconstruct bounded Opened/Closed/Responded evidence from complete single-item CBOR.
- Connector V3 reference scripts are consumed as raw script bytes, cryptographically checked against the pinned validator, and rejected when metadata or spending/reference input identity is invalid.
- Rust-generated `b9ac1e0` golden vectors, real Android Open construction/signing, Konduit data/wire tests, `androidCheck`, and a controlled GET-only Mainnet deployment smoke passed. Full five-intent node evaluation, funded submission, lifecycle interruption, OAuth, and release acceptance remain outstanding.

### 2026-09-09 — Mainnet validator pin refresh

- Mainnet now pins Konduit `b9ac1e0`'s Plutus V3 validator `b031eed54697d2c4b55659fe11dac2929228dd987fafe2b15904ce04` and its enterprise address `addr1wxcrrmk4g6ta93942evluyw6c2ffy2xanpl6lc43tyzvupqswlfa5`, matching the controlled adaptor.

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
