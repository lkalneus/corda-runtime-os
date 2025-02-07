package net.corda.flow.pipeline.handlers.requests

import net.corda.data.flow.event.mapper.ScheduleCleanup
import net.corda.data.flow.output.FlowStates
import net.corda.data.flow.state.waiting.WaitingFor
import net.corda.flow.fiber.FlowIORequest
import net.corda.flow.pipeline.events.FlowEventContext
import net.corda.flow.pipeline.factory.FlowMessageFactory
import net.corda.flow.pipeline.factory.FlowRecordFactory
import net.corda.flow.pipeline.handlers.requests.helper.recordFlowRuntimeMetric
import net.corda.schema.configuration.FlowConfig.PROCESSING_FLOW_CLEANUP_TIME
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.LoggerFactory
import java.time.Instant

@Suppress("Unused")
@Component(service = [FlowRequestHandler::class])
class FlowFinishedRequestHandler @Activate constructor(
    @Reference(service = FlowMessageFactory::class)
    private val flowMessageFactory: FlowMessageFactory,
    @Reference(service = FlowRecordFactory::class)
    private val flowRecordFactory: FlowRecordFactory
) : FlowRequestHandler<FlowIORequest.FlowFinished> {

    private companion object {
        val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override val type = FlowIORequest.FlowFinished::class.java

    override fun getUpdatedWaitingFor(context: FlowEventContext<Any>, request: FlowIORequest.FlowFinished): WaitingFor {
        return WaitingFor(null)
    }

    override fun postProcess(
        context: FlowEventContext<Any>,
        request: FlowIORequest.FlowFinished
    ): FlowEventContext<Any> {
        val checkpoint = context.checkpoint
        log.info("Flow [${checkpoint.flowId}] completed successfully")
        recordFlowRuntimeMetric(checkpoint, FlowStates.COMPLETED.toString())

        val status = flowMessageFactory.createFlowCompleteStatusMessage(checkpoint, request.result)

        val flowCleanupTime = context.config.getLong(PROCESSING_FLOW_CLEANUP_TIME)
        val expiryTime = Instant.now().plusMillis(flowCleanupTime).toEpochMilli()
        val records = listOf(
            flowRecordFactory.createFlowStatusRecord(status),
            flowRecordFactory.createFlowMapperEventRecord(checkpoint.flowKey.toString(), ScheduleCleanup(expiryTime))
        )

        context.checkpoint.markDeleted()
        return context.copy(outputRecords = context.outputRecords + records)
    }
}
