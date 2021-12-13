package net.corda.applications.examples.dbworker

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import net.corda.db.admin.LiquibaseSchemaMigrator
import net.corda.db.admin.impl.ClassloaderChangeLog
import net.corda.db.core.PostgresDataSourceFactory
import net.corda.db.schema.DbSchema
import net.corda.libs.configuration.SmartConfig
import net.corda.libs.configuration.SmartConfigFactory
import net.corda.libs.permissions.storage.writer.factory.PermissionStorageWriterProcessorFactory
import net.corda.lifecycle.LifecycleCoordinator
import net.corda.lifecycle.LifecycleCoordinatorFactory
import net.corda.lifecycle.LifecycleEvent
import net.corda.lifecycle.StartEvent
import net.corda.lifecycle.StopEvent
import net.corda.lifecycle.createCoordinator
import net.corda.messaging.api.subscription.factory.SubscriptionFactory
import net.corda.orm.DbEntityManagerConfiguration
import net.corda.orm.EntityManagerFactoryFactory
import net.corda.osgi.api.Application
import net.corda.osgi.api.Shutdown
import net.corda.permissions.model.ChangeAudit
import net.corda.permissions.model.Group
import net.corda.permissions.model.GroupProperty
import net.corda.permissions.model.Permission
import net.corda.permissions.model.Role
import net.corda.permissions.model.RoleGroupAssociation
import net.corda.permissions.model.RolePermissionAssociation
import net.corda.permissions.model.RoleUserAssociation
import net.corda.permissions.model.User
import net.corda.permissions.model.UserProperty
import net.corda.permissions.storage.writer.PermissionStorageWriterService
import net.corda.v5.base.util.contextLogger
import org.osgi.framework.FrameworkUtil
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Reference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.io.StringWriter
import java.util.*
import javax.persistence.EntityManagerFactory
import javax.sql.DataSource

@Component
@Suppress("LongParameterList")
class DbWorkerPrototypeApp @Activate constructor(
    @Reference(service = SubscriptionFactory::class)
    private val subscriptionFactory: SubscriptionFactory,
    @Reference(service = Shutdown::class)
    private val shutDownService: Shutdown,
    @Reference(service = LifecycleCoordinatorFactory::class)
    private val coordinatorFactory: LifecycleCoordinatorFactory,
    @Reference(service = LiquibaseSchemaMigrator::class)
    private val schemaMigrator: LiquibaseSchemaMigrator,
    @Reference(service = EntityManagerFactoryFactory::class)
    private val entityManagerFactoryFactory: EntityManagerFactoryFactory,
    @Reference(service = SmartConfigFactory::class)
    private val smartConfigFactory: SmartConfigFactory,
    @Reference(service = PermissionStorageWriterProcessorFactory::class)
    private val permissionStorageWriterProcessorFactory: PermissionStorageWriterProcessorFactory
) : Application {

    private companion object {
        val log: Logger = contextLogger()
        val consoleLogger: Logger = LoggerFactory.getLogger("Console")

        const val BOOTSTRAP_SERVERS = "bootstrap.servers"
        const val KAFKA_COMMON_BOOTSTRAP_SERVER = "messaging.kafka.common.bootstrap.servers"

        const val TOPIC_PREFIX = "messaging.topic.prefix"
        const val CONFIG_TOPIC_NAME = "config.topic.name"
    }

    private var lifeCycleCoordinator: LifecycleCoordinator? = null

    private var permissionStorageWriterService: PermissionStorageWriterService? = null

    @Suppress("SpreadOperator")
    override fun startup(args: Array<String>) {
        consoleLogger.info("DB Worker prototype application starting")
        val parameters = CliParameters()
        CommandLine(parameters).parseArgs(*args)

        if (parameters.helpRequested) {
            CommandLine.usage(CliParameters(), System.out)
            shutDownService.shutdown(FrameworkUtil.getBundle(this::class.java))
        } else {
            log.info("Creating life cycle coordinator")
            lifeCycleCoordinator =
                coordinatorFactory.createCoordinator<DbWorkerPrototypeApp>(
                ) { event: LifecycleEvent, _: LifecycleCoordinator ->
                    log.info("LifecycleEvent received: $event")
                    when (event) {
                        is StartEvent -> {
                            consoleLogger.info("Received start event")
                        }
                        is StopEvent -> {
                            consoleLogger.info("Received stop event")
                        }
                        else -> {
                            log.error("$event unexpected!")
                        }
                    }
                }
            log.info("Starting life cycle coordinator")
            lifeCycleCoordinator?.start()

            if (parameters.dbUrl.isBlank()) {
                consoleLogger.error("DB connectivity details were not provided")
                shutdown()
                return
            }

            consoleLogger.info("DB to be used: ${parameters.dbUrl}")
            val dbSource = PostgresDataSourceFactory().create(
                parameters.dbUrl,
                parameters.dbUser,
                parameters.dbPass
            )
            applyLiquibaseSchema(dbSource)
            val emf = obtainEntityManagerFactory(dbSource)

            val nodeConfig: SmartConfig = getBootstrapConfig(null)

            log.info("Creating and starting PermissionStorageWriterService")
            permissionStorageWriterService =
                PermissionStorageWriterService(
                    coordinatorFactory,
                    emf,
                    subscriptionFactory,
                    permissionStorageWriterProcessorFactory,
                    nodeConfig
                ).also { it.start() }

            consoleLogger.info("DB Worker prototype application fully started")
        }
    }

    private fun applyLiquibaseSchema(dbSource: DataSource) {
        val schemaClass = DbSchema::class.java
        val bundle = FrameworkUtil.getBundle(schemaClass)
        log.info("RBAC schema bundle $bundle")

        val fullName = schemaClass.packageName + ".rbac"
        val resourcePrefix = fullName.replace('.', '/')
        val cl = ClassloaderChangeLog(
            linkedSetOf(
                ClassloaderChangeLog.ChangeLogResourceFiles(
                    fullName,
                    listOf("$resourcePrefix/db.changelog-master.xml"),
                    classLoader = schemaClass.classLoader
                )
            )
        )
        StringWriter().use {
            // Cannot use DbSchema.RPC_RBAC schema for LB here as this schema needs to be created ahead of change
            // set being applied
            schemaMigrator.createUpdateSql(dbSource.connection, cl, it, LiquibaseSchemaMigrator.PUBLIC_SCHEMA)
            log.info("Schema creation SQL: $it")
        }
        schemaMigrator.updateDb(dbSource.connection, cl, LiquibaseSchemaMigrator.PUBLIC_SCHEMA)

        log.info("Liquibase schema applied")
    }

    private fun obtainEntityManagerFactory(dbSource: DataSource) : EntityManagerFactory {
        return entityManagerFactoryFactory.create(
            "RPC RBAC",
            listOf(
                User::class.java,
                Group::class.java,
                Role::class.java,
                Permission::class.java,
                UserProperty::class.java,
                GroupProperty::class.java,
                ChangeAudit::class.java,
                RoleUserAssociation::class.java,
                RoleGroupAssociation::class.java,
                RolePermissionAssociation::class.java
            ),
            DbEntityManagerConfiguration(dbSource),
        )
    }

    private fun getBootstrapConfig(kafkaConnectionProperties: Properties?): SmartConfig {
        val bootstrapServer = getConfigValue(kafkaConnectionProperties, BOOTSTRAP_SERVERS)
        return smartConfigFactory.create(ConfigFactory.empty()
            .withValue(KAFKA_COMMON_BOOTSTRAP_SERVER, ConfigValueFactory.fromAnyRef(bootstrapServer))
            .withValue(
                CONFIG_TOPIC_NAME,
                ConfigValueFactory.fromAnyRef(getConfigValue(kafkaConnectionProperties, CONFIG_TOPIC_NAME))
            )
            .withValue(
                TOPIC_PREFIX,
                ConfigValueFactory.fromAnyRef(getConfigValue(kafkaConnectionProperties, TOPIC_PREFIX, ""))
            ))
    }

    private fun getConfigValue(properties: Properties?, path: String, default: String? = null): String {
        var configValue = System.getProperty(path)
        if (configValue == null && properties != null) {
            configValue = properties[path].toString()
        }

        if (configValue == null) {
            if (default != null) {
                return default
            }
            log.error("No $path property found! Pass property in via --kafka properties file or via -D$path")
            shutdown()
        }
        return configValue
    }

    override fun shutdown() {
        consoleLogger.info("Shutting down DB Worker prototype application")
        lifeCycleCoordinator?.stop()
        lifeCycleCoordinator = null
        permissionStorageWriterService?.stop()
        permissionStorageWriterService = null
    }
}

class CliParameters {
    @CommandLine.Option(
        names = ["-k", "--kafka"],
        paramLabel = "KAKFA",
        description = ["Kafka broker"]
    )
    var kafka: String = ""

    @Suppress("ForbiddenComment")
    @CommandLine.Option(
        names = ["-j", "--jdbc-url"],
        paramLabel = "JDBC URL",
        description = ["JDBC URL for cluster db"]
    )
    var dbUrl: String = ""
    @CommandLine.Option(
        names = ["-u", "--db-user"],
        paramLabel = "DB USER",
        description = ["Cluster DB username"]
    )
    var dbUser: String = ""
    @CommandLine.Option(
        names = ["-p", "--db-password"],
        paramLabel = "DB PASSWORD",
        description = ["Cluster DB password"]
    )
    var dbPass: String = ""

    @CommandLine.Option(names = ["-h", "--help"], usageHelp = true, description = ["Display help and exit"])
    var helpRequested = false
}