use cardano_serialization_lib as csl;
use std::{panic::{catch_unwind, AssertUnwindSafe}, ptr, slice, sync::Once};
use zeroize::Zeroizing;

mod l1;
mod authorization;
mod channel;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FerretBytes { ptr: *const u8, len: usize }
#[repr(C)]
#[derive(Clone, Copy)]
pub struct FerretBuffer { ptr: *mut u8, len: usize }
impl Default for FerretBuffer {
    fn default() -> Self { Self { ptr: ptr::null_mut(), len: 0 } }
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum Failure { Invalid, Funds, Collateral, Internal }
impl Failure {
    fn status(self) -> i32 { match self { Self::Invalid => 1, Self::Funds => 2, Self::Collateral => 3, Self::Internal => 4 } }
    fn diagnostic(self) -> &'static [u8] { match self {
        Self::Invalid => b"Invalid transaction or authorization.",
        Self::Funds => b"Insufficient funds.",
        Self::Collateral => b"Insufficient collateral.",
        Self::Internal => b"Cardano operation failed.",
    } }
}
type Answer = Result<Vec<u8>, Failure>;

// A null pointer with a nonzero length is never dereferenced. All buffers are borrowed only
// during the call; requests are bounded before JSON parsing or CBOR deserialization.
unsafe fn input<'a>(bytes: FerretBytes, limit: usize) -> Result<&'a [u8], Failure> {
    if bytes.len > limit || (bytes.len != 0 && bytes.ptr.is_null()) { return Err(Failure::Invalid); }
    if bytes.len == 0 { return Ok(&[]); }
    Ok(slice::from_raw_parts(bytes.ptr, bytes.len))
}
fn output(data: Vec<u8>) -> FerretBuffer {
    if data.is_empty() { return FerretBuffer::default(); }
    let mut boxed = data.into_boxed_slice();
    let buffer = FerretBuffer { ptr: boxed.as_mut_ptr(), len: boxed.len() };
    std::mem::forget(boxed);
    buffer
}
fn redact_panics() {
    static HOOK: Once = Once::new();
    // Rust's panic hook runs before catch_unwind. This process-wide hook deliberately
    // replaces any other Rust panic hook in the host: no CSL panic payload can log seeds.
    HOOK.call_once(|| std::panic::set_hook(Box::new(|_| {})));
}
unsafe fn run(result: *mut FerretBuffer, error: *mut FerretBuffer, action: impl FnOnce() -> Answer) -> i32 {
    if result.is_null() || error.is_null() || result == error { return Failure::Invalid.status(); }
    *result = FerretBuffer::default();
    *error = FerretBuffer::default();
    let answer = catch_unwind(AssertUnwindSafe(|| {
        redact_panics();
        action()
    })).unwrap_or(Err(Failure::Internal));
    match answer {
        Ok(bytes) => { *result = output(bytes); 0 }
        Err(failure) => { *error = output(failure.diagnostic().to_vec()); failure.status() }
    }
}
#[no_mangle]
pub unsafe extern "C" fn ferret_buffer_free(buffer: FerretBuffer) {
    if !buffer.ptr.is_null() && buffer.len != 0 {
        drop(Box::from_raw(ptr::slice_from_raw_parts_mut(buffer.ptr, buffer.len)));
    }
}

fn account_key(root: &csl::Bip32PrivateKey) -> csl::Bip32PrivateKey {
    root.derive(1852 | 0x8000_0000).derive(1815 | 0x8000_0000).derive(0x8000_0000)
}
fn payment_key(entropy: &[u8]) -> Result<csl::Bip32PrivateKey, Failure> {
    if entropy.len() != 32 { return Err(Failure::Invalid); }
    let root = csl::Bip32PrivateKey::from_bip39_entropy(entropy, &[]);
    Ok(account_key(&root).derive(0).derive(0))
}
pub(crate) fn payment_public(entropy: &[u8]) -> Result<csl::PublicKey, Failure> {
    Ok(payment_key(entropy)?.to_public().to_raw_key())
}
fn network(network_id: u32) -> Result<u8, Failure> {
    match network_id { 0 | 1 => Ok(network_id as u8), _ => Err(Failure::Invalid) }
}
fn derived(entropy: &[u8], network_id: u32) -> Answer {
    let id = network(network_id)?;
    if entropy.len() != 32 { return Err(Failure::Invalid); }
    let root = csl::Bip32PrivateKey::from_bip39_entropy(entropy, &[]);
    let account = account_key(&root);
    let payment = account.derive(0).derive(0).to_public().to_raw_key().hash();
    let stake = account.derive(2).derive(0).to_public().to_raw_key().hash();
    let base = csl::BaseAddress::new(id, &csl::Credential::from_keyhash(&payment), &csl::Credential::from_keyhash(&stake));
    let reward = csl::RewardAddress::new(id, &csl::Credential::from_keyhash(&stake));
    let json = serde_json::json!({
        "paymentAddress": base.to_address().to_bech32(None).map_err(|_| Failure::Internal)?,
        "stakeAddress": reward.to_address().to_bech32(None).map_err(|_| Failure::Internal)?,
        "paymentCredentialHex": payment.to_hex(),
    });
    serde_json::to_vec(&json).map_err(|_| Failure::Internal)
}
#[no_mangle]
pub unsafe extern "C" fn ferret_derive(entropy: FerretBytes, network_id: u32, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || derived(input(entropy, 32)?, network_id))
}
#[no_mangle]
pub unsafe extern "C" fn ferret_protocol_key(entropy: FerretBytes, network_id: u32, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || {
        network(network_id)?;
        Ok(payment_public(input(entropy, 32)?)?.as_bytes())
    })
}
pub(crate) fn sign_payment(entropy: &[u8], message: &[u8]) -> Answer {
    let derived = payment_key(entropy)?;
    let secret = Zeroizing::new(derived.as_bytes());
    let key = ed25519_bip32::XPrv::from_slice_verified(&secret).map_err(|_| Failure::Internal)?;
    Ok(key.sign::<()>(message).to_bytes().to_vec())
}
#[no_mangle]
pub unsafe extern "C" fn ferret_protocol_sign(entropy: FerretBytes, network_id: u32, message: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || {
        network(network_id)?;
        let message = input(message, 1_048_576)?;
        sign_payment(input(entropy, 32)?, message)
    })
}
#[no_mangle]
pub unsafe extern "C" fn ferret_protocol_verify(key: FerretBytes, message: FerretBytes, signature: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || {
        let key = input(key, 32)?;
        let signature = input(signature, 64)?;
        if key.len() != 32 || signature.len() != 64 { return Err(Failure::Invalid); }
        let key = csl::PublicKey::from_bytes(key).map_err(|_| Failure::Invalid)?;
        let signature = csl::Ed25519Signature::from_bytes(signature.to_vec()).map_err(|_| Failure::Invalid)?;
        Ok(vec![u8::from(key.verify(input(message, 1_048_576)?, &signature))])
    })
}

fn transaction(cbor: &[u8]) -> Result<csl::Transaction, Failure> {
    if cbor.is_empty() || cbor.len() > 16_384 { return Err(Failure::Invalid); }
    let tx = csl::Transaction::from_bytes(cbor.to_vec()).map_err(|_| Failure::Invalid)?;
    if tx.to_bytes() != cbor || !tx.is_valid() || tx.auxiliary_data().is_some() {
        return Err(Failure::Invalid);
    }
    Ok(tx)
}
fn coins_per_byte(json: &[u8]) -> Result<u64, Failure> {
    let params: serde_json::Value = serde_json::from_slice(json).map_err(|_| Failure::Invalid)?;
    let value = params.get("coins_per_utxo_size").and_then(serde_json::Value::as_str).ok_or(Failure::Invalid)?;
    if !value.as_bytes().first().is_some_and(|b| matches!(b, b'1'..=b'9')) ||
        !value.bytes().all(|b| b.is_ascii_digit()) { return Err(Failure::Invalid); }
    value.parse::<u64>().ok().filter(|v| *v > 0 && *v <= i64::MAX as u64).ok_or(Failure::Invalid)
}
#[no_mangle]
pub unsafe extern "C" fn ferret_minimum_ada(cbor: FerretBytes, protocol_json: FerretBytes, output_index: u32, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || {
        let tx = transaction(input(cbor, 16_384)?)?;
        let per_byte = coins_per_byte(input(protocol_json, 65_536)?)?;
        let body = tx.body();
        let output = if output_index == u32::MAX {
            body.collateral_return().ok_or(Failure::Invalid)?
        } else {
            if output_index as usize >= body.outputs().len() { return Err(Failure::Invalid); }
            body.outputs().get(output_index as usize)
        };
        let cost = (160_u64).checked_add(output.to_bytes().len() as u64)
            .and_then(|size| size.checked_mul(per_byte)).ok_or(Failure::Invalid)?;
        Ok(cost.to_string().into_bytes())
    })
}

#[no_mangle]
pub unsafe extern "C" fn ferret_transaction_id(cbor: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || {
        let bytes = input(cbor, 16_384)?;
        transaction(bytes)?;
        let fixed = csl::FixedTransaction::from_bytes(bytes.to_vec()).map_err(|_| Failure::Invalid)?;
        if fixed.to_bytes() != bytes { return Err(Failure::Invalid); }
        Ok(fixed.transaction_hash().to_hex().into_bytes())
    })
}

fn l1_candidate(request_json: &[u8]) -> Result<(Vec<u8>, String, u64), Failure> {
    let request: l1::Request = serde_json::from_slice(request_json).map_err(|_| Failure::Invalid)?;
    let (transaction, fee, exact_sweep_amount) = match &request.intent {
        l1::Intent::Transfer { .. } => {
            let (transaction, fee) = l1::transfer(&request)?;
            (transaction, fee, None)
        }
        l1::Intent::SweepWallet { .. } => {
            let (transaction, amount) = l1::sweep(&request)?;
            let fee: u64 = transaction.body().fee().into();
            (transaction, fee, Some(amount))
        }
    };
    let operation_id = match &request.intent {
        l1::Intent::Transfer { operation_id, .. } | l1::Intent::SweepWallet { operation_id, .. } =>
            operation_id.clone(),
    };
    let cbor = transaction.to_bytes();
    let mut auth: serde_json::Value = serde_json::from_slice(request_json).map_err(|_| Failure::Invalid)?;
    if let Some(amount) = exact_sweep_amount {
        auth["intent"]["amount"] = serde_json::json!(amount);
    }
    auth["operationId"] = serde_json::json!(&operation_id);
    auth["feeBound"] = serde_json::json!(fee);
    let authorization = serde_json::to_vec(&auth).map_err(|_| Failure::Internal)?;
    authorization::authorize(&cbor, &authorization)?;
    Ok((cbor, operation_id, fee))
}

enum BuildState {
    L1 { cbor: Vec<u8>, operation_id: String, fee: u64, finished: bool },
    Channel(channel::ChannelBuild),
}
pub struct FerretBuild { state: BuildState }

#[no_mangle]
pub unsafe extern "C" fn ferret_build_free(build: *mut FerretBuild) {
    if !build.is_null() { drop(Box::from_raw(build)); }
}
#[no_mangle]
pub unsafe extern "C" fn ferret_build_begin(request_json: FerretBytes, result: *mut *mut FerretBuild, error: *mut FerretBuffer) -> i32 {
    if result.is_null() || error.is_null() || result.cast::<u8>() == error.cast::<u8>() {
        return Failure::Invalid.status();
    }
    *result = ptr::null_mut();
    *error = FerretBuffer::default();
    let built = catch_unwind(AssertUnwindSafe(|| {
        redact_panics();
        let request = input(request_json, 1_048_576)?;
        let typed: serde_json::Value = serde_json::from_slice(request).map_err(|_| Failure::Invalid)?;
        let kind = typed.get("intent").and_then(|v| v.get("type"))
            .and_then(serde_json::Value::as_str).ok_or(Failure::Invalid)?;
        let state = match kind {
            "io.riverark.ferret.core.cardano.CardanoIntent.Transfer" |
            "io.riverark.ferret.core.cardano.CardanoIntent.SweepWallet" => {
                let (cbor, operation_id, fee) = l1_candidate(request)?;
                BuildState::L1 { cbor, operation_id, fee, finished: false }
            }
            "io.riverark.ferret.core.cardano.CardanoIntent.OpenChannel" |
            "io.riverark.ferret.core.cardano.CardanoIntent.AddChannelFunds" |
            "io.riverark.ferret.core.cardano.CardanoIntent.CloseChannel" =>
                BuildState::Channel(channel::begin(request)?),
            _ => return Err(Failure::Invalid),
        };
        Ok::<_, Failure>(FerretBuild { state })
    })).unwrap_or(Err(Failure::Internal));
    match built {
        Ok(build) => { *result = Box::into_raw(Box::new(build)); 0 }
        Err(failure) => { *error = output(failure.diagnostic().to_vec()); failure.status() }
    }
}
#[no_mangle]
pub unsafe extern "C" fn ferret_build_next(build: *mut FerretBuild, evaluation_json: FerretBytes, evaluation_entropy: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || {
        if build.is_null() { return Err(Failure::Invalid); }
        let evaluation = input(evaluation_json, 1_048_576)?;
        let entropy = input(evaluation_entropy, 32)?;
        match &mut (*build).state {
            BuildState::L1 { cbor, operation_id, fee, finished } => {
                if *finished || !evaluation.is_empty() || !entropy.is_empty() { return Err(Failure::Invalid); }
                let response = serde_json::to_vec(&serde_json::json!({
                    "kind": "complete", "cborHex": hex::encode(cbor),
                    "operationId": operation_id, "feeBound": fee,
                })).map_err(|_| Failure::Internal)?;
                *finished = true;
                Ok(response)
            }
            BuildState::Channel(state) => channel::next(state, evaluation, entropy),
        }
    })
}
#[no_mangle]
pub unsafe extern "C" fn ferret_inspect(cbor: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || authorization::inspect(input(cbor, 16_384)?))
}
#[no_mangle]
pub unsafe extern "C" fn ferret_authorize(cbor: FerretBytes, request_json: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || authorization::authorize(input(cbor, 16_384)?, input(request_json, 1_048_576)?))
}
#[no_mangle]
pub unsafe extern "C" fn ferret_sign(cbor: FerretBytes, entropy: FerretBytes, request_json: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || authorization::sign(input(cbor, 16_384)?, input(entropy, 32)?, input(request_json, 1_048_576)?))
}
#[no_mangle]
pub unsafe extern "C" fn ferret_decode_datum(cbor_hex: FerretBytes, assets_json: FerretBytes, result: *mut FerretBuffer, error: *mut FerretBuffer) -> i32 {
    run(result, error, || channel::decode(input(cbor_hex, 16_384)?, input(assets_json, 65_536)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derived_account_and_protocol_signature() {
        let entropy: Vec<u8> = (0..32).collect();
        let mainnet: serde_json::Value = serde_json::from_slice(&derived(&entropy, 1).unwrap()).unwrap();
        let preprod: serde_json::Value = serde_json::from_slice(&derived(&entropy, 0).unwrap()).unwrap();
        assert_eq!(mainnet["paymentAddress"], "addr1qyzkxpwrnvu3ylqvj6wupde0pjk4w28zu9893wu55z4upfcuafluqtl6qqeua5h8m66l6mxpvvqh0w7gfuwrs6npgtusaefqse");
        assert_eq!(mainnet["stakeAddress"], "stake1uyww5l7q9laqqv7w6tnaad0adnqkxqthh0yy78pcdfs597g7cfafd");
        assert_eq!(preprod["paymentAddress"], "addr_test1qqzkxpwrnvu3ylqvj6wupde0pjk4w28zu9893wu55z4upfcuafluqtl6qqeua5h8m66l6mxpvvqh0w7gfuwrs6npgtus705qux");
        assert_eq!(preprod["stakeAddress"], "stake_test1uqww5l7q9laqqv7w6tnaad0adnqkxqthh0yy78pcdfs597gejrlds");
        assert_eq!(mainnet["paymentCredentialHex"], "056305c39b39127c0c969dc0b72f0cad5728e2e14e58bb94a0abc0a7");
        assert_eq!(mainnet["paymentCredentialHex"], preprod["paymentCredentialHex"]);
        let message = b"Ferret protocol signing regression";
        let key = payment_key(&entropy).unwrap().to_public().to_raw_key();
        let signature = csl::Ed25519Signature::from_bytes(sign_payment(&entropy, message).unwrap()).unwrap();
        assert_eq!(signature.to_bytes().len(), 64);
        assert!(key.verify(message, &signature));
        assert!(!key.verify(b"modified protocol message", &signature));
        assert!(derived(&entropy[..31], 1).is_err());
        assert!(derived(&entropy, 2).is_err());
    }

    #[test]
    fn rejects_invalid_protocol_costs() {
        assert_eq!(coins_per_byte(br#"{"coins_per_utxo_size":"4310"}"#).unwrap(), 4310);
        for json in [br#"{"coins_per_utxo_size":4310}"#.as_slice(), br#"{"coins_per_utxo_size":"0"}"#,
            br#"{"coins_per_utxo_size":"01"}"#, br#"{"coins_per_utxo_size":"-1"}"#,
            br#"{"coins_per_utxo_size":"9223372036854775808"}"#, br#"{}"#] {
            assert!(coins_per_byte(json).is_err());
        }
    }
}
