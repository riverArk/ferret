package io.riverark.ferret.core.cardano

import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.ByteString
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
import co.nstant.`in`.cbor.model.UnsignedInteger
import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.address.Address
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.model.EvaluationResult
import com.bloxbean.cardano.client.api.model.Result
import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.api.helper.impl.FeeCalculationServiceImpl
import com.bloxbean.cardano.client.api.common.OrderEnum
import com.bloxbean.cardano.client.api.model.Amount
import com.bloxbean.cardano.client.api.model.ProtocolParams
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.api.util.CostModelUtil
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.common.model.SlotConfigs
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.crypto.Blake2bUtil
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData
import com.bloxbean.cardano.client.plutus.spec.CostMdls
import com.bloxbean.cardano.client.plutus.spec.Language
import com.bloxbean.cardano.client.plutus.spec.PlutusData
import com.bloxbean.cardano.client.plutus.spec.PlutusScript
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder
import com.bloxbean.cardano.client.quicktx.ScriptTx
import com.bloxbean.cardano.client.quicktx.Tx
import com.bloxbean.cardano.client.plutus.util.ScriptDataHashGenerator
import com.bloxbean.cardano.client.spec.Era
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput
import com.bloxbean.cardano.client.transaction.util.TransactionUtil
import com.bloxbean.cardano.client.util.HexUtil
import com.bloxbean.cardano.client.transaction.spec.TransactionInput
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness
import com.fasterxml.jackson.databind.ObjectMapper
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.network.EvaluationResponse
import io.riverark.ferret.core.network.deployment
import java.math.BigInteger
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.RoundingMode
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
        val channelIntent = intent is CardanoIntent.OpenChannel ||
            intent is CardanoIntent.AddChannelFunds ||
            intent is CardanoIntent.CloseChannel
        val (params, coinsPerUtxoByte) = try {
            parseProtocolParameters(ledger.protocolParametersJson)
        } catch (error: IllegalArgumentException) {
            if (channelIntent) throw IllegalArgumentException("invalid channel protocol parameters")
            throw error
        }
        if (channelIntent) requireChannelProtocolParameters(params)
        val referenceScript = requireChannelSemantics(intent, ledger)
        val utxos = ledger.utxos.filter { it.isSpendableBy(intent.sourceAddress) }.map(::toBloxbean)
        val supplier = SnapshotUtxoSupplier(utxos)
        val checkedTransactionProcessor = if (!channelIntent) transactionProcessor else object : TransactionProcessor {
            override fun submitTransaction(cborData: ByteArray): Result<String> =
                transactionProcessor.submitTransaction(cborData)

            @Suppress("UNCHECKED_CAST")
            override fun evaluateTx(cbor: ByteArray, inputUtxos: Set<Utxo>): Result<List<EvaluationResult>> {
                val result = try {
                    transactionProcessor.evaluateTx(cbor, inputUtxos)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    throw IllegalArgumentException("invalid channel evaluation")
                }
                val checked = requireEvaluationResult(cbor, result, params)
                return Result.success("validated channel evaluation").withValue(checked) as Result<List<EvaluationResult>>
            }
        }
        val builder = QuickTxBuilder(supplier, ProtocolParamsSupplier { params }, checkedTransactionProcessor)
        val transaction = when (intent) {
            is CardanoIntent.Transfer -> builder.compose(
                Tx().payToAddress(intent.destinationAddress, intent.amount.amount()).from(intent.sourceAddress),
            ).validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.SweepWallet -> builder.compose(
                Tx().payToAddress(intent.destinationAddress, intent.amount.amount()).from(intent.sourceAddress),
            ).validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.OpenChannel -> builder.compose(
                Tx().payToContract(intent.validatorAddress, intent.amount.amount(), intent.datum.plutus())
                    .from(intent.sourceAddress),
            ).preBalanceTx { _, tx ->
                tx.body.referenceInputs = mutableListOf(
                    TransactionInput.builder().transactionId(intent.referenceInput.transactionId).index(intent.referenceInput.index).build(),
                )
            }.withReferenceScripts(requireNotNull(referenceScript)).additionalSignersCount(1)
                .validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.AddChannelFunds -> builder.compose(
                ScriptTx()
                    .readFrom(toBloxbean(intent.referenceInput))
                    .collectFrom(toBloxbean(intent.channelInput), ChannelRedeemer.ADD.plutus())
                    .payToContract(intent.channelInput.address, Amount.lovelace(BigInteger.valueOf(Math.addExact(intent.channelInput.lovelace.value, intent.amount.value))), intent.resultingDatum.plutus())
                    .withChangeAddress(intent.sourceAddress),
            ).feePayer(intent.sourceAddress).collateralPayer(intent.sourceAddress)
                .withReferenceScripts(requireNotNull(referenceScript))
                .withRequiredSigners(Address(intent.sourceAddress))
                .additionalSignersCount(1)
                .ignoreScriptCostEvaluationError(false)
                .postBalanceTx { _, tx -> canonicalizeChannelRedeemer(tx, intent.channelInput, params) }
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
                    .withReferenceScripts(requireNotNull(referenceScript))
                    .withRequiredSigners(Address(intent.sourceAddress))
                    .additionalSignersCount(1)
                    .ignoreScriptCostEvaluationError(false)
                    .postBalanceTx { _, tx -> canonicalizeChannelRedeemer(tx, intent.channelInput, params) }
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
        requireTransactionAuthorization(cbor, intent, ledger, fee, signed = false)
        if (channelIntent && intent !is CardanoIntent.OpenChannel) {
            val evaluated = requireNotNull(checkedTransactionProcessor.evaluateTx(cbor, emptySet()).value)
            requireFinalEvaluationBudgets(cbor, evaluated, params)
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

    private fun requireChannelProtocolParameters(params: ProtocolParams) {
        try {
            require(listOf(params.priceMem, params.priceStep, params.minFeeRefScriptCostPerByte).all {
                it != null && it.signum() >= 0
            })
            require(listOf(params.maxTxExMem, params.maxTxExSteps).all {
                it != null && POSITIVE_DECIMAL.matches(it) && it.toLongOrNull() != null
            })
            val models = requireNotNull(params.costModelsRaw)
            require(models["PlutusV3"]?.isNotEmpty() == true)
            require(models.values.all { model ->
                model != null && model.size in 1..1024 && model.all { it != null }
            })
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid channel protocol parameters")
        }
    }
    private data class EvaluationKey(val tag: RedeemerTag, val index: Int)

    private fun requireEvaluationResult(
        cbor: ByteArray,
        result: Result<List<EvaluationResult>>,
        params: ProtocolParams,
    ): List<EvaluationResult> {
        try {
            require(result.isSuccessful)
            val submitted = submittedEvaluationKeys(cbor)
            val values = requireNotNull(result.value)
            val byKey = LinkedHashMap<EvaluationKey, EvaluationResult>()
            var memory = BigInteger.ZERO
            var steps = BigInteger.ZERO
            values.forEach { value ->
                val tag = requireNotNull(value.redeemerTag)
                require(value.index >= 0)
                val units = requireNotNull(value.exUnits)
                val mem = requireNotNull(units.mem)
                val cpu = requireNotNull(units.steps)
                require(mem.signum() >= 0 && cpu.signum() >= 0)
                require(byKey.put(EvaluationKey(tag, value.index), value) == null)
                memory = memory.add(mem)
                steps = steps.add(cpu)
            }
            require(byKey.keys == submitted.toSet())
            val maxMemory = BigInteger(requireNotNull(params.maxTxExMem))
            val maxSteps = BigInteger(requireNotNull(params.maxTxExSteps))
            require(memory <= maxMemory && steps <= maxSteps)
            memory.longValueExact()
            steps.longValueExact()
            return submitted.map { requireNotNull(byKey[it]) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid channel evaluation")
        }
    }

    private fun submittedEvaluationKeys(cbor: ByteArray): List<EvaluationKey> {
        val decoded = CborDecoder(ByteArrayInputStream(cbor)).decode()
        require(decoded.size == 1)
        val envelope = decoded.single() as CborArray
        require(envelope.dataItems.size == 4)
        require(CborSerializationUtil.serialize(envelope).contentEquals(cbor))
        val witnesses = envelope.dataItems[1] as CborMap
        val redeemers = witnesses[UnsignedInteger(5)] ?: return emptyList()
        require(redeemers is CborMap)
        val keys = redeemers.keys.map { item ->
            val key = item as CborArray
            require(key.dataItems.size == 2)
            val tag = (key.dataItems[0] as UnsignedInteger).value.intValueExact()
            val index = (key.dataItems[1] as UnsignedInteger).value.intValueExact()
            EvaluationKey(requireNotNull(RedeemerTag.entries.firstOrNull { it.value == tag }), index)
        }
        require(keys.distinct().size == keys.size)
        return keys
    }

    private fun requireFinalEvaluationBudgets(
        cbor: ByteArray,
        evaluated: List<EvaluationResult>,
        params: ProtocolParams,
    ) {
        val declared = try {
            Transaction.deserialize(cbor).witnessSet.redeemers.associateBy {
                EvaluationKey(requireNotNull(it.tag), it.index.intValueExact())
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid channel evaluation")
        }
        var memory = BigInteger.ZERO
        var steps = BigInteger.ZERO
        evaluated.forEach { result ->
            val redeemer = declared[EvaluationKey(requireNotNull(result.redeemerTag), result.index)]
                ?: throw IllegalArgumentException("invalid channel evaluation")
            val units = requireNotNull(result.exUnits)
            if (units.mem > redeemer.exUnits.mem || units.steps > redeemer.exUnits.steps) {
                throw IllegalArgumentException("channel evaluation budget changed")
            }
            memory = memory.add(redeemer.exUnits.mem)
            steps = steps.add(redeemer.exUnits.steps)
        }
        try {
            require(memory <= BigInteger(requireNotNull(params.maxTxExMem)))
            require(steps <= BigInteger(requireNotNull(params.maxTxExSteps)))
            memory.longValueExact()
            steps.longValueExact()
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid channel evaluation")
        }
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

    private fun requireChannelSemantics(intent: CardanoIntent, ledger: LedgerSnapshot): PlutusV3Script? {
        if (intent is CardanoIntent.Transfer || intent is CardanoIntent.SweepWallet) return null
        val source = Address(intent.sourceAddress)
        require(source.isPubKeyHashInPaymentPart())
        require(source.network.networkId == ledger.network.bloxbean().networkId)
        val sourceCredential = source.paymentCredentialHash.orElseThrow()
        val expectedDeployment = deployment(ledger.network)
        val available = mutableMapOf<TransactionInputReference, LedgerUtxo>()
        ledger.utxos.forEach { utxo ->
            require(available.put(TransactionInputReference(utxo.transactionId, utxo.index), utxo) == null)
        }
        fun requireLedgerUtxo(utxo: LedgerUtxo) {
            require(available[TransactionInputReference(utxo.transactionId, utxo.index)] == utxo)
        }
        fun requireDatum(datum: ChannelDatum, address: String) {
            require(datum.validatorHashHex == expectedDeployment.validatorHashHex)
            require(address == expectedDeployment.validatorAddress)
            val scriptAddress = Address(address)
            require(scriptAddress.network.networkId == ledger.network.bloxbean().networkId)
            require(scriptAddress.isScriptHashInPaymentPart())
            require(scriptAddress.paymentCredentialHash.orElseThrow().contentEquals(HexUtil.decodeHexString(datum.validatorHashHex)))
            require(Blake2bUtil.blake2bHash224(HexUtil.decodeHexString(datum.constants.addVerificationKeyHex)).contentEquals(sourceCredential))
        }

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
        val referenceInput = when (intent) {
            is CardanoIntent.OpenChannel -> intent.referenceInput
            is CardanoIntent.AddChannelFunds -> intent.referenceInput
            is CardanoIntent.CloseChannel -> intent.referenceInput
            else -> error("unreachable")
        }
        requireLedgerUtxo(referenceInput)
        val validatorHash = current?.validatorHashHex ?: requireNotNull(resulting).validatorHashHex
        val referenceScript = referenceInput.requireChannelReferenceScript(validatorHash)

        if (intent is CardanoIntent.OpenChannel) {
            requireDatum(intent.datum, intent.validatorAddress)
            require(intent.datum.stage == ChannelDatumStage.Opened(0))
            require(intent.amount.value >= KONDUIT_MIN_ADA_BUFFER)
        }
        if (current != null) {
            val channelInput = when (intent) {
                is CardanoIntent.AddChannelFunds -> intent.channelInput
                is CardanoIntent.CloseChannel -> intent.channelInput
                else -> error("unreachable")
            }
            requireLedgerUtxo(channelInput)
            require(channelInput.transactionId != referenceInput.transactionId || channelInput.index != referenceInput.index)
            requireDatum(current, channelInput.address)
            require(channelInput.assets.isEmpty())
            require(channelInput.lovelace.value >= KONDUIT_MIN_ADA_BUFFER)
            require(channelInput.datumHex == current.plutus().serializeToHex())
            require(channelInput.datumHashHex == null && channelInput.scriptRefHex == null)
            require(resulting == null || resulting.constants == current.constants && resulting.validatorHashHex == current.validatorHashHex)
        }
        val lowerMillis = slotEpochMillis(ledger.network, intent.validFrom)
        val upperMillis = slotEpochMillis(ledger.network, intent.validUntil)
        when (intent) {
            is CardanoIntent.AddChannelFunds -> {
                require(intent.amount.value > 0)
                require(intent.currentDatum.stage is ChannelDatumStage.Opened)
                require(intent.resultingDatum == intent.currentDatum)
                require(Math.addExact(intent.channelInput.lovelace.value, intent.amount.value) >= KONDUIT_MIN_ADA_BUFFER)
            }
            is CardanoIntent.CloseChannel -> {
                require(intent.amount == intent.channelInput.lovelace)
                when (intent.step) {
                    CloseChannelStep.CLOSE -> {
                        val opened = intent.currentDatum.stage as? ChannelDatumStage.Opened
                            ?: error("Close requires an opened channel")
                        val closed = intent.resultingDatum?.stage as? ChannelDatumStage.Closed
                            ?: error("Close must retain the channel")
                        require(closed.accountedAmount == opened.accountedAmount)
                        require(closed.evidenceCborHex == opened.evidenceCborHex)
                        require(upperMillis <= Math.subtractExact(closed.elapseAtEpochMillis, intent.currentDatum.constants.closePeriodMillis))
                    }
                    CloseChannelStep.ELAPSE -> {
                        val closed = intent.currentDatum.stage as? ChannelDatumStage.Closed
                            ?: error("Elapse requires a closed channel")
                        require(intent.resultingDatum == null)
                        require(lowerMillis >= closed.elapseAtEpochMillis)
                    }
                    CloseChannelStep.END -> {
                        val responded = intent.currentDatum.stage as? ChannelDatumStage.Responded
                            ?: error("End requires a responded channel")
                        require(intent.resultingDatum == null)
                        responded.evidenceCborHex.forEach { require(requireEvidence(it, pending = true).timeoutMillis!! <= lowerMillis) }
                    }
                }
            }
            else -> Unit
        }
        return referenceScript
    }
    private fun canonicalizeChannelRedeemer(transaction: Transaction, channelInput: LedgerUtxo, params: ProtocolParams) {
        transaction.body.inputs = transaction.body.inputs.sortedWith { left, right ->
            val a = CborSerializationUtil.serialize(left.serialize())
            val b = CborSerializationUtil.serialize(right.serialize())
            a.size.compareTo(b.size).takeIf { it != 0 } ?: a.indices.firstNotNullOfOrNull { index ->
                a[index].toUByte().compareTo(b[index].toUByte()).takeIf { it != 0 }
            } ?: 0
        }.toMutableList()
        val index = transaction.body.inputs.indexOfFirst {
            it.transactionId == channelInput.transactionId && it.index == channelInput.index
        }
        val redeemer = transaction.witnessSet.redeemers.single()
        redeemer.setIndex(index)
        val costModels = CostMdls().also {
            it.add(CostModelUtil.getCostModelFromProtocolParams(params, Language.PLUTUS_V3).orElseThrow())
        }
        transaction.body.scriptDataHash = ScriptDataHashGenerator.generate(
            Era.Conway,
            transaction.witnessSet.redeemers,
            emptyList(),
            costModels,
        )
    }

    private fun requireTransactionAuthorization(
        cbor: ByteArray,
        intent: CardanoIntent,
        ledger: LedgerSnapshot,
        feeBound: Lovelace,
        signed: Boolean,
    ) {
        if (intent is CardanoIntent.Transfer || intent is CardanoIntent.SweepWallet) {
            val summary = inspect(cbor)
            summary.requireMatches(intent, ledger.network, feeBound)
            summary.requireL1Funding(intent, ledger)
            summary.requireL1Witnesses(Address(intent.sourceAddress).paymentCredentialHash.orElseThrow().let(HexUtil::encodeHexString), signed)
            requireMinimumAda(cbor, ledger.protocolParametersJson)
            return
        }
        try {
            val script = requireNotNull(requireChannelSemantics(intent, ledger))
            val params = parseProtocolParameters(ledger.protocolParametersJson).first
            requireChannelProtocolParameters(params)
            val summary = inspect(cbor)
            val (rawBody, rawWitnesses) = rawTransactionMaps(cbor)
            val sourceCredential = Address(intent.sourceAddress).paymentCredentialHash.orElseThrow()
            val sourceCredentialHex = HexUtil.encodeHexString(sourceCredential)
            val spend = intent is CardanoIntent.AddChannelFunds || intent is CardanoIntent.CloseChannel
            require(summary.network == ledger.network)
            require(summary.fee.value in 0..feeBound.value)
            require(summary.validityStart == intent.validFrom && summary.validityEnd == intent.validUntil)
            require(ledger.currentSlot < intent.validUntil)
            require(summary.prohibitedBodyFields.isEmpty())
            require(rawWitnesses.keys.map { (it as UnsignedInteger).value.longValueExact() }.toSet() ==
                if (spend) setOfNotNull(5L, 0L.takeIf { signed }) else setOfNotNull(0L.takeIf { signed }))
            summary.requireL1Witnesses(sourceCredentialHex, signed)

            val reference = when (intent) {
                is CardanoIntent.OpenChannel -> intent.referenceInput
                is CardanoIntent.AddChannelFunds -> intent.referenceInput
                is CardanoIntent.CloseChannel -> intent.referenceInput
                else -> error("unreachable")
            }.let { TransactionInputReference(it.transactionId, it.index) }
            require(summary.referenceInputs == listOf(reference))
            require(reference !in summary.inputs && reference !in summary.collateralInputs)

            val rawRequiredSigners = (rawBody[UnsignedInteger(14)] as? CborArray)?.dataItems.orEmpty().map {
                HexUtil.encodeHexString((it as ByteString).bytes.also { bytes -> require(bytes.size == 28) })
            }
            if (spend) {
                require(rawRequiredSigners == listOf(sourceCredentialHex))
                require(summary.requiredSigners == setOf(sourceCredentialHex))
            } else {
                require(rawBody[UnsignedInteger(14)] == null && rawRequiredSigners.isEmpty() && summary.requiredSigners.isEmpty())
            }

            val expectedChannelOutput = when (intent) {
                is CardanoIntent.OpenChannel -> Triple(intent.validatorAddress, intent.amount, intent.datum)
                is CardanoIntent.AddChannelFunds -> Triple(
                    intent.channelInput.address,
                    Lovelace(Math.addExact(intent.channelInput.lovelace.value, intent.amount.value)),
                    intent.resultingDatum,
                )
                is CardanoIntent.CloseChannel -> intent.resultingDatum?.let {
                    Triple(intent.channelInput.address, intent.channelInput.lovelace, it)
                }
            }
            if (expectedChannelOutput != null) {
                val (address, amount, datum) = expectedChannelOutput
                val channelOutput = TransactionOutputSummary(
                    address,
                    amount,
                    emptyMap(),
                    TransactionDatum.Inline(datum.plutus().serializeToHex()),
                )
                require(summary.outputs.count { it == channelOutput } == 1)
                require(summary.outputs.size in 1..2)
                require(summary.outputs.all {
                    it == channelOutput || it.address == intent.sourceAddress &&
                        it.assets.isEmpty() && it.datum == TransactionDatum.Absent && it.scriptReference == null
                })
                require(amount.value >= KONDUIT_MIN_ADA_BUFFER)
            } else {
                require(summary.outputs.size == 1)
                require(summary.outputs.single().let {
                    it.address == intent.sourceAddress &&
                        it.assets.isEmpty() && it.datum == TransactionDatum.Absent && it.scriptReference == null
                })
            }
            summary.requireChannelFunding(intent, ledger)

            val transaction = Transaction.deserialize(cbor)
            if (spend) {
                val channel = when (intent) {
                    is CardanoIntent.AddChannelFunds -> intent.channelInput
                    is CardanoIntent.CloseChannel -> intent.channelInput
                }.let { TransactionInputReference(it.transactionId, it.index) }
                require(channel !in summary.collateralInputs)
                val inputIndex = summary.inputs.indexOf(channel)
                require(inputIndex >= 0)
                val redeemer = summary.redeemers.single()
                val expectedRedeemer = when (intent) {
                    is CardanoIntent.AddChannelFunds -> ChannelRedeemer.ADD
                    is CardanoIntent.CloseChannel -> intent.step.redeemer()
                }
                require(redeemer.purpose == "SPEND")
                require(redeemer.index == inputIndex.toLong())
                require(redeemer.dataCborHex == expectedRedeemer.plutus().serializeToHex())
                val costModels = CostMdls().also {
                    it.add(CostModelUtil.getCostModelFromProtocolParams(params, Language.PLUTUS_V3).orElseThrow())
                }
                val expectedScriptDataHash = ScriptDataHashGenerator.generate(
                    Era.Conway,
                    transaction.witnessSet.redeemers,
                    emptyList(),
                    costModels,
                )
                require(transaction.body.scriptDataHash.contentEquals(expectedScriptDataHash))
                requireCollateral(summary, intent, ledger, params)
            } else {
                require(summary.redeemers.isEmpty() && summary.scriptDataHashHex == null)
                require(summary.collateralInputs.isEmpty() && summary.collateralReturn == null && summary.totalCollateral == null)
            }
            requireChannelFee(cbor, summary, ledger, params, script, signed)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid channel transaction")
        }
    }

    private fun requireCollateral(
        summary: TransactionSummary,
        intent: CardanoIntent,
        ledger: LedgerSnapshot,
        params: ProtocolParams,
    ) {
        require(summary.collateralInputs.isNotEmpty())
        require(summary.collateralInputs.distinct().size == summary.collateralInputs.size)
        val maxInputs = requireNotNull(params.maxCollateralInputs)
        require(maxInputs > 0 && summary.collateralInputs.size <= maxInputs)
        val available = ledger.utxos.associateBy { TransactionInputReference(it.transactionId, it.index) }
        require(available.size == ledger.utxos.size)
        val reference = when (intent) {
            is CardanoIntent.AddChannelFunds -> intent.referenceInput
            is CardanoIntent.CloseChannel -> intent.referenceInput
            else -> error("unreachable")
        }.let { TransactionInputReference(it.transactionId, it.index) }
        val channel = when (intent) {
            is CardanoIntent.AddChannelFunds -> intent.channelInput
            is CardanoIntent.CloseChannel -> intent.channelInput
        }.let { TransactionInputReference(it.transactionId, it.index) }
        val collateralTotal = summary.collateralInputs.fold(Lovelace(0)) { total, input ->
            require(input != reference && input != channel)
            val utxo = requireNotNull(available[input])
            require(utxo.isSpendableBy(intent.sourceAddress))
            total + utxo.lovelace
        }
        val percentage = requireNotNull(params.collateralPercent)
        require(percentage.signum() > 0)
        val expected = BigDecimal.valueOf(summary.fee.value).multiply(percentage)
            .divide(BigDecimal.valueOf(100)).setScale(0, RoundingMode.CEILING).toBigIntegerExact().longValueExact()
        require(summary.totalCollateral == Lovelace(expected))
        val returnAmount = Math.subtractExact(collateralTotal.value, expected)
        if (returnAmount == 0L) {
            require(summary.collateralReturn == null)
        } else {
            require(summary.collateralReturn == TransactionOutputSummary(intent.sourceAddress, Lovelace(returnAmount), emptyMap()))
        }
    }

    private fun requireChannelFee(
        cbor: ByteArray,
        summary: TransactionSummary,
        ledger: LedgerSnapshot,
        params: ProtocolParams,
        script: PlutusV3Script,
        signed: Boolean,
    ) {
        val pricedBytes = if (signed) cbor else Transaction.deserialize(cbor).also { transaction ->
            if (transaction.witnessSet == null) transaction.witnessSet = TransactionWitnessSet()
            transaction.witnessSet.vkeyWitnesses = mutableListOf(
                VkeyWitness.builder().vkey(ByteArray(32)).signature(ByteArray(64)).build(),
            )
        }.serialize()
        val calculator = FeeCalculationServiceImpl(
            SnapshotUtxoSupplier(emptyList()),
            ProtocolParamsSupplier { params },
        )
        val required = calculator.calculateFee(pricedBytes, params)
            .add(calculator.calculateScriptFee(Transaction.deserialize(cbor).witnessSet?.redeemers.orEmpty().map { it.exUnits }, params))
            .add(calculator.tierRefScriptFee(script.scriptRefBytes().size.toLong()))
        require(BigInteger.valueOf(summary.fee.value) >= required)
        require(pricedBytes.size <= requireNotNull(params.maxTxSize))
        requireMinimumAda(cbor, ledger.protocolParametersJson)
    }

    private fun rawTransactionMaps(cbor: ByteArray): Pair<CborMap, CborMap> {
        val decoded = CborDecoder(ByteArrayInputStream(cbor)).decode()
        require(decoded.size == 1)
        val envelope = decoded.single() as CborArray
        require(envelope.dataItems.size == 4)
        require(CborSerializationUtil.serialize(envelope).contentEquals(cbor))
        return (envelope.dataItems[0] as CborMap) to (envelope.dataItems[1] as CborMap)
    }

    private fun slotEpochMillis(network: CardanoNetwork, slot: Long): Long = try {
        val config = if (network == CardanoNetwork.MAINNET) SlotConfigs.mainnet() else SlotConfigs.preprod()
        require(slot >= config.zeroSlot)
        Math.addExact(
            config.zeroTime,
            Math.multiplyExact(Math.subtractExact(slot, config.zeroSlot), config.slotLength.toLong()),
        )
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("invalid channel slot")
    }
    override fun requireAuthorized(unsigned: UnsignedTransaction, intent: CardanoIntent, ledger: LedgerSnapshot) {
        require(unsigned.operationId == intent.operationId)
        requireTransactionAuthorization(unsigned.cbor, intent, ledger, unsigned.feeBound, signed = false)
    }

    override fun sign(
        unsigned: UnsignedTransaction,
        seed: ByteArray,
        intent: CardanoIntent,
        ledger: LedgerSnapshot,
    ): SignedTransaction {
        require(seed.size == 32)
        requireAuthorized(unsigned, intent, ledger)
        val mnemonic = MnemonicCode.INSTANCE.toMnemonic(seed).joinToString(" ")
        val account = Account.createFromMnemonic(ledger.network.bloxbean(), mnemonic)
        val sourceCredential = Address(intent.sourceAddress).paymentCredentialHash.orElseThrow()
        require(Blake2bUtil.blake2bHash224(account.publicKeyBytes()).contentEquals(sourceCredential))
        var signedBytes: ByteArray? = null
        try {
            signedBytes = account.sign(Transaction.deserialize(unsigned.cbor)).serialize()
            require(TransactionUtil.getTxHash(signedBytes) == TransactionUtil.getTxHash(unsigned.cbor))
            val unsignedWitnesses = rawTransactionMaps(unsigned.cbor).second
            val signedWitnesses = rawTransactionMaps(signedBytes).second
            val nonKeyFields = (unsignedWitnesses.keys + signedWitnesses.keys)
                .filter { it != UnsignedInteger(0) }.toSet()
            require(nonKeyFields.all { unsignedWitnesses[it] == signedWitnesses[it] })
            requireTransactionAuthorization(signedBytes, intent, ledger, unsigned.feeBound, signed = true)
            return SignedTransaction(signedBytes)
        } catch (error: CancellationException) {
            signedBytes?.fill(0)
            throw error
        } catch (error: Exception) {
            signedBytes?.fill(0)
            throw error
        }
    }

    override fun inspect(signedCbor: ByteArray): TransactionSummary {
        val decoded = CborDecoder(ByteArrayInputStream(signedCbor)).decode()
        require(decoded.size == 1)
        val envelope = requireNotNull(decoded.single() as? CborArray) { "transaction envelope must be an array" }
        require(envelope.dataItems.size == 4)
        require(CborSerializationUtil.serialize(envelope).contentEquals(signedCbor))
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

    private fun CloseChannelStep.redeemer() = when (this) {
        CloseChannelStep.CLOSE -> ChannelRedeemer.CLOSE
        CloseChannelStep.ELAPSE -> ChannelRedeemer.ELAPSE
        CloseChannelStep.END -> ChannelRedeemer.END
    }

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
private val POSITIVE_DECIMAL = Regex("[1-9][0-9]*")
private const val MAX_CHANNEL_EVIDENCE = 10
private const val KONDUIT_MIN_ADA_BUFFER = 2_000_000L

internal fun LedgerUtxo.requireChannelReferenceScript(expectedValidatorHashHex: String): PlutusV3Script = try {
    require(Regex("[0-9a-f]{56}").matches(expectedValidatorHashHex))
    val scriptHex = requireNotNull(scriptRefHex)
    require(scriptRefVersion == 3)
    require(scriptRefHashHex == expectedValidatorHashHex)
    require(scriptHex.length in 2..131_072 && scriptHex.length % 2 == 0 && scriptHex.all { it in "0123456789abcdef" })
    PlutusV3Script.deserialize(ByteString(HexUtil.decodeHexString(scriptHex))).also {
        require(it.policyId == expectedValidatorHashHex)
    }
} catch (_: Exception) {
    throw IllegalArgumentException("invalid channel reference script")
}

internal fun ChannelDatum.plutus(): PlutusData {
    val constants = ListPlutusData.of(
        BytesPlutusData.of(HexUtil.decodeHexString(constants.tagHex)),
        BytesPlutusData.of(HexUtil.decodeHexString(constants.addVerificationKeyHex)),
        BytesPlutusData.of(HexUtil.decodeHexString(constants.adaptorVerificationKeyHex)),
        BigIntPlutusData.of(constants.closePeriodMillis),
        ConstrPlutusData.of(0),
    )
    require(stage.evidenceCborHex.size <= MAX_CHANNEL_EVIDENCE) { "invalid channel evidence" }
    val evidence = ListPlutusData.of(*stage.evidenceCborHex.map { requireEvidence(it, stage is ChannelDatumStage.Responded).plutus }.toTypedArray())
    val encodedStage = when (val value = stage) {
        is ChannelDatumStage.Opened -> ConstrPlutusData.of(0, BigIntPlutusData.of(value.accountedAmount), evidence)
        is ChannelDatumStage.Closed -> {
            require(value.elapseAtEpochMillis >= 0) { "invalid channel timestamp" }
            ConstrPlutusData.of(
                1,
                BigIntPlutusData.of(value.accountedAmount),
                evidence,
                BigIntPlutusData.of(value.elapseAtEpochMillis),
            )
        }
        is ChannelDatumStage.Responded -> ConstrPlutusData.of(2, BigIntPlutusData.of(value.accountedAmount), evidence)
    }
    return ListPlutusData.of(
        BytesPlutusData.of(HexUtil.decodeHexString(validatorHashHex)),
        constants,
        encodedStage,
    )
}

private data class ChannelEvidence(val plutus: PlutusData, val timeoutMillis: Long?)

private fun requireEvidence(cborHex: String, pending: Boolean): ChannelEvidence = try {
    val decoded = CborDecoder(ByteArrayInputStream(HexUtil.decodeHexString(cborHex))).decode()
    require(decoded.size == 1)
    val fields = (PlutusData.deserialize(decoded.single()) as? ListPlutusData)?.plutusDataList
        ?: error("invalid evidence")
    if (pending) {
        require(fields.size == 3)
        val amount = fields[0].nonNegativeLong()
        val timeout = fields[1].nonNegativeLong()
        val lock = (fields[2] as? BytesPlutusData)?.value ?: error("invalid lock")
        require(lock.size == 32)
        ChannelEvidence(
            ListPlutusData.of(BigIntPlutusData.of(amount), BigIntPlutusData.of(timeout), BytesPlutusData.of(lock)),
            timeout,
        )
    } else {
        require(fields.size == 2)
        ChannelEvidence(
            ListPlutusData.of(
                BigIntPlutusData.of(fields[0].nonNegativeLong()),
                BigIntPlutusData.of(fields[1].nonNegativeLong()),
            ),
            null,
        )
    }
} catch (_: Exception) {
    throw IllegalArgumentException("invalid channel evidence")
}

private fun PlutusData.nonNegativeLong(): Long {
    val value = (this as? BigIntPlutusData)?.value ?: error("invalid integer")
    require(value.signum() >= 0)
    return value.longValueExact()
}

internal fun ChannelRedeemer.plutus(): PlutusData {
    val step = when (this) {
        ChannelRedeemer.ADD -> ConstrPlutusData.of(0, ConstrPlutusData.of(0))
        ChannelRedeemer.CLOSE -> ConstrPlutusData.of(0, ConstrPlutusData.of(2))
        ChannelRedeemer.ELAPSE -> ConstrPlutusData.of(1, ConstrPlutusData.of(1))
        ChannelRedeemer.END -> ConstrPlutusData.of(1, ConstrPlutusData.of(0))
    }
    return ConstrPlutusData.of(1, ListPlutusData.of(step))
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
