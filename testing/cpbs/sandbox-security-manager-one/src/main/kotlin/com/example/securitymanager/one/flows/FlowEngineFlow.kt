package com.example.securitymanager.one.flows

import net.corda.v5.application.flows.FlowEngine
import net.corda.v5.application.flows.SubFlow
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.util.loggerFor
import org.osgi.framework.BundleContext
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component

@Component
class FlowEngineFlow @Activate constructor(private val context: BundleContext): SubFlow<String> {
    private val logger = loggerFor<FlowEngineFlow>()

    @Suspendable
    override fun call(): String {
        val flowEngine = context.getServiceReference(FlowEngine::class.java)?.let(context::getService)
        logger.info("FlowEngine={}", flowEngine)
        return flowEngine?.flowId?.toString() ?: "FlowEngine not found"
    }
}
