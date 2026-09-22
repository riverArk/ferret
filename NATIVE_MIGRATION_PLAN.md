# Native Migration Status and Remaining Plan

## Scope and deployment decisions

Ferret is an Android-first Kotlin Multiplatform Cardano and Lightning wallet with shared Compose UI. The native cutover is complete: no PWA, Svelte, WASM, browser storage, raw-key import, plaintext backup, arbitrary endpoint, fiat, manual invoice entry, LNURL, or offline mutation path remains.

Production development and acceptance use low-value Mainnet wallets against the controlled connector and adaptor services under `crustypants.com`. Preprod remains in persisted domain models but is unsupported for deployment evidence. `ferret.channel` is not a dependency or test target.

Android is the implemented product. iOS remains a compile-only platform gate until `IOS_FOLLOW_UP.md` is complete.

## Current status — 2026-09-15

| Area | Status | Current evidence and boundary |
|---|---|---|
| Native build and shared UI | Complete | Kotlin Multiplatform, shared Compose Material 3 UI, Android host, typed `Route`, Java 17, SDK 28/36, dependency locks, `androidCheck`, release checks, and the iOS compile gate are present. |
| Android custody and onboarding | Complete | Biometric/device-credential unlock, encrypted atomic profiles and seed files, 24-word create/restore, resumable recovery confirmation, duplicate-credential rejection, and the five-minute background lock are implemented. |
| Deployment and transport | Complete for controlled Mainnet | HTTPS-only pinned connector/adaptor clients, bounded strict DTOs, validated deployment identity, foreground cancellation, session leases, and operation lookup/reconciliation are wired. Other deployments are unsupported. |
| L1 dashboard and top-up | Asset holdings implemented; connected surface verified | Home derives exact reviewed ADA/USDA/USDCx/USDM holdings from owned UTxOs, keeps ADA holdings separate from transfer availability and L2 channel capacity, bounds unknown assets, and exposes native rows as read-only. The packaged rows and logos were verified on the connected Pixel wallet. |
| L1 transfer and sweep | Implemented for ADA; live acceptance pending | Public previews are asset-tagged and reject non-ADA before Cardano build/signing. Native transfer and channel funding remain unavailable. A funded ADA device transfer/process-kill/finality run also remains. |
| Encrypted Drive recovery | Complete-collection implementation; multi-device acceptance pending | One encrypted deterministic channel collection now includes payment state, paid hashes, and unresolved migration evidence. Restore/takeover validates and atomically installs collection plus checkpoint; the complete device-A/device-B matrix remains. |
| Channel lifecycle | Keyed collection implemented; controlled multi-channel acceptance pending | Multiple entries use the full protocol keytag, operations/results target one entry, and a new ADA channel rejects only its exact duplicate keytag. Native funding and Add/Close/Elapse/End/Squash product controls remain unfinished. |
| Stablecoin and multi-channel support | P0 implementation complete; funded multi-channel acceptance pending | Runtime verifies the reviewed catalog/digest and packaged presentation metadata, uses exact asset-tagged amounts, persists multiple channels, and exposes native holdings/channel identity. The Pixel migrated its existing open ADA channel and safely cleaned failed zero-balance attempts; native funding/payment remains gated for P1/P2 and the controlled two-ADA-channel scenario remains. |
| Lightning payment | Existing ADA path migrated; multiple-payer acceptance pending | Quotes, authorizations, results, receipts, recovery, and routes bind an exact keytag and asset. More than one compatible ADA channel requires explicit selection; initialization squash is journaled. The controlled two-channel payment/interruption scenario remains. |
| Activity and history | Asset/source identity implemented; funded device activity acceptance pending | L1 transactions retain per-asset deltas with one ADA fee; L2 records retain their channel asset and keytag. Deterministic tests pass and terminal legacy history is preserved during guarded cleanup; funded new multi-channel activity remains to be exercised. |
| Wallet removal | Collection-wide gating implemented; full device acceptance pending | Removal checks every channel, pending channel/payment operation, asset/unknown inventory, unresolved migration, Drive state, and all relevant finality evidence. The funded close/sweep/finality/delete/restore scenario remains. |
| Android release | Partial | Financial actions are enabled in debug and release builds. Local `androidReleaseCheck` passes with a non-production OAuth placeholder. Production OAuth/signing material and the release-device MASVS matrix remain operator prerequisites. |
| iOS | Deferred | Shared contracts compile for `iosSimulatorArm64`; no Xcode project or iOS custody, Cardano, Drive, TLS, QR, lifecycle, or signing adapters exist. |

## Controlled Mainnet evidence

The current Android/Konduit integration has completed these device scenarios:

1. Created and restored encrypted Mainnet wallet state.
2. Connected and verified the Google Drive appData writer.
3. Built, signed, submitted, and reconciled a funded Mainnet channel opening.
4. Scanned a fresh BOLT11 invoice and obtained an invoice-aware LND route estimate.
5. Persisted the payment authorization before the Lightning side effect.
6. Completed the Lightning payment, persisted the returned preimage into the server receipt, reconciled the durable client record, reduced L2 spendable balance, and changed activity from pending to settled.
7. Re-entered QR payment from Home without retaining a prior payment error screen.

The payment investigation also established two compatibility requirements now implemented by the controlled Konduit service:

- `EstimateRouteFee` must use the actual BOLT11 invoice. Graph-only `QueryRoutes` can report a path that `SendPaymentV2` cannot use.
- LND variants may return `time_lock_delay` as either a relative delay or an absolute block height. Konduit normalizes both before adding the invoice final CLTV delta.

Konduit atomically stores a successful payment preimage with its reserved authorization. Replaying the same submitted authorization is idempotent and recovers an already-paid preimage instead of issuing a second payment.

## Required stablecoin and multi-channel expansion

Stablecoin payment is required scope. Konduit's generic contracts and asset catalog now support ADA, USDM, USDCx, USDA, and configured assets, but Ferret still assumes one ADA-denominated channel per wallet. Ferret must not claim stablecoin support until every requirement and acceptance scenario in this section is complete.

### Asset identity and amounts

- Add one shared channel-asset value containing the canonical alias, Cardano policy ID plus asset name (or ADA), decimal count, pricing definition, and verified catalog digest.
- Accept only assets from the controlled Konduit catalog whose digest matches adaptor discovery. Persist the complete asset identity with every channel, operation, quote, receipt, backup, and history record; never trust a ticker or alias alone.
- Ship the built-in definitions with Ferret. A later configured asset requires an authenticated, bounded catalog payload from the controlled deployment or a release-bundled definition; in either case its canonical digest must equal `asset_catalog_digest` before the asset or its channels become actionable.
- Represent all balances, transfers, channel capacities, quotes, and fees as integer base units tagged with their asset identity. Decimal formatting is presentation only; binary floating point must not authorize value.
- Built-in support must include ADA, USDM, and USDCx. USDA and later configured assets may use the same path only when the controlled deployment advertises and pins their definitions. An unknown or changed definition is unavailable, not guessed or silently treated as ADA.

### Embedded token presentation metadata

Status: complete. The release-bundled catalog, explicit maintainer updater, deterministic shared manifest/logos, strict bounded validation, focused tamper regressions, and offline Android check integration are implemented. Runtime catalog enforcement and all financial/multi-channel work below remain pending.

Token presentation data is a build-time input, never a mobile runtime dependency:

- Add an explicit maintainer task, `updateEmbeddedAssetMetadata`, that reads the approved native-asset identities from Ferret's pinned Konduit catalog and bulk-posts them to Mainnet Koios `POST https://api.koios.rest/api/v1/asset_info` as `_asset_list` policy-ID/asset-name pairs. ADA branding remains a local Ferret resource because ADA is not a native asset.
- Extract only bounded presentation fields needed by Ferret: CIP-14 fingerprint and Cardano Token Registry `name`, `ticker`, `description`, `url`, and base64 PNG `logo`. Do not embed volatile supply, mint-count, creation-time, or transaction metadata.
- Treat the Konduit definition as authoritative for policy ID, asset name, decimals, and pricing. Koios metadata is presentation-only. The updater must fail on missing/duplicate responses, identity mismatch, or a Koios registry decimal count that differs from the pinned Konduit definition.
- Decode each logo during generation, require a valid PNG, bound decoded bytes and dimensions, and write deterministic filenames. Sanitize and bound every text field before generating files.
- Commit a deterministic manifest and its PNGs under shared Compose resources so Android and future iOS code load identical packaged metadata without HTTP. Sort by canonical asset identity and store source/API version plus content hashes so reviews show intentional metadata changes.
- The refresh task may use the network only when a maintainer invokes it explicitly. Normal compilation, tests, CI verification, application startup, balance refresh, and payment flows must never contact Koios or any logo URL.
- Add an offline `verifyEmbeddedAssetMetadata` task and make `androidCheck`/`androidReleaseCheck` depend on it. It validates schema, hashes, PNG bounds, unique identities/tickers, catalog membership, and required embedded entries for USDM and USDCx without regenerating or making network calls.
- A release may continue using the last reviewed embedded metadata when Koios is unavailable. Adding or changing a supported asset requires rerunning the updater and reviewing the checked-in manifest and image diff.

### L1 asset transfer and L2 funding

- Show L1 balances per asset and allow a user to select an asset before transfer or channel funding.
- Extend input selection, previews, transaction building, signing inspection, and reconciliation to preserve every native asset exactly. The selected stablecoin quantity, minimum ADA carried by native-token outputs, fees paid in ADA, and all multi-asset change must be shown separately.
- Open or add funds to a channel with the selected catalog asset. A USDM/USDCx channel output must contain the exact token quantity and only the required ADA collateral; transaction validation must reject asset substitution, policy/name mismatch, token loss, unexpected tokens, or change sent outside the wallet.
- Keep arbitrary native-token transfer out of scope initially. The minimum safe implementation supports assets in the verified Konduit catalog rather than turning Ferret into a generic token wallet.

Implementation status (2026-09-15): catalog-selected transfer and channel Open are wired end to end with exact asset identity, dynamic native-output ADA, complete multi-asset change, schema migration, restart reconciliation, and Android confirmation fields. Host, Android, iOS compile-gate, and Konduit checks pass. Pixel acceptance covered the selectors, P2 boundary, and a confirmed controlled 2 ADA transfer. Funded USDM/USDCx transfer/Open, encrypted Drive recovery on a second installation, and offline visual metadata acceptance remain required; no native funds were available and the operator declined further real transfers. Add-funds remains intentionally unreachable with the other P2 controls.

### Multiple channels per wallet

- Replace the single wallet-wide `ChannelSnapshot` with a collection keyed by stable channel identity/keytag. Each entry owns its asset definition, state, spendable capacity, pending operation, receipt chain, and immutable history.
- Migrate the current encrypted single-channel journal and Drive recovery payload into the collection without losing the existing ADA channel. Backup, restore, stale-writer takeover, and reconciliation must cover the collection atomically under the existing wallet writer lease.
- Allow multiple open channels with different assets and multiple channels of the same asset. Display asset, spendable balance, lifecycle state, and a short stable channel identifier so same-asset channels remain distinguishable.
- Mutations and reconciliation address one explicit channel. The existing wallet lock may serialize operations initially, but a pending operation on one channel must not be mistaken for another channel's state.

### Payment channel selection

- After decoding the BOLT11 invoice, show compatible open channels before requesting the final quote. If more than one channel can pay, selection is mandatory; Ferret must not silently prefer ADA, a stablecoin, the largest balance, or the last-used channel.
- If exactly one compatible channel exists it may be preselected, but confirmation must still name its asset, short channel identifier, spendable balance, payment amount, routing fee, adaptor fee, and post-payment balance in that asset.
- Quote and submit with the selected channel keytag. Persist the channel identity and complete asset definition in the durable payment authorization before the side effect, then verify the receipt against both on reconciliation.
- Insufficient capacity is evaluated against the selected channel only. The first implementation does not split a payment across channels and does not retry against another channel after submission; either behavior would create a second authorization path.
- Home and History show balances and activity per asset and source channel. An ADA, USDM, or USDCx payment must update only the selected channel while leaving every other open channel unchanged.

### Stablecoin acceptance

Using controlled low-value Mainnet assets:

1. Detect and display L1 ADA, USDM, and USDCx balances with exact decimal/base-unit conversion.
2. Fund a new USDM channel from the L1 wallet, including minimum ADA and exact native-token change, then restore it from encrypted Drive state.
3. Keep ADA, USDM, and USDCx channels open simultaneously and select each explicitly for separate Lightning invoices.
4. Confirm each quote, fee, durable authorization, receipt, L2 balance, and history item retains the selected channel and asset identity.
5. Kill the app before and after payment submission and prove reconciliation cannot charge a different channel, repeat the payment, or change an unrelated channel.
6. Reject catalog-digest changes, alias/identity substitution, decimal mismatch, insufficient selected-channel capacity, unknown assets, and malformed multi-asset transaction bodies.
7. Close or sweep each asset channel and prove wallet removal remains blocked until all channels, native assets, pending operations, and finality requirements are resolved.
8. Build, launch, browse balances/channels, and complete a payment with Koios unreachable; verify packaged USDM/USDCx names, tickers, and logos render with no Koios or remote-image request.

## Security and recovery invariants

These remain release requirements, not optional feature gates:

- `SecureVault` is the only persistence boundary for wallet profiles, seed entropy, and operation journals.
- Mnemonics and seed entropy are never logged, copied, or exposed outside protected flows.
- Sensitive Android routes retain `FLAG_SECURE` and clear it when leaving composition.
- Channel and payment mutations require a validated foreground session, selected Mainnet wallet, current encrypted Drive checkpoint, and matching signed writer lease.
- Every financial mutation writes durable local and Drive state before its external side effect. Backup must not move after payment or transaction submission.
- Reconciliation reuses the original operation identity and exact signed payload; it never invents a replacement operation.
- Payment receipt signatures, cheque identity, amount, index, lock/preimage, and channel keytag are verified before settlement.
- Transaction inspection rejects unknown inputs, value imbalance, unexpected scripts/datums/redeemers, invalid witnesses, stale protocol parameters, and changed transaction bodies.
- Offline or mismatched deployment state exposes no cached financial actions.
- Koios and token logo URLs are forbidden at runtime. Only reviewed, bounded, hash-verified metadata and images packaged in the application may be rendered.

## Remaining Android work

### P0 — Asset and multi-channel foundation (implementation and single-channel migration complete)

The clean model/storage cutover is implemented and covered by deterministic tests. The connected Pixel verified packaged asset presentation, L1/L2 balance separation, keyed channel display, migration of its existing open ADA channel, and guarded cleanup of failed zero-balance attempts while preserving the open channel. Do not claim full P0 acceptance until the controlled two-ADA-channel payer/interruption scenario completes:

- introduce verified asset identity and integer asset amounts;
- migrate one `ChannelSnapshot` into a keyed channel collection;
- bind operations, writer backups, quotes, receipts, balances, and history to one channel and asset;
- expose ADA, USDM, and USDCx balances and channel identities without adding a second wallet or navigation store;
- add the explicit Koios refresh task and offline embedded-metadata verifier before asset presentation UI ships.

Migration restores existing encrypted ADA data only when durable keytag evidence is unambiguous. Guarded cleanup removes only terminal failed, zero-balance attempts, preserves terminal records that bind unambiguously to the recovered open channel, updates the verified encrypted Drive backup before local state, and refuses pending, funded, successful-unbound, or uncertain records.

### P1 — L1 transfer acceptance and asset-channel funding

First run the outstanding low-value Mainnet ADA transfer and verify:

- exact preview amount, fee, change, recipient, and transaction ID;
- one stable operation ID through submission;
- process kill after upstream acceptance without duplicate submission;
- confirmed, rollback, reconfirmed, and depth-2160 settlement transitions;
- refreshed connector balance and immutable activity.

Then extend the same transaction boundary to catalog assets and complete the USDM funding scenario. Stablecoin funding must prove token conservation, separate ADA fees/minimum output value, safe multi-asset change, submission reconciliation, and Drive recovery before USDM or USDCx payment is enabled.

### P2 — Multi-channel payment and lifecycle controls

Make channel selection part of the payment flow and prove ADA, USDM, and USDCx payments debit only the chosen channel. Then wire Add funds, Close, Elapse, End, and Squash through the keyed `ChannelRepository`, transaction authorizer, Drive write-ahead, connector operation identity, and adaptor reconciliation. Do not add another coordinator or state store.

For each asset and control, run controlled Mainnet evaluation plus interruption checks before and after the external side effect. Reuse the existing Channel screen for the channel list and lifecycle controls.

### P3 — Drive takeover matrix

With two Android installations and the production OAuth configuration, verify:

- initial backup creation and latest-only read-back;
- mnemonic restore of all channel assets, identities, operations, and receipts from the latest encrypted checkpoint;
- stale-writer detection and explicit takeover;
- device A rejection after device B takes over;
- tamper, missing-object, broken-chain, same-generation divergence, and asset-catalog mismatch rejection;
- successful restoration of terminal channel and payment records.

### P4 — Removal and release acceptance

Exercise closure of every asset channel, same-network ADA/native-asset sweep, finality, verified Drive deletion, local vault deletion, and mnemonic restoration. Then run the signed release with production OAuth credentials through the sensitive-screen, log, file, clipboard, backup, and network checks.

### P5 — iOS implementation

Execute `IOS_FOLLOW_UP.md` only after the Android contracts above are stable. Reuse shared asset/channel state, navigation, repositories, and Compose UI; do not create a duplicate SwiftUI product tree.

## Verification commands

Primary Ferret checks:

```sh
./gradlew :shared:allTests
./gradlew androidCheck
./gradlew :shared:compileKotlinIosSimulatorArm64
FERRET_GOOGLE_SERVER_CLIENT_ID='<client-id>' ./gradlew androidReleaseCheck
```

Install the production-enabled debug behavior without feature properties:

```sh
./gradlew :androidApp:installDebug
```

Relevant controlled Konduit checks after payment or channel protocol changes:

```sh
cd ../konduit
cargo test -p bln-client -p konduit-server
cargo build --release -p konduit-server
```

Device evidence must use the controlled Mainnet services. Passing builds alone do not replace the required ADA/USDM/USDCx multi-channel scenarios, funded transfer, multi-device Drive, channel-control, removal, or signed-release scenarios.
