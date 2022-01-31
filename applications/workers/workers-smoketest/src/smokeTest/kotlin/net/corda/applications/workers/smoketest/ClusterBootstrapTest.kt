package net.corda.applications.workers.smoketest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant


class ClusterBootstrapTest {
    private val healthChecks = mapOf(
        // TODO: can take these from properties
        "crypto-worker" to 7001,
        "db-worker" to 7002,
        "flow-worker" to 7003,
        "rpc-worker" to 7004,
    )
    private val client = HttpClient.newBuilder().build()

    @Test
    fun checkCluster() {
        runBlocking(Dispatchers.Default) {
            val softly = SoftAssertions()
            // check all workers are up and "ready"
            healthChecks.map {
                async {
                    val response = tryUntil(Duration.ofSeconds(90)) { checkReady(it.key, it.value) }
                    if (response in 200..299)
                        println("${it.key} is ready")
                    else
                        softly.fail("Problem with ${it.key}, \"isReady\" returns: $response")
                }
            }.awaitAll()

            // TODO: http request to insert config and validate it has been persisted.

            softly.assertAll()
        }
    }

    private fun tryUntil(timeOut: Duration, function: () -> HttpResponse<String>): Int {
        var statusCode = -1
        val startTime = Instant.now()
        while (statusCode < 200 && Instant.now() < startTime.plusNanos(timeOut.toNanos())) {
            try {
                val response = function()
                statusCode = response.statusCode()
                if (statusCode in 200..299)
                    return statusCode
            } catch (connectionException: IOException) {
                println("Cannot connect.")
            }
            Thread.sleep(1000)
        }
        return statusCode
    }

    private fun checkReady(name: String, port: Int): HttpResponse<String> {
        val url = "http://localhost:$port/isReady"
        println("Checking $name on $url")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}