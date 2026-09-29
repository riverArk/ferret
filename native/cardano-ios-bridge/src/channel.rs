use cardano_serialization_lib as csl;
use serde_json::{json, Value};
use crate::Failure;

fn field<'a>(value: &'a Value, name: &str) -> Result<&'a Value, Failure> { value.get(name).ok_or(Failure::Invalid) }
fn text<'a>(value: &'a Value, name: &str) -> Result<&'a str, Failure> { field(value,name)?.as_str().ok_or(Failure::Invalid) }
fn number(value: &Value, name: &str) -> Result<u64, Failure> { field(value,name)?.as_u64().filter(|n| *n <= i64::MAX as u64).ok_or(Failure::Invalid) }
fn bytes(value:&str,length:usize)->Result<Vec<u8>,Failure>{if value.len()!=length*2||!value.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)){return Err(Failure::Invalid)}hex::decode(value).map_err(|_|Failure::Invalid)}
fn array(items:impl IntoIterator<Item=csl::PlutusData>)->csl::PlutusData{let mut list=csl::PlutusList::new();for item in items{list.add(&item)}csl::PlutusData::new_list(&list)}
fn constructor(variant:u64,items:impl IntoIterator<Item=csl::PlutusData>)->csl::PlutusData{let mut list=csl::PlutusList::new();for item in items{list.add(&item)}csl::PlutusData::new_constr_plutus_data(&csl::ConstrPlutusData::new(&csl::BigNum::from(variant),&list))}
fn integer(n:u64)->csl::PlutusData{csl::PlutusData::new_integer(&csl::BigInt::from_str(&n.to_string()).expect("u64"))}
fn evidence(encoded:&str,pending:bool)->Result<(csl::PlutusData,Option<u64>),Failure>{
 if encoded.is_empty()||encoded.len()>8192||encoded.len()%2!=0{return Err(Failure::Invalid)}
 let original=hex::decode(encoded).map_err(|_|Failure::Invalid)?;
 let parsed=csl::PlutusData::from_bytes(original.clone()).map_err(|_|Failure::Invalid)?;
 if parsed.to_bytes()!=original{return Err(Failure::Invalid)}
 let list=parsed.as_list().ok_or(Failure::Invalid)?;
 if list.len()!=if pending{3}else{2}{return Err(Failure::Invalid)}
 let amount=nonnegative(&list.get(0))?;let timeout=nonnegative(&list.get(1))?;
 let normalized=if pending{let lock=list.get(2).as_bytes().filter(|v|v.len()==32).ok_or(Failure::Invalid)?;array([integer(amount),integer(timeout),csl::PlutusData::new_bytes(lock)])}else{array([integer(amount),integer(timeout)])};
 if normalized.to_bytes()!=original{return Err(Failure::Invalid)}
 Ok((normalized,pending.then_some(timeout)))
}
fn stage_type(value:&Value)->Result<&str,Failure>{text(value,"type")?.strip_prefix("io.riverark.ferret.core.cardano.ChannelDatumStage.").ok_or(Failure::Invalid)}
fn asset(value:&Value)->Result<(),Failure>{let alias=text(value,"alias")?;let policy=field(value,"policyId")?;let name=field(value,"assetName")?;let pricing=text(value,"pricing")?;bytes(text(value,"catalogDigest")?,32)?;let decimals=number(value,"decimals")?;
 if alias.is_empty()||alias.len()>32||!alias.bytes().next().is_some_and(|b|b.is_ascii_lowercase())||!alias.bytes().all(|b|b.is_ascii_lowercase()||b.is_ascii_digit()||b==b'_'){return Err(Failure::Invalid)}
 if policy.is_null()&&name.is_null()&&alias=="ada"&&decimals==6&&pricing=="ADA"{return Ok(())}
 if alias!="ada"&&pricing=="USD_PEG"&&decimals<=19{bytes(policy.as_str().ok_or(Failure::Invalid)?,28)?;let name=name.as_str().ok_or(Failure::Invalid)?;if name.len()<=64&&name.len()%2==0&&name.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)){return Ok(())}}Err(Failure::Invalid)
}
pub(crate) fn datum(value:&Value)->Result<csl::PlutusData,Failure>{
 let validator=bytes(text(value,"validatorHashHex")?,28)?;let constants=field(value,"constants")?;let asset_value=field(constants,"asset")?;asset(asset_value)?;
 let asset_data=if let Some(policy)=field(asset_value,"policyId")?.as_str(){constructor(1,[csl::PlutusData::new_bytes(bytes(policy,28)?),csl::PlutusData::new_bytes(hex::decode(text(asset_value,"assetName")?).map_err(|_|Failure::Invalid)?)])}else{constructor(0,[])};
 let close_period=number(constants,"closePeriodMillis")?;if close_period==0{return Err(Failure::Invalid)}
 let constants_data=array([csl::PlutusData::new_bytes(bytes(text(constants,"tagHex")?,32)?),csl::PlutusData::new_bytes(bytes(text(constants,"addVerificationKeyHex")?,32)?),csl::PlutusData::new_bytes(bytes(text(constants,"adaptorVerificationKeyHex")?,32)?),integer(close_period),asset_data]);
 let stage=field(value,"stage")?;let variant=match stage_type(stage)?{"Opened"=>0,"Closed"=>1,"Responded"=>2,_=>return Err(Failure::Invalid)};
 let evidence_value=stage.get("evidenceCborHex").map(|v|v.as_array().ok_or(Failure::Invalid)).transpose()?;
 if evidence_value.is_some_and(|items|items.len()>10){return Err(Failure::Invalid)}
 let mut evidence_data=Vec::new();for item in evidence_value.into_iter().flatten(){evidence_data.push(evidence(item.as_str().ok_or(Failure::Invalid)?,variant==2)?.0)}
 let mut stage_items=vec![integer(number(stage,"accountedAmount")?),array(evidence_data)];if variant==1{stage_items.push(integer(number(stage,"elapseAtEpochMillis")?))}
 Ok(array([csl::PlutusData::new_bytes(validator),constants_data,constructor(variant,stage_items)]))
}
fn list(data:&csl::PlutusData,size:usize)->Result<csl::PlutusList,Failure>{data.as_list().filter(|l|l.len()==size).ok_or(Failure::Invalid)}
fn fixed(data:&csl::PlutusData,size:usize)->Result<String,Failure>{Ok(hex::encode(data.as_bytes().filter(|v|v.len()==size).ok_or(Failure::Invalid)?))}
fn nonnegative(data:&csl::PlutusData)->Result<u64,Failure>{let n=data.as_integer().ok_or(Failure::Invalid)?.to_str().parse::<u64>().map_err(|_|Failure::Invalid)?;if n>i64::MAX as u64{return Err(Failure::Invalid)}Ok(n)}
fn parts(data:&csl::PlutusData,variant:u64,size:usize)->Result<csl::PlutusList,Failure>{let c=data.as_constr_plutus_data().ok_or(Failure::Invalid)?;if u64::from(c.alternative())!=variant||c.data().len()!=size{return Err(Failure::Invalid)}Ok(c.data())}
fn decode_value(data:&csl::PlutusData,assets:&[Value])->Result<Value,Failure>{
 let root=list(data,3)?;let validator=fixed(&root.get(0),28)?;let constants=list(&root.get(1),5)?;let asset_datum=constants.get(4);let c=asset_datum.as_constr_plutus_data().ok_or(Failure::Invalid)?;
 let unit=match u64::from(c.alternative()){0=>{parts(&asset_datum,0,0)?;None},1=>{let fields=parts(&asset_datum,1,2)?;let name=fields.get(1).as_bytes().filter(|v|v.len()<=32).ok_or(Failure::Invalid)?;Some(format!("{}{}",fixed(&fields.get(0),28)?,hex::encode(name)))},_=>return Err(Failure::Invalid)};
 let matching:Vec<_>=assets.iter().filter(|entry|asset(entry).is_ok()&&match &unit{None=>entry.get("policyId").is_some_and(Value::is_null),Some(unit)=>entry.get("policyId").and_then(Value::as_str).zip(entry.get("assetName").and_then(Value::as_str)).is_some_and(|(p,n)|format!("{p}{n}")==*unit)}).collect();if matching.len()!=1{return Err(Failure::Invalid)}
 let stage_c=root.get(2).as_constr_plutus_data().ok_or(Failure::Invalid)?;let variant:u64=stage_c.alternative().into();if variant>2{return Err(Failure::Invalid)}let fields=parts(&root.get(2),variant,if variant==1{3}else{2})?;let evidence_list=fields.get(1).as_list().ok_or(Failure::Invalid)?;if evidence_list.len()>10{return Err(Failure::Invalid)}
 let mut evidence_hex=Vec::with_capacity(evidence_list.len());for index in 0..evidence_list.len(){let encoded=hex::encode(evidence_list.get(index).to_bytes());evidence(&encoded,variant==2)?;evidence_hex.push(encoded)}
 let name=match variant{0=>"Opened",1=>"Closed",_=>"Responded"};let mut stage=json!({"type":format!("io.riverark.ferret.core.cardano.ChannelDatumStage.{name}"),"accountedAmount":nonnegative(&fields.get(0))?,"evidenceCborHex":evidence_hex});if variant==1{stage["elapseAtEpochMillis"]=json!(nonnegative(&fields.get(2))?)}
 let close_period=nonnegative(&constants.get(3))?;if close_period==0{return Err(Failure::Invalid)}
 Ok(json!({"validatorHashHex":validator,"constants":{"tagHex":fixed(&constants.get(0),32)?,"addVerificationKeyHex":fixed(&constants.get(1),32)?,"adaptorVerificationKeyHex":fixed(&constants.get(2),32)?,"closePeriodMillis":close_period,"asset":matching[0]},"stage":stage}))
}
pub(crate) fn decode(cbor_hex:&[u8],assets_json:&[u8])->Result<Vec<u8>,Failure>{let encoded=std::str::from_utf8(cbor_hex).map_err(|_|Failure::Invalid)?;if encoded.is_empty()||encoded.len()>16384||encoded.len()%2!=0||!encoded.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)){return Err(Failure::Invalid)}let original=hex::decode(encoded).map_err(|_|Failure::Invalid)?;let plutus=csl::PlutusData::from_bytes(original.clone()).map_err(|_|Failure::Invalid)?;let assets:Vec<Value>=serde_json::from_slice(assets_json).map_err(|_|Failure::Invalid)?;let decoded=decode_value(&plutus,&assets)?;if datum(&decoded)?.to_bytes()!=original{return Err(Failure::Invalid)}serde_json::to_vec(&decoded).map_err(|_|Failure::Internal)}

fn network(ledger:&Value)->Result<(u8,&'static str,&'static str,u64,u64),Failure>{
 match text(ledger,"network")?{
  "MAINNET"=>Ok((1,"addr1wxcrrmk4g6ta93942evluyw6c2ffy2xanpl6lc43tyzvupqswlfa5","b031eed54697d2c4b55659fe11dac2929228dd987fafe2b15904ce04",4492800,1596059091000)),
  "PREPROD"=>Ok((0,"addr_test1wrpc0agp7ce78zefuk38kyza8rnu3gzy9vy6ynhh7t9ygygy5fd4h","c387f501f633e38b29e5a27b105d38e7c8a0442b09a24ef7f2ca4411",86400,1655769600000)),
  _=>Err(Failure::Invalid)
 }
}
fn address(value:&str,network:u8)->Result<csl::Address,Failure>{
 let address=csl::Address::from_bech32(value).map_err(|_|Failure::Invalid)?;
 if address.network_id().map_err(|_|Failure::Invalid)?!=network ||
    !(if network==1{value.starts_with("addr1")}else{value.starts_with("addr_test1")}){return Err(Failure::Invalid)}
 Ok(address)
}
fn utxo<'a>(ledger:&'a Value,candidate:&Value)->Result<&'a Value,Failure>{
 let id=text(candidate,"transactionId")?;bytes(id,32)?;
 let index=number(candidate,"index")?;
 let all=field(ledger,"utxos")?.as_array().ok_or(Failure::Invalid)?;
 if all.iter().filter(|u|text(u,"transactionId").ok()==Some(id)&&number(u,"index").ok()==Some(index)).count()!=1{return Err(Failure::Invalid)}
 all.iter().find(|u|text(u,"transactionId").ok()==Some(id)&&number(u,"index").ok()==Some(index)&&*u==candidate).ok_or(Failure::Invalid)
}
fn asset_unit(value:&Value)->Result<Option<String>,Failure>{
 asset(value)?;
 Ok(field(value,"policyId")?.as_str().map(|policy|format!("{policy}{}",text(value,"assetName").unwrap_or_default())))
}
fn owned(candidate:&Value,source:&str,allow_assets:bool)->bool{
 candidate.get("address").and_then(Value::as_str)==Some(source)&&
 (allow_assets||candidate.get("assets").and_then(Value::as_object).is_none_or(|m|m.is_empty()))&&
 ["datumHex","datumHashHex","scriptRefHashHex"].iter().all(|key|candidate.get(*key).is_none_or(Value::is_null))
}
fn slot_millis(slot:u64,zero_slot:u64,zero_time:u64)->Result<u64,Failure>{
 slot.checked_sub(zero_slot).and_then(|distance|distance.checked_mul(1000)).and_then(|elapsed|zero_time.checked_add(elapsed)).filter(|n|*n<=i64::MAX as u64).ok_or(Failure::Invalid)
}
fn checked_asset<'a>(asset:&Value,assets:&'a Value)->Result<&'a Value,Failure>{
 let catalog=assets.as_array().ok_or(Failure::Invalid)?;
 let alias=text(asset,"alias")?;
 if catalog.iter().filter(|entry|entry.get("alias").and_then(Value::as_str)==Some(alias)).count()!=1{return Err(Failure::Invalid)}
 catalog.iter().find(|entry|*entry==asset).ok_or(Failure::Invalid)
}
fn reference_script(reference:&Value,hash:&str)->Result<csl::PlutusScript,Failure>{
 if number(reference,"scriptRefVersion")?!=3||text(reference,"scriptRefHashHex")?!=hash{return Err(Failure::Invalid)}
 let encoded=text(reference,"scriptRefHex")?;
 if encoded.is_empty()||encoded.len()>131072||encoded.len()%2!=0{return Err(Failure::Invalid)}
 let script=csl::PlutusScript::from_bytes_v3(hex::decode(encoded).map_err(|_|Failure::Invalid)?).map_err(|_|Failure::Invalid)?;
 if script.hash().to_hex()!=hash{return Err(Failure::Invalid)}
 Ok(script)
}
pub(crate) fn validate_intent(intent:&Value,ledger:&Value,assets:&Value)->Result<(),Failure>{
 let kind=text(intent,"type")?.strip_prefix("io.riverark.ferret.core.cardano.CardanoIntent.").ok_or(Failure::Invalid)?;
 if !["OpenChannel","AddChannelFunds","CloseChannel"].contains(&kind){return Err(Failure::Invalid)}
 let (network,validator,hash,zero_slot,zero_time)=network(ledger)?;
 let source=text(intent,"sourceAddress")?;
 let source_address=address(source,network)?;
 let credential=source_address.payment_cred().and_then(|c|c.to_keyhash()).ok_or(Failure::Invalid)?;
 let from=number(intent,"validFrom")?;let until=number(intent,"validUntil")?;
 if from<number(ledger,"currentSlot")?||until<=from{return Err(Failure::Invalid)}
 let lower=slot_millis(from,zero_slot,zero_time)?;let upper=slot_millis(until,zero_slot,zero_time)?;
 let reference=field(intent,"referenceInput")?;utxo(ledger,reference)?;
 let current=if kind=="OpenChannel"{None}else{Some(field(intent,"currentDatum")?)};
 let result=if kind=="OpenChannel"{Some(field(intent,"datum")?)}else{intent.get("resultingDatum").filter(|v|!v.is_null())};
 let channel=if kind=="OpenChannel"{None}else{Some(field(intent,"channelInput")?)};
 let active=current.or(result).ok_or(Failure::Invalid)?;
 let script=reference_script(reference,text(active,"validatorHashHex")?)?;
 if script.hash().to_hex()!=hash{return Err(Failure::Invalid)}
 let check_datum=|d:&Value,target:&str|->Result<(),Failure>{
  datum(d)?;
  if text(d,"validatorHashHex")?!=hash||target!=validator{return Err(Failure::Invalid)}
  let payment=address(target,network)?.payment_cred().and_then(|c|c.to_scripthash()).ok_or(Failure::Invalid)?;
  if payment.to_hex()!=hash{return Err(Failure::Invalid)}
  let key=csl::PublicKey::from_bytes(&bytes(text(field(d,"constants")?,"addVerificationKeyHex")?,32)?).map_err(|_|Failure::Invalid)?;
  if key.hash()!=credential{return Err(Failure::Invalid)}
  Ok(())
 };
 if let Some(channel)=channel{
  utxo(ledger,channel)?;
  if text(channel,"transactionId")?==text(reference,"transactionId")?&&number(channel,"index")?==number(reference,"index")?{return Err(Failure::Invalid)}
  let current=current.ok_or(Failure::Invalid)?;
  check_datum(current,text(channel,"address")?)?;
  if number(channel,"lovelace")?<2_000_000||text(channel,"datumHex")?!=hex::encode(datum(current)?.to_bytes()) ||
      channel.get("scriptRefHex").is_some_and(|v|!v.is_null())||channel.get("scriptRefHashHex").is_some_and(|v|!v.is_null()){return Err(Failure::Invalid)}
  if let Some(next)=result{
   check_datum(next,text(channel,"address")?)?;
   if field(current,"constants")?!=field(next,"constants")?{return Err(Failure::Invalid)}
  }
 }
 if kind=="OpenChannel"{
  let next=result.ok_or(Failure::Invalid)?;check_datum(next,text(intent,"validatorAddress")?)?;
  let stage=field(next,"stage")?;
  if stage_type(stage)?!="Opened"||number(stage,"accountedAmount")?!=0||stage.get("evidenceCborHex").and_then(Value::as_array).is_some_and(|items|!items.is_empty()){return Err(Failure::Invalid)}
 }
 if kind=="OpenChannel"||kind=="AddChannelFunds"{
  let amount=field(intent,"amount")?;let quantity=number(amount,"baseUnits")?;
  if quantity==0{return Err(Failure::Invalid)}
  let selected=checked_asset(field(amount,"asset")?,assets)?;
  let expected=field(field(active,"constants")?,"asset")?;
  if selected!=expected{return Err(Failure::Invalid)}
  let unit=asset_unit(selected)?;
  if kind=="OpenChannel"{
   if unit.is_none()&&quantity<=2_000_000{return Err(Failure::Invalid)}
  }else{
   let channel=channel.ok_or(Failure::Invalid)?;
   if stage_type(field(current.ok_or(Failure::Invalid)?,"stage")?)?!="Opened"||result!=current{return Err(Failure::Invalid)}
   let channel_assets=balances(channel)?;
   if let Some(unit)=unit{
    if channel_assets.len()!=1||channel_assets.get(&unit).filter(|n|**n>0).is_none(){return Err(Failure::Invalid)}
    channel_assets[&unit].checked_add(quantity).filter(|n|*n<=i64::MAX as u64).ok_or(Failure::Invalid)?;
   }else{
    if !channel_assets.is_empty(){return Err(Failure::Invalid)}
    number(channel,"lovelace")?.checked_add(quantity).filter(|n|*n<=i64::MAX as u64).ok_or(Failure::Invalid)?;
   }
  }
 }else{
  let channel=channel.ok_or(Failure::Invalid)?;
  if asset_unit(field(field(active,"constants")?,"asset")?)?.is_some()||
     !balances(channel)?.is_empty()||
     number(intent,"amount")?!=number(channel,"lovelace")?{return Err(Failure::Invalid)}
  let stage=field(active,"stage")?;
  match text(intent,"step")?{
   "CLOSE"=>{
    let next=result.ok_or(Failure::Invalid)?;
    if stage_type(stage)?!="Opened"||stage_type(field(next,"stage")?)?!="Closed"||
       number(stage,"accountedAmount")?!=number(field(next,"stage")?,"accountedAmount")?||
       stage.get("evidenceCborHex").and_then(Value::as_array)!=field(next,"stage")?.get("evidenceCborHex").and_then(Value::as_array){return Err(Failure::Invalid)}
    let elapsed=number(field(next,"stage")?,"elapseAtEpochMillis")?;
    if upper>elapsed.checked_sub(number(field(active,"constants")?,"closePeriodMillis")?).ok_or(Failure::Invalid)?{return Err(Failure::Invalid)}
   },
   "ELAPSE"=>{if result.is_some()||stage_type(stage)?!="Closed"||lower<number(stage,"elapseAtEpochMillis")?{return Err(Failure::Invalid)}},
   "END"=>{if result.is_some()||stage_type(stage)?!="Responded"{return Err(Failure::Invalid)}for item in stage.get("evidenceCborHex").and_then(Value::as_array).into_iter().flatten(){if evidence(item.as_str().ok_or(Failure::Invalid)?,true)?.1.ok_or(Failure::Invalid)?>lower{return Err(Failure::Invalid)}}},
   _=>return Err(Failure::Invalid)
  }
 }
 Ok(())
}

fn tx_input(value:&Value)->Result<csl::TransactionInput,Failure>{
 Ok(csl::TransactionInput::new(&csl::TransactionHash::from_hex(text(value,"transactionId")?).map_err(|_|Failure::Invalid)?,
     u32::try_from(number(value,"index")?).map_err(|_|Failure::Invalid)?))
}
fn balances(value:&Value)->Result<std::collections::BTreeMap<String,u64>,Failure>{
 let mut output=std::collections::BTreeMap::new();
 if value.get("assets").is_some_and(|items|!items.is_object()){return Err(Failure::Invalid)}
 for (unit,quantity) in value.get("assets").and_then(Value::as_object).into_iter().flatten(){
  if unit.len()<56||unit.len()>120||unit.len()%2!=0||!unit.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)){return Err(Failure::Invalid)}
  bytes(&unit[..56],28)?;
  let amount=quantity.as_u64().filter(|n|*n>0&&*n<=i64::MAX as u64).ok_or(Failure::Invalid)?;
  output.insert(unit.clone(),amount);
 }
 Ok(output)
}
fn add_balances(total:&mut std::collections::BTreeMap<String,u64>,incoming:&std::collections::BTreeMap<String,u64>)->Result<(),Failure>{
 for (unit,quantity) in incoming{
  let entry=total.entry(unit.clone()).or_insert(0);
  *entry=entry.checked_add(*quantity).filter(|n|*n<=i64::MAX as u64).ok_or(Failure::Invalid)?;
 }
 Ok(())
}
fn output(address:&csl::Address,coin:u64,units:&std::collections::BTreeMap<String,u64>,inline:Option<&csl::PlutusData>)->Result<csl::TransactionOutput,Failure>{
 let mut value=csl::Value::new(&csl::BigNum::from(coin));
 if !units.is_empty(){
  let mut multi=csl::MultiAsset::new();
  for (unit,quantity) in units{
   let policy=csl::ScriptHash::from_hex(&unit[..56]).map_err(|_|Failure::Invalid)?;
   let name=csl::AssetName::new(hex::decode(&unit[56..]).map_err(|_|Failure::Invalid)?).map_err(|_|Failure::Invalid)?;
   let mut entries=multi.get(&policy).unwrap_or_else(csl::Assets::new);
   entries.insert(&name,&csl::BigNum::from(*quantity));
   multi.insert(&policy,&entries);
  }
  value.set_multiasset(&multi);
 }
 let mut result=csl::TransactionOutput::new(address,&value);
 if let Some(datum)=inline{result.set_plutus_data(datum)}
 Ok(result)
}
fn minimum(output:&csl::TransactionOutput,per_byte:u64)->Result<u64,Failure>{
 (160_u64).checked_add(output.to_bytes().len() as u64).and_then(|size|size.checked_mul(per_byte)).ok_or(Failure::Invalid)
}
fn fixed_minimum(address:&csl::Address,units:&std::collections::BTreeMap<String,u64>,datum:Option<&csl::PlutusData>,per_byte:u64,floor:u64)->Result<u64,Failure>{
 let mut coin=floor;
 for _ in 0..8{
  let exact=minimum(&output(address,coin,units,datum)?,per_byte)?.max(floor);
  if exact==coin{return Ok(coin)}
  coin=exact;
 }
 Err(Failure::Invalid)
}
fn parameters(ledger:&Value)->Result<(csl::LinearFee,u64,usize,Value),Failure>{
 let params:Value=serde_json::from_str(text(ledger,"protocolParametersJson")?).map_err(|_|Failure::Invalid)?;
 let positive=|name:&str|params.get(name).and_then(Value::as_u64).filter(|n|*n>0).ok_or(Failure::Invalid);
 let coins=crate::coins_per_byte(text(ledger,"protocolParametersJson")?.as_bytes())?;
 let linear=csl::LinearFee::new(&csl::BigNum::from(positive("min_fee_a")?),&csl::BigNum::from(positive("min_fee_b")?));
 Ok((linear,coins,usize::try_from(positive("max_tx_size")?).map_err(|_|Failure::Invalid)?,params))
}
fn dummy_witness()->Result<csl::TransactionWitnessSet,Failure>{
 let key=csl::PublicKey::from_bytes(&[0;32]).map_err(|_|Failure::Internal)?;
 let signature=csl::Ed25519Signature::from_bytes(vec![0;64]).map_err(|_|Failure::Internal)?;
 let mut keys=csl::Vkeywitnesses::new();
 keys.add(&csl::Vkeywitness::new(&csl::Vkey::new(&key),&signature));
 let mut witnesses=csl::TransactionWitnessSet::new();witnesses.set_vkeys(&keys);Ok(witnesses)
}
fn decimal(value:&Value,key:&str)->Result<(u128,u128),Failure>{
 let text=value.get(key).and_then(Value::as_str).ok_or(Failure::Invalid)?;
 let (integer,fraction)=text.split_once('.').unwrap_or((text,""));
 if integer.is_empty()||!integer.bytes().all(|b|b.is_ascii_digit())||fraction.len()>18||!fraction.bytes().all(|b|b.is_ascii_digit()){return Err(Failure::Invalid)}
 let factor=10_u128.checked_pow(fraction.len() as u32).ok_or(Failure::Invalid)?;
 let whole=integer.parse::<u128>().map_err(|_|Failure::Invalid)?;
 let part=if fraction.is_empty(){0}else{fraction.parse::<u128>().map_err(|_|Failure::Invalid)?};
 Ok((whole.checked_mul(factor).and_then(|n|n.checked_add(part)).ok_or(Failure::Invalid)?,factor))
}
fn ceil_ratio(amount:u128,numerator:u128,denominator:u128)->Result<u64,Failure>{
 let total=amount.checked_mul(numerator).and_then(|v|v.checked_add(denominator.checked_sub(1)?)).ok_or(Failure::Invalid)?;
 u64::try_from(total/denominator).map_err(|_|Failure::Invalid)
}
fn script_fee(memory:u64,steps:u64,reference_bytes:u64,params:&Value)->Result<u64,Failure>{
 let (mem_n,mem_d)=decimal(params,"price_mem")?;
 let (step_n,step_d)=decimal(params,"price_step")?;
 let execution=ceil_ratio((memory as u128).checked_mul(mem_n).and_then(|v|v.checked_mul(step_d)).and_then(|v|v.checked_add((steps as u128).checked_mul(step_n)?.checked_mul(mem_d)?)).ok_or(Failure::Invalid)?,1,mem_d.checked_mul(step_d).ok_or(Failure::Invalid)?)?;
 let (ref_n,ref_d)=decimal(params,"min_fee_ref_script_cost_per_byte")?;
 if ref_n==0{return Ok(execution)}
 // Cardano's reference-script tiers increase 1.2x per complete 25 KiB.
 let mut remaining=reference_bytes;let mut tier=ref_n;let mut scale=ref_d;let mut fee=execution;
 while remaining!=0{
  let count=remaining.min(25_600);
  fee=fee.checked_add(ceil_ratio(count as u128,tier,scale)?).ok_or(Failure::Invalid)?;
  remaining-=count;
  if remaining!=0{tier=tier.checked_mul(6).ok_or(Failure::Invalid)?;scale=scale.checked_mul(5).ok_or(Failure::Invalid)?}
 }
 Ok(fee)
}
fn script_hash(redeemers:&csl::Redeemers,params:&Value)->Result<csl::ScriptDataHash,Failure>{
 let costs=params.get("cost_models_raw").and_then(|v|v.get("PlutusV3")).and_then(Value::as_array).filter(|v|!v.is_empty()&&v.len()<=1024).ok_or(Failure::Invalid)?;
 let mut model=csl::CostModel::new();
 for (index,value) in costs.iter().enumerate(){
  let item=value.as_i64().ok_or(Failure::Invalid)?;
  let number=csl::Int::from_str(&item.to_string()).map_err(|_|Failure::Invalid)?;
  model.set(index,&number).map_err(|_|Failure::Invalid)?;
 }
 let mut models=csl::Costmdls::new();models.insert(&csl::Language::new_plutus_v3(),&model);
 Ok(csl::hash_script_data(redeemers,&models,None))
}
fn channel_redeemer(kind:&str,step:&str)->csl::PlutusData{
 let pair=match (kind,step){("AddChannelFunds",_)=>constructor(0,[constructor(0,[])]),(_, "CLOSE")=>constructor(0,[constructor(2,[])]),(_,"ELAPSE")=>constructor(1,[constructor(1,[])]),_=>constructor(1,[constructor(0,[])])};
 constructor(1,[array([pair])])
}

fn txid(transaction:&csl::Transaction)->Result<String,Failure>{
 Ok(csl::FixedTransaction::from_bytes(transaction.to_bytes()).map_err(|_|Failure::Invalid)?.transaction_hash().to_hex())
}
fn response(kind:&str,transaction:&csl::Transaction,intent:&Value,fee:u64)->Result<Vec<u8>,Failure>{
 let result=if kind=="evaluate"{json!({"kind":"evaluate","cborHex":hex::encode(transaction.to_bytes())})}
 else{json!({"kind":"complete","cborHex":hex::encode(transaction.to_bytes()),"operationId":text(intent,"operationId")?,"feeBound":fee})};
 serde_json::to_vec(&result).map_err(|_|Failure::Internal)
}
fn complete(request:&Value,transaction:&csl::Transaction,fee:u64)->Result<Vec<u8>,Failure>{
 let intent=field(request,"intent")?;
 let authorization=json!({"intent":intent,"ledger":field(request,"ledger")?,
     "assets":field(request,"assets")?,"operationId":text(intent,"operationId")?,"feeBound":fee});
 crate::authorization::authorize(&transaction.to_bytes(),&serde_json::to_vec(&authorization).map_err(|_|Failure::Internal)?)?;
 response("complete",transaction,intent,fee)
}
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct EvaluationResponse{
 #[serde(rename="transaction_id")]
 transaction_id:String,
 redeemers:Vec<EvaluationRedeemer>,
}
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct EvaluationRedeemer{purpose:String,index:u64,memory:u64,steps:u64}
fn evaluation(value:&[u8],transaction:&csl::Transaction,params:&Value,final_budget:Option<(u64,u64)>)->Result<(u64,u64),Failure>{
 let response:EvaluationResponse=serde_json::from_slice(value).map_err(|_|Failure::Invalid)?;
 if response.transaction_id!=txid(transaction)?||response.redeemers.len()!=1{return Err(Failure::Invalid)}
 let one=&response.redeemers[0];
 let expected=transaction.witness_set().redeemers().ok_or(Failure::Invalid)?;
 if expected.len()!=1{return Err(Failure::Invalid)}
 let r=expected.get(0);
 if one.purpose!="spend"||one.index>i32::MAX as u64||one.index!=u64::from(r.index())||
    one.memory>i64::MAX as u64||one.steps>i64::MAX as u64{return Err(Failure::Invalid)}
 let (memory,steps)=(one.memory,one.steps);
 let max_mem=params.get("max_tx_ex_mem").and_then(Value::as_str).and_then(|v|v.parse::<u64>().ok()).filter(|n|*n>0).ok_or(Failure::Invalid)?;
 let max_steps=params.get("max_tx_ex_steps").and_then(Value::as_str).and_then(|v|v.parse::<u64>().ok()).filter(|n|*n>0).ok_or(Failure::Invalid)?;
 if memory>max_mem||steps>max_steps{return Err(Failure::Invalid)}
 if let Some((allowed_mem,allowed_steps))=final_budget{
  if memory>allowed_mem||steps>allowed_steps{return Err(Failure::Invalid)}
 }
 Ok((memory,steps))
}
pub(crate) struct ChannelBuild{
 request:Value,
 pending:Option<csl::Transaction>,
 fee:u64,
 budget:Option<(u64,u64)>,
 finished:bool,
}
pub(crate) fn begin(request_json:&[u8])->Result<ChannelBuild,Failure>{
 let request:Value=serde_json::from_slice(request_json).map_err(|_|Failure::Invalid)?;
 request_schema(&request)?;
 let intent=field(&request,"intent")?;let ledger=field(&request,"ledger")?;let assets=field(&request,"assets")?;
 validate_intent(intent,ledger,assets)?;
 Ok(ChannelBuild{request,pending:None,fee:0,budget:None,finished:false})
}
pub(crate) fn next(build:&mut ChannelBuild,evaluation_json:&[u8],evaluation_entropy:&[u8])->Result<Vec<u8>,Failure>{
 if build.finished{return Err(Failure::Invalid)}
 let intent=field(&build.request,"intent")?;let ledger=field(&build.request,"ledger")?;
 let kind=text(intent,"type")?.rsplit('.').next().ok_or(Failure::Invalid)?;
 let (_,_,_,params)=parameters(ledger)?;
 if build.pending.is_none(){
  if !evaluation_json.is_empty(){return Err(Failure::Invalid)}
  if kind!="AddChannelFunds"&&!evaluation_entropy.is_empty(){return Err(Failure::Invalid)}
  if kind=="AddChannelFunds"&&evaluation_entropy.len()!=32{return Err(Failure::Invalid)}
  let (tx,fee)=build_candidate(&build.request,None)?;
  build.fee=fee;
  if kind=="OpenChannel"{let result=complete(&build.request,&tx,fee)?;build.finished=true;return Ok(result)}
  let exposed=evaluation_candidate(&tx,intent,ledger,evaluation_entropy)?;
  build.pending=Some(exposed.clone());
  return response("evaluate",&exposed,intent,fee)
 }
 if !evaluation_entropy.is_empty(){return Err(Failure::Invalid)}
 let pending=build.pending.take().ok_or(Failure::Invalid)?;
 let measured=evaluation(evaluation_json,&pending,&params,build.budget)?;
 if build.budget.is_some(){
  let (tx,fee)=build_candidate(&build.request,Some(build.budget.ok_or(Failure::Invalid)?))?;
  if tx.body().to_bytes()!=pending.body().to_bytes()||fee!=build.fee{return Err(Failure::Invalid)}
  let result=complete(&build.request,&tx,fee)?;
  build.finished=true;
  return Ok(result)
 }
 let (tx,fee)=build_candidate(&build.request,Some(measured))?;
 build.budget=Some(measured);build.fee=fee;
 build.pending=Some(tx.clone());
 response("evaluate",&tx,intent,fee)
}
fn evaluation_candidate(transaction:&csl::Transaction,intent:&Value,ledger:&Value,entropy:&[u8])->Result<csl::Transaction,Failure>{
 if text(intent,"type")?.ends_with(".AddChannelFunds"){
  let public=crate::payment_public(entropy)?;
  if hex::encode(public.as_bytes())!=text(field(field(intent,"currentDatum")?,"constants")?,"addVerificationKeyHex")?{return Err(Failure::Invalid)}
  let body=transaction.body();
  let hash=csl::FixedTransaction::from_bytes(transaction.to_bytes()).map_err(|_|Failure::Invalid)?.transaction_hash();
  let signature=csl::Ed25519Signature::from_bytes(crate::sign_payment(entropy,&hash.to_bytes())?).map_err(|_|Failure::Internal)?;
  let mut witnesses=transaction.witness_set();
  let mut keys=csl::Vkeywitnesses::new();
  keys.add(&csl::Vkeywitness::new(&csl::Vkey::new(&public),&signature));
  witnesses.set_vkeys(&keys);
  let signed=csl::Transaction::new(&body,&witnesses,None);
  if signed.body().to_bytes()!=body.to_bytes(){return Err(Failure::Invalid)}
  return Ok(signed)
 }
 let _=ledger;Ok(transaction.clone())
}

fn build_candidate(request:&Value,budget:Option<(u64,u64)>)->Result<(csl::Transaction,u64),Failure>{
 let intent=field(request,"intent")?;let ledger=field(request,"ledger")?;
 validate_intent(intent,ledger,field(request,"assets")?)?;
 let kind=text(intent,"type")?.rsplit('.').next().ok_or(Failure::Invalid)?;
 let (network,_,_,_,_)=network(ledger)?;
 let source=text(intent,"sourceAddress")?;
 let source_address=address(source,network)?;
 let source_cred=source_address.payment_cred().and_then(|c|c.to_keyhash()).ok_or(Failure::Invalid)?;
 let (linear,per_byte,max_size,params)=parameters(ledger)?;
 let channel=if kind=="OpenChannel"{None}else{Some(field(intent,"channelInput")?)};
 let spending=channel.is_some();
 let reference=field(intent,"referenceInput")?;
 let ref_script=reference_script(reference,text(if spending{field(intent,"currentDatum")?}else{field(intent,"datum")?},"validatorHashHex")?)?;
 let quantity=if kind=="CloseChannel"{0}else{number(field(intent,"amount")?,"baseUnits")?};
 let selected=if kind=="CloseChannel"{None}else{asset_unit(field(field(intent,"amount")?,"asset")?)?};
 let native=selected.is_some();
 let all=field(ledger,"utxos")?.as_array().ok_or(Failure::Invalid)?;
 let mut wallet:Vec<_>=all.iter().filter(|u|owned(u,source,native)).collect();
 wallet.sort_by(|a,b|(text(a,"transactionId").unwrap_or_default(),number(a,"index").unwrap_or_default()).cmp(&(text(b,"transactionId").unwrap_or_default(),number(b,"index").unwrap_or_default())));
 if wallet.is_empty(){return Err(Failure::Funds)}
 let mut collateral=Vec::new();
 let mut collateral_coin=0_u64;
 if spending{
  let limit=params.get("max_collateral_inputs").and_then(Value::as_u64).filter(|v|*v>0).ok_or(Failure::Invalid)?;
  let candidates:Vec<_>=wallet.iter().copied().filter(|u|owned(u,source,false)).collect();
  for entry in candidates{
   if collateral_coin>=5_000_000{break}
   if collateral.len() as u64>=limit{break}
   collateral_coin=collateral_coin.checked_add(number(entry,"lovelace")?).ok_or(Failure::Invalid)?;
   collateral.push(entry);
  }
  if collateral_coin<5_000_000{return Err(Failure::Collateral)}
  wallet.retain(|u|!collateral.contains(u));
 }
 let mut inputs:Vec<&Value>=Vec::new();
 if let Some(c)=channel{inputs.push(c)}
 let mut total_ada=channel.map(|c|number(c,"lovelace")).transpose()?.unwrap_or(0);
 let mut total_assets=std::collections::BTreeMap::<String,u64>::new();
 if let Some(c)=channel{add_balances(&mut total_assets,&balances(c)?)?}
 // Deterministically retain every selected source input and every non-selected native unit.
 // Excluding collateral from regular spending is mandatory even if a wallet has no ADA change.
 for entry in wallet{
  inputs.push(entry);
  total_ada=total_ada.checked_add(number(entry,"lovelace")?).filter(|n|*n<=i64::MAX as u64).ok_or(Failure::Invalid)?;
  add_balances(&mut total_assets,&balances(entry)?)?;
 }
 inputs.sort_by(|a,b|{
  let first=tx_input(a).map(|v|v.to_bytes()).unwrap_or_default();
  let second=tx_input(b).map(|v|v.to_bytes()).unwrap_or_default();
  first.len().cmp(&second.len()).then(first.cmp(&second))
 });
 let mut transaction_inputs=csl::TransactionInputs::new();
 for entry in &inputs{transaction_inputs.add(&tx_input(entry)?);}
 let channel_index=channel.map(|c|inputs.iter().position(|entry|*entry==c).ok_or(Failure::Invalid)).transpose()?;
 let mut channel_assets=std::collections::BTreeMap::<String,u64>::new();
 let inline=if kind=="CloseChannel"{intent.get("resultingDatum").filter(|v|!v.is_null())}else if kind=="OpenChannel"{Some(field(intent,"datum")?)}else{Some(field(intent,"resultingDatum")?)};
 let plutus=inline.map(datum).transpose()?;
 let channel_address=if kind=="OpenChannel"{address(text(intent,"validatorAddress")?,network)?}
 else{address(text(channel.ok_or(Failure::Invalid)?,"address")?,network)?};
 let destination=if kind=="CloseChannel"&&inline.is_none(){&source_address}else{&channel_address};
 let recipient_ada=if kind=="OpenChannel"{
  if let Some(unit)=&selected{channel_assets.insert(unit.clone(),quantity);fixed_minimum(destination,&channel_assets,plutus.as_ref(),per_byte,2_000_000)?}else{quantity}
 }else if kind=="AddChannelFunds"{
  let c=channel.ok_or(Failure::Invalid)?;
  if let Some(unit)=&selected{
   let base=balances(c)?.get(unit).copied().ok_or(Failure::Invalid)?;
   channel_assets.insert(unit.clone(),base.checked_add(quantity).ok_or(Failure::Invalid)?);
   fixed_minimum(destination,&channel_assets,plutus.as_ref(),per_byte,number(c,"lovelace")?.max(2_000_000))?
  }else{number(c,"lovelace")?.checked_add(quantity).ok_or(Failure::Invalid)?}
 }else{number(channel.ok_or(Failure::Invalid)?,"lovelace")?};
 for (unit,amount) in &channel_assets{
  let owned=total_assets.get_mut(unit).ok_or(Failure::Funds)?;
  *owned=owned.checked_sub(*amount).ok_or(Failure::Funds)?;
 }
 total_assets.retain(|_,n|*n>0);
 let fixed=output(destination,recipient_ada,&channel_assets,plutus.as_ref())?;
 if minimum(&fixed,per_byte)?>recipient_ada{return Err(Failure::Funds)}
 let mut collateral_inputs=csl::TransactionInputs::new();
 for entry in &collateral{collateral_inputs.add(&tx_input(entry)?);}
 let cost=if spending{
  let (memory,steps)=budget.unwrap_or((0,0));
  let ex=csl::ExUnits::new(&csl::BigNum::from(memory),&csl::BigNum::from(steps));
  let mut redeemers=csl::Redeemers::new();
  redeemers.add(&csl::Redeemer::new(&csl::RedeemerTag::new_spend(),&csl::BigNum::from(channel_index.ok_or(Failure::Invalid)? as u64),&channel_redeemer(kind,if kind=="CloseChannel"{text(intent,"step")?}else{"ADD"}),&ex));
  Some(redeemers)
 }else{None};
 let mut fee=0_u64;
 let mut result=None;
 for _ in 0..12{
  let change=total_ada.checked_sub(recipient_ada).and_then(|v|v.checked_sub(fee)).ok_or(Failure::Funds)?;
  let mut outputs=csl::TransactionOutputs::new();
  outputs.add(&fixed);
  if change>0||!total_assets.is_empty(){
   let wallet_change=output(&source_address,change,&total_assets,None)?;
   if change<minimum(&wallet_change,per_byte)?{return Err(Failure::Funds)}
   outputs.add(&wallet_change);
  }
  let mut body=csl::TransactionBody::new_tx_body(&transaction_inputs,&outputs,&csl::BigNum::from(fee));
  body.set_validity_start_interval_bignum(&csl::BigNum::from(number(intent,"validFrom")?));
  body.set_ttl(&csl::BigNum::from(number(intent,"validUntil")?));
  if let Some(redeemers)=&cost{
   let mut refs=csl::TransactionInputs::new();refs.add(&tx_input(reference)?);body.set_reference_inputs(&refs);
   body.set_collateral(&collateral_inputs);
   let mut signers=csl::Ed25519KeyHashes::new();signers.add(&source_cred);body.set_required_signers(&signers);
   body.set_script_data_hash(&script_hash(redeemers,&params)?);
   let percent=params.get("collateral_percent").and_then(Value::as_u64).filter(|n|*n>0).ok_or(Failure::Invalid)?;
   let taken=ceil_ratio(fee as u128,percent as u128,100)?;
   if taken>collateral_coin{return Err(Failure::Collateral)}
   let returned=collateral_coin-taken;
   body.set_total_collateral(&csl::BigNum::from(taken));
   if returned!=0{
    let collateral_return=output(&source_address,returned,&std::collections::BTreeMap::new(),None)?;
    if returned<minimum(&collateral_return,per_byte)?{return Err(Failure::Collateral)}
    body.set_collateral_return(&collateral_return)
   }
  }
  let mut witnesses=csl::TransactionWitnessSet::new();
  if let Some(redeemers)=&cost{witnesses.set_redeemers(redeemers)}
  let unsigned=csl::Transaction::new(&body,&witnesses,None);
  let mut priced=witnesses.clone();
  let dummy=dummy_witness()?.vkeys().ok_or(Failure::Internal)?;
  priced.set_vkeys(&dummy);
  let signed_size=csl::Transaction::new(&body,&priced,None);
  if signed_size.to_bytes().len()>max_size{return Err(Failure::Invalid)}
  let linear_required:u64=csl::min_fee(&signed_size,&linear).map_err(|_|Failure::Invalid)?.into();
  let (memory,steps)=budget.unwrap_or((0,0));
  let script_required=if spending{script_fee(memory,steps,csl::ScriptRef::new_plutus_script(&ref_script).to_unwrapped_bytes().len() as u64,&params)?}else{0};
  let required=linear_required.checked_add(script_required).ok_or(Failure::Invalid)?;
  if required==fee{result=Some(unsigned);break}
  fee=required;
 }
 let transaction=result.ok_or(Failure::Invalid)?;
 if spending&&transaction.witness_set().redeemers().is_none(){return Err(Failure::Invalid)}
 Ok((transaction,fee))
}

#[cfg(test)]
mod tests{
 use super::*;
 #[test]
 fn datum_and_redeemer_match_android_fixture(){
  let fixture:Value=serde_json::from_str(include_str!("../../../shared/src/androidHostTest/resources/konduit/channel-conformance.json")).unwrap();
  let ada=json!({"alias":"ada","policyId":null,"assetName":null,"decimals":6,"pricing":"ADA","catalogDigest":"a".repeat(64)});
  let assets=serde_json::to_vec(&vec![ada]).unwrap();
  for key in ["datum_opened_empty","datum_opened_used","datum_closed_used","datum_responded_pending"]{
   let encoded=fixture[key].as_str().unwrap();
   let decoded=decode(encoded.as_bytes(),&assets).unwrap();
   let value:Value=serde_json::from_slice(&decoded).unwrap();
   assert_eq!(hex::encode(datum(&value).unwrap().to_bytes()),encoded);
  }
  for (step,key) in [("ADD","redeemer_add"),("CLOSE","redeemer_close"),("ELAPSE","redeemer_elapse"),("END","redeemer_end")]{
   let kind=if step=="ADD"{"AddChannelFunds"}else{"CloseChannel"};
   assert_eq!(hex::encode(channel_redeemer(kind,step).to_bytes()),fixture[key].as_str().unwrap());
  }
 }
}

fn schema(value:&Value,required:&[&str],optional:&[&str])->Result<(),Failure>{
 let object=value.as_object().ok_or(Failure::Invalid)?;
 if required.iter().any(|key|!object.contains_key(*key))||
    object.keys().any(|key|!required.contains(&key.as_str())&&!optional.contains(&key.as_str())){return Err(Failure::Invalid)}
 Ok(())
}
fn datum_schema(value:&Value)->Result<(),Failure>{
 schema(value,&["validatorHashHex","constants","stage"],&[])?;
 let constants=field(value,"constants")?;
 schema(constants,&["tagHex","addVerificationKeyHex","adaptorVerificationKeyHex","closePeriodMillis","asset"],&[])?;
 asset_schema(field(constants,"asset")?)?;
 let stage=field(value,"stage")?;
 match stage_type(stage)?{
  "Opened"|"Responded"=>schema(stage,&["type","accountedAmount"],&["evidenceCborHex"]),
  "Closed"=>schema(stage,&["type","accountedAmount","elapseAtEpochMillis"],&["evidenceCborHex"]),
  _=>Err(Failure::Invalid),
 }
}
fn asset_schema(value:&Value)->Result<(),Failure>{
 schema(value,&["alias","policyId","assetName","decimals","pricing","catalogDigest"],&[])?;
 asset(value)
}
fn utxo_schema(value:&Value)->Result<(),Failure>{
 schema(value,&["transactionId","index","address","lovelace"],
     &["assets","datumHex","datumHashHex","scriptRefHex","scriptRefVersion","scriptRefHashHex"])?;
 bytes(text(value,"transactionId")?,32)?;
 if number(value,"index")?>u32::MAX as u64{return Err(Failure::Invalid)}
 number(value,"lovelace")?;
 balances(value)?;
 for key in ["datumHex","datumHashHex","scriptRefHex","scriptRefHashHex"]{
  if value.get(key).is_some_and(|item|!item.is_null()&&!item.is_string()){return Err(Failure::Invalid)}
 }
 if let Some(datum)=value.get("datumHex").and_then(Value::as_str){hex::decode(datum).map_err(|_|Failure::Invalid)?;}
 if let Some(hash)=value.get("datumHashHex").and_then(Value::as_str){bytes(hash,32)?;}
 if let Some(hash)=value.get("scriptRefHashHex").and_then(Value::as_str){bytes(hash,28)?;}
 if let Some(script)=value.get("scriptRefHex").and_then(Value::as_str){hex::decode(script).map_err(|_|Failure::Invalid)?;}
 if value.get("scriptRefVersion").is_some_and(|v|!v.is_null()){
  if number(value,"scriptRefVersion")?>3||value.get("scriptRefHex").is_none_or(Value::is_null)||
      value.get("scriptRefHashHex").is_none_or(Value::is_null){return Err(Failure::Invalid)}
 }else if value.get("scriptRefHex").is_some_and(|v|!v.is_null())||
     value.get("scriptRefHashHex").is_some_and(|v|!v.is_null()){return Err(Failure::Invalid)}
 Ok(())
}
fn request_schema(request:&Value)->Result<(),Failure>{
 schema(request,&["intent","ledger","assets"],&[])?;
 let ledger=field(request,"ledger")?;
 schema(ledger,&["network","utxos","protocolParametersJson","currentSlot"],&[])?;
 number(ledger,"currentSlot")?;
 let all=field(ledger,"utxos")?.as_array().filter(|items|items.len()<=500).ok_or(Failure::Invalid)?;
 let mut seen=std::collections::HashSet::new();
 for u in all{
  utxo_schema(u)?;
  if !seen.insert((text(u,"transactionId")?,number(u,"index")?)){return Err(Failure::Invalid)}
 }
 let assets=field(request,"assets")?.as_array().filter(|items|items.len()<=100).ok_or(Failure::Invalid)?;
 let mut aliases=std::collections::HashSet::new();
 for entry in assets{
  asset_schema(entry)?;
  if !aliases.insert(text(entry,"alias")?){return Err(Failure::Invalid)}
 }
 let intent=field(request,"intent")?;
 let kind=text(intent,"type")?;
 match kind{
  "io.riverark.ferret.core.cardano.CardanoIntent.OpenChannel"=>{
   schema(intent,&["type","sourceAddress","validatorAddress","referenceInput","datum","amount","operationId","validFrom","validUntil"],&[])?;
   datum_schema(field(intent,"datum")?)?;
  },
  "io.riverark.ferret.core.cardano.CardanoIntent.AddChannelFunds"=>{
   schema(intent,&["type","sourceAddress","channelInput","referenceInput","currentDatum","resultingDatum","amount","operationId","validFrom","validUntil"],&[])?;
   datum_schema(field(intent,"currentDatum")?)?;datum_schema(field(intent,"resultingDatum")?)?;
   utxo_schema(field(intent,"channelInput")?)?;
  },
  "io.riverark.ferret.core.cardano.CardanoIntent.CloseChannel"=>{
   schema(intent,&["type","sourceAddress","channelInput","referenceInput","currentDatum","step","resultingDatum","amount","operationId","validFrom","validUntil"],&[])?;
   datum_schema(field(intent,"currentDatum")?)?;
   if let Some(value)=intent.get("resultingDatum").filter(|v|!v.is_null()){datum_schema(value)?}
   utxo_schema(field(intent,"channelInput")?)?;
  },
  _=>return Err(Failure::Invalid)
 }
 utxo_schema(field(intent,"referenceInput")?)?;
 if !kind.ends_with(".CloseChannel"){
  let amount=field(intent,"amount")?;
  schema(amount,&["asset","baseUnits"],&[])?;
  asset_schema(field(amount,"asset")?)?;
 }
 Ok(())
}
