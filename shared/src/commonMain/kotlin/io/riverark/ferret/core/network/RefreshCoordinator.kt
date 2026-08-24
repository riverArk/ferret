package io.riverark.ferret.core.network

import io.riverark.ferret.core.model.WalletProfile

class RefreshCoordinator(
    private val deployment: NetworkDeployment,
    private val connectorHealth: suspend () -> HealthDto,
    private val connectorNetwork: suspend () -> NetworkDto,
    private val adaptorInfo: suspend () -> AdaptorInfoDto,
) {
    constructor(deployment: NetworkDeployment, connector: ConnectorClient, adaptor: AdaptorClient) : this(
        deployment,
        connector::health,
        connector::network,
        adaptor::info,
    )

    suspend fun validate(profile: WalletProfile) {
        require(profile.network == deployment.network)
        require(connectorHealth().status == "ok")
        require(connectorNetwork().network == deployment.network.name.lowercase())
        val info = adaptorInfo()
        require(info.channelParameters.adaptorKeyHex == deployment.adaptorIdentityHex)
        require(info.transactionHelp.hostAddress == deployment.scriptDeploymentAddress)
        require(info.transactionHelp.validator == deployment.validatorHashHex)
    }
}
