package io.riverark.ferret.core.cardano

import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.SimpleValue
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
import co.nstant.`in`.cbor.model.UnsignedInteger
import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.address.Address
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.model.EvaluationResult
import com.bloxbean.cardano.client.api.model.Result
import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.api.common.OrderEnum
import com.bloxbean.cardano.client.api.model.Amount
import com.bloxbean.cardano.client.api.model.ProtocolParams
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.crypto.Blake2bUtil
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData
import com.bloxbean.cardano.client.plutus.spec.PlutusData
import com.bloxbean.cardano.client.plutus.spec.PlutusScript
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder
import com.bloxbean.cardano.client.quicktx.ScriptTx
import com.bloxbean.cardano.client.quicktx.Tx
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput
import com.bloxbean.cardano.client.transaction.util.TransactionUtil
import com.bloxbean.cardano.client.util.HexUtil
import com.fasterxml.jackson.databind.ObjectMapper
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.network.EvaluationResponse
import java.math.BigInteger
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import java.util.Optional

class AndroidCardanoTransactionEngine(
    private val transactionProcessor: TransactionProcessor,
) : CardanoTransactionEngine {
    private val objectMapper = ObjectMapper()

    override suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork) =
        deriveAndroidWallet(entropy, network)

    override suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot): UnsignedTransaction {
        require(ledger.network.addressMatches(intent.sourceAddress))
        when (intent) {
            is CardanoIntent.Transfer -> require(ledger.network.addressMatches(intent.destinationAddress))
            is CardanoIntent.SweepWallet -> require(ledger.network.addressMatches(intent.destinationAddress))
            is CardanoIntent.OpenChannel -> require(ledger.network.addressMatches(intent.validatorAddress))
            is CardanoIntent.AddChannelFunds -> require(ledger.network.addressMatches(intent.channelInput.address))
            is CardanoIntent.CloseChannel -> require(ledger.network.addressMatches(intent.channelInput.address))
        }
        require(intent.validFrom >= ledger.currentSlot && intent.validUntil > intent.validFrom)
        requireChannelSemantics(intent)
        val (params, coinsPerUtxoByte) = parseProtocolParameters(ledger.protocolParametersJson)
        val utxos = ledger.utxos.filter { it.isSpendableBy(intent.sourceAddress) }.map(::toBloxbean)
        val supplier = SnapshotUtxoSupplier(utxos)
        val builder = QuickTxBuilder(supplier, ProtocolParamsSupplier { params }, transactionProcessor)
        val transaction = when (intent) {
            is CardanoIntent.Transfer -> builder.compose(
                Tx().payToAddress(intent.destinationAddress, intent.amount.amount()).from(intent.sourceAddress),
            ).validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.SweepWallet -> builder.compose(
                Tx().payToAddress(intent.destinationAddress, intent.amount.amount()).from(intent.sourceAddress),
            ).validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.OpenChannel -> builder.compose(
                ScriptTx()
                    .readFrom(toBloxbean(intent.referenceInput))
                    .payToContract(intent.validatorAddress, intent.amount.amount(), intent.datum.plutus())
                    .withChangeAddress(intent.sourceAddress),
            ).feePayer(intent.sourceAddress)
                .validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.AddChannelFunds -> builder.compose(
                ScriptTx()
                    .readFrom(toBloxbean(intent.referenceInput))
                    .collectFrom(toBloxbean(intent.channelInput), ChannelRedeemer.ADD.plutus())
                    .payToContract(intent.channelInput.address, Amount.lovelace(BigInteger.valueOf(Math.addExact(intent.channelInput.lovelace.value, intent.amount.value))), intent.resultingDatum.plutus())
                    .withChangeAddress(intent.sourceAddress),
            ).feePayer(intent.sourceAddress).collateralPayer(intent.sourceAddress)
                .validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.CloseChannel -> {
                val script = ScriptTx()
                    .readFrom(toBloxbean(intent.referenceInput))
                    .collectFrom(toBloxbean(intent.channelInput), intent.step.redeemer().plutus())
                    .withChangeAddress(intent.sourceAddress)
                if (intent.resultingDatum == null) {
                    script.payToAddress(intent.sourceAddress, intent.amount.amount())
                } else {
                    script.payToContract(intent.channelInput.address, intent.amount.amount(), intent.resultingDatum.plutus())
                }
                builder.compose(script).feePayer(intent.sourceAddress).collateralPayer(intent.sourceAddress)
                    .validFrom(intent.validFrom).validTo(intent.validUntil).build()
            }
        }
        val fee = Lovelace(transaction.body.fee.longValueExact())
        val cbor = transaction.serialize()
        requireMinimumAda(cbor, coinsPerUtxoByte)
        if (intent is CardanoIntent.Transfer || intent is CardanoIntent.SweepWallet) {
            val summary = inspect(cbor)
            summary.requireMatches(intent, ledger.network, fee)
            summary.requireL1Funding(intent, ledger)
        }
        return UnsignedTransaction(cbor, intent.operationId, fee)
    }

    override fun requireMinimumAda(cbor: ByteArray, protocolParametersJson: String) {
        val (_, coinsPerUtxoByte) = parseProtocolParameters(protocolParametersJson)
        requireMinimumAda(cbor, coinsPerUtxoByte)
    }

    private fun parseProtocolParameters(json: String): Pair<ProtocolParams, Long> {
        val tree = try {
            objectMapper.readTree(json)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid coins_per_utxo_size")
        }
        val cost = tree?.get("coins_per_utxo_size")
            ?.takeIf { it.isTextual }
            ?.textValue()
            ?.takeIf { COINS_PER_UTXO_SIZE.matches(it) }
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("invalid coins_per_utxo_size")
        val params = try {
            objectMapper.treeToValue(tree, ProtocolParams::class.java)
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid protocol parameters")
        }
        return params to cost
    }

    private fun requireMinimumAda(cbor: ByteArray, coinsPerUtxoByte: Long) {
        val sufficient = try {
            val decoded = CborDecoder(ByteArrayInputStream(cbor)).decode()
            require(decoded.size == 1)
            val envelope = decoded.single() as? CborArray ?: error("invalid envelope")
            require(envelope.dataItems.size == 4)
            val body = envelope.dataItems[0] as? CborMap ?: error("invalid body")
            require(CborSerializationUtil.serialize(envelope).contentEquals(cbor))
            val outputs = (body[UnsignedInteger(1)] as? CborArray)?.dataItems
                ?.takeIf { it.isNotEmpty() }
                ?: error("invalid outputs")
            val checked = outputs + listOfNotNull(body[UnsignedInteger(16)])
            checked.all { output ->
                val encodedSize = CborSerializationUtil.serialize(output).size.toLong()
                val minimum = Math.multiplyExact(Math.addExact(160L, encodedSize), coinsPerUtxoByte)
                TransactionOutput.deserialize(output).value.coin.longValueExact() >= minimum
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid transaction CBOR")
        }
        require(sufficient) { "output below minimum ADA" }
    }

    private fun requireChannelSemantics(intent: CardanoIntent) {
        val channelIntent = intent as? CardanoIntent.OpenChannel
        val current = when (intent) {
            is CardanoIntent.AddChannelFunds -> intent.currentDatum
            is CardanoIntent.CloseChannel -> intent.currentDatum
            else -> null
        }
        val resulting = when (intent) {
            is CardanoIntent.OpenChannel -> intent.datum
            is CardanoIntent.AddChannelFunds -> intent.resultingDatum
            is CardanoIntent.CloseChannel -> intent.resultingDatum
            else -> null
        }
        if (channelIntent != null) {
            require(channelIntent.referenceInput.scriptRefHex != null)
            require(channelIntent.referenceInput.scriptRefVersion in 1..3)
            require(channelIntent.referenceInput.scriptRefHashHex == channelIntent.datum.validatorHashHex)
            require(channelIntent.referenceInput.scriptRefHex.hashHex() == channelIntent.datum.validatorHashHex)
            require(channelIntent.datum.stage == ChannelDatumStage.Opened(0))
        }
        if (current != null) {
            val channelInput = when (intent) {
                is CardanoIntent.AddChannelFunds -> intent.channelInput
                is CardanoIntent.CloseChannel -> intent.channelInput
                else -> error("unreachable")
            }
            val referenceInput = when (intent) {
                is CardanoIntent.AddChannelFunds -> intent.referenceInput
                is CardanoIntent.CloseChannel -> intent.referenceInput
                else -> error("unreachable")
            }
            require(channelInput !== referenceInput)
            require(channelInput.datumHex == current.plutus().serializeToHex())
            require(referenceInput.scriptRefHex != null && referenceInput.scriptRefVersion in 1..3)
            require(referenceInput.scriptRefHashHex == current.validatorHashHex)
            require(referenceInput.scriptRefHex.hashHex() == current.validatorHashHex)
            require(resulting == null || resulting.constants == current.constants && resulting.validatorHashHex == current.validatorHashHex)
        }
        when (intent) {
            is CardanoIntent.AddChannelFunds -> {
                require(intent.amount.value > 0)
                require(intent.currentDatum.stage is ChannelDatumStage.Opened)
                require(intent.resultingDatum.stage == intent.currentDatum.stage)
            }
            is CardanoIntent.CloseChannel -> when (intent.step) {
                CloseChannelStep.CLOSE -> {
                    val opened = intent.currentDatum.stage as? ChannelDatumStage.Opened
                        ?: error("Close requires an opened channel")
                    val closed = intent.resultingDatum?.stage as? ChannelDatumStage.Closed
                        ?: error("Close must retain the channel")
                    require(closed.accountedAmount == opened.accountedAmount)
                    require(closed.evidenceCborHex == opened.evidenceCborHex)
                }
                CloseChannelStep.ELAPSE -> {
                    require(intent.currentDatum.stage is ChannelDatumStage.Closed)
                    require(intent.resultingDatum == null)
                }
                CloseChannelStep.END -> {
                    require(intent.currentDatum.stage is ChannelDatumStage.Responded)
                    require(intent.resultingDatum == null)
                }
            }
            else -> Unit
        }
    }
    override fun sign(unsigned: UnsignedTransaction, seed: ByteArray): SignedTransaction {
        require(seed.size == 32)
        val mnemonic = MnemonicCode.INSTANCE.toMnemonic(seed).joinToString(" ")
        val account = Account.createFromMnemonic(inferNetwork(Transaction.deserialize(unsigned.cbor)), mnemonic)
        return SignedTransaction(account.sign(Transaction.deserialize(unsigned.cbor)).serialize())
    }

    override fun inspect(signedCbor: ByteArray): TransactionSummary {
        val envelope = requireNotNull(
            CborDecoder(ByteArrayInputStream(signedCbor)).decodeNext() as? CborArray,
        ) { "transaction envelope must be an array" }
        require(envelope.dataItems.size == 4)
        val rawBody = requireNotNull(envelope.dataItems[0] as? CborMap) { "transaction body must be a map" }
        val rawWitnesses = requireNotNull(envelope.dataItems[1] as? CborMap) { "transaction witnesses must be a map" }
        require(envelope.dataItems[2] == SimpleValue.TRUE)
        require(rawBody.keys.all { key ->
            key is UnsignedInteger && key.value.longValueExact() in SUPPORTED_BODY_KEYS
        })

        val transaction = Transaction.deserialize(signedCbor)
        val body = transaction.body
        val network = inferNetwork(transaction).let {
            if (it.networkId == Networks.mainnet().networkId) CardanoNetwork.MAINNET else CardanoNetwork.PREPROD
        }
        rawBody[UnsignedInteger(15)]?.let { encoded ->
            require(encoded is UnsignedInteger)
            require(encoded.value.longValueExact() == if (network == CardanoNetwork.MAINNET) 1L else 0L)
        }
        val bodyHash = HexUtil.decodeHexString(TransactionUtil.getTxHash(signedCbor))
        val signingProvider = EdDSASigningProvider()
        return TransactionSummary(
            network = network,
            outputs = body.outputs.map(::summarize),
            fee = Lovelace(body.fee.longValueExact()),
            requiredSigners = body.requiredSigners.map(HexUtil::encodeHexString).toSet(),
            validityStart = body.validityStartInterval.takeIf { rawBody[UnsignedInteger(8)] != null },
            validityEnd = body.ttl.takeIf { rawBody[UnsignedInteger(3)] != null },
            inputs = body.inputs.map(::reference),
            referenceInputs = body.referenceInputs.map(::reference),
            collateralInputs = body.collateral.map(::reference),
            collateralReturn = body.collateralReturn?.let(::summarize),
            totalCollateral = body.totalCollateral?.let { Lovelace(it.longValueExact()) },
            keyWitnesses = transaction.witnessSet?.vkeyWitnesses.orEmpty().map { witness ->
                TransactionKeyWitness(
                    verificationKeyHex = HexUtil.encodeHexString(witness.vkey),
                    keyHashHex = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(witness.vkey)),
                    signatureHex = HexUtil.encodeHexString(witness.signature),
                    signatureValid = signingProvider.verify(witness.signature, bodyHash, witness.vkey),
                )
            },
            redeemers = transaction.witnessSet?.redeemers.orEmpty().map { redeemer ->
                TransactionRedeemer(
                    purpose = redeemer.tag.name.uppercase(),
                    index = redeemer.index.longValueExact(),
                    dataCborHex = redeemer.data.serializeToHex(),
                    memory = redeemer.exUnits.mem.longValueExact(),
                    steps = redeemer.exUnits.steps.longValueExact(),
                )
            },
            scriptDataHashHex = body.scriptDataHash?.let(HexUtil::encodeHexString),
            prohibitedBodyFields = buildSet {
                if (rawBody[UnsignedInteger(9)] != null) add(ProhibitedBodyField.MINT)
                if (rawBody[UnsignedInteger(4)] != null) add(ProhibitedBodyField.CERTIFICATES)
                if (rawBody[UnsignedInteger(5)] != null) add(ProhibitedBodyField.WITHDRAWALS)
                if (rawBody[UnsignedInteger(6)] != null) add(ProhibitedBodyField.UPDATE)
                if (rawBody[UnsignedInteger(19)] != null || rawBody[UnsignedInteger(20)] != null) add(ProhibitedBodyField.GOVERNANCE)
                if (rawBody[UnsignedInteger(7)] != null || envelope.dataItems[3] != SimpleValue.NULL) add(ProhibitedBodyField.AUXILIARY_DATA)
                if (rawBody[UnsignedInteger(21)] != null) add(ProhibitedBodyField.TREASURY)
                if (rawBody[UnsignedInteger(22)] != null) add(ProhibitedBodyField.DONATION)
            },
            containsNonKeyWitnesses = rawWitnesses.keys.any { it != UnsignedInteger(0) },
        )
    }

    private fun reference(input: com.bloxbean.cardano.client.transaction.spec.TransactionInput) =
        TransactionInputReference(input.transactionId, input.index)

    private fun summarize(output: com.bloxbean.cardano.client.transaction.spec.TransactionOutput) =
        TransactionOutputSummary(
            address = output.address,
            lovelace = Lovelace(output.value.coin.longValueExact()),
            assets = output.value.multiAssets.orEmpty().flatMap { multi ->
                multi.assets.map { asset -> "${multi.policyId}${asset.name}" to asset.value.longValueExact() }
            }.toMap(),
            datum = when {
                output.inlineDatum != null -> TransactionDatum.Inline(output.inlineDatum.serializeToHex())
                output.datumHash != null -> TransactionDatum.Hash(HexUtil.encodeHexString(output.datumHash))
                else -> TransactionDatum.Absent
            },
            scriptReference = output.scriptRef?.let { bytes ->
                val script = PlutusScript.deserializeScriptRef(bytes)
                TransactionScriptReference(script.scriptType, HexUtil.encodeHexString(bytes), script.policyId)
            },
        )

    private companion object {
        val COINS_PER_UTXO_SIZE = Regex("[1-9][0-9]*")
        val SUPPORTED_BODY_KEYS = setOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 11L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L)
    }

    override fun transactionId(signedCbor: ByteArray): String =
        TransactionUtil.getTxHash(signedCbor)

    private fun inferNetwork(transaction: Transaction) = Address(transaction.body.outputs.firstOrNull()?.address ?: error("transaction has no outputs")).network
    private fun plutus(hex: String) = PlutusData.deserialize(HexUtil.decodeHexString(hex))
    private fun ChannelDatum.plutus(): PlutusData {
        val constants = ConstrPlutusData.of(
            0,
            BytesPlutusData.of(HexUtil.decodeHexString(constants.tagHex)),
            BytesPlutusData.of(HexUtil.decodeHexString(constants.addVerificationKeyHex)),
            BytesPlutusData.of(HexUtil.decodeHexString(constants.adaptorVerificationKeyHex)),
            BigIntPlutusData.of(constants.closePeriodMillis),
        )
        val evidence = ListPlutusData.of(*stage.evidenceCborHex.map { plutus(it) }.toTypedArray())
        val encodedStage = when (val value = stage) {
            is ChannelDatumStage.Opened -> ConstrPlutusData.of(0, BigIntPlutusData.of(value.accountedAmount), evidence)
            is ChannelDatumStage.Closed -> ConstrPlutusData.of(
                1,
                BigIntPlutusData.of(value.accountedAmount),
                evidence,
                BigIntPlutusData.of(value.elapseAtEpochMillis),
            )
            is ChannelDatumStage.Responded -> ConstrPlutusData.of(2, BigIntPlutusData.of(value.accountedAmount), evidence)
        }
        return ListPlutusData.of(
            BytesPlutusData.of(HexUtil.decodeHexString(validatorHashHex)),
            constants,
            encodedStage,
        )
    }

    private fun ChannelRedeemer.plutus(): PlutusData {
        val step = when (this) {
            ChannelRedeemer.ADD -> ConstrPlutusData.of(0, ConstrPlutusData.of(0))
            ChannelRedeemer.CLOSE -> ConstrPlutusData.of(0, ConstrPlutusData.of(2))
            ChannelRedeemer.ELAPSE -> ConstrPlutusData.of(1, ConstrPlutusData.of(1))
            ChannelRedeemer.END -> ConstrPlutusData.of(1, ConstrPlutusData.of(0))
        }
        return ConstrPlutusData.of(1, ListPlutusData.of(step))
    }

    private fun CloseChannelStep.redeemer() = when (this) {
        CloseChannelStep.CLOSE -> ChannelRedeemer.CLOSE
        CloseChannelStep.ELAPSE -> ChannelRedeemer.ELAPSE
        CloseChannelStep.END -> ChannelRedeemer.END
    }

    private fun String.hashHex() = PlutusScript.deserializeScriptRef(HexUtil.decodeHexString(this)).policyId
    private fun Lovelace.amount() = Amount.lovelace(BigInteger.valueOf(value))

    private fun toBloxbean(utxo: LedgerUtxo) = Utxo.builder()
        .txHash(utxo.transactionId)
        .outputIndex(utxo.index)
        .address(utxo.address)
        .amount(listOf(utxo.lovelace.amount()) + utxo.assets.map { Amount.asset(it.key, BigInteger.valueOf(it.value)) })
        .dataHash(utxo.datumHashHex)
        .inlineDatum(utxo.datumHex)
        .referenceScriptHash(utxo.scriptRefHashHex)
        .build()

    private class SnapshotUtxoSupplier(private val utxos: List<Utxo>) : UtxoSupplier {
        override fun getPage(address: String, count: Int?, page: Int?, order: OrderEnum?): List<Utxo> =
            utxos.filter { it.address == address }
                .drop((page ?: 0) * (count ?: 100)).take(count ?: 100)
        override fun getTxOutput(hash: String, index: Int): Optional<Utxo> = Optional.ofNullable(utxos.firstOrNull { it.txHash == hash && it.outputIndex == index })
    }
}

fun androidCardanoTransactionEngine(
    evaluate: suspend (CardanoNetwork, ByteArray) -> EvaluationResponse,
): CardanoTransactionEngine =
    AndroidCardanoTransactionEngine(object : TransactionProcessor {
        override fun submitTransaction(cborData: ByteArray): Result<String> =
            error("Connector submission must use an operation ID.")

        @Suppress("UNCHECKED_CAST")
        override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> {
            val transactionNetwork = Address(Transaction.deserialize(cbor).body.outputs.first().address).network
                .let { if (it.networkId == Networks.mainnet().networkId) CardanoNetwork.MAINNET else CardanoNetwork.PREPROD }
            val response = runBlocking { evaluate(transactionNetwork, cbor) }
            require(response.transactionId == TransactionUtil.getTxHash(cbor))
            val results = response.redeemers.map { redeemer ->
                EvaluationResult.builder()
                    .redeemerTag(redeemer.purpose.bloxbeanRedeemerTag())
                    .index(redeemer.index)
                    .exUnits(ExUnits.builder().mem(BigInteger.valueOf(redeemer.memory)).steps(BigInteger.valueOf(redeemer.steps)).build())
                    .build()
            }
            return Result.success("controlled-node evaluation").withValue(results) as Result<List<EvaluationResult>>
        }
    })

private fun String.bloxbeanRedeemerTag() = when (this) {
    "spend" -> RedeemerTag.Spend
    "mint" -> RedeemerTag.Mint
    "cert" -> RedeemerTag.Cert
    "reward" -> RedeemerTag.Reward
    "voting" -> RedeemerTag.Voting
    "proposing" -> RedeemerTag.Proposing
    else -> error("unsupported redeemer purpose")
}

suspend fun deriveAndroidWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet {
    require(entropy.size == 32)
    val mnemonic = MnemonicCode.INSTANCE.toMnemonic(entropy).joinToString(" ")
    val account = Account.createFromMnemonic(network.bloxbean(), mnemonic)
    val address = account.baseAddress()
    return DerivedWallet(
        paymentAddress = address,
        stakeAddress = account.stakeAddress(),
        paymentCredentialHex = HexUtil.encodeHexString(Address(address).paymentCredentialHash.orElseThrow()),
    )
}

private fun CardanoNetwork.bloxbean() = if (this == CardanoNetwork.MAINNET) Networks.mainnet() else Networks.preprod()
private fun CardanoNetwork.addressMatches(address: String) = if (this == CardanoNetwork.MAINNET) address.startsWith("addr1") else address.startsWith("addr_test1")
