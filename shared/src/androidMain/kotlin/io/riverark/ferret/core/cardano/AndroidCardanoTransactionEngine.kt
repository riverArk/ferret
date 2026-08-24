package io.riverark.ferret.core.cardano

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.address.Address
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier
import com.bloxbean.cardano.client.api.TransactionProcessor
import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.api.common.OrderEnum
import com.bloxbean.cardano.client.api.model.Amount
import com.bloxbean.cardano.client.api.model.ProtocolParams
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.crypto.bip39.MnemonicCode
import com.bloxbean.cardano.client.plutus.spec.PlutusData
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder
import com.bloxbean.cardano.client.quicktx.ScriptTx
import com.bloxbean.cardano.client.quicktx.Tx
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.util.HexUtil
import com.fasterxml.jackson.databind.ObjectMapper
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.Lovelace
import java.math.BigInteger
import java.util.Optional

class AndroidCardanoTransactionEngine(
    private val transactionProcessor: TransactionProcessor,
) : CardanoTransactionEngine {
    override suspend fun deriveWallet(entropy: ByteArray, network: CardanoNetwork): DerivedWallet {
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

    override suspend fun build(intent: CardanoIntent, ledger: LedgerSnapshot): UnsignedTransaction {
        require(ledger.network.addressMatches(intent.sourceAddress))
        when (intent) {
            is CardanoIntent.Transfer -> require(ledger.network.addressMatches(intent.destinationAddress))
            is CardanoIntent.SweepWallet -> require(ledger.network.addressMatches(intent.destinationAddress))
            is CardanoIntent.OpenChannel -> require(ledger.network.addressMatches(intent.deploymentAddress))
            is CardanoIntent.AddChannelFunds -> require(ledger.network.addressMatches(intent.channelInput.address))
            is CardanoIntent.CloseChannel -> require(ledger.network.addressMatches(intent.channelInput.address))
        }
        require(intent.validFrom >= ledger.currentSlot && intent.validUntil > intent.validFrom)
        val utxos = ledger.utxos.map(::toBloxbean)
        val supplier = SnapshotUtxoSupplier(utxos)
        val params = ObjectMapper().readValue(ledger.protocolParametersJson, ProtocolParams::class.java)
        val builder = QuickTxBuilder(supplier, ProtocolParamsSupplier { params }, transactionProcessor)
        val transaction = when (intent) {
            is CardanoIntent.Transfer -> builder.compose(
                Tx().payToAddress(intent.destinationAddress, intent.amount.amount()).from(intent.sourceAddress),
            ).validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.SweepWallet -> builder.compose(
                Tx().payToAddress(intent.destinationAddress, intent.amount.amount()).from(intent.sourceAddress),
            ).validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.OpenChannel -> builder.compose(
                Tx().payToContract(intent.deploymentAddress, intent.amount.amount(), plutus(intent.datumHex)).from(intent.sourceAddress),
            ).validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.AddChannelFunds -> builder.compose(
                ScriptTx()
                    .readFrom(toBloxbean(intent.referenceInput))
                    .collectFrom(toBloxbean(intent.channelInput), plutus(intent.redeemerHex))
                    .payToContract(intent.channelInput.address, Amount.lovelace(BigInteger.valueOf(Math.addExact(intent.channelInput.lovelace.value, intent.amount.value))), plutus(intent.datumHex))
                    .withChangeAddress(intent.sourceAddress),
            ).feePayer(intent.sourceAddress).collateralPayer(intent.sourceAddress)
                .validFrom(intent.validFrom).validTo(intent.validUntil).build()
            is CardanoIntent.CloseChannel -> builder.compose(
                ScriptTx()
                    .readFrom(toBloxbean(intent.referenceInput))
                    .collectFrom(toBloxbean(intent.channelInput), plutus(intent.redeemerHex))
                    .payToAddress(intent.sourceAddress, intent.amount.amount())
                    .withChangeAddress(intent.sourceAddress),
            ).feePayer(intent.sourceAddress).collateralPayer(intent.sourceAddress)
                .validFrom(intent.validFrom).validTo(intent.validUntil).build()
        }
        val fee = Lovelace(transaction.body.fee.longValueExact())
        return UnsignedTransaction(transaction.serialize(), intent.operationId, fee)
    }

    override fun sign(unsigned: UnsignedTransaction, seed: ByteArray): SignedTransaction {
        require(seed.size == 32)
        val mnemonic = MnemonicCode.INSTANCE.toMnemonic(seed).joinToString(" ")
        val account = Account.createFromMnemonic(inferNetwork(Transaction.deserialize(unsigned.cbor)), mnemonic)
        return SignedTransaction(account.sign(Transaction.deserialize(unsigned.cbor)).serialize())
    }

    override fun inspect(signedCbor: ByteArray): TransactionSummary {
        val transaction = Transaction.deserialize(signedCbor)
        val body = transaction.body
        val network = inferNetwork(transaction).let { if (it.networkId == Networks.mainnet().networkId) CardanoNetwork.MAINNET else CardanoNetwork.PREPROD }
        return TransactionSummary(
            network = network,
            outputs = body.outputs.map { output ->
                TransactionOutputSummary(
                    address = output.address,
                    lovelace = Lovelace(output.value.coin.longValueExact()),
                    assets = output.value.multiAssets.orEmpty().flatMap { multi ->
                        multi.assets.map { asset -> "${multi.policyId}${asset.name}" to asset.value.longValueExact() }
                    }.toMap(),
                )
            },
            fee = Lovelace(body.fee.longValueExact()),
            requiredSigners = body.requiredSigners.map(HexUtil::encodeHexString).toSet(),
            validityStart = body.validityStartInterval.takeIf { it != 0L },
            validityEnd = body.ttl.takeIf { it != 0L },
        )
    }

    private fun inferNetwork(transaction: Transaction) = Address(transaction.body.outputs.firstOrNull()?.address ?: error("transaction has no outputs")).network
    private fun plutus(hex: String) = PlutusData.deserialize(HexUtil.decodeHexString(hex))
    private fun Lovelace.amount() = Amount.lovelace(BigInteger.valueOf(value))

    private fun toBloxbean(utxo: LedgerUtxo) = Utxo.builder()
        .txHash(utxo.transactionId)
        .outputIndex(utxo.index)
        .address(utxo.address)
        .amount(listOf(utxo.lovelace.amount()) + utxo.assets.map { Amount.asset(it.key, BigInteger.valueOf(it.value)) })
        .inlineDatum(utxo.datumHex)
        .referenceScriptHash(utxo.scriptRefHex)
        .build()

    private class SnapshotUtxoSupplier(private val utxos: List<Utxo>) : UtxoSupplier {
        override fun getPage(address: String, count: Int?, page: Int?, order: OrderEnum?): List<Utxo> =
            utxos.filter { it.address == address && it.amount.size == 1 && it.amount.single().unit == "lovelace" }
                .drop((page ?: 0) * (count ?: 100)).take(count ?: 100)
        override fun getTxOutput(hash: String, index: Int): Optional<Utxo> = Optional.ofNullable(utxos.firstOrNull { it.txHash == hash && it.outputIndex == index })
    }
}

private fun CardanoNetwork.bloxbean() = if (this == CardanoNetwork.MAINNET) Networks.mainnet() else Networks.preprod()
private fun CardanoNetwork.addressMatches(address: String) = if (this == CardanoNetwork.MAINNET) address.startsWith("addr1") else address.startsWith("addr_test1")
