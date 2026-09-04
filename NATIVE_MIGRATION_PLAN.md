# Native Migration Plan

## Scope recovered from prior sessions

The original migration contract is the greenfield plan preserved in the prior agent artifact `kotlin-multiplatform-wallet-plan.md`, corroborated by the `SecurityBoundaryMap`, `UxBehaviorMap`, `DomainRuntimeMap`, and `BuildTestMap` session reports. The product is:

- one shared Compose UI for Android and iOS, with Android completed and hardened first;
- independent 24-word wallet profiles for the existing networks, with supported deployment and all device acceptance testing restricted to Mainnet;
- Cardano L1 funding, same-network transfers, balances, and history;
- one Konduit L2 channel per wallet, encrypted Google Drive channel recovery, and one active writer device;
- QR-only BOLT11 payments with durable reconciliation;
- safe wallet removal after channel closure, balance sweep, and finality;
- no retained PWA, Svelte architecture, WASM runtime, old signing-key import, plaintext backup, fiat/FX, custom endpoints, mutable network setting, manual invoice entry, LNURL, or offline wallet use.

Later user sessions additionally required the full visual refresh, documentation refresh, a proper adaptive Android icon, random recovery-word verification positions, live ADA balance, and pull-to-refresh. Those requirements are included below.

**Deployment decision (2026-09-04):** Preprod and all `ferret.channel` services are out of scope because they are not controlled by this project. Networked development, device acceptance, and release evidence use low-value Mainnet wallets against the controlled `crustypants.com` connector/adaptor services only. Existing Preprod model support is not release evidence.

## Current conclusion

The KMP shell, Android secure onboarding, shared visual system, adaptive icon, validated foreground session, live L1 dashboard, top-up, history, durable transfer orchestration, encrypted channel recovery, native Google account selection, Drive appData backup initialization/verification/restoration/takeover, payment reconciliation, settings/diagnostics, and safe removal boundaries are implemented. Mnemonic restore installs only a fully verified encrypted Drive channel backup into the local journal. Settings identifies a newer remote backup as a stale local writer and requires explicit confirmation before restoring it and starting a new generation. Channel mutation and reconciliation interfaces now revalidate the remote Drive checkpoint immediately before requiring the exact writer lease, so a missing, changed, or superseded backup fails before journaling or any remote side effect. Android now persists one non-backed-up per-install device identity, and the session lease claimant binds that identity to the verified backup generation and ciphertext hash. The adaptor client now has strict pinned-Konduit quote/pay request types, duration encoding, keytag construction, and canonical JSON/CBOR fixtures instead of accepting raw quote/payment bodies. Financial mutations remain unreachable until every required Mainnet contract is verified on the controlled `crustypants.com` services. The remaining concrete channel transaction adapters and lifecycle UI are the next financial functionality.

Status legend: **Complete** means connected behavior exists; **Partial** means reusable code exists but the end-to-end feature does not; **Missing** means no usable implementation exists; **Deferred** is an explicit sequencing decision.

## Next implementation set

### P0.1 — Foreground lock and validated online session

**Current status:** Complete. Android retains the unlocked vault only while foregrounded, locks at the exact five-minute continuous-background boundary, cancels in-flight balance/history refreshes on background or connectivity loss, and exposes no wallet data while checking or offline. `RefreshCoordinator` validates the selected profile against connector health/network and the pinned adaptor identity/script tuple before `Ready`; foreground retry repeats every deployment check. Authentication and keystore failures preserve encrypted wallet files and fail back to `Locked`.

**Missing work:** None.

**Affected files:** `androidApp/src/main/kotlin/io/riverark/ferret/MainActivity.kt`; `shared/src/commonMain/kotlin/io/riverark/ferret/core/model/WalletRepository.kt`; `shared/src/commonMain/kotlin/io/riverark/ferret/core/network/Clients.kt`; `shared/src/commonMain/kotlin/io/riverark/ferret/core/network/FerretHttpClient.kt`; a minimal coordinator under `core/network`; Android lifecycle tests.

**Dependencies:** Existing `AndroidSecureVault`, `AndroidUserAuthenticator`, deployment constants, pinned network-security config, and `WalletRepository`. No new framework is needed.

**Acceptance criteria:**

- After 4:59 continuously backgrounded, returning does not prompt; at 5:00, the vault key is wiped, `AppState.Locked` is published, and returning requires authentication.
- Connector/adaptor network or identity mismatch prevents `Ready` and exposes only `Offline` with retry.
- Disabling connectivity before unlock or while ready exposes no wallet balance/history/actions, cancels work, and creates no pending operation.
- Foreground recovery succeeds only after all deployment checks pass.

**Verification:** `./gradlew androidCheck`; install with `./gradlew :androidApp:installDebug`, then perform the 4:59/5:00 background checks and an airplane-mode unlock/retry check on an API 36 device. Add one lifecycle test using an injected clock so the five-minute boundary is deterministic.

### P0.2 — Complete the Android L1 wallet vertical slice

**Current status:** Partial. Home loads the real connector balance and immutable history together. Top Up is reachable, generates its address QR locally, and uses explicit sensitive clipboard copy with conditional 60-second clearing. Transfer is registered in the existing `NavHost`, accepts only another same-network profile, previews amount/fee bound/change/recipient/network, inspects unsigned and signed intent semantics, journals before signing/submission, and reconciles a stable operation ID after process death without reposting.

**Missing work:**

1. Validate L1 history against a funded low-value Mainnet wallet on the controlled connector; merge verified L2 records in P2.
2. Verify the controlled Mainnet L1 operation endpoint, idempotent submission, and process-kill reconciliation before setting `l1MutationsAvailable`; Transfer remains unreachable until this deployment gate passes.
3. Keep external-address entry out of normal transfer. It belongs only to wallet removal.

**Affected files:** `shared/src/commonMain/kotlin/io/riverark/ferret/FerretApp.kt`; `shared/src/commonMain/kotlin/io/riverark/ferret/feature/wallet/WalletScreens.kt`; `shared/src/commonMain/kotlin/io/riverark/ferret/feature/wallet/WalletViewModels.kt`; `shared/src/commonMain/kotlin/io/riverark/ferret/core/network/Clients.kt`; `shared/src/commonMain/kotlin/io/riverark/ferret/core/cardano/CardanoTransactionEngine.kt`; `shared/src/androidMain/kotlin/io/riverark/ferret/core/cardano/AndroidCardanoTransactionEngine.kt`; `androidApp/src/main/kotlin/io/riverark/ferret/MainActivity.kt`.

**Dependencies:** P0.1 validated online session; existing vault, wallet mutex, connector, deployment, and Android Cardano engine. Use an already installed local QR library only if present; otherwise prefer the smallest platform implementation and add no general image framework.

**Controlled deployment prerequisite:** Only the Mainnet connector/adaptor services
under `crustypants.com` are in scope. `/protocol-parameters` is deployed there.
Before enabling a financial mutation, verify its operation lookup, idempotent
submission, writer-lease, and reconciliation contracts on those owned services.
Preprod and `ferret.channel` availability must not block or satisfy this gate.

**Minimal cross-repository unblock plan:**

1. In Konduit's `packages/cardano/connector-server`, keep the existing
   lease-gated `/submit` for channel transactions. Add a separate L1 operation
   endpoint whose request contains `operation_id`, the expected
   `transaction_id`, and signed transaction CBOR. A signed Cardano transaction
   already authorizes an independent L1 spend; it must not depend on the
   channel backup writer lease.
2. Persist the L1 operation ID and expected transaction ID before submission,
   reject reuse of an operation ID with different transaction bytes, and expose
   `GET /operations/{operation_id}`. Reconciliation returns the original
   transaction ID and queries chain state; it never creates a second operation
   or substitutes a different transaction.
3. Add Konduit contract tests for exact UUID/hex validation, conflicting reuse,
   concurrent duplicate submission, lookup before/after upstream acceptance,
   and lookup after client disconnect. Update OpenAPI, deploy to the controlled
   Mainnet services, and verify `/protocol-parameters`, L1 submission, and
   operation lookup there before changing Ferret reachability.
4. In Ferret, add strict typed UTxO/protocol/operation DTOs and response bounds;
   an encrypted atomic L1 journal in the existing wallet secret; one
   `L1WalletRepository` using the existing per-wallet mutex and Android
   transaction engine; and Transfer screen/ViewModel/navigation wiring.
   Journal before signing/submission, inspect with `requireMatches`, and on
   restart reconcile the stored operation ID instead of posting again.
5. Verify with shared boundary tests, Android semantic tests, and
   `./gradlew androidCheck`, then run two funded low-value Mainnet wallets on a
   device with a process kill after submit. Only after one operation ID resolves
   to one transaction through confirmed/settled may Transfer become reachable.

**Acceptance criteria:**

- A funded low-value Mainnet wallet shows the exact connector balance and refreshed immutable history after pull-to-refresh.
- Top-up QR payload equals the full selected wallet address; copy occurs only on explicit action and is cleared after 60 seconds when still owned.
- Transfer destinations contain same-network profiles only. Cross-network transfer fails before transaction building.
- One submitted transfer has one stable operation ID, passes signed-intent inspection, appears pending, and reconciles to confirmed/settled without duplicate submission after process death.
- Top-up, Transfer, and History are reachable through the single `Route`/`NavHost` model.

**Verification:** `./gradlew androidCheck`; run the existing Android transaction semantic tests plus new shared transfer/history contract tests; on funded low-value Mainnet wallets, top up, copy/scan the QR, transfer between profiles, kill after submit, relaunch, and observe one transaction ID and updated balances/history.

### P0.3 — Finish Android release security for the L1 slice

**Current status:** Partial. Dependency locks/checksums, min/target SDK policy, cleartext denial, SPKI pins, release OAuth configuration gate, encrypted vault, packaged notices, minification checks, deterministic version/build diagnostics, stable bounded local diagnostic codes, and release-wide production logging rejection exist. Unlock, mnemonic, transfer confirmation, payment, and removal routes use reference-counted `FLAG_SECURE` protection. An API 36 emulator confirmed the secure window produces a black screenshot and startup logs contain no wallet secrets.

**Missing work:** Perform the focused MASVS review and repeat the release screenshot/log/file/clipboard checks with the real release OAuth configuration and funded Mainnet wallets. Do not add analytics, remote crash reporting, root-detection, or Play Integrity without a consuming policy.

**Affected files:** `androidApp/build.gradle.kts`; Android manifest/resources/proguard configuration; `MainActivity.kt`; shared settings/diagnostics models; CI/release configuration when present.

**Dependencies:** P0.1 and P0.2 behavior complete.

**Acceptance criteria:** Release build is non-debuggable, cleartext-disabled, backup-disabled, minified, dependency-verified, and contains required notices. Sensitive routes never appear in screenshots/app switcher. App-private files, clipboard, and captured logs contain no mnemonic, entropy, private key, invoice, signed CBOR, or decrypted channel state.

**Verification:** `FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck`; inspect the release APK manifest with Android Studio APK Analyzer; execute the sensitive-route screenshot/log/file/clipboard checks on a release build; record the MASVS checklist result before enabling financial actions.

## Full original-scope coverage map

### 1. Greenfield KMP cutover and build — Complete

**Current status:** The Svelte/PWA/WASM runtime is gone. Root Gradle Kotlin DSL, `shared`, `androidApp`, iOS framework targets, locked dependencies, checksums, Java 17, SDK 28/36, manual dependency construction, `androidCheck`, and `androidReleaseCheck` exist.

**Missing work:** None for the cutover itself. The iOS Xcode host remains under item 15.

**Priority:** Done.

**Affected files:** Root Gradle files, `gradle/libs.versions.toml`, `shared/build.gradle.kts`, `androidApp/build.gradle.kts`.

**Dependencies:** None.

**Acceptance criteria:** No web runtime is needed to build or run Android; dependencies are pinned and locked.

**Verification:** `./gradlew androidCheck`; repository inspection confirms no Svelte/Vite/npm runtime files.

### 2. Shared state, typed navigation, and coroutine ownership — Partial

**Current status:** Immutable domain types, `WalletRepository`, per-wallet mutexes, shared ViewModels, serializable `Route`, and one root `NavHost` exist. Only Unlock, connectivity, wallet lifecycle, recovery, and Home routes are registered. Operation journaling exists only inside channel scaffolding; no common L1 operation journal is connected.

**Missing work:** Register every implemented product destination as its feature lands; keep all actions in the existing repository/ViewModel boundaries; add durable L1 operation records; keep cancellation lifecycle-owned and errors redacted. Do not introduce MVI, reducers, generic use cases, a DI framework, or a second navigator.

**Priority:** P0 through P2, incrementally.

**Affected files:** `FerretApp.kt`, `Route.kt`, wallet/payment ViewModels, `WalletRepository.kt`, channel repositories.

**Dependencies:** Feature implementations below.

**Acceptance criteria:** Every reachable screen is represented by `Route`; process recreation restores only non-secret identifiers; no second active-wallet store or navigator exists; concurrent mutations for one wallet serialize.

**Verification:** `./gradlew androidCheck`; navigation tests exercise each registered route and process recreation without secrets in route arguments.

### 3. Android custody, multi-wallet onboarding, and recovery — Complete

**Current status:** Android biometric/device-credential unlock, StrongBox-with-fallback Keystore wrapping, encrypted atomic profile/seed files, 32-byte entropy, BIP-39 create/restore, existing Preprod/Mainnet profiles, duplicate credential rejection across networks, secure mnemonic routes, resumable confirmation, random three-position verification, the exact five-minute background lock, Drive backup discovery during restore, and Settings rename are implemented. Deployment and device acceptance testing are Mainnet-only. Old raw-key/Svelte backups are unsupported as required.

**Missing work:** None.

**Priority:** Done.

**Affected files:** `AndroidSecurity.kt`, `MainActivity.kt`, `WalletManager.kt`, `WalletViewModels.kt`, `WalletScreens.kt`.

**Dependencies:** Drive discovery for channel restore.

**Acceptance criteria:** New-wallet confirmation resumes after process death; restored wallets skip confirmation; three distinct random positions are verified; duplicate entropy/payment credentials are rejected across profiles; no seed or mnemonic leaves protected memory/UI.

**Verification:** `./gradlew androidCheck`; existing `WalletManagerTest`, `RecoveryVerificationTest`, and `SecurityTest`; device create/restore/restart/invalidated-key scenarios.

### 4. Ferret visual system, accessibility, icon, and docs — Complete for connected screens; Partial overall

**Current status:** Shared cream/charcoal/yellow/coral theme, bundled Exo 2/Ubuntu Mono fonts, Material Symbols, reusable components, 48dp targets, safe insets, bundled art, adaptive launcher resources, refreshed README/CONTEXT/AGENTS/NOTICE, and styled wallet/payment/settings surfaces exist. The adaptive circular launcher and random recovery verification requested in later sessions are present.

**Missing work:** Apply existing components to the missing Transfer/Open Channel/Channel/Remove Wallet screens and verify TalkBack semantics, non-gesture alternatives, and live announcements when those screens become reachable. No dark theme was in the original scope.

**Priority:** Alongside each feature, not a separate redesign.

**Affected files:** `FerretTheme.kt`, `FerretComponents.kt`, feature screens, Compose resources, Android launcher resources.

**Dependencies:** Reachable feature screens.

**Acceptance criteria:** Every new screen reuses shared tokens/components, supports system text scaling and 48dp controls, and provides TalkBack labels/state descriptions. Decorative imagery is not authoritative.

**Verification:** Install the app and inspect each reachable screen with TalkBack and font scale 200%; verify adaptive and round launcher icons on API 36. No additional documentation refresh is required until behavior changes.

### 5. Deployment validation, transport, refresh, and offline policy — Partial

**Current status:** Immutable deployment tuples, connector/adaptor clients, HTTPS-only Android config, no redirects, strict JSON, timeouts, actual-body byte limits across connector/adaptor responses, strict transaction/operation/session/adaptor DTO validation, and current/backup SPKI pins exist. Only the controlled Mainnet `crustypants.com` tuple is a supported deployment/test target. Home uses the real connector balance and repeats coordinated balance/history refresh every 20 seconds only while an L1 or journal-backed payment record is pending and the Home lifecycle is started.

**Missing work:** Confirm the controlled connector/adaptor services support idempotent L1 submission, signed `/session/claim`, operation lookup/reconciliation, and shared lease enforcement before enabling mutations. Extend the pending refresh inputs with verified channel operations when the channel repository is connected.

**Priority:** P0; server contract is a blocker for P1/P2.

**Affected files:** `Deployments.kt`, `Clients.kt`, `FerretHttpClient.kt`, `MainActivity.kt`, external Konduit connector/adaptor services.

**Dependencies:** Valid Ferret deployment identity/pins and server support.

**Acceptance criteria:** Wrong host/network/identity/pin fails closed; GET retries at most once only for timeout; mutations never blindly retry; foreground/pull/pending refresh rules are exact; no offline data/actions are exposed.

**Verification:** `./gradlew androidCheck`; controlled HTTP contract tests for mismatch, redirect, timeout, oversized response, unknown field, and operation reconciliation; device airplane-mode scenario.

### 6. Android Cardano engine and semantic conformance — Partial

**Current status:** Common typed intents and Android Bloxbean derivation/build/sign/inspect implementations exist for Transfer, OpenChannel, AddChannelFunds, CloseChannel, and SweepWallet. Android semantic tests exist. No connected feature currently builds/signs/submits an intent, and controlled-node evaluation/golden equivalence to pinned Konduit is not evidenced.

**Missing work:** Wire P0 transfer first, then channel/removal intents. Add golden semantic fixtures from pinned Konduit commit `a68cfedd4a0188ef9adad970e89c12b2b805b678`, controlled-node ledger evaluation, exact fee/value/script/signer/validity checks, and mutation tests proving `requireMatches` rejects extra or altered outputs.

**Priority:** P0 transfer; P1 channel; P3 removal; Mainnet release blocker.

**Affected files:** `CardanoTransactionEngine.kt`, `AndroidCardanoTransactionEngine.kt`, Android host tests, connector submission orchestration.

**Dependencies:** Current protocol parameters/UTxOs from validated connector; pinned Konduit fixtures.

**Acceptance criteria:** All five intents conserve value and match expected outputs, scripts/datums/redeemers, signer set, validity, and fee bounds; mutated intent semantics are rejected; a controlled Mainnet node evaluates each transaction successfully.

**Verification:** `./gradlew :shared:testAndroidHostTest`; run the five fixture evaluations against a controlled Mainnet node; include exact decoded summaries in test artifacts.

### 7. Home balance and pull-to-refresh — Complete for the connected L1 dashboard

**Current status:** The selected profile’s connector balance, immutable unified history, latest activity, and refresh timestamp update together on initial load and pull-to-refresh. While an L1 or journal-backed payment record is pending, Home repeats the same coordinated refresh every 20 seconds and stops when the record becomes terminal or Home leaves the started lifecycle. The primary action follows wallet state: zero L1 balance offers top-up, a funded wallet without an open channel keeps channel opening disabled, and an open channel keeps payment disabled until its deployment gate passes. No fiat value is shown.

**Missing work:** Add the L2 balance projection and verified channel-operation records to the existing pending cadence in P1.

**Priority:** P1 extension.

**Affected files:** `FerretApp.kt`, `WalletViewModels.kt`, `WalletScreens.kt`.

**Dependencies:** Connected channel repository for L2 and pending-operation state.

**Acceptance criteria:** Balance exactly matches connector UTxOs; one refresh updates balance, latest immutable activity, and refresh timestamp; action precedence matches channel state; connectivity failure routes to Offline before cached wallet data is exposed.

**Verification:** `WalletBalanceTest`; `./gradlew androidCheck`; API 34 device pull-to-refresh confirms the timestamp and dashboard activity state update together.

### 8. Top-up address QR and clipboard — Complete

**Current status:** `TopUpScreen` is reachable from Home, renders a locally encoded QR containing the exact payment address, and copies only on explicit action. Android marks the clip sensitive and clears it after 60 seconds only when its unique Ferret label and address still match.

**Missing work:** None.

**Priority:** P0.

**Affected files:** `WalletScreens.kt`, `FerretApp.kt`, Android clipboard/QR implementation.

**Dependencies:** Selected ready wallet and validated network.

**Acceptance criteria:** QR and clipboard contain exactly the full selected network address; ordinary address screen remains shareable; mnemonic/invoice/signed payload copy actions do not exist.

**Verification:** Android host decoding verifies exact address payloads. On a Pixel 8a, the rendered Mainnet QR decoded to the full displayed address, explicit copy produced the same address, and the owned clip cleared after the 60-second timeout when Ferret resumed; replacement clips are protected by the unique-label/address ownership check.

### 9. Same-network L1 transfer — Partial

**Current status:** Typed `Route.Transfer`, `CardanoIntent.Transfer`, Android transaction builder, and a shared same-network destination boundary exist. Normal transfer cannot accept an external address. No screen, repository orchestration, journal, or submission wiring exists.

**Missing work:** P0.2 transfer vertical slice.

**Priority:** P0.

**Affected files:** Cardano engine, wallet feature screens/ViewModels, connector, `FerretApp.kt`, Android composition root.

**Dependencies:** P0.1, connector protocol parameters/submit/reconcile, same-network profiles.

**Acceptance criteria:** Only available L1 funds and same-network profiles are selectable; preview shows amount/fee/change/network; one process-death-safe operation is submitted; active channel UTxOs are never selected.

**Verification:** Shared boundary tests plus a funded low-value Mainnet two-wallet device scenario and process-kill reconciliation.

### 10. Encrypted Drive recovery and single-writer ownership — Partial

**Current status:** Partial. `FerretChannelBackupV1`, HKDF/AES-GCM/hash-chain repository logic, backup state types, a bounded Drive appData-only REST client, Android crypto, native Google account selection, OAuth scope authorization, encrypted initial backup read-back/decrypt verification, checkpoint persistence, and Settings verification are connected. Mnemonic restoration offers Google Drive recovery, fails closed on missing/conflicting/modified backup data, and installs the verified channel snapshot into the encrypted local journal. Verification distinguishes a valid newer remote checkpoint from corruption; Settings presents the stale writer and requires confirmation before restoring the newest snapshot and starting a new generation. Every channel mutation and reconciliation revalidates the remote Drive checkpoint before resolving a writer lease, so a takeover invalidates the stale device before its next journal or remote side effect. Android persists one fail-closed, non-backed-up device identity per installation. `SessionLeaseRepository` signs the exact verified generation, ciphertext hash, device identity, adaptor identity, and monotonic claim timestamp, then reuses a lease only while every bound value still matches and it remains unexpired. Concrete mutations still do not claim or consume that lease.

**Missing work:** Connect the checkpoint-bound claimant during channel creation and share the resulting lease with the concrete remote adapter. Never put wallet identifiers, network, addresses, or credentials in Drive metadata.

**Priority:** P1; hard blocker for channel creation.

**Affected files:** `DriveBackup.kt`, `AndroidBackupCrypto.kt`, new Android OAuth/Drive adapters, secure wallet journal schema, `SessionLeaseRepository.kt`, settings/restore UI, Android composition root, external connector/adaptor servers.

**Dependencies:** Google OAuth client, dedicated Drive test account, server-enforced generation lease, P0 online/session lifecycle.

**Acceptance criteria:** Channel open is disabled until backup round-trips and decrypts; tampered headers/ciphertext, broken chain, stale generation, and same-generation divergence fail closed; takeover on device B invalidates device A before its next mutation; Drive failure after write-ahead blocks later L2 action without losing authorization state.

**Verification:** `FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck`; automated crypto/hash-chain fixtures; device A/B Drive initial backup, tamper, restore, takeover, stale-writer, and conflict scenarios.

### 11. Channel protocol and lifecycle — Partial

**Current status:** Protocol wire/signing types, Android signer, session lease repository, channel state machine, per-wallet mutex, concrete encrypted `ChannelJournal`, Drive backup protocol adapter, and tests exist. The adaptor client uses strict pinned-Konduit quote/pay request types, duration encoding, keytag construction, and bounded strict quote decoding; canonical JSON and CBOR fixtures lock those shapes to commit `a68cfedd4a0188ef9adad970e89c12b2b805b678`. Every channel mutation and reconciliation revalidates the remote Drive checkpoint and requires a validated writer lease before local write-ahead or remote execution. No complete remote channel implementation, balance projection, open/add/close screens, or navigation/composition wiring exists.

**Missing work:** Complete the connector/adaptor mutation and reconciliation adapters, including strict squash/receipt response types; claim the writer lease from the verified Drive checkpoint and stable device identity; verify adaptor signatures; preview dynamic min-ADA/fees; wire open/add/close/squash through local journal → Drive write-ahead → sign/submit/mutate → chain/adaptor reconciliation → terminal Drive snapshot; reconcile from chain, adaptor, local journal, and Drive on launch/resume.

**Priority:** P1 after item 10 and server contract.

**Affected files:** `ProtocolWire.kt`, `AndroidProtocolSigner.kt`, `SessionLeaseRepository.kt`, `ChannelRepository.kt`, secure vault schema, network clients, new channel screens/ViewModels, `FerretApp.kt`, Android composition root.

**Dependencies:** Items 5, 6, and 10; controlled Mainnet `crustypants.com` services; server operation IDs and writer lease.

**Acceptance criteria:** Repository rejects illegal/concurrent actions independent of UI; open/add/close survive process death at every external-side-effect boundary with one reconciled result; missing/conflicting backup blocks mutation; channel balance excludes exact protocol-required min-ADA from transaction preview, not a fixed UI constant.

**Verification:** `./gradlew androidCheck`; pinned wire/conformance vectors; kill-before-submit, kill-after-submit, and kill-after-adaptor-acceptance scenarios for open/add/close; controlled Mainnet chain/adaptor reconciliation.

### 12. BOLT11 QR payment — Partial

**Current status:** CameraX/ML Kit QR-only scanner, denial/settings UI, ACINQ BOLT11 parsing, expiry/network/amount checks, three-second guard, payment state model, confirmation screen, and durable-style receipt screen exist. They are unreachable and have no real `PaymentGateway`, `ChannelRemote`, Drive write-ahead, receipt verification, duplicate persistence, or reconciliation wiring.

**Missing work:** Register scan/confirm/receipt routes; construct the scanner on Android only; implement gateway/adaptor quote validation and receipt verification; persist paid hashes/quote index; execute payment through the item 11 journal/Drive/lease path; stop camera after one value and on background; restore pending reconciliation after interruption. Keep QR-only: no paste, manual entry, deep link, LNURL, or Lightning Address.

**Priority:** P2 after a verified open channel.

**Affected files:** `QrPaymentScannerScreen.kt`, `AndroidQrScanner.kt`, `PaymentViewModel.kt`, `PaymentScreens.kt`, `ChannelRepository.kt`, network/protocol adapters, `FerretApp.kt`, Android composition root.

**Dependencies:** Open channel, verified Drive writer, adaptor identity, session lease, ACINQ parser.

**Acceptance criteria:** Invalid/expired/wrong-network/duplicate/amountless or amount-mismatched QR never requests a quote; camera stops after one accepted result and on background; confirmation shows amount, both fees, total, expiry, and three-second guard; one confirmation yields one durable verified/pending receipt and no duplicate authorization after process death.

**Verification:** `./gradlew androidCheck`; scanner lifecycle/permission tests; controlled valid and invalid BOLT11 fixtures; kill before/after adaptor acceptance and verify one reconciled payment.

### 13. Activity/history — Partial

**Current status:** L1 connector parsing, immutable ordering/merge policy, deterministic finality states, pull-to-refresh, typed History navigation, expandable detail, refresh timestamp, and Home’s latest-activity projection are connected. Journal-backed payment records are merged into history. Verified channel activity is not connected.

**Missing work:** Validate the L1 response against a funded low-value Mainnet wallet, then merge verified journal/adaptor activity in P2. Preserve immutable ordering, status semantics, amount, fee, realm, ID, and last refresh.

**Priority:** P0 for L1; P2 for L2.

**Affected files:** `Clients.kt`, domain transaction models, wallet/channel repositories, `WalletScreens.kt`, `FerretApp.kt`.

**Dependencies:** Connector transaction contract; later verified adaptor records.

**Acceptance criteria:** L1 and L2 records are merged by immutable timestamp without source mutation; 5-block confirmed and 2160-block settled labels are deterministic; pending/failed survive refresh and process interruption through their journals.

**Verification:** Shared ordering/status tests; funded low-value Mainnet transfer/channel/payment scenario; compare displayed IDs/statuses to connector/adaptor responses.

### 14. Settings and diagnostics — Partial

**Current status:** `SettingsScreen` is reachable from Home and projects the selected wallet's credentials, immutable network, channel/adaptor state, Drive account/generation/sequence, lock state, version/build commit, and redacted diagnostic code. Rename, Drive connect/verify/takeover, and Remove Wallet navigation are wired. Original placeholder settings remain removed: no custom endpoints, mutable network, fiat, FX, language, raw-key export, or plaintext backup.

**Missing work:** Replace the coarse channel/adaptor status with live channel lifecycle state when concrete channel adapters land. Do not restore removed web settings.

**Priority:** P1 backup controls; P3 complete settings.

**Affected files:** `SettingsScreen.kt`, wallet/backup/channel repositories, build metadata, `FerretApp.kt`, Android composition root.

**Dependencies:** Drive and channel state for meaningful status.

**Acceptance criteria:** Every displayed value comes from the selected wallet/runtime; backup verification runs the real read-back/decrypt path; diagnostics contain no sensitive values; prohibited settings/actions are absent.

**Verification:** `./gradlew androidCheck`; navigation/projection tests; device inspection for each status and diagnostic redaction.

### 15. Safe wallet removal — Partial

**Current status:** `WalletRemovalManager`, the protected confirmation screen/ViewModel, typed navigation, and Android readiness producer are connected. Removal checks closed/absent channel state, pending L1/channel/payment journals, live balance, transaction finality, and the encrypted Drive checkpoint. An already empty, settled wallet can delete its verified Drive backup with read-after-delete verification before the local encrypted profile, seed, and journals are deleted. Same-network sweep orchestration remains unavailable.

**Missing work:** Add same-network wallet or validated external sweep through journaled `SweepWallet` submission/reconciliation, then perform the funded-device interruption and mnemonic-restore scenario. The UI already explains that mnemonic/provider retention cannot be erased.

**Priority:** P3 after transfer, Drive, and channel lifecycle.

**Affected files:** `WalletRemoval.kt`, Cardano engine, vault, backup/channel repositories, new removal UI/ViewModel, `FerretApp.kt`, Android composition root.

**Dependencies:** Items 6, 10, and 11; finality data from connector.

**Acceptance criteria:** Removal is blocked for active channel, pending operation, Drive conflict, nonzero unswept balance, or depth below 2160; successful removal deletes local profile/seed/journal and Drive object; unlock cannot find the wallet; mnemonic restore recreates its L1 identity.

**Verification:** `./gradlew androidCheck`; readiness boundary tests; device close/sweep/finality/delete/restore scenario with Drive object inspection.

### 16. iOS 17 parity — Deferred, compile gate only

**Current status:** Shared iOS framework targets compile and a thin Swift source host exists, but there is no Xcode project. `walletManager = null` intentionally renders platform unavailable. No Keychain/LocalAuthentication vault, atomic files, CSL bridge, CryptoKit backup, Google Drive, Darwin TLS/pinning, AVFoundation scanner, Core Image QR, pasteboard expiry, lifecycle protection, or physical-device verification exists.

**Missing work:** Execute `IOS_FOLLOW_UP.md` only after Android P0–P3 contracts are stable: Xcode host; narrow CSL 17.0.0 Rust C ABI/XCFramework; security and lifecycle adapters; byte-compatible backup crypto and Drive; Darwin transport/pins; scanner/QR/clipboard; sensitive UI protection; Android behavioral parity.

**Priority:** P4.

**Affected files:** `iosApp`, `shared/src/iosMain`, `native/cardano-ios-bridge`, shared platform interfaces.

**Dependencies:** Stable Android semantics, macOS/Xcode/signing, Apple/Google credentials, pinned Rust toolchain, controlled fixtures.

**Acceptance criteria:** iOS no longer passes a null manager; simulator and physical iOS 17 device pass the same create/restore/transfer/Drive/channel/payment/removal and interruption contracts as Android; decoded CSL intent semantics match Bloxbean fixtures.

**Verification:** Linux gate `./gradlew :shared:compileKotlinIosSimulatorArm64`; on macOS `./gradlew :shared:iosSimulatorArm64Test`; `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' test`; execute the ten physical-device checks in `IOS_FOLLOW_UP.md`.

### 17. Mainnet release gate — Missing

**Current status:** Mainnet wallet creation/restore and immutable deployment configuration exist. No connected transfer/channel/payment/removal path or controlled Mainnet release evidence exists.

**Missing work:** Keep unfinished financial actions unreachable. After P0–P3, independently verify deployment tuple and pins; run low-value Mainnet create/restore, transfer, channel open/pay/close, Drive takeover, and removal; complete release hardening and SBOM/license/security review.

**Priority:** Final Android gate before P4/App Store work.

**Affected files:** Release configuration and evidence; no feature-specific alternate Mainnet implementation.

**Dependencies:** All Android features and external production services.

**Acceptance criteria:** Every financial action uses the selected immutable Mainnet deployment and passes the semantic, recovery, interruption, and security checks against the controlled `crustypants.com` services.

**Verification:** `FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck`; documented controlled low-value Mainnet scenario covering all actions and interruption boundaries.

### 18. Explicitly excluded web behavior — Complete by omission

**Current status:** The native repository does not retain the PWA, browser history, localStorage, raw 64-hex key import, Svelte JSON backup, arbitrary connector/adaptor URLs, mutable network, fiat/FX/language placeholders, manual invoice entry, LNURL/Lightning Address, background/offline queued actions, or immediate fake wallet exit.

**Missing work:** None. Do not reintroduce these as compatibility shims.

**Priority:** Permanent constraint.

**Affected files:** All future feature work.

**Dependencies:** None.

**Acceptance criteria:** Searches and reachable UI show none of the excluded controls/runtime paths; wallet restore accepts only the approved 24-word flow.

**Verification:** Repository/UI inspection after each phase; security review rejects any reintroduced plaintext secret export, custom endpoint, manual/LNURL payment, offline mutation, or local-delete-only exit.

## Execution order and gates

1. **P0 Android L1:** P0.1 lifecycle/network gate → P0.2 top-up/history/transfer → P0.3 release security. Keep channel/payment actions disabled.
2. **P1 Recovery and channel:** Drive/Google identity + server lease → concrete channel adapters → open/add/close and reconciliation.
3. **P2 Payment:** QR scan → quote → guarded confirmation → journaled payment → durable receipt/history.
4. **P3 Product closure:** Settings/diagnostics/rename → safe sweep/removal → Android Mainnet gate.
5. **P4 iOS:** Implement `IOS_FOLLOW_UP.md` against stable Android contracts; no duplicate SwiftUI product tree.

A phase does not open the next financial action merely because UI exists. Its acceptance scenario, interruption checks, and security prerequisites must pass first.

## Evidence cross-check

The coverage map accounts for every product capability and constraint in the original migration plan and prior agent reports:

- build/cutover, shared Compose, manual dependencies, state/navigation/coroutines;
- Android custody, biometric/device credential, multi-wallet 24-word create/restore, random verification, process-death recovery, and Mainnet-only deployment acceptance;
- visual system, resources, accessibility, adaptive icon, and documentation;
- immutable deployments, connector/adaptor transport, TLS pins, offline policy, refresh behavior;
- Bloxbean Cardano intents and conformance;
- Home balance and pull-to-refresh, top-up QR/copy, transfer, and history;
- encrypted Drive recovery, conflict/takeover, signed single-writer lease, journal/write-ahead protocol;
- channel open/add/pay/close/squash and interruption reconciliation;
- QR-only BOLT11 confirmation and durable receipt;
- settings/diagnostics and safe wallet removal;
- release hardening/Mainnet gate and full iOS parity;
- explicit rejection of legacy web architecture and unsafe/placeholder behaviors.

No repository evidence conflicts with the recovered scope. `CONTEXT.md`, `README.md`, and `IOS_FOLLOW_UP.md` describe a subset/current-state handoff consistent with the original Android-first sequencing.