use cardano_serialization_lib as csl;
use serde::Deserialize;
use std::collections::HashSet;

use crate::Failure;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Request {
    pub intent: Intent,
    pub ledger: Ledger,
    pub assets: Vec<Asset>,
}
#[derive(Clone, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub(super) struct Asset {
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
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Amount { asset: Asset, #[serde(rename = "baseUnits")] base_units: i64 }
#[derive(Deserialize)]
#[serde(tag = "type", deny_unknown_fields)]
pub(super) enum Intent {
    #[serde(rename = "io.riverark.ferret.core.cardano.CardanoIntent.Transfer")]
    Transfer {
        #[serde(rename = "sourceAddress")] source_address: String,
        #[serde(rename = "destinationAddress")] destination_address: String,
        amount: Amount,
        #[serde(rename = "operationId")] operation_id: String,
        #[serde(rename = "validFrom")] valid_from: i64,
        #[serde(rename = "validUntil")] valid_until: i64,
    },
    #[serde(rename = "io.riverark.ferret.core.cardano.CardanoIntent.SweepWallet")]
    SweepWallet {
        #[serde(rename = "sourceAddress")] source_address: String,
        #[serde(rename = "destinationAddress")] destination_address: String,
        amount: i64,
        #[serde(rename = "operationId")] operation_id: String,
        #[serde(rename = "validFrom")] valid_from: i64,
        #[serde(rename = "validUntil")] valid_until: i64,
    },
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Ledger {
    network: String,
    utxos: Vec<Utxo>,
    #[serde(rename = "protocolParametersJson")]
    parameters: String,
    #[serde(rename = "currentSlot")]
    current_slot: i64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Utxo {
    #[serde(rename = "transactionId")]
    transaction_id: String,
    index: i32,
    address: String,
    lovelace: i64,
    #[serde(default)]
    assets: std::collections::BTreeMap<String, i64>,
    #[serde(rename = "datumHex")]
    datum_hex: Option<String>,
    #[serde(rename = "datumHashHex")]
    datum_hash_hex: Option<String>,
    #[serde(rename = "scriptRefHex")]
    script_ref_hex: Option<String>,
    #[serde(rename = "scriptRefHashHex")]
    script_ref_hash_hex: Option<String>,
    #[serde(rename = "scriptRefVersion")]
    script_ref_version: Option<i32>,
}

fn valid_hex(s: &str, length: usize) -> bool { s.len() == length && s.bytes().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()) }
fn address(value: &str, network: u8) -> Result<csl::Address, Failure> {
    let addr = csl::Address::from_bech32(value).map_err(|_| Failure::Invalid)?;
    if addr.network_id().map_err(|_| Failure::Invalid)? != network ||
        (network == 1 && !value.starts_with("addr1")) ||
        (network == 0 && !value.starts_with("addr_test1")) { return Err(Failure::Invalid); }
    Ok(addr)
}
fn parameters(ledger: &Ledger) -> Result<(csl::LinearFee, u64, usize), Failure> {
    let value: serde_json::Value = serde_json::from_str(&ledger.parameters).map_err(|_| Failure::Invalid)?;
    let number = |key: &str| value.get(key).and_then(serde_json::Value::as_u64).filter(|n| *n > 0).ok_or(Failure::Invalid);
    let per_byte = crate::coins_per_byte(ledger.parameters.as_bytes())?;
    let max_size = usize::try_from(number("max_tx_size")?).map_err(|_| Failure::Invalid)?;
    Ok((csl::LinearFee::new(&csl::BigNum::from(number("min_fee_a")?), &csl::BigNum::from(number("min_fee_b")?)), per_byte, max_size))
}
fn source_utxos<'a>(ledger: &'a Ledger, source: &str, native: bool) -> Result<Vec<&'a Utxo>, Failure> {
    let mut unique = HashSet::new();
    for u in &ledger.utxos {
        if !valid_hex(&u.transaction_id, 64) || u.index < 0 || u.lovelace < 0 ||
            !unique.insert((&u.transaction_id, u.index)) ||
            u.datum_hash_hex.as_ref().is_some_and(|hash| !valid_hex(hash, 64)) ||
            u.script_ref_hash_hex.as_ref().is_some_and(|hash| !valid_hex(hash, 56)) ||
            u.script_ref_hex.as_ref().is_some_and(|script| script.len() % 2 != 0 || !script.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())) ||
            (u.script_ref_hex.is_some() && u.script_ref_hash_hex.is_none()) ||
            u.script_ref_version.is_some_and(|v| u.script_ref_hex.is_none() || !(0..=3).contains(&v)) ||
            u.assets.iter().any(|(unit, amount)| unit.len() < 56 || unit.len() > 120 ||
                unit.len() % 2 != 0 || !unit.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()) || *amount <= 0) {
            return Err(Failure::Invalid);
        }
    }
    let mut owned: Vec<_> = ledger.utxos.iter().filter(|u| u.address == source &&
        (native || u.assets.is_empty()) && u.datum_hex.is_none() &&
        u.datum_hash_hex.is_none() && u.script_ref_hash_hex.is_none()).collect();
    owned.sort_by(|a, b| (&a.transaction_id, a.index).cmp(&(&b.transaction_id, b.index)));
    if owned.is_empty() { return Err(Failure::Funds); }
    Ok(owned)
}
fn input(u: &Utxo) -> Result<csl::TransactionInput, Failure> {
    Ok(csl::TransactionInput::new(
        &csl::TransactionHash::from_hex(&u.transaction_id).map_err(|_| Failure::Invalid)?,
        u.index as u32,
    ))
}
fn validate(request: &Request) -> Result<(u8, csl::Address, csl::Address, i64, i64), Failure> {
    let network = match request.ledger.network.as_str() { "MAINNET" => 1, "PREPROD" => 0, _ => return Err(Failure::Invalid) };
    let (source, destination, from, until) = match &request.intent {
        Intent::Transfer { source_address, destination_address, valid_from, valid_until, .. } =>
            (source_address, destination_address, *valid_from, *valid_until),
        Intent::SweepWallet { source_address, destination_address, amount, valid_from, valid_until, .. } => {
            if *amount < 0 { return Err(Failure::Invalid); }
            (source_address, destination_address, *valid_from, *valid_until)
        }
    };
    if from < 0 || from < request.ledger.current_slot || until <= from || destination == source {
        return Err(Failure::Invalid);
    }
    let source = address(source, network)?;
    if source.payment_cred().and_then(|c| c.to_keyhash()).is_none() { return Err(Failure::Invalid); }
    let destination = address(destination, network)?;
    Ok((network, source, destination, from, until))
}

// Only a fully funded, ADA-only sweep is constructed here: no native UTxO is silently
// discarded, no provisional amount is trusted, and the fee is priced at signed size.
pub(super) fn sweep(request: &Request) -> Result<(csl::Transaction, i64), Failure> {
    let (_, source, destination, from, until) = validate(request)?;
    if !matches!(&request.intent, Intent::SweepWallet { .. }) { return Err(Failure::Invalid); }
    let (linear_fee, per_byte, max_size) = parameters(&request.ledger)?;
    let source_bech = source.to_bech32(None).map_err(|_| Failure::Invalid)?;
    let owned = source_utxos(&request.ledger, &source_bech, false)?;
    let mut total = 0_i64;
    let mut inputs = csl::TransactionInputs::new();
    for u in owned { total = total.checked_add(u.lovelace).ok_or(Failure::Invalid)?; inputs.add(&input(u)?); }
    let mut fee = 0_u64;
    let mut final_tx = None;
    let priced_witness = fake_witness()?;
    for _ in 0..8 {
        let output_coin = total.checked_sub(i64::try_from(fee).map_err(|_| Failure::Funds)?).filter(|coin| *coin > 0).ok_or(Failure::Funds)? as u64;
        let output = csl::TransactionOutput::new(&destination, &csl::Value::new(&csl::BigNum::from(output_coin)));
        let minimum = (160_u64).checked_add(output.to_bytes().len() as u64).and_then(|size| size.checked_mul(per_byte)).ok_or(Failure::Invalid)?;
        if output_coin < minimum { return Err(Failure::Funds); }
        let mut outputs = csl::TransactionOutputs::new();
        outputs.add(&output);
        let mut body = csl::TransactionBody::new_tx_body(&inputs, &outputs, &csl::BigNum::from(fee));
        body.set_validity_start_interval_bignum(&csl::BigNum::from(from as u64));
        body.set_ttl(&csl::BigNum::from(until as u64));
        let unsigned = csl::Transaction::new(&body, &csl::TransactionWitnessSet::new(), None);
        let priced = csl::Transaction::new(&body, &priced_witness, None);
        if priced.to_bytes().len() > max_size { return Err(Failure::Invalid); }
        let required: u64 = csl::min_fee(&priced, &linear_fee).map_err(|_| Failure::Invalid)?.into();
        if required == fee { final_tx = Some((unsigned, output_coin as i64)); break; }
        fee = required;
    }
    final_tx.ok_or(Failure::Invalid)
}

fn multiasset(units: &std::collections::BTreeMap<String, i64>) -> Result<csl::MultiAsset, Failure> {
    let mut result = csl::MultiAsset::new();
    for (unit, quantity) in units {
        if unit.len() < 56 || unit.len() > 120 || unit.len() % 2 != 0 || *quantity <= 0 {
            return Err(Failure::Invalid);
        }
        let policy = csl::ScriptHash::from_hex(&unit[..56]).map_err(|_| Failure::Invalid)?;
        let name = csl::AssetName::new(hex::decode(&unit[56..]).map_err(|_| Failure::Invalid)?)
            .map_err(|_| Failure::Invalid)?;
        let mut assets = result.get(&policy).unwrap_or_else(csl::Assets::new);
        assets.insert(&name, &csl::BigNum::from(*quantity as u64));
        result.insert(&policy, &assets);
    }
    Ok(result)
}
fn value(coin: u64, units: &std::collections::BTreeMap<String, i64>) -> Result<csl::Value, Failure> {
    let mut result = csl::Value::new(&csl::BigNum::from(coin));
    if !units.is_empty() { result.set_multiasset(&multiasset(units)?); }
    Ok(result)
}
fn output(address: &csl::Address, coin: u64, assets: &std::collections::BTreeMap<String, i64>) -> Result<csl::TransactionOutput, Failure> {
    Ok(csl::TransactionOutput::new(address, &value(coin, assets)?))
}
fn output_minimum(output: &csl::TransactionOutput, per_byte: u64) -> Result<u64, Failure> {
    (160_u64).checked_add(output.to_bytes().len() as u64)
        .and_then(|size| size.checked_mul(per_byte)).ok_or(Failure::Invalid)
}
fn fake_witness() -> Result<csl::TransactionWitnessSet, Failure> {
    let key = csl::PublicKey::from_bytes(&[0_u8; 32]).map_err(|_| Failure::Internal)?;
    let signature = csl::Ed25519Signature::from_bytes(vec![0_u8; 64]).map_err(|_| Failure::Internal)?;
    let mut vkeys = csl::Vkeywitnesses::new();
    vkeys.add(&csl::Vkeywitness::new(&csl::Vkey::new(&key), &signature));
    let mut witnesses = csl::TransactionWitnessSet::new();
    witnesses.set_vkeys(&vkeys);
    Ok(witnesses)
}

// Retain all other native units in wallet change; selected units are never spent for ADA
// fees or output minimums. Exhaustive input selection is safe but raises fees for large
// wallets; switch to deterministic minimal sorted selection only after parity fixtures.
pub(super) fn transfer(request: &Request) -> Result<(csl::Transaction, u64), Failure> {
    let (_, source, destination, from, until) = validate(request)?;
    let Intent::Transfer { amount, .. } = &request.intent else { return Err(Failure::Invalid); };
    if amount.base_units <= 0 || request.assets.iter().filter(|a| a.alias == amount.asset.alias).count() != 1 ||
        !request.assets.iter().any(|a| a == &amount.asset) { return Err(Failure::Invalid); }
    let selected_unit = match (&amount.asset.policy_id, &amount.asset.asset_name) {
        (None, None) if amount.asset.alias == "ada" && amount.asset.decimals == 6 &&
            amount.asset.pricing == "ADA" => None,
        (Some(policy), Some(name)) if amount.asset.alias != "ada" &&
            amount.asset.pricing == "USD_PEG" && valid_hex(policy, 56) &&
            name.len() <= 64 && name.len() % 2 == 0 &&
            name.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()) =>
            Some(format!("{policy}{name}")),
        _ => return Err(Failure::Invalid),
    };
    if !valid_hex(&amount.asset.catalog_digest, 64) { return Err(Failure::Invalid); }
    let (linear_fee, per_byte, max_size) = parameters(&request.ledger)?;
    let source_bech = source.to_bech32(None).map_err(|_| Failure::Invalid)?;
    let owned = source_utxos(&request.ledger, &source_bech, selected_unit.is_some())?;
    let mut total = 0_i64;
    let mut balances = std::collections::BTreeMap::<String, i64>::new();
    let mut inputs = csl::TransactionInputs::new();
    for u in owned {
        total = total.checked_add(u.lovelace).ok_or(Failure::Invalid)?;
        inputs.add(&input(u)?);
        for (unit, quantity) in &u.assets {
            let current = balances.get(unit).copied().unwrap_or_default();
            balances.insert(unit.clone(), current.checked_add(*quantity).ok_or(Failure::Invalid)?);
        }
    }
    let mut recipient_assets = std::collections::BTreeMap::new();
    if let Some(unit) = selected_unit {
        let balance = balances.get_mut(&unit).ok_or(Failure::Funds)?;
        *balance = balance.checked_sub(amount.base_units).filter(|x| *x >= 0).ok_or(Failure::Funds)?;
        if *balance == 0 { balances.remove(&unit); }
        recipient_assets.insert(unit, amount.base_units);
    }
    let mut recipient_ada = if recipient_assets.is_empty() { amount.base_units as u64 } else { 0 };
    if !recipient_assets.is_empty() {
        let mut converged = false;
        for _ in 0..8 {
            let exact = output_minimum(&output(&destination, recipient_ada, &recipient_assets)?, per_byte)?;
            if recipient_ada == exact { converged = true; break; }
            recipient_ada = exact;
        }
        if !converged { return Err(Failure::Invalid); }
    }
    let mut fee = 0_u64;
    let mut final_transaction = None;
    let fake = fake_witness()?;
    for _ in 0..12 {
        let recipient = output(&destination, recipient_ada, &recipient_assets)?;
        if recipient_ada < output_minimum(&recipient, per_byte)? { return Err(Failure::Funds); }
        let change_ada = total.checked_sub(i64::try_from(recipient_ada).map_err(|_| Failure::Funds)?)
            .and_then(|n| n.checked_sub(i64::try_from(fee).ok()?))
            .filter(|n| *n >= 0).ok_or(Failure::Funds)? as u64;
        let mut outputs = csl::TransactionOutputs::new();
        outputs.add(&recipient);
        if change_ada > 0 || !balances.is_empty() {
            let change = output(&source, change_ada, &balances)?;
            if change_ada < output_minimum(&change, per_byte)? { return Err(Failure::Funds); }
            outputs.add(&change);
        }
        let mut body = csl::TransactionBody::new_tx_body(&inputs, &outputs, &csl::BigNum::from(fee));
        body.set_validity_start_interval_bignum(&csl::BigNum::from(from as u64));
        body.set_ttl(&csl::BigNum::from(until as u64));
        let signed_size = csl::Transaction::new(&body, &fake, None);
        if signed_size.to_bytes().len() > max_size { return Err(Failure::Invalid); }
        let required: u64 = csl::min_fee(&signed_size, &linear_fee).map_err(|_| Failure::Invalid)?.into();
        if fee == required {
            let transaction = csl::Transaction::new(&body, &csl::TransactionWitnessSet::new(), None);
            final_transaction = Some((transaction, fee));
            break;
        }
        fee = required;
    }
    final_transaction.ok_or(Failure::Invalid)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(kind: &str) -> Request {
        let source_key = crate::payment_key(&(0..32).collect::<Vec<u8>>()).unwrap().to_public().to_raw_key().hash();
        let destination_key = crate::payment_key(&(1..33).collect::<Vec<u8>>()).unwrap().to_public().to_raw_key().hash();
        let source = csl::EnterpriseAddress::new(0, &csl::Credential::from_keyhash(&source_key))
            .to_address().to_bech32(None).unwrap();
        let destination = csl::EnterpriseAddress::new(0, &csl::Credential::from_keyhash(&destination_key))
            .to_address().to_bech32(None).unwrap();
        let ada = serde_json::json!({"alias":"ada", "policyId":null,"assetName":null,
            "decimals":6,"pricing":"ADA","catalogDigest":"a".repeat(64)});
        let intent = if kind == "SweepWallet" {
            serde_json::json!({"type":"io.riverark.ferret.core.cardano.CardanoIntent.SweepWallet",
                "sourceAddress":source,"destinationAddress":destination,"amount":1,
                "operationId":"00000000-0000-4000-8000-000000000001","validFrom":100,"validUntil":200})
        } else {
            serde_json::json!({"type":"io.riverark.ferret.core.cardano.CardanoIntent.Transfer",
                "sourceAddress":source,"destinationAddress":destination,"amount":{"asset":ada,"baseUnits":5_000_000},
                "operationId":"00000000-0000-4000-8000-000000000001","validFrom":100,"validUntil":200})
        };
        serde_json::from_value(serde_json::json!({
            "intent":intent,"assets":[ada],
            "ledger":{"network":"PREPROD","currentSlot":100,
                "protocolParametersJson":r#"{"coins_per_utxo_size":"4310","min_fee_a":44,"min_fee_b":155381,"max_tx_size":16384}"#,
                "utxos":[{"transactionId":"ab".repeat(32),"index":0,"address":source,"lovelace":20_000_000,"assets":{}}]}
        })).unwrap()
    }

    #[test]
    fn l1_money_is_conserved_and_sweep_ignores_provisional_amount() {
        let (transfer, fee) = transfer(&request("Transfer")).unwrap();
        assert_eq!(transfer.body().outputs().len(), 2);
        let recipient: u64 = transfer.body().outputs().get(0).amount().coin().into();
        let change: u64 = transfer.body().outputs().get(1).amount().coin().into();
        assert_eq!(recipient, 5_000_000);
        assert_eq!(recipient + change + fee, 20_000_000);
        let (sweep, output) = sweep(&request("SweepWallet")).unwrap();
        let fee: u64 = sweep.body().fee().into();
        assert_eq!(sweep.body().outputs().len(), 1);
        assert_eq!(output as u64 + fee, 20_000_000);
        assert_ne!(output, 1);
    }

    #[test]
    fn native_transfer_preserves_unselected_assets() {
        let mut request = request("Transfer");
        let selected = "11".repeat(28) + "01";
        let other = "22".repeat(28) + "02";
        let token: Asset = serde_json::from_value(serde_json::json!({
            "alias":"reviewed","policyId":"11".repeat(28),"assetName":"01",
            "decimals":6,"pricing":"USD_PEG","catalogDigest":"c".repeat(64)
        })).unwrap();
        request.assets.push(token.clone());
        let Intent::Transfer { amount, .. } = &mut request.intent else { panic!("wrong fixture") };
        amount.asset = token;
        amount.base_units = 50;
        request.ledger.utxos[0].assets.insert(selected.clone(), 100);
        request.ledger.utxos[0].assets.insert(other.clone(), 40);
        let (tx, fee) = transfer(&request).unwrap();
        let outputs = tx.body().outputs();
        assert_eq!(outputs.len(), 2);
        let recipient = outputs.get(0).amount().multiasset().unwrap();
        let change = outputs.get(1).amount().multiasset().unwrap();
        let quantity = |assets: &csl::MultiAsset, unit: &str| -> u64 {
            let policy = csl::ScriptHash::from_hex(&unit[..56]).unwrap();
            let name = csl::AssetName::new(hex::decode(&unit[56..]).unwrap()).unwrap();
            assets.get(&policy).and_then(|a| a.get(&name)).map(Into::into).unwrap_or(0)
        };
        assert_eq!(quantity(&recipient, &selected), 50);
        assert_eq!(quantity(&change, &selected), 50);
        assert_eq!(quantity(&change, &other), 40);
        assert_eq!(quantity(&recipient, &other), 0);
        let ada: u64 = outputs.get(0).amount().coin().into();
        let change_ada: u64 = outputs.get(1).amount().coin().into();
        assert_eq!(ada + change_ada + fee, 20_000_000);
    }
}
