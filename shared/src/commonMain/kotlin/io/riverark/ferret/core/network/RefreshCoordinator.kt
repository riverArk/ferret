package io.riverark.ferret.core.network

import io.riverark.ferret.core.model.WalletProfile
import io.riverark.ferret.core.model.AssetCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job

class RefreshCoordinator(
    private val deployment: NetworkDeployment,
    private val assets: AssetCatalog,
    private val connectorHealth: suspend () -> HealthDto,
    private val connectorNetwork: suspend () -> NetworkDto,
    private val adaptorInfo: suspend () -> AdaptorInfoDto,
) {
    private val refreshJobs = mutableSetOf<Job>()

    constructor(deployment: NetworkDeployment, assets: AssetCatalog, connector: ConnectorClient, adaptor: AdaptorClient) : this(
        deployment,
        assets,
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
        assets.requireDiscoveryDigest(info.assetCatalogDigest)
    }

    suspend fun <T> refresh(block: suspend () -> T): T = coroutineScope {
        val job = coroutineContext.job
        refreshJobs += job
        try {
            block()
        } finally {
            refreshJobs -= job
        }
    }

    fun cancelActiveWork() {
        refreshJobs.toList().forEach(Job::cancel)
        refreshJobs.clear()
    }
}
