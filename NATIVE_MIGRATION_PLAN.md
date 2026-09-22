# Native Migration Status and Remaining Plan

## Scope and deployment decisions

Ferret is an Android-first Kotlin Multiplatform Cardano and Lightning wallet with shared Compose UI. The native cutover is complete: no PWA, Svelte, WASM, browser storage, raw-key import, plaintext backup, arbitrary endpoint, fiat-accounting, manual invoice entry, LNURL, or offline mutation path remains.

Production development and acceptance use low-value Mainnet wallets against the controlled connector and adaptor services under `crustypants.com`. Preprod remains in persisted domain models but is unsupported for deployment evidence. `ferret.channel` is not a dependency or test target.

Android is the implemented product. iOS remains compile-only until `IOS_FOLLOW_UP.md` is complete.

## Current status — 2026-09-22

| Area | Status | Current evidence and boundary |
|---|---|---|
| Native build and shared UI | Complete | Kotlin Multiplatform, shared Compose Material 3 UI, Android host, typed `Route`, Java 17, SDK 28/36, dependency locks, `androidCheck`, release checks, and the iOS compile gate are present. |
| Android custody and onboarding | Complete | Biometric/device-credential unlock, encrypted atomic profiles and seed files, 24-word create/restore, resumable recovery confirmation, duplicate-credential rejection, protected sensitive routes, and the five-minute background lock are implemented. |
| Deployment and transport | Complete for controlled Mainnet | HTTPS-only pinned connector/adaptor clients, bounded strict DTOs, validated deployment identity, foreground cancellation, session leases, and durable operation lookup/reconciliation are wired. Other deployments are unsupported. |
| Reviewed asset catalog | Complete | Ferret hash-verifies the release-bundled ADA/USDA/USDCx/USDM catalog, presentation manifest, and logos. Exact policy/name/decimals/pricing/digest identity is required; runtime Koios and remote-logo requests are forbidden. |
| L1 dashboard and top-up | Implemented and device-verified | Home derives exact reviewed holdings from owned UTxOs, separates L1 holdings, transfer availability, and L2 capacity, bounds unknown assets, and renders packaged metadata. Top-up remains ADA-address based. |
| L1 transfer and sweep | Catalog-asset transfer implemented; funded native acceptance pending | Transfer selection, previews, input conservation, dynamic minimum ADA, multi-asset change, authorization, persistence, reconciliation, and history carry exact assets. Send-all and wallet sweep remain ADA-only. A controlled 2 ADA transfer passed; funded USDA/USDCx/USDM transfer and interruption runs remain. |
| Encrypted Drive recovery | Complete-collection implementation; restore matrix pending | One encrypted deterministic `ChannelCollectionV4` contains every keyed channel, payment journal, paid hash, and unresolved migration record. Write-ahead, writer verification, commit, restore, and takeover operate on the complete collection. The operator skipped restore/takeover testing for the stablecoin-payment pass; the broader two-device matrix remains. |
| Channel lifecycle | Catalog-asset Open and Add implemented; later controls hidden | Open and per-channel Add accept reviewed assets and validate exact token quantity, channel-output ADA, ledger minimum, protocol reserve, complete change, signed transaction identity, and ADA-only collateral for script-spending Add transactions. Add revalidates the selected open channel, asset, keytag, current capacity, and durable operation identity through submission and restart reconciliation. Close, Elapse, End, and user-initiated Squash remain unreachable until their complete asset-bound flows receive controlled acceptance. Internal zero-squash payment initialization is implemented. |
| Multi-channel storage | Complete | Channels are keyed by full protocol keytag. Each entry owns its asset, state, spendable capacity, operation, receipt chain, payment journal, and history. Mutations and reconciliation target one exact entry under the existing wallet lock. |
| Lightning payment | Implemented for reviewed catalog assets; live settlement acceptance incomplete | ADA, USDA, USDCx, and USDM share one asset-aware selection, initialization, quote, confirmation, authorization, submission, reconciliation, receipt, and history path. Deterministic native settlement and restart recovery pass. Pixel acceptance reached a valid USDM confirmation; the operator declined Pay, so live USDM settlement and the new ADA/USDCx/USDA regressions remain. |
| Activity and history | Implemented | L1 records retain per-asset deltas with one ADA fee. L2 records retain exact amount, fee, selected asset, and channel keytag. Completion mutates only the chosen channel and wallet-wide paid-invoice set. |
| Wallet removal | Collection-wide gating implemented; full acceptance pending | Removal checks every channel, pending channel/payment operation, reviewed and unknown L1 inventory, unresolved migration evidence, Drive state, and finality. The funded close/sweep/finality/delete/restore scenario remains. |
| Android release | Partial | Financial routes are enabled in debug and release builds. Local release checks pass with a non-production OAuth placeholder. Production OAuth, signing material, and the release-device security matrix remain operator prerequisites. |
| iOS | Deferred | Shared contracts compile for `iosSimulatorArm64`; no Xcode project or iOS custody, Cardano, Drive, TLS, QR, lifecycle, or signing adapters exist. |

## Implemented native-asset architecture

### Exact asset identity and amounts

`ChannelAsset` carries the canonical alias, policy ID and asset name (or ADA), decimal count, pricing definition, and catalog digest. `AssetAmount` carries nonnegative integer base units plus the complete asset identity; checked addition and subtraction reject mismatch, overflow, and negative results. Decimal conversion is presentation-only.

The reviewed build currently contains ADA, USDA, USDCx, and USDM. Catalog membership is exact equality, not alias matching. Discovery must return the packaged catalog digest before a financial action proceeds. Unknown, changed, or tampered assets fail closed rather than becoming ADA or inheriting another asset's decimals or pricing.

Asset identity is persisted through channel definitions, prepared operations, quotes, authorizations, encrypted backups, receipts, balances, and history. Existing ADA-only legacy migrations remain ADA-only.

### Embedded token presentation metadata

Token metadata is a reviewed build-time input:

- `updateEmbeddedAssetMetadata` explicitly reads the pinned Konduit catalog and performs one bounded Mainnet Koios bulk request.
- The updater accepts only bounded presentation fields, verifies identity and decimals, validates PNG structure/CRC/dimensions, and writes deterministic shared resources.
- The checked-in manifest and logos are hash-verified offline by normal Android checks.
- Compilation, tests, startup, balance refresh, and payments do not contact Koios or remote image URLs.
- A release can use the last reviewed metadata when Koios is unavailable. Adding or changing an asset requires an explicit updater run and reviewed resource diff.

### L1 transfer and channel funding

Transfer and channel Open allow selection from the reviewed catalog. Per-channel Add fixes the asset to the selected open channel and accepts only an eligible exact-keytag channel with no pending channel or payment operation. The transaction boundary preserves every input asset and separately displays:

- selected asset quantity;
- ADA transaction fee;
- recipient or channel-output ADA;
- ledger minimum ADA;
- protocol reserve;
- resulting channel capacity;
- Add collateral; and
- complete ADA/native change.

Native output validation rejects policy/name substitution, token loss, unexpected tokens, insufficient ADA, foreign change, changed transaction bodies, signed-witness contamination, and non-ADA collateral. Add consumes the exact current channel output, conserves its prior capacity plus the requested amount, and preserves one durable operation identity through encrypted local/Drive write-ahead, connector submission, replay, and restart reconciliation.

Implementation is complete for transfer, Open, and Add. Remaining work is controlled funded-device evidence for USDA/USDCx/USDM transfer and Open, plus approved low-value ADA/native Add preview, submission, interruption, and Drive-recovery scenarios.

### Keyed channel collection

`ChannelCollectionV4` replaces the singular wallet-wide channel record. It stores channels by full keytag plus a wallet-wide paid-invoice set and unresolved legacy evidence. Each `ChannelSnapshot` owns its exact asset, lifecycle state, pending operation, protocol receipt, spendable capacity, payment journal, and history.

The encrypted journal and Drive backup persist the complete collection atomically. Guarded cleanup removes only terminal failed zero-balance attempts, preserves records that bind unambiguously to a recovered channel, writes the verified Drive backup before local replacement, and rejects pending, funded, successful-unbound, or uncertain records.

### Reviewed-asset Lightning payments

Payment capability is the exact packaged catalog, not an ADA gate or a USDM special case. A channel is eligible only when:

- its complete asset equals the catalog entry for its alias;
- its spendable amount carries the same asset;
- it is open;
- neither channel nor payment work is pending; and
- spendable capacity is positive.

Home, channel selection, and gateway authorization use the same predicate. Wallet-wide unresolved legacy evidence blocks payment. One eligible channel auto-selects; multiple eligible channels require explicit user choice sorted by keytag. Ferret never chooses the cheapest currency, largest balance, last-used channel, or an ADA fallback.

The chosen keytag and asset remain bound through zero-squash initialization, quote, confirmation, cheque signing, submission, reconciliation, receipt, and history. Before confirmation Ferret re-reads the current selected snapshot and verifies quote keytag, complete asset, invoice hash, invoice millisatoshis, expiry, and checked total against current capacity. Before signing the gateway repeats authoritative catalog, expiry, and capacity checks under the repository wallet lock.

Payment completion requires matching verified unlocked-cheque evidence. A pay response alone is not success. Transport uncertainty leaves the durable operation pending and reconciliation replays the same authorization; it does not create a second invoice payment. Completion debits only the selected channel and records its receipt/hash. Terminal failure preserves capacity and records failed history.

The Android selected-channel callback is snapshot-only. This avoids recursively acquiring the non-reentrant wallet mutex while `initializePayment` or `submitPayment` already owns it; repository serialization, writer verification, and write-ahead remain unchanged.

Early confirmation taps are ignored. Quote expiry and invoice expiry return recoverable rescan errors without payment or an automatic replacement quote.

## Verification and controlled evidence

### Automated evidence

The stablecoin-focused host checks cover:

- sequential USDM then USDCx completion with isolated balances, receipts, fees, keytags, history, and paid hashes;
- duplicate invoices across ADA/native channels;
- an ADA quote addressed to a USDM channel;
- real `DefaultPaymentGateway` USDM initialization, quote, Ed25519 authorization, pay, unlocked receipt, completion, and sibling-channel isolation;
- catalog, asset, keytag, and capacity rejection before pay;
- USDA/native eligibility boundaries; and
- restart reconciliation of one submitted USDM authorization into exactly one debit and receipt.

The following passed on 2026-09-22:

```sh
./gradlew :shared:testAndroidHostTest \
  --tests io.riverark.ferret.core.cardano.AndroidCardanoTransactionEngineTest
./gradlew :shared:testAndroidHostTest \
  --tests io.riverark.ferret.core.channel.OpenChannelTransactionsTest \
  --tests io.riverark.ferret.ChannelRepositoryTest \
  --tests io.riverark.ferret.core.channel.ChannelRemoteRecoveryTest \
  --tests io.riverark.ferret.DurableChannelStorageTest
./gradlew :shared:testAndroidHostTest \
  --tests io.riverark.ferret.core.channel.StablecoinPaymentTest \
  --tests io.riverark.ferret.ChannelRepositoryTest \
  --tests io.riverark.ferret.core.channel.ChannelRemoteRecoveryTest
./gradlew androidCheck
./gradlew :shared:compileKotlinIosSimulatorArm64

cd ../konduit
cargo test -p konduit-server channel_operation_reservation_is_idempotent_and_fenced
cargo test -p konduit-server quote_handler_uses_authenticated_channel_definition
cargo test -p konduit-server quote_amount_uses_persisted_definition_pricing
```

These tests establish deterministic reviewed-asset Add construction, ADA-only collateral, authorization, durable admission, idempotent reservation, isolation, restart recovery, and the existing cross-asset payment guarantees. They are not claims of live channel funding or payment.

### Existing controlled Mainnet evidence

Earlier controlled ADA acceptance completed channel opening and one QR-only Lightning payment, including encrypted write-ahead, route-aware quote, signed authorization, returned preimage, verified receipt, reconciliation, spendable-balance reduction, and settled activity.

A controlled 2 ADA L1 transfer also completed and reconciled with its exact previewed fee and transaction ID.

### 2026-09-22 Pixel stablecoin evidence

Debug was installed without clearing Pixel `3B251JEKB11124`. Controlled discovery returned packaged digest `09ce40fc9bfd7b600506400417b4c09ba0ca2bd5b58703aeb00699c084d298ae` and USD-base FX data.

Home showed:

- open USDM channel `499b3bf7e98d` with `0.1 USDM` spendable capacity; and
- open ADA channel `257410d9617c` with `₳0.463953` spendable capacity.

The chooser offered both channels with distinct labels and correct assets/capacities. USDM zero-squash initialization completed without hanging. An expired invoice produced a recoverable rescan error and no pay request.

A fresh controlled `$0.01` invoice reached USDM confirmation with:

- payment amount: `0.011201 USDM`;
- routing fee: `0.001733 USDM`;
- adaptor fee: `0.001414 USDM`;
- total: `0.014348 USDM`; and
- projected capacity: `0.085652 USDM`.

The operator reviewed and declined Pay. No live debit, recipient settlement, receipt, paid hash, or history update is claimed. The declined confirmation was cleared by process relaunch without clearing application data.

## Remaining Android work

### P0 — Complete live payment acceptance

Implementation is complete. Remaining evidence requires explicit operator approval:

1. Pay a fresh low-value invoice from USDM and verify receiver settlement.
2. Verify the signed receipt, invoice hash, operation ID, amount, fees, keytag, and exact `0.014348 USDM`-or-current-quote debit.
3. Verify ADA, USDCx, USDA, L1 holdings, and unrelated channel history remain unchanged.
4. Reject the same invoice wallet-wide after completion.
5. Repeat a fresh low-value ADA payment through the new chooser as a regression.
6. When funded channels exist and payment is explicitly approved, repeat channel-isolation acceptance for USDCx and USDA.

Do not create or fund channels automatically. A new live payment always requires operator review and confirmation.

### P1 — Complete funded native L1/Open acceptance

Use operator-approved low-value Mainnet funds to exercise USDA/USDCx/USDM transfer and channel Open. Verify exact token conservation, ADA fee/minimum/reserve, multi-asset change, signed transaction identity, connector reconciliation, activity, and interruption before and after submission.

The earlier ADA transfer passed, but its process-kill/finality/rollback matrix remains incomplete.

### P2 — Complete Add acceptance and expose later controls

Per-channel Add is wired through the existing Channel screen, repository, wallet lock, and encrypted collection. On 2026-09-22 the debug build on unlocked Pixel `3B251JEKB11124` exposed Add only for open USDM `499b3bf7e98d` and ADA `257410d9617c`, bound each form to the correct fixed asset, full selected channel, and current capacity, accepted decimal edits, and cleared failed preview state after an edit. Encrypted Drive backup sequence 89 verified successfully. Non-mutating attempts for `0.001 USDM` and `0.1 ADA` were rejected before preview with the recoverable funding-prerequisite error, so fee, minimum ADA, collateral, projected-capacity rendering, and live Add acceptance remain pending. No confirmation or financial submission was attempted.

Wire Close, Elapse, End, and user-initiated Squash only after their asset-bound previews, authorization, encrypted Drive write-ahead, connector/adaptor mutation, reconciliation, interruption recovery, and UI confirmations are complete. Reuse `ChannelRepository`, `WalletRepository` locking, and the existing Channel screen; do not add another coordinator or state store.

### P3 — Complete Drive restore/takeover acceptance

The operator skipped encrypted Drive restore testing for the stablecoin-payment pass. Ordinary write-ahead, writer verification, and commit safety remain mandatory and implemented. Broader release acceptance still needs two Android installations with production OAuth to verify:

- latest-only encrypted read-back;
- mnemonic restoration of all channel assets, identities, operations, receipts, and paid hashes;
- stale-writer detection and explicit takeover;
- old-writer rejection after takeover;
- tamper, missing-object, broken-chain, same-generation divergence, and catalog-mismatch rejection; and
- restoration of terminal native-channel and payment records.

### P4 — Complete removal and release acceptance

Exercise closure of every asset channel, same-network ADA/native sweep, finality, verified Drive deletion, local vault deletion, and mnemonic restoration. Then run the signed release with production OAuth/signing material through sensitive-screen, log, file, clipboard, backup, and network checks.

### P5 — Implement iOS

Execute `IOS_FOLLOW_UP.md` only after the Android contracts are stable. Reuse shared asset/channel state, navigation, repositories, and Compose UI; do not create a duplicate SwiftUI product tree.

## Security and recovery invariants

These remain release requirements:

- `SecureVault` is the only persistence boundary for wallet profiles, seed entropy, and operation journals.
- Mnemonics and seed entropy are never logged, copied, or exposed outside protected flows.
- Sensitive Android routes retain `FLAG_SECURE` and clear it when leaving composition.
- Financial mutations require a validated foreground session, selected Mainnet wallet, current encrypted Drive checkpoint, and matching signed writer lease.
- Every mutation writes durable local and Drive state before its external side effect.
- Reconciliation reuses the original operation identity and exact signed payload; it never invents a replacement operation.
- Receipt signatures, cheque identity, amount, index, timeout, lock/preimage, asset, and channel keytag are verified before settlement.
- Transaction inspection rejects unknown inputs, value imbalance, unexpected assets/scripts/datums/redeemers, invalid witnesses, stale protocol parameters, and changed transaction bodies.
- Offline or mismatched deployment state exposes no cached financial actions.
- Koios and token-logo URLs are forbidden at runtime.

## Verification commands

Primary Ferret checks:

```sh
./gradlew :shared:allTests
./gradlew androidCheck
./gradlew :shared:compileKotlinIosSimulatorArm64
FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck
```

Install production-enabled debug behavior without clearing data:

```sh
ANDROID_SERIAL='<serial>' ./gradlew :androidApp:installDebug
```

Relevant controlled Konduit checks after payment or channel protocol changes:

```sh
cd ../konduit
cargo test -p bln-client -p konduit-server
cargo build --release -p konduit-server
```

Device evidence must use controlled Mainnet services. Passing builds do not replace live ADA/USDM/USDCx/USDA payment, funded native transfer/Open/Add, Drive takeover, later channel-control, removal, or signed-release acceptance.
