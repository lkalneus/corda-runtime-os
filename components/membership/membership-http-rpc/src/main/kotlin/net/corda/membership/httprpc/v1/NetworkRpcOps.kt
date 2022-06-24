package net.corda.membership.httprpc.v1

import net.corda.httprpc.RpcOps
import net.corda.httprpc.annotations.HttpRpcPUT
import net.corda.httprpc.annotations.HttpRpcPathParameter
import net.corda.httprpc.annotations.HttpRpcRequestBodyParameter
import net.corda.httprpc.annotations.HttpRpcResource

@HttpRpcResource(
    name = "NetworkRpcOps",
    description = "Network API",
    path = "network"
)
interface NetworkRpcOps : RpcOps {

    /**
     * POST endpoint which set up the identity network.
     *
     * @param holdingIdentityId ID of the holding identity to set up.
     * @param certificateChainAlias The certificates chain alias.
     * @param tlsTenantId The TLS tenant ID (either 'p2p' or the holdingIdentityId, default to the holdingIdentityId).
     * @param sessionKeyAlias The session key alias (will use the first one if null).
     */
    @HttpRpcPUT(
        path = "setup/{holdingIdentityId}",
        description = "Set up the holding identity on the network."
    )
    fun setupHostedIdentities(
        @HttpRpcPathParameter(description = "ID of the holding identity to set up.")
        holdingIdentityId: String,
        @HttpRpcRequestBodyParameter(
            description = "The certificates chain alias.",
            required = true,
        )
        certificateChainAlias: String,
        @HttpRpcRequestBodyParameter(
            description = "The TLS tenant ID (either 'p2p' or the holdingIdentityId, default to the holdingIdentityId).",
            required = false,
        )
        tlsTenantId: String?,
        @HttpRpcRequestBodyParameter(
            description = "The session key ID (will use the first session one by default).",
            required = false,
        )
        sessionKeyId: String?,
    )
}