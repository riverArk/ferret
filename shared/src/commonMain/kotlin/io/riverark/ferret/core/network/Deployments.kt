package io.riverark.ferret.core.network

import io.riverark.ferret.core.model.CardanoNetwork
import kotlin.jvm.JvmInline

@JvmInline value class HttpsUrl(val value: String) {
    init { require(value.startsWith("https://") && !value.contains('@')) }
}

data class NetworkDeployment(
    val network: CardanoNetwork,
    val scriptDeploymentAddress: String,
    val validatorAddress: String,
    val adaptor: HttpsUrl,
    val connector: HttpsUrl,
    val adaptorIdentityHex: String,
    val validatorHashHex: String,
) {
    fun validate() {
        val prefix = if (network == CardanoNetwork.PREPROD) "addr_test1" else "addr1"
        require(scriptDeploymentAddress.startsWith(prefix))
        require(validatorAddress.startsWith(prefix))
        require(adaptor.value.substringAfter("https://").substringBefore('/') in allowedHosts)
        require(connector.value.substringAfter("https://").substringBefore('/') in allowedHosts)
        require(Regex("[0-9a-f]{64}").matches(adaptorIdentityHex))
        require(Regex("[0-9a-f]{56}").matches(validatorHashHex))
    }

    companion object {
        private val allowedHosts = setOf(
            "preprod-adaptor.ferret.channel", "preprod-cardano.ferret.channel",
            "adaptor.ferret.channel", "cardano.ferret.channel",
        )
    }
}

val PREPROD = NetworkDeployment(
    CardanoNetwork.PREPROD,
    "addr_test1vrpynvza5vswczszkjhe5cvqz2awmzukf84xa5wway8durqpmfm2m",
    "addr_test1wrpc0agp7ce78zefuk38kyza8rnu3gzy9vy6ynhh7t9ygygy5fd4h",
    HttpsUrl("https://preprod-adaptor.ferret.channel"),
    HttpsUrl("https://preprod-cardano.ferret.channel"),
    "fe7d2454c30c6ca3337dd83b64d42358580dd199ac10322ed59475c7f1e20134",
    "c387f501f633e38b29e5a27b105d38e7c8a0442b09a24ef7f2ca4411",
).also(NetworkDeployment::validate)

val MAINNET = NetworkDeployment(
    CardanoNetwork.MAINNET,
    "addr1qyvf5xgy6kn78mn66epp3ztlw3z47hpyz6v2l7l3v4eqyj40utxhve3xuj42n3fxaz64ldnjzg07yw30f3ypuncx9ajsee6f34",
    "addr1w8pc0agp7ce78zefuk38kyza8rnu3gzy9vy6ynhh7t9ygyglua36j",
    HttpsUrl("https://adaptor.ferret.channel"),
    HttpsUrl("https://cardano.ferret.channel"),
    "fe7d2454c30c6ca3337dd83b64d42358580dd199ac10322ed59475c7f1e20134",
    "c387f501f633e38b29e5a27b105d38e7c8a0442b09a24ef7f2ca4411",
).also(NetworkDeployment::validate)

fun deployment(network: CardanoNetwork) = if (network == CardanoNetwork.PREPROD) PREPROD else MAINNET
