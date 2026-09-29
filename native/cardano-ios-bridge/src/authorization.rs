use cardano_serialization_lib as csl;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use crate::Failure;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    intent: Intent,
    ledger: Ledger,
    assets: Vec<Asset>,
    #[serde(rename = "operationId")]
    operation_id: String,
    #[serde(rename = "feeBound")]
    fee_bound: i64,
}
#[derive(Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
struct Asset {
    alias: String,
    #[serde(rename = "policyId")]
    policy_id: Option<String>,
    #[serde(rename = "assetName")]
    asset_name: Option<String>,
    decimals: u8,
    pricing: String,
    #[serde(rename = "catalogDigest")]
    catalog_digest: String,
}
fn lowercase_hex(value: &str, minimum: usize, maximum: usize) -> bool {
    value.len() >= minimum && value.len() <= maximum && value.len() % 2 == 0 &&
        value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn valid_asset(asset: &Asset) -> bool {
    let alias = asset.alias.as_bytes();
    if alias.is_empty() || alias.len() > 32 || !(b'a'..=b'z').contains(&alias[0]) ||
        !alias.iter().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'_') ||
        !lowercase_hex(&asset.catalog_digest, 64, 64) { return false; }
    match (&asset.policy_id, &asset.asset_name) {
        (None, None) => asset.alias == "ada" && asset.decimals == 6 && asset.pricing == "ADA",
        (Some(policy), Some(name)) => asset.alias != "ada" && asset.decimals <= 19 &&
            asset.pricing == "USD_PEG" && lowercase_hex(policy, 56, 56) && lowercase_hex(name, 0, 64),
        _ => false,
    }
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Amount { asset: Asset, #[serde(rename = "baseUnits")] base_units: i64 }
#[derive(Deserialize, Serialize)]
#[serde(tag = "type", deny_unknown_fields)]
enum Intent {
    #[serde(rename = "io.riverark.ferret.core.cardano.CardanoIntent.Transfer")]
    Transfer {
        #[serde(rename = "sourceAddress")] source: String,
        #[serde(rename = "destinationAddress")] destination: String,
        amount: Amount,
        #[serde(rename = "operationId")] operation_id: String,
        #[serde(rename = "validFrom")] from: i64,
        #[serde(rename = "validUntil")] until: i64,
    },
    #[serde(rename = "io.riverark.ferret.core.cardano.CardanoIntent.SweepWallet")]
    SweepWallet {
        #[serde(rename = "sourceAddress")] source: String,
        #[serde(rename = "destinationAddress")] destination: String,
        amount: i64,
        #[serde(rename = "operationId")] operation_id: String,
        #[serde(rename = "validFrom")] from: i64,
        #[serde(rename = "validUntil")] until: i64,
    },
    #[serde(rename = "io.riverark.ferret.core.cardano.CardanoIntent.OpenChannel")]
    OpenChannel {
        #[serde(rename = "sourceAddress")] source: String,
        #[serde(rename = "validatorAddress")] validator: String,
        #[serde(rename = "referenceInput")] reference: Utxo,
        datum: serde_json::Value,
        amount: Amount,
        #[serde(rename = "operationId")] operation_id: String,
        #[serde(rename = "validFrom")] from: i64,
        #[serde(rename = "validUntil")] until: i64,
    },
    #[serde(rename = "io.riverark.ferret.core.cardano.CardanoIntent.AddChannelFunds")]
    AddChannelFunds {
        #[serde(rename = "sourceAddress")] source: String,
        #[serde(rename = "channelInput")] channel: Utxo,
        #[serde(rename = "referenceInput")] reference: Utxo,
        #[serde(rename = "currentDatum")] current: serde_json::Value,
        #[serde(rename = "resultingDatum")] resulting: serde_json::Value,
        amount: Amount,
        #[serde(rename = "operationId")] operation_id: String,
        #[serde(rename = "validFrom")] from: i64,
        #[serde(rename = "validUntil")] until: i64,
    },
    #[serde(rename = "io.riverark.ferret.core.cardano.CardanoIntent.CloseChannel")]
    CloseChannel {
        #[serde(rename = "sourceAddress")] source: String,
        #[serde(rename = "channelInput")] channel: Utxo,
        #[serde(rename = "referenceInput")] reference: Utxo,
        #[serde(rename = "currentDatum")] current: serde_json::Value,
        step: String,
        #[serde(rename = "resultingDatum")] resulting: Option<serde_json::Value>,
        amount: i64,
        #[serde(rename = "operationId")] operation_id: String,
        #[serde(rename = "validFrom")] from: i64,
        #[serde(rename = "validUntil")] until: i64,
    },
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Ledger {
    network: String,
    utxos: Vec<Utxo>,
    #[serde(rename = "protocolParametersJson")]
    parameters: String,
    #[serde(rename = "currentSlot")]
    current_slot: i64,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Utxo {
    #[serde(rename = "transactionId")]
    transaction_id: String,
    index: i32,
    address: String,
    lovelace: i64,
    #[serde(default)]
    assets: BTreeMap<String, i64>,
    #[serde(rename = "datumHex")]
    datum_hex: Option<String>,
    #[serde(rename = "scriptRefHex")]
    script_ref_hex: Option<String>,
    #[serde(rename = "datumHashHex")]
    datum_hash_hex: Option<String>,
    #[serde(rename = "scriptRefVersion")]
    script_ref_version: Option<i32>,
    #[serde(rename = "scriptRefHashHex")]
    script_ref_hash_hex: Option<String>,
}
#[derive(Serialize, PartialEq, Eq, PartialOrd, Ord, Clone)]
struct Reference { #[serde(rename = "transactionId")] transaction_id: String, index: i32 }
#[derive(Serialize, PartialEq, Eq)]
struct Output {
    address: String,
    lovelace: i64,
    assets: BTreeMap<String, i64>,
    datum: serde_json::Value,
    #[serde(rename = "scriptReference")]
    script_reference: Option<serde_json::Value>,
}
#[derive(Serialize)]
struct Witness {
    #[serde(rename = "verificationKeyHex")]
    verification_key_hex: String,
    #[serde(rename = "keyHashHex")]
    key_hash_hex: String,
    #[serde(rename = "signatureHex")]
    signature_hex: String,
    #[serde(rename = "signatureValid")]
    signature_valid: bool,
}
#[derive(Serialize)]
struct Redeemer {
    purpose: String,
    index: i64,
    #[serde(rename = "dataCborHex")]
    data_cbor_hex: String,
    memory: i64,
    steps: i64,
}
#[derive(Serialize)]
struct Summary {
    network: String,
    outputs: Vec<Output>,
    fee: i64,
    #[serde(rename = "requiredSigners")]
    required_signers: BTreeSet<String>,
    #[serde(rename = "validityStart")]
    validity_start: Option<i64>,
    #[serde(rename = "validityEnd")]
    validity_end: Option<i64>,
    inputs: Vec<Reference>,
    #[serde(rename = "referenceInputs")]
    reference_inputs: Vec<Reference>,
    #[serde(rename = "collateralInputs")]
    collateral_inputs: Vec<Reference>,
    #[serde(rename = "collateralReturn")]
    collateral_return: Option<Output>,
    #[serde(rename = "totalCollateral")]
    total_collateral: Option<i64>,
    #[serde(rename = "keyWitnesses")]
    key_witnesses: Vec<Witness>,
    redeemers: Vec<Redeemer>,
    #[serde(rename = "scriptDataHashHex")]
    script_data_hash_hex: Option<String>,
    #[serde(rename = "prohibitedBodyFields")]
    prohibited_body_fields: BTreeSet<String>,
    #[serde(rename = "containsNonKeyWitnesses")]
    contains_non_key_witnesses: bool,
}
fn checked(value: u64) -> Result<i64, Failure> { i64::try_from(value).map_err(|_| Failure::Invalid) }
fn references(inputs: Option<csl::TransactionInputs>) -> Result<Vec<Reference>, Failure> {
    let mut result = Vec::new();
    if let Some(inputs) = inputs {
        for index in 0..inputs.len() {
            let input = inputs.get(index);
            result.push(Reference { transaction_id: input.transaction_id().to_hex(), index: i32::try_from(input.index()).map_err(|_| Failure::Invalid)? });
        }
    }
    Ok(result)
}
fn output(value: csl::TransactionOutput) -> Result<Output, Failure> {
    let amount = value.amount();
    let mut assets = BTreeMap::new();
    if let Some(multi) = amount.multiasset() {
        let policies = multi.keys();
        for i in 0..policies.len() {
            let policy = policies.get(i);
            let names = multi.get(&policy).ok_or(Failure::Invalid)?;
            let keys = names.keys();
            for j in 0..keys.len() {
                let name = keys.get(j);
                let quantity = checked(names.get(&name).ok_or(Failure::Invalid)?.into())?;
                if quantity <= 0 || name.name().len() > 32 || assets.insert(format!("{}{}", policy.to_hex(), hex::encode(name.name())), quantity).is_some() {
                    return Err(Failure::Invalid);
                }
            }
        }
    }
    let datum = if let Some(data) = value.plutus_data() {
        serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Inline","cborHex":hex::encode(data.to_bytes())})
    } else if let Some(hash) = value.data_hash() {
        serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Hash","hex":hash.to_hex()})
    } else {
        serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Absent"})
    };
    let script_reference = value.script_ref().map(|script| {
        let plutus = script.plutus_script();
        serde_json::json!({
            "language": plutus.as_ref().map_or(0, |s| match s.language_version().kind() {
                csl::LanguageKind::PlutusV1 => 1,
                csl::LanguageKind::PlutusV2 => 2,
                csl::LanguageKind::PlutusV3 => 3,
            }),
            "cborHex": hex::encode(script.to_bytes()),
            "hashHex": plutus.map(|s| s.hash().to_hex()).unwrap_or_else(|| script.native_script().map(|s| s.hash().to_hex()).unwrap_or_default()),
        })
    });
    Ok(Output {
        address: value.address().to_bech32(None).map_err(|_| Failure::Invalid)?,
        lovelace: checked(amount.coin().into())?,
        assets, datum, script_reference,
    })
}
fn summary(cbor: &[u8]) -> Result<Summary, Failure> {
    let tx = crate::transaction(cbor)?;
    let body = tx.body();
    let witnesses = tx.witness_set();
    let outputs = body.outputs();
    if outputs.len() == 0 { return Err(Failure::Invalid); }
    let mut rendered = Vec::new();
    let mut network = None;
    for i in 0..outputs.len() {
        let item = outputs.get(i);
        let id = item.address().network_id().map_err(|_| Failure::Invalid)?;
        if id > 1 || network.is_some_and(|existing| existing != id) { return Err(Failure::Invalid); }
        network = Some(id);
        rendered.push(output(item)?);
    }
    let network_id = network.ok_or(Failure::Invalid)?;
    if body.network_id().is_some_and(|id| id.kind() != if network_id == 1 { csl::NetworkIdKind::Mainnet } else { csl::NetworkIdKind::Testnet }) { return Err(Failure::Invalid); }
    let mut prohibited = BTreeSet::new();
    for (present, label) in [
        (body.mint().is_some(), "MINT"), (body.certs().is_some(), "CERTIFICATES"),
        (body.withdrawals().is_some(), "WITHDRAWALS"), (body.update().is_some(), "UPDATE"),
        (body.auxiliary_data_hash().is_some() || tx.auxiliary_data().is_some(), "AUXILIARY_DATA"),
        (body.voting_procedures().is_some() || body.voting_proposals().is_some(), "GOVERNANCE"),
        (body.current_treasury_value().is_some(), "TREASURY"), (body.donation().is_some(), "DONATION"),
    ] { if present { prohibited.insert(label.to_owned()); } }
    let hash = csl::FixedTransaction::from_bytes(cbor.to_vec()).map_err(|_| Failure::Invalid)?.transaction_hash();
    let mut key_witnesses = Vec::new();
    if let Some(keys) = witnesses.vkeys() {
        for i in 0..keys.len() {
            let witness = keys.get(i);
            let key = witness.vkey().public_key();
            let signature = witness.signature();
            key_witnesses.push(Witness {
                verification_key_hex: hex::encode(key.as_bytes()),
                key_hash_hex: key.hash().to_hex(),
                signature_hex: hex::encode(signature.to_bytes()),
                signature_valid: key.verify(&hash.to_bytes(), &signature),
            });
        }
    }
    let mut redeemers = Vec::new();
    if let Some(items) = witnesses.redeemers() {
        for i in 0..items.len() {
            let item = items.get(i);
            let units = item.ex_units();
            redeemers.push(Redeemer {
                purpose: match item.tag().kind() {
                    csl::RedeemerTagKind::Spend => "SPEND",
                    csl::RedeemerTagKind::Mint => "MINT",
                    csl::RedeemerTagKind::Cert => "CERT",
                    csl::RedeemerTagKind::Reward => "REWARD",
                    csl::RedeemerTagKind::Vote => "VOTE",
                    csl::RedeemerTagKind::VotingProposal => "VOTING_PROPOSAL",
                }.to_owned(),
                index: checked(item.index().into())?,
                data_cbor_hex: hex::encode(item.data().to_bytes()),
                memory: checked(units.mem().into())?,
                steps: checked(units.steps().into())?,
            });
        }
    }
    if !prohibited.is_empty() || witnesses.native_scripts().is_some() || witnesses.bootstraps().is_some() ||
        witnesses.plutus_scripts().is_some() || witnesses.plutus_data().is_some() ||
        witnesses.vkeys().is_some_and(|keys| keys.len() == 0) ||
        witnesses.redeemers().is_some_and(|items| items.len() == 0) ||
        key_witnesses.len() > 1 || key_witnesses.iter().any(|key| !key.signature_valid) ||
        redeemers.len() > 1 || redeemers.iter().any(|r| r.purpose != "SPEND") ||
        (redeemers.is_empty() != body.script_data_hash().is_none()) {
        return Err(Failure::Invalid);
    }
    Ok(Summary {
        network: (if network_id == 1 { "MAINNET" } else { "PREPROD" }).to_owned(),
        outputs: rendered,
        fee: checked(body.fee().into())?,
        required_signers: body.required_signers().map(|keys| (0..keys.len()).map(|i| keys.get(i).to_hex()).collect()).unwrap_or_default(),
        validity_start: body.validity_start_interval_bignum().map(|n| checked(n.into())).transpose()?,
        validity_end: body.ttl_bignum().map(|n| checked(n.into())).transpose()?,
        inputs: references(Some(body.inputs()))?,
        reference_inputs: references(body.reference_inputs())?,
        collateral_inputs: references(body.collateral())?,
        collateral_return: body.collateral_return().map(output).transpose()?,
        total_collateral: body.total_collateral().map(|n| checked(n.into())).transpose()?,
        key_witnesses, redeemers,
        script_data_hash_hex: body.script_data_hash().map(|hash| hash.to_hex()),
        prohibited_body_fields: prohibited,
        contains_non_key_witnesses: witnesses.native_scripts().is_some() || witnesses.bootstraps().is_some() || witnesses.plutus_scripts().is_some() || witnesses.plutus_data().is_some() || witnesses.redeemers().is_some(),
    })
}
pub(crate) fn inspect(cbor: &[u8]) -> Result<Vec<u8>, Failure> {
    serde_json::to_vec(&summary(cbor)?).map_err(|_| Failure::Internal)
}
fn positive_decimal(params: &serde_json::Value, name: &str) -> Result<u64, Failure> {
    let text = params.get(name).and_then(serde_json::Value::as_str).ok_or(Failure::Invalid)?;
    if text.is_empty() || text.starts_with('0') || !text.bytes().all(|b| b.is_ascii_digit()) {
        return Err(Failure::Invalid);
    }
    text.parse::<i64>().ok().filter(|n| *n > 0).map(|n| n as u64).ok_or(Failure::Invalid)
}
fn decimal(value: &serde_json::Value) -> Result<csl::UnitInterval, Failure> {
    let text = value.as_str().ok_or(Failure::Invalid)?;
    let (whole, fraction) = text.split_once('.').unwrap_or((text, ""));
    if whole.is_empty() || !whole.bytes().all(|c| c.is_ascii_digit()) ||
        !fraction.bytes().all(|c| c.is_ascii_digit()) || fraction.len() > 16 { return Err(Failure::Invalid); }
    let denominator = 10_u64.checked_pow(fraction.len() as u32).ok_or(Failure::Invalid)?;
    let numerator = whole.parse::<u64>().map_err(|_| Failure::Invalid)?
        .checked_mul(denominator)
        .and_then(|value| value.checked_add(if fraction.is_empty() { 0 } else { fraction.parse::<u64>().ok()? }))
        .ok_or(Failure::Invalid)?;
    Ok(csl::UnitInterval::new(&csl::BigNum::from(numerator), &csl::BigNum::from(denominator)))
}
fn ref_of(utxo: &Utxo) -> Reference {
    Reference { transaction_id: utxo.transaction_id.clone(), index: utxo.index }
}
fn channel_datum(value: &serde_json::Value) -> Result<serde_json::Value, Failure> {
    Ok(serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Inline",
        "cborHex":hex::encode(crate::channel::datum(value)?.to_bytes())}))
}
fn authorize_channel(tx: &csl::Transaction, report: &Summary, request: &Request, signed: bool) -> Result<(), Failure> {
    let intent = serde_json::to_value(&request.intent).map_err(|_| Failure::Internal)?;
    let ledger = serde_json::to_value(&request.ledger).map_err(|_| Failure::Internal)?;
    let assets = serde_json::to_value(&request.assets).map_err(|_| Failure::Internal)?;
    crate::channel::validate_intent(&intent, &ledger, &assets)?;
    let (source, reference, channel, from, until, operation_id, expected, selected) = match &request.intent {
        Intent::OpenChannel { source, reference, validator, datum, amount, from, until, operation_id } => {
            let selected = amount.asset.policy_id.as_ref().zip(amount.asset.asset_name.as_ref())
                .map(|(policy, name)| format!("{policy}{name}"));
            let quantity = selected.as_ref().map(|unit| BTreeMap::from([(unit.clone(), amount.base_units)])).unwrap_or_default();
            let required = if selected.is_some() { None } else { Some(amount.base_units) };
            (source, reference, None, *from, *until, operation_id,
                Some((validator.as_str(), channel_datum(datum)?, quantity, required)), selected)
        },
        Intent::AddChannelFunds { source, reference, channel, resulting, amount, from, until, operation_id, .. } => {
            let selected = amount.asset.policy_id.as_ref().zip(amount.asset.asset_name.as_ref())
                .map(|(policy, name)| format!("{policy}{name}"));
            let mut quantity = channel.assets.clone();
            let required = if let Some(unit) = &selected {
                let value = quantity.get_mut(unit).ok_or(Failure::Invalid)?;
                *value = value.checked_add(amount.base_units).ok_or(Failure::Invalid)?;
                None
            } else {
                Some(channel.lovelace.checked_add(amount.base_units).ok_or(Failure::Invalid)?)
            };
            (source, reference, Some(channel), *from, *until, operation_id,
                Some((channel.address.as_str(), channel_datum(resulting)?, quantity, required)), selected)
        },
        Intent::CloseChannel { source, reference, channel, resulting, from, until, operation_id, .. } =>
            (source, reference, Some(channel), *from, *until, operation_id,
                resulting.as_ref().map(|datum| (channel.address.as_str(), channel_datum(datum), channel.assets.clone(), Some(channel.lovelace)))
                    .map(|(address, datum, assets, lovelace)| datum.map(|datum| (address, datum, assets, lovelace))).transpose()?, None),
        _ => return Err(Failure::Invalid),
    };
    let network_id = match request.ledger.network.as_str() { "MAINNET" => 1, "PREPROD" => 0, _ => return Err(Failure::Invalid) };
    let source_address = csl::Address::from_bech32(source).map_err(|_| Failure::Invalid)?;
    let source_hash = source_address.payment_cred().and_then(|c| c.to_keyhash()).ok_or(Failure::Invalid)?.to_hex();
    if source_address.network_id().map_err(|_| Failure::Invalid)? != network_id { return Err(Failure::Invalid); }
    if operation_id != &request.operation_id || from < 0 || until <= from || request.ledger.current_slot >= until ||
        report.network != request.ledger.network || report.fee < 0 || report.fee > request.fee_bound ||
        report.validity_start != Some(from) || report.validity_end != Some(until) ||
        !report.prohibited_body_fields.is_empty() ||
        report.reference_inputs != channel.map(|_| vec![ref_of(reference)]).unwrap_or_default() || report.inputs.contains(&ref_of(reference)) ||
        report.collateral_inputs.contains(&ref_of(reference)) || report.inputs.is_empty() ||
        report.inputs.len() != report.inputs.iter().collect::<BTreeSet<_>>().len() {
        return Err(Failure::Invalid);
    }
    let encoded: Vec<_> = (0..tx.body().inputs().len()).map(|i| tx.body().inputs().get(i).to_bytes()).collect();
    if encoded.windows(2).any(|pair| (pair[0].len(), &pair[0]) >= (pair[1].len(), &pair[1])) {
        return Err(Failure::Invalid);
    }
    if signed {
        if report.key_witnesses.len() != 1 || !report.key_witnesses[0].signature_valid ||
            report.key_witnesses[0].key_hash_hex != source_hash { return Err(Failure::Invalid); }
    } else if !report.key_witnesses.is_empty() { return Err(Failure::Invalid); }
    let spending = channel.is_some();
    if report.contains_non_key_witnesses != spending ||
        tx.witness_set().native_scripts().is_some() || tx.witness_set().bootstraps().is_some() ||
        tx.witness_set().plutus_scripts().is_some() || tx.witness_set().plutus_data().is_some() { return Err(Failure::Invalid); }
    let raw_signers = tx.body().required_signers();
    if spending {
        if raw_signers.as_ref().is_none_or(|hashes| hashes.len() != 1 || hashes.get(0).to_hex() != source_hash) ||
            report.required_signers != BTreeSet::from([source_hash.clone()]) { return Err(Failure::Invalid); }
    } else if raw_signers.is_some() || !report.required_signers.is_empty() { return Err(Failure::Invalid); }
    let per_byte = crate::coins_per_byte(request.ledger.parameters.as_bytes())?;
    if let Some((address, datum, quantity, required)) = &expected {
        let candidates: Vec<_> = report.outputs.iter().enumerate().filter(|(_, out)| {
            out.address == *address && out.datum == *datum && out.assets == *quantity && out.script_reference.is_none() &&
                required.is_none_or(|value| out.lovelace == value)
        }).collect();
        if candidates.len() != 1 { return Err(Failure::Invalid); }
        let (index, output) = candidates[0];
        let minimum = (160_u64).checked_add(tx.body().outputs().get(index).to_bytes().len() as u64)
            .and_then(|size| size.checked_mul(per_byte)).ok_or(Failure::Invalid)?;
        if output.lovelace < 2_000_000 || selected.is_some() &&
            output.lovelace != match channel { Some(channel) => channel.lovelace.max(2_000_000).max(checked(minimum)?), None => 2_000_000_i64.max(checked(minimum)?) } {
            return Err(Failure::Invalid);
        }
        if report.outputs.len() > 2 || report.outputs.iter().enumerate().any(|(i, out)| i != index &&
            (out.address != *source || out.datum != serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Absent"}) || out.script_reference.is_some())) {
            return Err(Failure::Invalid);
        }
    } else if report.outputs.len() != 1 || report.outputs[0].address != *source ||
        !report.outputs[0].assets.is_empty() ||
        report.outputs[0].datum != serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Absent"}) ||
        report.outputs[0].script_reference.is_some() { return Err(Failure::Invalid); }
    if report.outputs.is_empty() || report.outputs.len() > 2 { return Err(Failure::Invalid); }
    let mut available = BTreeMap::new();
    for utxo in &request.ledger.utxos {
        if utxo.index < 0 || utxo.transaction_id.len() != 64 || !utxo.transaction_id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) ||
            utxo.lovelace < 0 || available.insert((utxo.transaction_id.clone(), utxo.index), utxo).is_some() { return Err(Failure::Invalid); }
    }
    let channel_ref = channel.map(ref_of);
    if channel_ref.as_ref().is_some_and(|id| report.inputs.iter().filter(|input| *input == id).count() != 1) { return Err(Failure::Invalid); }
    let mut ada = 0_i64;
    let mut tokens = BTreeMap::<String, i64>::new();
    for input in &report.inputs {
        let utxo = available.get(&(input.transaction_id.clone(), input.index)).ok_or(Failure::Invalid)?;
        if channel_ref.as_ref() == Some(input) {
            if utxo.address != channel.ok_or(Failure::Invalid)?.address || utxo.lovelace != channel.ok_or(Failure::Invalid)?.lovelace ||
                utxo.assets != channel.ok_or(Failure::Invalid)?.assets { return Err(Failure::Invalid); }
        } else if utxo.address != *source || utxo.datum_hex.is_some() || utxo.datum_hash_hex.is_some() ||
            utxo.script_ref_hash_hex.is_some() || (selected.is_none() && !utxo.assets.is_empty()) { return Err(Failure::Invalid); }
        ada = ada.checked_add(utxo.lovelace).ok_or(Failure::Invalid)?;
        for (unit, value) in &utxo.assets {
            if *value <= 0 || unit.len() < 56 || unit.len() > 120 || unit.len() % 2 != 0 ||
                !unit.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) { return Err(Failure::Invalid); }
            let entry = tokens.entry(unit.clone()).or_default();
            *entry = entry.checked_add(*value).ok_or(Failure::Invalid)?;
        }
    }
    let mut paid = report.fee;
    let mut output_tokens = BTreeMap::<String, i64>::new();
    for (i, out) in report.outputs.iter().enumerate() {
        paid = paid.checked_add(out.lovelace).ok_or(Failure::Invalid)?;
        for (unit, value) in &out.assets {
            let entry = output_tokens.entry(unit.clone()).or_default();
            *entry = entry.checked_add(*value).ok_or(Failure::Invalid)?;
        }
        let minimum = (160_u64).checked_add(tx.body().outputs().get(i).to_bytes().len() as u64)
            .and_then(|size| size.checked_mul(per_byte)).ok_or(Failure::Invalid)?;
        if out.lovelace < checked(minimum)? { return Err(Failure::Invalid); }
    }
    if ada != paid || tokens != output_tokens { return Err(Failure::Invalid); }
    let params: serde_json::Value = serde_json::from_str(&request.ledger.parameters).map_err(|_| Failure::Invalid)?;
    decimal(params.get("price_mem").ok_or(Failure::Invalid)?)?;
    decimal(params.get("price_step").ok_or(Failure::Invalid)?)?;
    decimal(params.get("min_fee_ref_script_cost_per_byte").ok_or(Failure::Invalid)?)?;
    positive_decimal(&params, "max_tx_ex_mem")?;
    positive_decimal(&params, "max_tx_ex_steps")?;
    let models = params.get("cost_models_raw").and_then(serde_json::Value::as_object).ok_or(Failure::Invalid)?;
    if models.values().any(|entry| entry.as_array().is_none_or(|v| v.is_empty() || v.len() > 1024 ||
        v.iter().any(|n| n.as_i64().is_none()))) { return Err(Failure::Invalid); }
    let raw = models.get("PlutusV3").and_then(serde_json::Value::as_array).filter(|v| !v.is_empty()).ok_or(Failure::Invalid)?;
    let positive = |name: &str| params.get(name).and_then(serde_json::Value::as_u64).filter(|n| *n > 0).ok_or(Failure::Invalid);
    let linear = csl::LinearFee::new(&csl::BigNum::from(positive("min_fee_a")?), &csl::BigNum::from(positive("min_fee_b")?));
    let priced = if signed { tx.clone() } else {
        let mut witnesses = tx.witness_set();
        let mut keys = csl::Vkeywitnesses::new();
        let public = csl::PublicKey::from_bytes(&[0; 32]).map_err(|_| Failure::Internal)?;
        let signature = csl::Ed25519Signature::from_bytes(vec![0; 64]).map_err(|_| Failure::Internal)?;
        keys.add(&csl::Vkeywitness::new(&csl::Vkey::new(&public), &signature));
        witnesses.set_vkeys(&keys);
        csl::Transaction::new(&tx.body(), &witnesses, None)
    };
    if priced.to_bytes().len() as u64 > positive("max_tx_size")? { return Err(Failure::Invalid); }
    let mut minimum_fee: u64 = csl::min_fee(&priced, &linear).map_err(|_| Failure::Invalid)?.into();
    if spending {
        let redeemers = tx.witness_set().redeemers().ok_or(Failure::Invalid)?;
        if redeemers.len() != 1 || report.redeemers.len() != 1 || report.redeemers[0].purpose != "SPEND" ||
            report.redeemers[0].index != report.inputs.iter().position(|input| Some(input) == channel_ref.as_ref()).ok_or(Failure::Invalid)? as i64 ||
            report.collateral_inputs.is_empty() || report.collateral_inputs.len() > positive("max_collateral_inputs")? as usize {
            return Err(Failure::Invalid);
        }
        let redeemer = redeemers.get(0);
        let name = match &request.intent { Intent::AddChannelFunds { .. } => "ADD",
            Intent::CloseChannel { step, .. } => step.as_str(), _ => return Err(Failure::Invalid) };
        let (outer, inner) = match name {
            "ADD" => (0, 0), "CLOSE" => (0, 2), "ELAPSE" => (1, 1), "END" => (1, 0),
            _ => return Err(Failure::Invalid),
        };
        let nested = csl::PlutusData::new_constr_plutus_data(
            &csl::ConstrPlutusData::new(&csl::BigNum::from(inner as u64), &csl::PlutusList::new()));
        let mut inner_fields = csl::PlutusList::new();
        inner_fields.add(&nested);
        let nested = csl::PlutusData::new_constr_plutus_data(
            &csl::ConstrPlutusData::new(&csl::BigNum::from(outer as u64), &inner_fields));
        let mut outer_fields = csl::PlutusList::new();
        outer_fields.add(&nested);
        let wrapper = csl::PlutusData::new_list(&outer_fields);
        let mut root_fields = csl::PlutusList::new();
        root_fields.add(&wrapper);
        let expected_redeemer = csl::PlutusData::new_constr_plutus_data(
            &csl::ConstrPlutusData::new(&csl::BigNum::from(1_u64), &root_fields));
        if redeemer.data().to_bytes() != expected_redeemer.to_bytes() { return Err(Failure::Invalid); }
        let mut cost_model = csl::CostModel::new();
        for (i, cost) in raw.iter().enumerate() {
            let n = cost.as_i64().ok_or(Failure::Invalid)?;
            let value = if n < 0 { csl::Int::new_negative(&csl::BigNum::from(n.unsigned_abs())) }
                else { csl::Int::new(&csl::BigNum::from(n as u64)) };
            cost_model.set(i, &value).map_err(|_| Failure::Invalid)?;
        }
        let mut cost_models = csl::Costmdls::new();
        cost_models.insert(&csl::Language::new_plutus_v3(), &cost_model);
        if report.script_data_hash_hex != Some(csl::hash_script_data(&redeemers, &cost_models, None).to_hex()) {
            return Err(Failure::Invalid);
        }
        let price_mem = decimal(params.get("price_mem").ok_or(Failure::Invalid)?)?;
        let price_steps = decimal(params.get("price_step").ok_or(Failure::Invalid)?)?;
        let script_fee: u64 = csl::min_script_fee(tx, &csl::ExUnitPrices::new(&price_mem, &price_steps))
            .map_err(|_| Failure::Invalid)?.into();
        minimum_fee = minimum_fee.checked_add(script_fee).ok_or(Failure::Invalid)?;
        let max_mem = positive_decimal(&params, "max_tx_ex_mem")?;
        let max_steps = positive_decimal(&params, "max_tx_ex_steps")?;
        if max_mem == 0 || max_steps == 0 || report.redeemers[0].memory < 0 || report.redeemers[0].steps < 0 ||
            report.redeemers[0].memory as u64 > max_mem || report.redeemers[0].steps as u64 > max_steps {
            return Err(Failure::Invalid);
        }
        let collateral_percent = positive("collateral_percent")?;
        let required_collateral = (report.fee as u128).checked_mul(collateral_percent as u128)
            .and_then(|n| n.checked_add(99)).ok_or(Failure::Invalid)? / 100;
        let required_collateral = i64::try_from(required_collateral).map_err(|_| Failure::Invalid)?;
        if report.total_collateral != Some(required_collateral) { return Err(Failure::Invalid); }
        let mut unique = BTreeSet::new();
        let mut total = 0_i64;
        for input in &report.collateral_inputs {
            if !unique.insert((&input.transaction_id, input.index)) || report.inputs.contains(input) ||
                Some(input) == channel_ref.as_ref() || input == &ref_of(reference) { return Err(Failure::Invalid); }
            let utxo = available.get(&(input.transaction_id.clone(), input.index)).ok_or(Failure::Invalid)?;
            if utxo.address != *source || !utxo.assets.is_empty() || utxo.datum_hex.is_some() ||
                utxo.datum_hash_hex.is_some() || utxo.script_ref_hash_hex.is_some() { return Err(Failure::Invalid); }
            total = total.checked_add(utxo.lovelace).ok_or(Failure::Invalid)?;
        }
        let remaining = total.checked_sub(required_collateral).filter(|n| *n >= 0).ok_or(Failure::Collateral)?;
        if remaining == 0 {
            if report.collateral_return.is_some() { return Err(Failure::Invalid); }
        } else if report.collateral_return.as_ref().is_none_or(|out| out.address != *source ||
            out.lovelace != remaining || !out.assets.is_empty() ||
            out.datum != serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Absent"}) ||
            out.script_reference.is_some()) { return Err(Failure::Invalid); }
        if let Some(output) = tx.body().collateral_return() {
            let minimum = (160_u64).checked_add(output.to_bytes().len() as u64)
                .and_then(|n| n.checked_mul(per_byte)).ok_or(Failure::Invalid)?;
            if remaining < checked(minimum)? { return Err(Failure::Invalid); }
        }
        let script = csl::PlutusScript::from_bytes_v3(
            hex::decode(reference.script_ref_hex.as_ref().ok_or(Failure::Invalid)?).map_err(|_| Failure::Invalid)?,
        ).map_err(|_| Failure::Invalid)?;
        let ref_bytes = csl::ScriptRef::new_plutus_script(&script).to_unwrapped_bytes().len();
        let price = decimal(params.get("min_fee_ref_script_cost_per_byte").ok_or(Failure::Invalid)?)?;
        let ref_fee: u64 = csl::min_ref_script_fee(ref_bytes, &price).map_err(|_| Failure::Invalid)?.into();
        minimum_fee = minimum_fee.checked_add(ref_fee).ok_or(Failure::Invalid)?;
    } else if report.script_data_hash_hex.is_some() || !report.redeemers.is_empty() ||
        !report.collateral_inputs.is_empty() || report.collateral_return.is_some() || report.total_collateral.is_some() {
        return Err(Failure::Invalid);
    }
    if checked(minimum_fee)? > report.fee { return Err(Failure::Invalid); }
    Ok(())
}
fn authorize_with(cbor: &[u8], request_json: &[u8], signed: bool) -> Result<(), Failure> {
    if request_json.len() > 1_048_576 { return Err(Failure::Invalid); }
    let request: Request = serde_json::from_slice(request_json).map_err(|_| Failure::Invalid)?;
    if request.assets.iter().any(|asset| !valid_asset(asset)) { return Err(Failure::Invalid); }
    let tx = crate::transaction(cbor)?;
    let report = summary(cbor)?;
    if matches!(&request.intent, Intent::OpenChannel { .. } | Intent::AddChannelFunds { .. } | Intent::CloseChannel { .. }) {
        return authorize_channel(&tx, &report, &request, signed);
    }
    let (source, destination, operation_id, from, until, amount, unit) = match &request.intent {
        Intent::Transfer { source, destination, operation_id, from, until, amount } => {
            if !valid_asset(&amount.asset) || request.assets.iter().filter(|asset| *asset == &amount.asset).count() != 1 {
                return Err(Failure::Invalid);
            }
            let unit = match (&amount.asset.policy_id, &amount.asset.asset_name) {
                (None, None) => None,
                (Some(policy), Some(name)) if policy.len() == 56 && name.len() <= 64 && name.len() % 2 == 0 && hex::decode(policy).is_ok() && hex::decode(name).is_ok() => Some(format!("{policy}{name}")),
                _ => return Err(Failure::Invalid),
            };
            (source, destination, operation_id, *from, *until, amount.base_units, unit)
        },
        Intent::SweepWallet { source, destination, operation_id, from, until, amount } =>
            (source, destination, operation_id, *from, *until, *amount, None),
        _ => return Err(Failure::Invalid),
    };
    let network_id = match request.ledger.network.as_str() { "MAINNET" => 1, "PREPROD" => 0, _ => return Err(Failure::Invalid) };
    let source_addr = csl::Address::from_bech32(source).map_err(|_| Failure::Invalid)?;
    let destination_addr = csl::Address::from_bech32(destination).map_err(|_| Failure::Invalid)?;
    if source_addr.network_id().map_err(|_| Failure::Invalid)? != network_id || destination_addr.network_id().map_err(|_| Failure::Invalid)? != network_id ||
        source_addr.to_bech32(None).map_err(|_| Failure::Invalid)? != *source ||
        destination_addr.to_bech32(None).map_err(|_| Failure::Invalid)? != *destination ||
        source == destination || amount <= 0 || from < request.ledger.current_slot || until <= from || request.ledger.current_slot >= until ||
        operation_id != &request.operation_id || report.network != request.ledger.network ||
        report.fee < 0 || report.fee > request.fee_bound || report.validity_start != Some(from) || report.validity_end != Some(until) ||
        !report.required_signers.is_empty() || !report.reference_inputs.is_empty() || !report.collateral_inputs.is_empty() ||
        report.collateral_return.is_some() || report.total_collateral.is_some() || !report.redeemers.is_empty() ||
        report.script_data_hash_hex.is_some() || !report.prohibited_body_fields.is_empty() || report.contains_non_key_witnesses ||
        report.inputs.is_empty() || report.outputs.is_empty() || report.outputs.len() > 2 ||
        report.outputs.iter().any(|o| o.datum != serde_json::json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Absent"}) || o.script_reference.is_some()) {
        return Err(Failure::Invalid);
    }
    if report.inputs.windows(2).any(|pair| (&pair[0].transaction_id, pair[0].index) >=
        (&pair[1].transaction_id, pair[1].index)) { return Err(Failure::Invalid); }
    let source_hash = source_addr.payment_cred().and_then(|cred| cred.to_keyhash()).ok_or(Failure::Invalid)?.to_hex();
    if signed {
        if report.key_witnesses.len() != 1 || !report.key_witnesses[0].signature_valid || report.key_witnesses[0].key_hash_hex != source_hash { return Err(Failure::Invalid); }
    } else if !report.key_witnesses.is_empty() { return Err(Failure::Invalid); }
    let designated = report.outputs.iter().filter(|o| {
        o.address == *destination && match &request.intent {
            Intent::SweepWallet { .. } => o.lovelace == amount && o.assets.is_empty(),
            Intent::Transfer { .. } => if let Some(unit) = &unit { o.lovelace > 0 && o.assets.len() == 1 && o.assets.get(unit) == Some(&amount) }
                else { o.lovelace == amount && o.assets.is_empty() },
            _ => false,
        }
    }).count();
    if designated != 1 || report.outputs.iter().any(|o| o.address != *source && o.address != *destination) ||
        (unit.is_none() && report.outputs.iter().any(|o| !o.assets.is_empty())) { return Err(Failure::Invalid); }
    let mut available = BTreeMap::new();
    for utxo in request.ledger.utxos {
        if utxo.index < 0 || utxo.transaction_id.len() != 64 || hex::decode(&utxo.transaction_id).is_err() ||
            utxo.lovelace < 0 || utxo.assets.iter().any(|(key, value)| key.len() < 56 || key.len() > 120 || key.len() % 2 != 0 || hex::decode(key).is_err() || *value <= 0) ||
            available.insert((utxo.transaction_id.clone(), utxo.index), utxo).is_some() { return Err(Failure::Invalid); }
    }
    let mut unique = BTreeSet::new();
    let mut ada = 0_i64;
    let mut assets = BTreeMap::<String, i64>::new();
    for input in &report.inputs {
        if !unique.insert((&input.transaction_id, input.index)) { return Err(Failure::Invalid); }
        let utxo = available.get(&(input.transaction_id.clone(), input.index)).ok_or(Failure::Invalid)?;
        if utxo.address != *source || utxo.datum_hex.is_some() || utxo.datum_hash_hex.is_some() || utxo.script_ref_hash_hex.is_some() ||
            (unit.is_none() && !utxo.assets.is_empty()) { return Err(Failure::Invalid); }
        ada = ada.checked_add(utxo.lovelace).ok_or(Failure::Invalid)?;
        for (key, quantity) in &utxo.assets {
            let entry = assets.entry(key.clone()).or_default();
            *entry = (*entry).checked_add(*quantity).ok_or(Failure::Invalid)?;
        }
    }
    let mut out_ada = report.fee;
    let mut out_assets = BTreeMap::<String, i64>::new();
    let per_byte = crate::coins_per_byte(request.ledger.parameters.as_bytes())?;
    for (index, item) in report.outputs.iter().enumerate() {
        out_ada = out_ada.checked_add(item.lovelace).ok_or(Failure::Invalid)?;
        for (key, quantity) in &item.assets {
            let entry = out_assets.entry(key.clone()).or_default();
            *entry = (*entry).checked_add(*quantity).ok_or(Failure::Invalid)?;
        }
        let raw = tx.body().outputs().get(index);
        let minimum = (160_u64).checked_add(raw.to_bytes().len() as u64).and_then(|n| n.checked_mul(per_byte)).ok_or(Failure::Invalid)?;
        if item.lovelace < checked(minimum)? || (unit.is_some() && item.address == *destination && item.lovelace != checked(minimum)?) { return Err(Failure::Invalid); }
    }
    if ada != out_ada || assets != out_assets { return Err(Failure::Invalid); }
    let params: serde_json::Value = serde_json::from_str(&request.ledger.parameters).map_err(|_| Failure::Invalid)?;
    let param = |key: &str| params.get(key).and_then(serde_json::Value::as_u64).filter(|n| *n > 0).ok_or(Failure::Invalid);
    let fee = csl::LinearFee::new(&csl::BigNum::from(param("min_fee_a")?), &csl::BigNum::from(param("min_fee_b")?));
    let priced = if signed { tx } else {
        let mut witnesses = csl::TransactionWitnessSet::new();
        let mut keys = csl::Vkeywitnesses::new();
        let key = csl::PublicKey::from_bytes(&[0_u8; 32]).map_err(|_| Failure::Internal)?;
        let signature = csl::Ed25519Signature::from_bytes(vec![0_u8; 64]).map_err(|_| Failure::Internal)?;
        keys.add(&csl::Vkeywitness::new(&csl::Vkey::new(&key), &signature));
        witnesses.set_vkeys(&keys);
        csl::Transaction::new(&tx.body(), &witnesses, None)
    };
    if priced.to_bytes().len() as u64 > param("max_tx_size")? || checked(csl::min_fee(&priced, &fee).map_err(|_| Failure::Invalid)?.into())? > report.fee {
        return Err(Failure::Invalid);
    }
    Ok(())
}
pub(crate) fn authorize(cbor: &[u8], request_json: &[u8]) -> Result<Vec<u8>, Failure> {
    authorize_with(cbor, request_json, false)?;
    Ok(Vec::new())
}
pub(crate) fn sign(cbor: &[u8], entropy: &[u8], request_json: &[u8]) -> Result<Vec<u8>, Failure> {
    authorize_with(cbor, request_json, false)?;
    let public = crate::payment_public(entropy)?;
    let tx = crate::transaction(cbor)?;
    let original = csl::FixedTransaction::from_bytes(cbor.to_vec()).map_err(|_| Failure::Invalid)?;
    let hash = original.transaction_hash();
    let mut witnesses = tx.witness_set();
    let mut keys = csl::Vkeywitnesses::new();
    let signature = csl::Ed25519Signature::from_bytes(crate::sign_payment(entropy, &hash.to_bytes())?).map_err(|_| Failure::Internal)?;
    keys.add(&csl::Vkeywitness::new(&csl::Vkey::new(&public), &signature));
    witnesses.set_vkeys(&keys);
    let signed = csl::Transaction::new(&tx.body(), &witnesses, None).to_bytes();
    let assembled = csl::FixedTransaction::from_bytes(signed.clone()).map_err(|_| Failure::Invalid)?;
    if assembled.transaction_hash() != hash || assembled.raw_body() != original.raw_body() ||
        assembled.witness_set().redeemers().map(|r| r.to_bytes()) != original.witness_set().redeemers().map(|r| r.to_bytes()) ||
        assembled.witness_set().native_scripts() != original.witness_set().native_scripts() ||
        assembled.witness_set().bootstraps() != original.witness_set().bootstraps() ||
        assembled.witness_set().plutus_scripts() != original.witness_set().plutus_scripts() ||
        assembled.witness_set().plutus_data() != original.witness_set().plutus_data() {
        return Err(Failure::Invalid);
    }
    authorize_with(&signed, request_json, true)?;
    Ok(signed)
}
