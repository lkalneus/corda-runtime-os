package net.corda.cli.plugin.initialconfig

import com.github.stefanbirkner.systemlambda.SystemLambda
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine

class TestInitialConfigPluginDb {
    @Test
    fun testDbConfigCreationMissingOptions() {
        val colorScheme = CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.OFF).build()
        val app = InitialConfigPlugin.PluginEntryPoint()

        val outText = SystemLambda.tapSystemErrNormalized {
            CommandLine(
                app
            ).setColorScheme(colorScheme).execute("create-db-config")
        }
        assertThat(outText).startsWith(
            "Missing required options: '--name=<connectionName>'," +
                " '--jdbcURL=<jdbcUrl>', '--user=<username>', '--password=<password>'," +
                " '--salt=<salt>', '--passphrase=<passphrase>'"
        )
    }

    @Test
    fun testDbConfigCreation() {
        val colorScheme = CommandLine.Help.ColorScheme.Builder().ansi(CommandLine.Help.Ansi.OFF).build()
        val app = InitialConfigPlugin.PluginEntryPoint()

        val outText = SystemLambda.tapSystemOutNormalized {
            CommandLine(
                app
            ).setColorScheme(colorScheme).execute(
                "create-db-config",
                "-n", "connection name",
                "-j", "jdbd:postgres://testurl",
                "-u", "testuser",
                "-p", "password",
                "-s", "not so secure",
                "-e", "not so secret"
            )
        }
        println(outText)
        assertThat(outText).startsWith(
            "insert into CONFIG.db_connection" +
                " (config, description, connection_id, connection_name, privilege, update_actor, update_ts, version)" +
                " values ('{\"database\":{\"jdbc\":{\"url\":\"jdbd:postgres://testurl\"}," +
                "\"pass\":{\"configSecret\":{\"encryptedSecret\":"
        ).contains(
            "\"}},\"user\":\"testuser\"}}'," +
                " 'Initial configuration - autogenerated by setup script',"
        ).contains(
            "'connection name'," +
                " 'DML'," +
                " 'Setup Script',"
        ).endsWith("Z', 0)\n")
    }
}