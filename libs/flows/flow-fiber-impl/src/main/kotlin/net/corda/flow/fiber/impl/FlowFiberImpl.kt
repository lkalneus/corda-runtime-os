package net.corda.flow.fiber.impl

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.FiberScheduler
import net.corda.data.flow.FlowKey
import net.corda.data.flow.state.Checkpoint
import net.corda.flow.fiber.FlowContinuation
import net.corda.flow.fiber.FlowFiber
import net.corda.flow.fiber.HousekeepingState
import net.corda.flow.fiber.NonSerializableState
import net.corda.flow.fiber.requests.FlowIORequest
import net.corda.v5.application.flows.Destination
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.identity.Party
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.concurrent.getOrThrow
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.debug
import net.corda.v5.base.util.uncheckedCast
import org.slf4j.Logger
import org.slf4j.MDC
import java.nio.ByteBuffer

class TransientReference<out A>(@Transient val value: A)

@Suppress("TooManyFunctions", "ComplexMethod", "LongParameterList")
class FlowFiberImpl<R>(
    private val id: FlowKey,
    override val logic: Flow<R>,
    scheduler: FiberScheduler
) : Fiber<Unit>(id.toString(), scheduler), FlowFiber<R> {

    private companion object {
        val log: Logger = contextLogger()
    }

    private var nonSerializableStateReference: TransientReference<NonSerializableState>? = null
    private var nonSerializableState: NonSerializableState
        // After the flow has been created, the transient values should never be null
        get() = nonSerializableStateReference!!.value
        set(values) {
            check(nonSerializableStateReference?.value == null) { "The transient values should only be set once when initialising a flow" }
            nonSerializableStateReference = TransientReference(values)
        }
    private var housekeepingStateReference: TransientReference<HousekeepingState>? = null
    private var housekeepingState: HousekeepingState
        // After the flow has been created, the transient state should never be null
        get() = housekeepingStateReference!!.value
        set(state) {
            housekeepingStateReference = TransientReference(state)
        }

    val creationTime: Long = System.currentTimeMillis()

    private fun setLoggingContext() {
        MDC.put("flow-id", id.flowId)
        MDC.put("fiber-id", this.getId().toString())
        MDC.put("thread-id", Thread.currentThread().id.toString())
    }

    private fun Throwable.isUnrecoverable(): Boolean = this is VirtualMachineError && this !is StackOverflowError

    private fun logFlowError(throwable: Throwable) {
        log.warn("Flow raised an error: ${throwable.message}")
    }

    @Suspendable
    override fun run() {
        setLoggingContext()
        log.debug { "Calling flow: $logic" }

        housekeepingState = try {
            val result = executeFlowLogic()
            log.debug { "flow ended $id successfully}" }
            housekeepingState.copy(output = FlowIORequest.FlowFinished(result))
        } catch (t: Throwable) {
            terminateUnrecoverableError(t)
            logFlowError(t)
            housekeepingState.copy(output = FlowIORequest.FlowFailed(t))
        }

        housekeepingState.suspended.complete(null)
    }

    @Suspendable
    private fun executeFlowLogic(): R {
        //TODOs: we might need the sandbox class loader
        Thread.currentThread().contextClassLoader = logic.javaClass.classLoader
        log.info("Executing flow and about to make initial checkpoint")
        suspend(FlowIORequest.ForceCheckpoint)
        log.info("Made initial checkpoint")
        return logic.call()
    }

    private fun terminateUnrecoverableError(t: Throwable) {
        if (t.isUnrecoverable()) {
            errorAndTerminate(
                "Caught unrecoverable error from flow. Forcibly terminating the JVM, this might leave " +
                        "resources open, and most likely will.",
                t
            )
        }
    }

    @Suspendable
    override fun <SUSPENDRETURN : Any> suspend(request: FlowIORequest<SUSPENDRETURN>): SUSPENDRETURN {
        log.info("Suspend (${request::class.java.name}) $request")
        housekeepingState = housekeepingState.copy(output = request)
        parkAndSerialize { _, _ ->
            val fiberState = nonSerializableState.checkpointSerializer.serialize(this)
            housekeepingState.suspended.complete(fiberState)
        }
        setLoggingContext()
        return when (val outcome = housekeepingState.input) {
            is FlowContinuation.Run -> uncheckedCast(outcome.value)
            is FlowContinuation.Error -> throw outcome.exception
            else -> throw IllegalStateException("Tried to return when suspension outcome says to continue")
        }
    }

    override fun initiateFlow(destination: Destination, wellKnownParty: Party): FlowSession {
        TODO("Not yet implemented")
    }

    override fun <SUBFLOWRETURN> subFlow(currentFlow: Flow<*>, subFlow: Flow<SUBFLOWRETURN>): SUBFLOWRETURN {
        TODO("Not yet implemented")
    }

    override fun updateTimedFlowTimeout(timeoutSeconds: Long) {
        TODO("Not yet implemented")
    }

    override fun waitForCheckpoint(): Pair<Checkpoint, FlowIORequest<*>> {
        val checkpoint = housekeepingState.checkpoint.apply {
            fiber = ByteBuffer.wrap(housekeepingState.suspended.getOrThrow() ?: byteArrayOf())
        }
        return checkpoint to housekeepingState.output!!
    }

    override fun startFlow(): Fiber<Unit> = start()

    override fun nonSerializableState(nonSerializableState: NonSerializableState) {
        this.nonSerializableState = nonSerializableState
    }

    override fun housekeepingState(housekeepingState: HousekeepingState) {
        this.housekeepingState = housekeepingState
    }
}