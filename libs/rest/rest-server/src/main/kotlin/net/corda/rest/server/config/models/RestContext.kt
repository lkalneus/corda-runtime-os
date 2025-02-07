package net.corda.rest.server.config.models

data class RestContext(
    val version: String,
    val basePath: String,
    val title: String,
    val description: String
) {
    object Defaults {
        const val version = "1"
        const val basePath = "/api"
        const val title = ""
        const val description = ""
    }
}