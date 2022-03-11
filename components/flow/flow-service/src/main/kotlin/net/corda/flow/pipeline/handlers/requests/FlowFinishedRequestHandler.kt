package net.corda.flow.pipeline.handlers.requests

import net.corda.data.flow.state.waiting.WaitingFor
import net.corda.flow.fiber.FlowIORequest
import net.corda.flow.pipeline.FlowEventContext
import net.corda.flow.pipeline.factory.FlowMessageFactory
import net.corda.messaging.api.records.Record
import net.corda.schema.Schemas.Flow.Companion.FLOW_STATUS_TOPIC
import net.corda.v5.base.util.contextLogger
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

@Suppress("Unused")
@Component(service = [FlowRequestHandler::class])
class FlowFinishedRequestHandler @Activate constructor(
    @Reference(service = FlowMessageFactory::class)
    private val flowMessageFactory: FlowMessageFactory
) : FlowRequestHandler<FlowIORequest.FlowFinished> {

    private companion object {
        val log = contextLogger()
    }

    override val type = FlowIORequest.FlowFinished::class.java

    override fun getUpdatedWaitingFor(context: FlowEventContext<Any>, request: FlowIORequest.FlowFinished): WaitingFor {
        return WaitingFor(null)
    }

    override fun postProcess(
        context: FlowEventContext<Any>,
        request: FlowIORequest.FlowFinished
    ): FlowEventContext<Any> {
        val checkpoint = requireCheckpoint(context)

        log.info("Flow [${checkpoint.flowKey.flowId}] completed successfully")

        val status = flowMessageFactory.createFlowCompleteStatusMessage(checkpoint, request.result)
        val record = Record(FLOW_STATUS_TOPIC, status.key, status)

        return context.copy(checkpoint = null, outputRecords = context.outputRecords + record)
    }
}