package net.corda.flow.pipeline.runner.impl

import net.corda.data.KeyValuePairList
import net.corda.flow.utils.toMap

/**
 * Map context properties sent from an initiating remote party into context properties presented to initiated user code.
 * Whilst context is propagated down the chain of flows, it can be tweaked across the initiating/initiated flow
 * boundaries by Corda. The context presented to user code in an initiated flow does not need to be an exact replica of
 * context from the initiating party. On top of this, the local session passed to initiated flows contains context
 * relating only to the counterparty which initiated it and not the "main" context of the executing flow.
 *
 * @param remoteUserContextProperties User context properties from the remote initiating party
 * @param remotePlatformContextProperties Platform context properties from the remote initiating party
 * @return Context properties translated to a local context
 */
fun remoteToLocalContextMapper(
    remoteUserContextProperties: KeyValuePairList,
    remotePlatformContextProperties: KeyValuePairList
): LocalContext {
    return LocalContext(
        userProperties = remoteUserContextProperties,
        platformProperties = remotePlatformContextProperties,
        counterpartySessionProperties = remotePlatformContextProperties.toMap()
    )
}

/**
 * User and platform properties live in the domain of the checkpoint, hence they must be modelled by avro types.
 * Session properties are passed to initiated flows and always live on the stack, so must be managed as Kotlin types
 * that can be kryo serialized.
 */
data class LocalContext(
    val userProperties: KeyValuePairList,
    val platformProperties: KeyValuePairList,
    val counterpartySessionProperties: Map<String, String>
)
