package net.corda.flow.manager.impl


import net.corda.data.flow.FlowKey
import net.corda.data.flow.event.FlowEvent
import net.corda.data.flow.event.StartRPCFlow
import net.corda.data.flow.event.Wakeup
import net.corda.data.flow.state.Checkpoint
import net.corda.flow.manager.FlowMetaData
import net.corda.flow.manager.FlowMetaDataFactory
import net.corda.messaging.api.records.Record
import net.corda.packaging.CPI
import net.corda.virtualnode.HoldingIdentity
import org.osgi.service.component.annotations.Component

@Component(service = [FlowMetaDataFactory::class])
class FlowMetaDataFactoryImpl : FlowMetaDataFactory {
    override fun createFromEvent(state: Checkpoint?, eventRecord: Record<FlowKey, FlowEvent>): FlowMetaData {
        val flowEvent = eventRecord.value
        val payload = flowEvent!!.payload
        val flowKey = flowEvent.flowKey

        var flowName = ""
        var jsonArgs = ""
        var clientId = ""
        var cpiId = ""

       when (payload) {
            is StartRPCFlow -> {
                flowName = payload.flowName
                jsonArgs = payload.jsonArgs
                clientId = payload.clientId
                cpiId = payload.cpiId
            }
            is Wakeup -> {
                flowName = payload.flowName
            }
        }

        if (state != null) {
            cpiId = state.cpiId
        }

        // This will need to be cleaned up to remove the duplicate and redundant properties
        // leaving it for now so both old and new code can compile
        return FlowMetaDataImpl(
            flowEvent= flowEvent,
            clientId = clientId,
            flowName = flowName,
            flowKey = flowEvent.flowKey,
            jsonArg = jsonArgs,
            cpiId = cpiId,
            flowEventTopic = eventRecord.topic,
            holdingIdentity = HoldingIdentity(flowKey.identity.x500Name, flowKey.identity.groupId),
            cpi = CPI.Identifier.newInstance(cpiId, "1", null),
            payload = payload,
            checkpoint = state
        )
    }
}