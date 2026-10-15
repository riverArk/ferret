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
 let bytes=hex::decode(encoded).map_err(|_|Failure::Invalid)?;
 if csl::PlutusScript::from_bytes_v3(bytes.clone()).map_err(|_|Failure::Invalid)?.to_bytes()!=bytes{return Err(Failure::Invalid)}
 let script=csl::PlutusScript::new_v3(bytes);
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
fn pack_open_change(address:&csl::Address,residual_ada:u64,assets:&std::collections::BTreeMap<String,u64>,coins_per_byte:u64,max_value_size:usize)->Result<Vec<csl::TransactionOutput>,Failure>{
 let mut buckets=Vec::new();
 let mut bucket=std::collections::BTreeMap::new();
 for (unit,quantity) in assets.iter().filter(|(_,quantity)|**quantity>0){
  bucket.insert(unit.clone(),*quantity);
  // Reserve the largest coin encoding any bucket can receive, not its minimum ADA.
  if output(address,residual_ada,&bucket,None)?.amount().to_bytes().len()>max_value_size{
   bucket.remove(unit);
   if bucket.is_empty(){return Err(Failure::Invalid)}
   buckets.push(std::mem::take(&mut bucket));
   bucket.insert(unit.clone(),*quantity);
   if output(address,residual_ada,&bucket,None)?.amount().to_bytes().len()>max_value_size{return Err(Failure::Invalid)}
  }
 }
 if !bucket.is_empty(){buckets.push(bucket);}
 if buckets.is_empty(){
  if residual_ada==0{return Ok(Vec::new())}
  buckets.push(std::collections::BTreeMap::new());
 }
 let mut minima=Vec::with_capacity(buckets.len());
 let mut total_minimum=0_u64;
 for bucket in &buckets{
  let coin=fixed_minimum(address,bucket,None,coins_per_byte,0)?;
  total_minimum=total_minimum.checked_add(coin).ok_or(Failure::Invalid)?;
  minima.push(coin);
 }
 let extra=residual_ada.checked_sub(total_minimum).ok_or(Failure::Funds)?;
 let last=minima.last_mut().ok_or(Failure::Internal)?;
 *last=last.checked_add(extra).ok_or(Failure::Invalid)?;
 let mut outputs=Vec::with_capacity(buckets.len());
 for (bucket,coin) in buckets.iter().zip(minima){
  let change=output(address,coin,bucket,None)?;
  if change.amount().to_bytes().len()>max_value_size{return Err(Failure::Invalid)}
  if coin<minimum(&change,coins_per_byte)?{return Err(Failure::Funds)}
  outputs.push(change);
 }
 Ok(outputs)
}
pub(crate) fn open_value_limit(params:&Value)->Result<usize,Failure>{
 let value=params.get("max_val_size").and_then(Value::as_str).ok_or(Failure::Invalid)?;
 if value.is_empty()||value.starts_with('0')||!value.bytes().all(|b|b.is_ascii_digit()){return Err(Failure::Invalid)}
 let limit=value.parse::<i32>().map_err(|_|Failure::Invalid)?;
 if limit<=0{return Err(Failure::Invalid)}
 Ok(limit as usize)
}
fn build_open(request:&Value)->Result<(csl::Transaction,u64),Failure>{
 let intent=field(request,"intent")?;let ledger=field(request,"ledger")?;
 let (linear,per_byte,max_size,params)=parameters(ledger)?;
 let limit=open_value_limit(&params)?;
 let (network,_,_,_,_)=network(ledger)?;
 let source=text(intent,"sourceAddress")?;
 let source_address=address(source,network)?;
 let selected=asset_unit(field(field(intent,"amount")?,"asset")?)?;
 let quantity=number(field(intent,"amount")?,"baseUnits")?;
 let mut candidates:Vec<_>=field(ledger,"utxos")?.as_array().ok_or(Failure::Invalid)?.iter().filter(|u|owned(u,source,true)).collect();
 let reference_order=|a:&&Value,b:&&Value|(text(a,"transactionId").unwrap_or_default(),number(a,"index").unwrap_or_default()).cmp(&(text(b,"transactionId").unwrap_or_default(),number(b,"index").unwrap_or_default()));
 candidates.sort_by(|a,b|number(b,"lovelace").unwrap_or_default().cmp(&number(a,"lovelace").unwrap_or_default()).then_with(||reference_order(a,b)));
 let mut selected_inputs=Vec::new();
 if let Some(unit)=&selected{
  let selected_quantity=|u:&Value|u.get("assets").and_then(|a|a.get(unit)).and_then(Value::as_u64).unwrap_or(0);
  // Stable sorting preserves the ADA/reference tie order established above.
  let mut token_candidates:Vec<_>=candidates.iter().copied().filter_map(|u|{let n=selected_quantity(u);(n>0).then_some((n,u))}).collect();
  token_candidates.sort_by(|a,b|b.0.cmp(&a.0));
  let mut covered=0_u64;
  for (amount,entry) in token_candidates{
   if covered>=quantity{break}
   covered=covered.checked_add(amount).filter(|n|*n<=i64::MAX as u64).ok_or(Failure::Invalid)?;
   selected_inputs.push(entry);
  }
  if covered<quantity{return Err(Failure::Funds)}
 }else{selected_inputs.push(*candidates.first().ok_or(Failure::Funds)?);}
 candidates.retain(|u|!selected_inputs.contains(u));
 let channel_address=address(text(intent,"validatorAddress")?,network)?;
 let inline=datum(field(intent,"datum")?)?;
 let channel_assets=selected.as_ref().map(|u|std::collections::BTreeMap::from([(u.clone(),quantity)])).unwrap_or_default();
 let channel_ada=if selected.is_some(){fixed_minimum(&channel_address,&channel_assets,Some(&inline),per_byte,2_000_000)?}else{quantity};
 let fixed=output(&channel_address,channel_ada,&channel_assets,Some(&inline))?;
 if channel_ada<minimum(&fixed,per_byte)?{return Err(Failure::Funds)}
 if fixed.amount().to_bytes().len()>limit{return Err(Failure::Invalid)}
 loop{
  let mut ada=0_u64;let mut assets=std::collections::BTreeMap::new();
  for input in &selected_inputs{
   ada=ada.checked_add(number(input,"lovelace")?).filter(|n|*n<=i64::MAX as u64).ok_or(Failure::Invalid)?;
   add_balances(&mut assets,&balances(input)?)?;
  }
  for (unit,amount) in &channel_assets{
   let balance=assets.get_mut(unit).ok_or(Failure::Funds)?;
   *balance=balance.checked_sub(*amount).ok_or(Failure::Funds)?;
  }
  assets.retain(|_,n|*n>0);
  let mut sorted=selected_inputs.clone();
  sorted.sort_by(|a,b|{
   let a=tx_input(a).map(|v|v.to_bytes()).unwrap_or_default();let b=tx_input(b).map(|v|v.to_bytes()).unwrap_or_default();
   a.len().cmp(&b.len()).then(a.cmp(&b))
  });
  let mut inputs=csl::TransactionInputs::new();
  for entry in sorted{inputs.add(&tx_input(entry)?);}
  let mut fee=0_u64;
  let mut needs_funds=false;
  for _ in 0..12{
   let Some(change)=ada.checked_sub(channel_ada).and_then(|v|v.checked_sub(fee))else{needs_funds=true;break};
   let changes=match pack_open_change(&source_address,change,&assets,per_byte,limit){
    Ok(value)=>value,Err(Failure::Funds)=>{
     // Dust is never donated to fees. An exactly funded no-change vector is
     // valid only when its entire residual equals its actual witness-priced fee.
     if assets.is_empty(){
      let exact_fee=ada.checked_sub(channel_ada).ok_or(Failure::Funds)?;
      let mut outputs=csl::TransactionOutputs::new();outputs.add(&fixed);
      let mut body=csl::TransactionBody::new_tx_body(&inputs,&outputs,&csl::BigNum::from(exact_fee));
      body.set_validity_start_interval_bignum(&csl::BigNum::from(number(intent,"validFrom")?));
      body.set_ttl(&csl::BigNum::from(number(intent,"validUntil")?));
      let priced=csl::Transaction::new(&body,&dummy_witness()?,None);
      let required:u64=csl::min_fee(&priced,&linear).map_err(|_|Failure::Invalid)?.into();
      if exact_fee==required&&priced.to_bytes().len()<=max_size{
       return Ok((csl::Transaction::new(&body,&csl::TransactionWitnessSet::new(),None),exact_fee))
      }
     }
     needs_funds=true;break
    },Err(error)=>return Err(error)
   };
   let mut outputs=csl::TransactionOutputs::new();outputs.add(&fixed);
   for change in changes{outputs.add(&change);}
   let mut body=csl::TransactionBody::new_tx_body(&inputs,&outputs,&csl::BigNum::from(fee));
   body.set_validity_start_interval_bignum(&csl::BigNum::from(number(intent,"validFrom")?));
   body.set_ttl(&csl::BigNum::from(number(intent,"validUntil")?));
   let priced=csl::Transaction::new(&body,&dummy_witness()?,None);
   if priced.to_bytes().len()>max_size{return Err(Failure::Invalid)}
   let required:u64=csl::min_fee(&priced,&linear).map_err(|_|Failure::Invalid)?.into();
   if fee>=required{return Ok((csl::Transaction::new(&body,&csl::TransactionWitnessSet::new(),None),fee))}
   fee=fee.max(required);
  }
  if !needs_funds{return Err(Failure::Invalid)}
  if candidates.is_empty(){return Err(Failure::Funds)}
  selected_inputs.push(candidates.remove(0));
 }
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
 if kind=="OpenChannel"{return build_open(request)}
 let (network,_,_,_,_)=network(ledger)?;
 let source=text(intent,"sourceAddress")?;
 let source_address=address(source,network)?;
 let source_cred=source_address.payment_cred().and_then(|c|c.to_keyhash()).ok_or(Failure::Invalid)?;
 let (linear,per_byte,max_size,params)=parameters(ledger)?;
 let channel=Some(field(intent,"channelInput")?);
 let spending=channel.is_some();
 let reference=field(intent,"referenceInput")?;
 let ref_script=reference_script(reference,text(field(intent,"currentDatum")?,"validatorHashHex")?)?;
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
 let inline=if kind=="CloseChannel"{intent.get("resultingDatum").filter(|v|!v.is_null())}else{Some(field(intent,"resultingDatum")?)};
 let plutus=inline.map(datum).transpose()?;
 let channel_address=address(text(channel.ok_or(Failure::Invalid)?,"address")?,network)?;
 let destination=if kind=="CloseChannel"&&inline.is_none(){&source_address}else{&channel_address};
 let recipient_ada=if kind=="AddChannelFunds"{
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
  if kind=="CloseChannel"&&inline.is_none(){
   outputs.add(&output(&source_address,total_ada.checked_sub(fee).ok_or(Failure::Funds)?,&total_assets,None)?);
  }else{
   outputs.add(&fixed);
   if change>0||!total_assets.is_empty(){
    let wallet_change=output(&source_address,change,&total_assets,None)?;
    if change<minimum(&wallet_change,per_byte)?{return Err(Failure::Funds)}
    outputs.add(&wallet_change);
   }
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
 fn change_address()->csl::Address{
  let source:Value=serde_json::from_slice(&crate::derived(&(0..32).collect::<Vec<u8>>(),1).unwrap()).unwrap();
  csl::Address::from_bech32(source["paymentAddress"].as_str().unwrap()).unwrap()
 }
 fn change_units(change:&csl::TransactionOutput)->std::collections::BTreeMap<String,u64>{
  let mut units=std::collections::BTreeMap::new();
  if let Some(multi)=change.amount().multiasset(){
   let policies=multi.keys();
   for i in 0..policies.len(){
    let policy=policies.get(i);
    let entries=multi.get(&policy).unwrap();
    let names=entries.keys();
    for j in 0..names.len(){
     let name=names.get(j);
     units.insert(format!("{}{}",policy.to_hex(),hex::encode(name.name())),entries.get(&name).unwrap().into());
    }
   }
  }
  units
 }
 #[test]
 fn open_change_packs_160_units_greedily_and_preserves_every_quantity(){
  let address=change_address();
  let assets:std::collections::BTreeMap<String,u64>=(0..160).map(|i|(format!("{}{:064x}","6".repeat(56),i),1)).collect();
  let ada=120_000_000;
  let per_byte=4310;
  assert!(output(&address,ada,&assets,None).unwrap().amount().to_bytes().len()>5000);
  for start in [0,80]{
   let input_assets=assets.iter().skip(start).take(80).map(|(unit,quantity)|(unit.clone(),*quantity)).collect();
   assert!(output(&address,60_000_000,&input_assets,None).unwrap().amount().to_bytes().len()<=5000);
  }
  let changes=pack_open_change(&address,ada,&assets,per_byte,5000).unwrap();
  assert!(changes.len()>=2);
  let mut total_ada=0_u64;
  let mut returned=std::collections::BTreeMap::new();
  let mut ordered_units=Vec::new();
  let mut minima=0_u64;
  for (index,change) in changes.iter().enumerate(){
   let coin:u64=change.amount().coin().into();
   let units=change_units(change);
   let required=fixed_minimum(&address,&units,None,per_byte,0).unwrap();
   minima=minima.checked_add(required).unwrap();
   assert_eq!(change.address().to_bytes(),address.to_bytes());
   assert!(change.plutus_data().is_none());
   assert!(change.data_hash().is_none());
   assert!(change.script_ref().is_none());
   assert!(change.amount().to_bytes().len()<=5000);
   assert!(coin>=minimum(change,per_byte).unwrap());
   if index+1<changes.len(){
    assert_eq!(coin,required);
    let mut candidate=units.clone();
    let next=change_units(&changes[index+1]).into_iter().next().unwrap();
    candidate.insert(next.0,next.1);
    assert!(output(&address,ada,&candidate,None).unwrap().amount().to_bytes().len()>5000);
   }
   total_ada=total_ada.checked_add(coin).unwrap();
   ordered_units.extend(units.keys().cloned());
   for (unit,quantity) in units{assert!(returned.insert(unit,quantity).is_none());}
  }
  assert_eq!(total_ada,ada);
  assert_eq!(returned,assets);
  assert_eq!(ordered_units,assets.keys().cloned().collect::<Vec<_>>());
  let last=changes.last().unwrap();
  let last_coin:u64=last.amount().coin().into();
  assert_eq!(last_coin,fixed_minimum(&address,&change_units(last),None,per_byte,0).unwrap()+ada-minima);
  assert!(matches!(pack_open_change(&address,minima-1,&assets,per_byte,5000),Err(Failure::Funds)));
  let repeated=pack_open_change(&address,ada,&assets,per_byte,5000).unwrap();
  assert_eq!(changes.iter().map(|change|change.to_bytes()).collect::<Vec<_>>(),repeated.iter().map(|change|change.to_bytes()).collect::<Vec<_>>());
 }
 #[test]
 fn open_change_reserves_coin_width_at_two_to_the_32(){
  let address=change_address();
  let assets:std::collections::BTreeMap<String,u64>=(0..2).map(|i|(format!("{}{:064x}","6".repeat(56),i),7)).collect();
  let below=(1_u64<<32)-1;
  let limit=output(&address,below,&assets,None).unwrap().amount().to_bytes().len();
  assert_eq!(output(&address,1_u64<<32,&assets,None).unwrap().amount().to_bytes().len(),limit+4);
  for ada in [below,1_u64<<32,(1_u64<<32)+1]{
   let changes=pack_open_change(&address,ada,&assets,4310,limit).unwrap();
   assert_eq!(changes.len(),if ada==below{1}else{2});
   let mut total=0_u64;
   let mut returned=std::collections::BTreeMap::new();
   for change in changes{
    let coin:u64=change.amount().coin().into();
    total+=coin;
    assert!(change.amount().to_bytes().len()<=limit);
    assert!(coin>=minimum(&change,4310).unwrap());
    returned.extend(change_units(&change));
   }
   assert_eq!(total,ada);
   assert_eq!(returned,assets);
  }
 }
 #[test]
 fn open_change_handles_empty_plain_ada_and_singleton_limits(){
  let address=change_address();
  let empty=std::collections::BTreeMap::new();
  assert!(pack_open_change(&address,0,&empty,4310,5000).unwrap().is_empty());
  let required=fixed_minimum(&address,&empty,None,4310,0).unwrap();
  for ada in [required,required+1,1_u64<<32]{
   let changes=pack_open_change(&address,ada,&empty,4310,5000).unwrap();
   assert_eq!(changes.len(),1);
   let coin:u64=changes[0].amount().coin().into();
   assert_eq!(coin,ada);
   assert!(change_units(&changes[0]).is_empty());
   assert!(coin>=minimum(&changes[0],4310).unwrap());
  }
  assert!(matches!(pack_open_change(&address,required-1,&empty,4310,5000),Err(Failure::Funds)));
  assert!(matches!(pack_open_change(&address,required,&empty,4310,0),Err(Failure::Invalid)));
  let assets=std::collections::BTreeMap::from([(format!("{}{}","4".repeat(56),"ff".repeat(32)),11)]);
  let ada=10_000_000;
  let limit=output(&address,ada,&assets,None).unwrap().amount().to_bytes().len();
  let changes=pack_open_change(&address,ada,&assets,4310,limit).unwrap();
  assert_eq!(changes.len(),1);
  assert_eq!(change_units(&changes[0]),assets);
  assert!(matches!(pack_open_change(&address,ada,&assets,4310,limit-1),Err(Failure::Invalid)));
  assert!(matches!(pack_open_change(&address,0,&assets,4310,limit),Err(Failure::Funds)));
  assert!(matches!(pack_open_change(&address,ada,&assets,u64::MAX,limit),Err(Failure::Invalid)));
  let zero_assets=std::collections::BTreeMap::from([(format!("{}","5".repeat(56)),0)]);
  assert!(pack_open_change(&address,0,&zero_assets,4310,5000).unwrap().is_empty());
 }
 fn reviewed_assets()->Vec<Value>{
  let mut assets=vec![json!({"alias":"ada","policyId":null,"assetName":null,"decimals":6,"pricing":"ADA","catalogDigest":"a".repeat(64)})];
  for (alias,policy,name) in [
   ("usda","fe7c786ab321f41c654ef6c1af7b3250a613c24e4213e0425a7ae456","55534441"),
   ("usdcx","1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e34","5553444378"),
   ("usdm","c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad","0014df105553444d")
  ]{assets.push(json!({"alias":alias,"policyId":policy,"assetName":name,"decimals":6,"pricing":"USD_PEG","catalogDigest":"a".repeat(64)}));}
  assets
 }
 fn mixed_request(selected:usize,quantity:u64,coins:&[u64],maps:&[std::collections::BTreeMap<String,u64>])->Value{
  let fixture:Value=serde_json::from_str(include_str!("../../../shared/src/androidHostTest/resources/konduit/channel-conformance.json")).unwrap();
  let entropy:Vec<u8>=(0..32).collect();
  let derived:Value=serde_json::from_slice(&crate::derived(&entropy,1).unwrap()).unwrap();
  let source=derived["paymentAddress"].as_str().unwrap();
  let assets=reviewed_assets();
  let mut inline:Value=serde_json::from_slice(&decode(fixture["datum_opened_empty"].as_str().unwrap().as_bytes(),&serde_json::to_vec(&assets).unwrap()).unwrap()).unwrap();
  inline["constants"]["asset"]=assets[selected].clone();
  inline["constants"]["addVerificationKeyHex"]=json!(hex::encode(crate::payment_public(&entropy).unwrap().as_bytes()));
  let validator="addr1wxcrrmk4g6ta93942evluyw6c2ffy2xanpl6lc43tyzvupqswlfa5";
  let reference=json!({"transactionId":"11".repeat(32),"index":0,"address":validator,"lovelace":2_000_000,
   "scriptRefHex":fixture["reference_script"],"scriptRefVersion":3,"scriptRefHashHex":fixture["validator_hash"]});
  let mut utxos=vec![reference.clone()];
  for (index,coin) in coins.iter().enumerate(){utxos.push(json!({"transactionId":format!("{:064x}",index+32),"index":0,"address":source,"lovelace":coin,"assets":maps[index]}));}
  json!({"intent":{"type":"io.riverark.ferret.core.cardano.CardanoIntent.OpenChannel","sourceAddress":source,
   "validatorAddress":validator,"referenceInput":reference,"datum":inline,"amount":{"asset":assets[selected],"baseUnits":quantity},
   "operationId":"00000000-0000-4000-8000-000000000018","validFrom":4_492_800,"validUntil":4_492_900},
   "assets":assets,"ledger":{"network":"MAINNET","utxos":utxos,"currentSlot":4_492_800,"protocolParametersJson":fixture["protocol_parameters_fixture"].to_string()}})
 }
 fn auth_json(request:&Value,fee:u64)->Vec<u8>{
  serde_json::to_vec(&json!({"intent":request["intent"],"ledger":request["ledger"],"assets":request["assets"],
   "operationId":request["intent"]["operationId"],"feeBound":fee})).unwrap()
 }
 fn completed_open(request:&Value)->(csl::Transaction,u64){
  let mut session=begin(&serde_json::to_vec(request).unwrap()).unwrap();
  let result:Value=serde_json::from_slice(&next(&mut session,&[],&[]).unwrap()).unwrap();
  assert_eq!(result["kind"],"complete");
  let tx=csl::Transaction::from_bytes(hex::decode(result["cborHex"].as_str().unwrap()).unwrap()).unwrap();
  let fee=result["feeBound"].as_u64().unwrap();
  let report:Value=serde_json::from_slice(&crate::authorization::inspect(&tx.to_bytes()).unwrap()).unwrap();
  let mut ada=0;let mut tokens=std::collections::BTreeMap::new();
  for input in report["inputs"].as_array().unwrap(){
   let utxo=request["ledger"]["utxos"].as_array().unwrap().iter().find(|u|u["transactionId"]==input["transactionId"]&&u["index"]==input["index"]).unwrap();
   assert!(owned(utxo,request["intent"]["sourceAddress"].as_str().unwrap(),true));
   ada+=number(utxo,"lovelace").unwrap();add_balances(&mut tokens,&balances(utxo).unwrap()).unwrap();
  }
  let mut paid=fee;let mut returned=std::collections::BTreeMap::new();
  let expected=asset_unit(&request["intent"]["amount"]["asset"]).unwrap().map(|u|std::collections::BTreeMap::from([(u,number(&request["intent"]["amount"],"baseUnits").unwrap())])).unwrap_or_default();
  assert_eq!(change_units(&tx.body().outputs().get(0)),expected);
  assert_eq!(tx.body().outputs().get(0).plutus_data().unwrap().to_bytes(),datum(&request["intent"]["datum"]).unwrap().to_bytes());
  if expected.is_empty(){assert_eq!(u64::from(tx.body().outputs().get(0).amount().coin()),number(&request["intent"]["amount"],"baseUnits").unwrap());}
  for i in 0..tx.body().outputs().len(){
   let out=tx.body().outputs().get(i);let coin:u64=out.amount().coin().into();
   paid+=coin;add_balances(&mut returned,&change_units(&out)).unwrap();
   assert!(coin>=minimum(&out,4310).unwrap());assert!(out.amount().to_bytes().len()<=5000);
   if i>0{assert_eq!(out.address().to_bech32(None).unwrap(),request["intent"]["sourceAddress"].as_str().unwrap());assert!(out.plutus_data().is_none());assert!(out.data_hash().is_none());assert!(out.script_ref().is_none());}
  }
  assert_eq!(ada,paid);assert_eq!(tokens,returned);
  assert!(tx.body().mint().is_none());assert!(tx.body().collateral().is_none());assert!(tx.witness_set().redeemers().is_none());
  let signed=crate::authorization::sign(&tx.to_bytes(),&(0..32).collect::<Vec<u8>>(),&auth_json(request,fee)).unwrap();
  assert!(signed.len()<=16384);
  let signed_tx=csl::Transaction::from_bytes(signed.clone()).unwrap();assert_eq!(txid(&tx).unwrap(),txid(&signed_tx).unwrap());
  crate::authorization::authorize_signed(&signed,&auth_json(request,fee)).unwrap();
  (tx,fee)
 }
 fn unknowns()->std::collections::BTreeMap<String,u64>{
  std::collections::BTreeMap::from([("4".repeat(56),7),(format!("{}{}","4".repeat(56),"ff".repeat(32)),11),(format!("{}5553444d","5".repeat(56)),13)])
 }
 #[test]
 fn open_every_reviewed_subset_and_unknown_only_preserves_exact_units(){
  let assets=reviewed_assets();
  for subset in 0..8{for unknown in [false,true]{
   let mut units=if unknown{unknowns()}else{Default::default()};
   for i in 1..4{if subset&(1<<(i-1))!=0{units.insert(asset_unit(&assets[i]).unwrap().unwrap(),10_000_000);}}
   for selected in 0..4{
    if selected>0&&subset&(1<<(selected-1))==0{continue}
    completed_open(&mixed_request(selected,if selected==0{5_000_000}else{1_250_000},&[100_000_000],&[units.clone()]));
   }
  }}
 }
 #[test]
 fn open_multiple_inputs_exact_deposits_and_ledger_order(){
  let assets=reviewed_assets();let mut maps=vec![std::collections::BTreeMap::new();3];
  for map in &mut maps{for asset in &assets[1..]{map.insert(asset_unit(asset).unwrap().unwrap(),4_000_000);}}
  for (i,(unit,n)) in unknowns().into_iter().enumerate(){maps[i].insert(unit,n);}maps[1].insert("4".repeat(56),3);
  for selected in 0..4{
   for quantity in if selected==0{vec![90_000_000]}else{vec![9_000_000,12_000_000]}{
    let request=mixed_request(selected,quantity,&[40_000_000;3],&maps);
    let (tx,_)=completed_open(&request);assert_eq!(tx.body().inputs().len(),3);
    if selected>0&&quantity==12_000_000{let unit=asset_unit(&assets[selected]).unwrap().unwrap();for i in 1..tx.body().outputs().len(){assert!(!change_units(&tx.body().outputs().get(i)).contains_key(&unit));}}
    let mut reversed=request.clone();reversed["ledger"]["utxos"].as_array_mut().unwrap().reverse();
    let (reverse_tx,_)=completed_open(&reversed);assert_eq!(tx.to_bytes(),reverse_tx.to_bytes());
   }
   if selected>0{assert!(build_candidate(&mixed_request(selected,12_000_001,&[40_000_000;3],&maps),None).is_err());}
  }
 }
 fn multi_change_request(selected:usize)->Value{
  let assets=reviewed_assets();let mut maps=vec![std::collections::BTreeMap::new();2];
  for i in 0..2{
   if selected>0{maps[i].insert(asset_unit(&assets[selected]).unwrap().unwrap(),750_000);}
   for n in i*80..(i+1)*80{maps[i].insert(format!("{}{:064x}","6".repeat(56),n),1);}
  }
  mixed_request(selected,if selected==0{90_000_000}else{1_250_000},&[60_000_000;2],&maps)
 }
 #[test]
 fn open_real_multi_change_for_every_selected_asset(){
  for selected in 0..4{
   let request=multi_change_request(selected);let (tx,_)=completed_open(&request);assert!(tx.body().outputs().len()>=3);
   for utxo in request["ledger"]["utxos"].as_array().unwrap().iter().skip(1){
    assert!(output(&address(utxo["address"].as_str().unwrap(),1).unwrap(),number(utxo,"lovelace").unwrap(),&balances(utxo).unwrap(),None).unwrap().amount().to_bytes().len()<=5000);
   }
  }
 }
 fn replace_outputs(tx:&csl::Transaction,outputs:&[csl::TransactionOutput])->csl::Transaction{
  let mut list=csl::TransactionOutputs::new();for out in outputs{list.add(out);}
  let old=tx.body();let mut body=csl::TransactionBody::new_tx_body(&old.inputs(),&list,&old.fee());
  body.set_validity_start_interval_bignum(&old.validity_start_interval_bignum().unwrap());body.set_ttl(&old.ttl_bignum().unwrap());
  csl::Transaction::new(&body,&csl::TransactionWitnessSet::new(),None)
 }
 fn locally_signed(tx:&csl::Transaction)->Vec<u8>{
  let entropy:Vec<u8>=(0..32).collect();let public=crate::payment_public(&entropy).unwrap();
  let fixed=csl::FixedTransaction::from_bytes(tx.to_bytes()).unwrap();
  let signature=csl::Ed25519Signature::from_bytes(crate::sign_payment(&entropy,&fixed.transaction_hash().to_bytes()).unwrap()).unwrap();
  let mut keys=csl::Vkeywitnesses::new();keys.add(&csl::Vkeywitness::new(&csl::Vkey::new(&public),&signature));
  let mut witnesses=csl::TransactionWitnessSet::new();witnesses.set_vkeys(&keys);
  csl::Transaction::new(&tx.body(),&witnesses,None).to_bytes()
 }
 fn rejects_both(tx:&csl::Transaction,request:&Value,fee:u64){
  let auth=auth_json(request,fee);
  assert!(crate::authorization::authorize(&tx.to_bytes(),&auth).is_err());
  // Sign the altered body itself: rejection cannot be explained by a stale
  // transaction identifier, signature, or preview metadata.
  assert!(crate::authorization::authorize_signed(&locally_signed(tx),&auth).is_err());
 }
 fn update_params(request:&mut Value,f:impl FnOnce(&mut Value)){
  let mut params:Value=serde_json::from_str(request["ledger"]["protocolParametersJson"].as_str().unwrap()).unwrap();
  f(&mut params);request["ledger"]["protocolParametersJson"]=json!(params.to_string());
 }
 #[test]
 fn open_multi_change_authorization_rejects_value_and_output_attacks(){
  let mut request=multi_change_request(3);
  let unselected=asset_unit(&reviewed_assets()[1]).unwrap().unwrap();
  request["ledger"]["utxos"][1]["assets"][&unselected]=json!(10);
  let decremented_unit=format!("{}{:064x}","6".repeat(56),0);
  request["ledger"]["utxos"][1]["assets"][&decremented_unit]=json!(2);
  let (tx,fee)=completed_open(&request);
  let originals:Vec<_>=(0..tx.body().outputs().len()).map(|i|tx.body().outputs().get(i)).collect();
  let unknown=change_units(&originals[1]).keys().find(|u|u.starts_with(&"6".repeat(56))).unwrap().clone();
  let source=originals[1].address();
  for attack in 0..9{
   let mut outs=originals.clone();
   let mut units=change_units(&outs[1]);let coin:u64=outs[1].amount().coin().into();
   match attack{
    0=>{units.remove(&unknown);outs[1]=output(&source,coin,&units,None).unwrap();},
    1=>{let unit=decremented_unit.clone();let i=(1..outs.len()).find(|i|change_units(&outs[*i]).contains_key(&unit)).unwrap();let mut units=change_units(&outs[i]);units.insert(unit,1);outs[i]=output(&source,outs[i].amount().coin().into(),&units,None).unwrap();},
    2=>{let i=(1..outs.len()).find(|i|change_units(&outs[*i]).contains_key(&unselected)).unwrap();let mut units=change_units(&outs[i]);units.insert(unselected.clone(),9);outs[i]=output(&source,outs[i].amount().coin().into(),&units,None).unwrap();},
    3=>{let mut units=change_units(&outs[2]);units.insert(unknown.clone(),1);outs[2]=output(&source,outs[2].amount().coin().into(),&units,None).unwrap();},
    4=>{outs[2]=output(&outs[0].address(),outs[2].amount().coin().into(),&change_units(&outs[2]),None).unwrap();},
    5=>{outs[2].set_plutus_data(&csl::PlutusData::new_integer(&csl::BigInt::from_str("0").unwrap()));},
    6=>{let script=reference_script(&request["intent"]["referenceInput"],request["intent"]["datum"]["validatorHashHex"].as_str().unwrap()).unwrap();outs[2].set_script_ref(&csl::ScriptRef::new_plutus_script(&script));},
    7=>{let mut units=change_units(&outs[0]);units.insert(unknown.clone(),1);outs[0]=output(&outs[0].address(),outs[0].amount().coin().into(),&units,outs[0].plutus_data().as_ref()).unwrap();},
    _=>{let coin:u64=outs[2].amount().coin().into();outs[2]=output(&source,1,&change_units(&outs[2]),None).unwrap();outs[1]=output(&source,u64::from(outs[1].amount().coin())+coin-1,&change_units(&outs[1]),None).unwrap();}
   }
   rejects_both(&replace_outputs(&tx,&outs),&request,fee);
  }
  // A coherent transaction with one large Value, well below the transaction
  // byte bound, must still fail its independent per-Value ledger limit.
  let mut all=std::collections::BTreeMap::new();let mut coin=0;
  for out in &originals[1..]{add_balances(&mut all,&change_units(out)).unwrap();coin+=u64::from(out.amount().coin());}
  let merged=output(&source,coin,&all,None).unwrap();assert!(merged.amount().to_bytes().len()>5000);
  let oversized=replace_outputs(&tx,&[originals[0].clone(),merged]);assert!(locally_signed(&oversized).len()<16384);
  rejects_both(&oversized,&request,fee);
 }
 #[test]
 fn open_live_value_parameters_are_strict_at_every_boundary(){
  let request=multi_change_request(3);let (tx,fee)=completed_open(&request);
  for value in [None,Some(json!(5000)),Some(Value::Null),Some(json!("0")),Some(json!("-1")),Some(json!("5.0")),Some(json!("05000")),Some(json!("2147483648")),Some(json!("")),Some(json!("+5000"))]{
   let mut bad=request.clone();update_params(&mut bad,|p|{if let Some(v)=value{p["max_val_size"]=v;}else{p.as_object_mut().unwrap().remove("max_val_size");}});
   assert!(build_candidate(&bad,None).is_err());rejects_both(&tx,&bad,fee);
  }
  let mut lowered=request.clone();update_params(&mut lowered,|p|p["max_val_size"]=json!("1000"));rejects_both(&tx,&lowered,fee);
  let mut tx_limit=request.clone();update_params(&mut tx_limit,|p|p["max_tx_size"]=json!(1000));
  assert!(build_candidate(&tx_limit,None).is_err());rejects_both(&tx,&tx_limit,fee);
  assert_eq!(open_value_limit(&json!({"max_val_size":"2147483647"})).unwrap(),i32::MAX as usize);
 }
 #[test]
 fn open_funding_boundaries_and_exact_no_change(){
  let maps=vec![unknowns()];
  assert!(build_candidate(&mixed_request(1,1,&[100_000_000],&maps),None).is_err());
  let mut units=unknowns();units.insert(asset_unit(&reviewed_assets()[3]).unwrap().unwrap(),10_000_000);
  assert!(build_candidate(&mixed_request(0,5_000_000,&[5_200_000],&[units]),None).is_err());
  let plain=mixed_request(0,5_000_000,&[100_000_000],&[Default::default()]);
  let (tx,_)=completed_open(&plain);assert_eq!(tx.body().outputs().len(),2);
  // Price the actual one-output vector, including its final fee encoding width.
  let mut fee=0;
  for _ in 0..12{
   let old=tx.body();let mut outputs=csl::TransactionOutputs::new();outputs.add(&old.outputs().get(0));
   let mut body=csl::TransactionBody::new_tx_body(&old.inputs(),&outputs,&csl::BigNum::from(fee));
   body.set_validity_start_interval_bignum(&old.validity_start_interval_bignum().unwrap());body.set_ttl(&old.ttl_bignum().unwrap());
   let priced=csl::Transaction::new(&body,&dummy_witness().unwrap(),None);
   let linear=parameters(&plain["ledger"]).unwrap().0;
   let required:u64=csl::min_fee(&priced,&linear).unwrap().into();if required==fee{break}fee=required;
  }
  let exact=mixed_request(0,5_000_000,&[5_000_000+fee],&[Default::default()]);
  let (tx,actual)=completed_open(&exact);assert_eq!(actual,fee);assert_eq!(tx.body().outputs().len(),1);
  let dust=mixed_request(0,5_000_000,&[5_000_001+fee],&[Default::default()]);assert!(build_candidate(&dust,None).is_err());
  for coin in [(1_u64<<32)-1,1_u64<<32,(1_u64<<32)+1]{completed_open(&mixed_request(0,5_000_000,&[coin],&[unknowns()]));}
  // Additional ADA is selected only when the token-bearing input cannot fund
  // the channel and all residual token minima.
  let mut token=std::collections::BTreeMap::new();token.insert(asset_unit(&reviewed_assets()[3]).unwrap().unwrap(),2_000_000);
  let extended=mixed_request(3,1_250_000,&[2_000_000,10_000_000],&[token,unknowns()]);
  assert_eq!(completed_open(&extended).0.body().inputs().len(),2);
  let mut singleton=plain.clone();
  singleton["ledger"]["utxos"][1]["assets"]=json!({format!("{}{}","4".repeat(56),"ff".repeat(32)):1});
  update_params(&mut singleton,|p|p["max_val_size"]=json!("1"));assert!(build_candidate(&singleton,None).is_err());
  for field in ["datumHex","datumHashHex","scriptRefHashHex"]{
   let mut bad=plain.clone();bad["ledger"]["utxos"][1][field]=json!("00".repeat(32));assert!(build_candidate(&bad,None).is_err());
  }
  let mut foreign=plain.clone();foreign["ledger"]["utxos"][1]["address"]=foreign["intent"]["validatorAddress"].clone();assert!(build_candidate(&foreign,None).is_err());
 }
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

 #[test]
 fn synthetic_channel_transactions_preserve_reviewed_value_and_script_policy(){
  let fixture:Value=serde_json::from_str(include_str!("../../../shared/src/androidHostTest/resources/konduit/channel-conformance.json")).unwrap();
  let entropy:Vec<u8>=(0..32).collect();
  let source:Value=serde_json::from_slice(&crate::derived(&entropy,1).unwrap()).unwrap();
  let source=source["paymentAddress"].as_str().unwrap();
  let public=hex::encode(crate::payment_public(&entropy).unwrap().as_bytes());
  let ada=json!({"alias":"ada","policyId":null,"assetName":null,"decimals":6,"pricing":"ADA","catalogDigest":"a".repeat(64)});
  let assets=json!([ada]);
  let datum_for=|name:&str|{
   let mut value:Value=serde_json::from_slice(&decode(fixture[name].as_str().unwrap().as_bytes(),&serde_json::to_vec(&assets).unwrap()).unwrap()).unwrap();
   value["constants"]["addVerificationKeyHex"]=json!(public);
   value
  };
  let opened=datum_for("datum_opened_empty");
  let used=datum_for("datum_opened_used");
  let closed=datum_for("datum_closed_used");
  let responded=datum_for("datum_responded_pending");
  let validator="addr1wxcrrmk4g6ta93942evluyw6c2ffy2xanpl6lc43tyzvupqswlfa5";
  let reference=json!({"transactionId":"11".repeat(32),"index":0,"address":validator,"lovelace":2_000_000,
   "scriptRefHex":fixture["reference_script"],"scriptRefVersion":3,"scriptRefHashHex":fixture["validator_hash"]});
  let collateral=json!({"transactionId":"00".repeat(32),"index":0,"address":source,"lovelace":5_000_000});
  let funding=json!({"transactionId":"33".repeat(32),"index":0,"address":source,"lovelace":100_000_000});
  let channel=|value:&Value|json!({"transactionId":"22".repeat(32),"index":0,"address":validator,"lovelace":5_000_000,
   "datumHex":hex::encode(datum(value).unwrap().to_bytes())});
  let request=|intent:Value,channel_input:Option<Value>,slot:u64|{
   let mut utxos=vec![reference.clone(),collateral.clone(),funding.clone()];
   if let Some(input)=channel_input{utxos.push(input);}
   json!({"intent":intent,"assets":assets,"ledger":{"network":"MAINNET","utxos":utxos,
    "protocolParametersJson":fixture["protocol_parameters_fixture"].to_string(),"currentSlot":slot}})
  };
  let base=|kind:&str,from:u64|json!({"type":format!("io.riverark.ferret.core.cardano.CardanoIntent.{kind}"),
   "sourceAddress":source,"referenceInput":reference,"operationId":"00000000-0000-4000-8000-000000000018",
   "validFrom":from,"validUntil":from+100});
  let identity=|value:&Value|json!({"transactionId":value["transactionId"],"index":value["index"]});
  let inspect=|cbor:&str|->Value{
   serde_json::from_slice(&crate::authorization::inspect(&hex::decode(cbor).unwrap()).unwrap()).unwrap()
  };

  let from=4_492_800;
  let mut open=base("OpenChannel",from);
  open["validatorAddress"]=json!(validator);
  open["datum"]=opened.clone();
  open["amount"]=json!({"asset":ada,"baseUnits":5_000_000});
  let mut build=begin(&serde_json::to_vec(&request(open,None,from)).unwrap()).unwrap();
  let complete:Value=serde_json::from_slice(&next(&mut build,&[],&[]).unwrap()).unwrap();
  assert_eq!(complete["kind"],"complete");
  let summary=inspect(complete["cborHex"].as_str().unwrap());
  let consumed=|summary:&Value,utxos:&Value|summary["inputs"].as_array().unwrap().iter().map(|input|
   utxos.as_array().unwrap().iter().find(|u|identity(u)==*input).unwrap()["lovelace"].as_u64().unwrap()).sum::<u64>();
  let open_inputs=json!([collateral,funding]);
  let open_total=consumed(&summary,&open_inputs);
  assert_eq!(summary["referenceInputs"],json!([]));
  assert_eq!(summary["collateralInputs"],json!([]));
  assert_eq!(summary["outputs"][0]["address"],validator);
  assert_eq!(summary["outputs"][0]["lovelace"],5_000_000);
  assert_eq!(summary["outputs"][0]["datum"]["cborHex"],hex::encode(datum(&opened).unwrap().to_bytes()));
  assert_eq!(summary["outputs"][1]["address"],source);
  assert_eq!(summary["outputs"].as_array().unwrap().iter().map(|out|out["lovelace"].as_u64().unwrap()).sum::<u64>()+summary["fee"].as_u64().unwrap(),open_total);
  assert_eq!(summary["validityStart"],from);
  assert_eq!(summary["validityEnd"],from+100);
  assert_eq!(complete["feeBound"],summary["fee"]);
  assert!(summary["scriptDataHashHex"].is_null());
  assert!(matches!(next(&mut build,&[],&[]),Err(Failure::Invalid)));

  let token=json!({"alias":"usda","policyId":"fe7c786ab321f41c654ef6c1af7b3250a613c24e4213e0425a7ae456",
   "assetName":"55534441","decimals":6,"pricing":"USD_PEG","catalogDigest":"a".repeat(64)});
  let selected=format!("{}{}",token["policyId"].as_str().unwrap(),token["assetName"].as_str().unwrap());
  let other=format!("{}{}",token["policyId"].as_str().unwrap(),"ff");
  let mut token_datum=opened.clone();
  token_datum["constants"]["asset"]=token.clone();
  let mut token_open=base("OpenChannel",from);
  token_open["validatorAddress"]=json!(validator);
  token_open["datum"]=token_datum.clone();
  token_open["amount"]=json!({"asset":token,"baseUnits":40});
  let mut token_request=request(token_open,None,from);
  token_request["assets"]=json!([ada,token]);
  let mut balances=serde_json::Map::new();
  balances.insert(selected.clone(),json!(90));
  balances.insert(other.clone(),json!(7));
  token_request["ledger"]["utxos"][2]["assets"]=Value::Object(balances);
  let mut token_build=begin(&serde_json::to_vec(&token_request).unwrap()).unwrap();
  let token_result:Value=serde_json::from_slice(&next(&mut token_build,&[],&[]).unwrap()).unwrap();
  let token_summary=inspect(token_result["cborHex"].as_str().unwrap());
  assert_eq!(token_summary["outputs"][0]["assets"][selected.as_str()],40);
  assert_eq!(token_summary["outputs"][1]["assets"][selected.as_str()],50);
  assert_eq!(token_summary["outputs"][1]["assets"][other.as_str()],7);
  assert_eq!(token_summary["outputs"][0]["datum"]["cborHex"],hex::encode(datum(&token_datum).unwrap().to_bytes()));
  assert_eq!(token_summary["outputs"][0]["lovelace"].as_u64().unwrap()+
   token_summary["outputs"][1]["lovelace"].as_u64().unwrap()+
   token_summary["fee"].as_u64().unwrap(),consumed(&token_summary,&token_request["ledger"]["utxos"]));

  for (step,current,result,slot) in [
   ("ADD",&opened,Some(&opened),from),
   ("CLOSE",&used,Some(&closed),from),
   ("ELAPSE",&closed,None,108_433_709),
   ("END",&responded,None,108_433_710),
  ]{
   let mut intent=base(if step=="ADD"{"AddChannelFunds"}else{"CloseChannel"},slot);
   let spent=channel(current);
   intent["channelInput"]=spent.clone();
   intent["currentDatum"]=current.clone();
   intent["resultingDatum"]=result.cloned().unwrap_or(Value::Null);
   if step=="ADD"{intent["amount"]=json!({"asset":ada,"baseUnits":1_000_000});}
   else{intent["step"]=json!(step);intent["amount"]=json!(5_000_000);}
   if step=="ADD"{
    let mut bad=request(intent.clone(),Some(spent.clone()),slot);
    bad["intent"]["referenceInput"]["scriptRefHashHex"]=json!("00".repeat(28));
    assert!(matches!(begin(&serde_json::to_vec(&bad).unwrap()),Err(Failure::Invalid)));
    bad=request(intent.clone(),Some(spent.clone()),slot);
    bad["intent"]["amount"]["asset"]["catalogDigest"]=json!("b".repeat(64));
    assert!(matches!(begin(&serde_json::to_vec(&bad).unwrap()),Err(Failure::Invalid)));
   }
   let mut build=begin(&serde_json::to_vec(&request(intent,Some(spent.clone()),slot)).unwrap()).unwrap();
   let exposed:Value=serde_json::from_slice(&next(&mut build,&[],if step=="ADD"{&entropy}else{&[]}).unwrap()).unwrap();
   assert_eq!(exposed["kind"],"evaluate");
   let candidate=inspect(exposed["cborHex"].as_str().unwrap());
   assert_eq!(candidate["referenceInputs"],json!([identity(&reference)]));
   assert_eq!(candidate["collateralInputs"],json!([identity(&collateral)]));
   assert_eq!(candidate["inputs"],json!([identity(&spent),identity(&funding)]));
   assert_eq!(candidate["outputs"][0]["address"],if result.is_some(){validator}else{source});
   assert_eq!(candidate["outputs"][0]["datum"],result.map_or(json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Absent"}),|value|
    json!({"type":"io.riverark.ferret.core.cardano.TransactionDatum.Inline","cborHex":hex::encode(datum(value).unwrap().to_bytes())})));
   if result.is_some(){
    assert_eq!(candidate["outputs"][0]["lovelace"],if step=="ADD"{6_000_000}else{5_000_000});
    assert_eq!(candidate["outputs"][1]["address"],source);
   }else{assert_eq!(candidate["outputs"].as_array().unwrap().len(),1);}
   assert_eq!(candidate["validityStart"],slot);
   assert_eq!(candidate["validityEnd"],slot+100);
   assert_eq!(candidate["redeemers"][0]["purpose"],"SPEND");
   assert_eq!(candidate["redeemers"][0]["dataCborHex"],fixture[format!("redeemer_{}",step.to_lowercase())]);
   assert_eq!(candidate["redeemers"][0]["index"],0);
   assert_eq!(candidate["redeemers"].as_array().unwrap().len(),1);
   assert_eq!(candidate["totalCollateral"].as_u64().unwrap()+candidate["collateralReturn"]["lovelace"].as_u64().unwrap(),5_000_000);
   assert_eq!(candidate["outputs"].as_array().unwrap().iter().map(|out|out["lovelace"].as_u64().unwrap()).sum::<u64>()+
    candidate["fee"].as_u64().unwrap(),105_000_000);
   assert!(candidate["scriptDataHashHex"].as_str().is_some_and(|v|v.len()==64));
   assert_eq!(candidate["keyWitnesses"].as_array().unwrap().len(),usize::from(step=="ADD"));

   let respond=|summary:&Value,memory:u64|serde_json::to_vec(&json!({"transaction_id":
    crate::channel::txid(&csl::Transaction::from_bytes(hex::decode(exposed["cborHex"].as_str().unwrap()).unwrap()).unwrap()).unwrap(),
    "redeemers":[{"purpose":"spend","index":summary["redeemers"][0]["index"],"memory":memory,"steps":10_000_000}]})).unwrap();
   let wrong=json!({"transaction_id":"ff".repeat(32),"redeemers":[{"purpose":"spend","index":0,"memory":10_000,"steps":10_000_000}]});
   let tx=csl::Transaction::from_bytes(hex::decode(exposed["cborHex"].as_str().unwrap()).unwrap()).unwrap();
   let params=fixture["protocol_parameters_fixture"].clone();
   assert!(evaluation(&serde_json::to_vec(&wrong).unwrap(),&tx,&params,None).is_err());
   let mut extra:Value=serde_json::from_slice(&respond(&candidate,10_000)).unwrap();
   let duplicate=extra["redeemers"][0].clone();
   extra["redeemers"].as_array_mut().unwrap().push(duplicate);
   assert!(evaluation(&serde_json::to_vec(&extra).unwrap(),&tx,&params,None).is_err());
   extra["redeemers"]=json!([{"purpose":"mint","index":0,"memory":10_000,"steps":10_000_000}]);
   assert!(evaluation(&serde_json::to_vec(&extra).unwrap(),&tx,&params,None).is_err());
   let next_result:Value=serde_json::from_slice(&next(&mut build,&respond(&candidate,10_000),&[]).unwrap()).unwrap();
   assert_eq!(next_result["kind"],"evaluate");
   let final_candidate=inspect(next_result["cborHex"].as_str().unwrap());
   assert_eq!(final_candidate["redeemers"][0]["memory"],10_000);
   assert_eq!(final_candidate["redeemers"][0]["steps"],10_000_000);
   assert!(final_candidate["fee"].as_u64().unwrap()>candidate["fee"].as_u64().unwrap());
   if result.is_some(){assert_eq!(final_candidate["outputs"][0],candidate["outputs"][0]);}
   else{assert_eq!(final_candidate["outputs"][0]["lovelace"].as_u64().unwrap()+
    final_candidate["fee"].as_u64().unwrap(),105_000_000);}
   assert_eq!(final_candidate["totalCollateral"].as_u64().unwrap()+
    final_candidate["collateralReturn"]["lovelace"].as_u64().unwrap(),5_000_000);
   let final_tx=csl::Transaction::from_bytes(hex::decode(next_result["cborHex"].as_str().unwrap()).unwrap()).unwrap();
   let final_reply=json!({"transaction_id":txid(&final_tx).unwrap(),
    "redeemers":[{"purpose":"spend","index":final_candidate["redeemers"][0]["index"],"memory":10_000,"steps":10_000_000}]});
   assert!(evaluation(&serde_json::to_vec(&final_reply).unwrap(),&final_tx,&params,Some((9_999,10_000_000))).is_err());
   let completed:Value=serde_json::from_slice(&next(&mut build,&serde_json::to_vec(&final_reply).unwrap(),&[]).unwrap()).unwrap();
   assert_eq!(completed["kind"],"complete");
   assert_eq!(completed["cborHex"],next_result["cborHex"]);
   assert_eq!(completed["feeBound"],final_candidate["fee"]);
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
