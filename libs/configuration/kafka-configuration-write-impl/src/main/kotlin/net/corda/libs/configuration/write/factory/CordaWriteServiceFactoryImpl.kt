package net.corda.libs.configuration.write.factory

import net.corda.data.config.Configuration
import net.corda.libs.configuration.write.ConfigWriteService
import net.corda.libs.configuration.write.ConfigWriteServiceImpl
import net.corda.messaging.api.publisher.config.PublisherConfig
import net.corda.messaging.api.publisher.factory.PublisherFactory
import net.corda.messaging.api.publisher.factory.createPublisher
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference

/**
 * Kafka implementation for [CordaWriteServiceFactory].
 * @property publisherFactory
 */
@Component
class CordaWriteServiceFactoryImpl @Activate constructor(
    @Reference(service = PublisherFactory::class)
    private val publisherFactory: PublisherFactory
) : CordaWriteServiceFactory {

    private val CONFIGURATION_WRITE_SERVICE = "CONFIGURATION_WRITE_SERVICE"

    override fun createWriteService(destination: String): ConfigWriteService {
        val publisher = publisherFactory.createPublisher<String, Configuration>(
            PublisherConfig(
                CONFIGURATION_WRITE_SERVICE,
                destination
            ), mapOf()
        )
        return ConfigWriteServiceImpl(destination, publisher)
    }
}