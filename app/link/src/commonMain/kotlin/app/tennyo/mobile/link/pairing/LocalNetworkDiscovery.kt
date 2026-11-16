package app.tennyo.mobile.link.pairing

data class LocalNetworkServiceAdvertisement(
    val serviceType: String,
    val serviceInstanceId: String,
    val host: String,
    val port: Int,
) {
    /**
     * Whether this service is [companionId]'s. DNS-SD renames an instance whose name is already taken on the network
     * to "name (2)", so a host that re-announced itself may carry the suffix.
     */
    fun advertises(companionId: String): Boolean =
        serviceInstanceId == companionId ||
            serviceInstanceId.startsWith("$companionId (") && serviceInstanceId.endsWith(")")
}

interface LocalNetworkDiscoveryGateway {
    suspend fun discoverServices(
        serviceType: String,
        timeoutMillis: Long,
    ): List<LocalNetworkServiceAdvertisement>
}
