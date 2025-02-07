package net.corda.utilities

import java.io.OutputStream
import java.io.PrintStream
import java.time.Duration
import java.util.Collections
import org.slf4j.Logger
import org.slf4j.MDC

inline fun <T> logElapsedTime(label: String, logger: Logger, body: () -> T): T {
    // Use nanoTime as it's monotonic.
    val now = System.nanoTime()
    var failed = false
    try {
        return body()
    } catch (th: Throwable) {
        failed = true
        throw th
    } finally {
        val elapsed = Duration.ofNanos(System.nanoTime() - now).toMillis()
        val msg = (if (failed) "Failed " else "") + "$label took $elapsed msec"
        logger.info(msg)
    }
}

private const val MAX_SIZE = 100
private val warnings = Collections.newSetFromMap(createSimpleCache<String, Boolean>(MAX_SIZE)).toSynchronised()

/**
 * Utility to help log a warning message only once.
 * It implements an ad hoc Fifo cache because there's none available in the standard libraries.
 */
fun Logger.warnOnce(warning: String) {
    if (warnings.add(warning)) {
        this.warn(warning)
    }
}

/**
 * Run a code block temporary suppressing any StdErr output that might be produced
 */
fun <T : Any?> executeWithStdErrSuppressed(block: () -> T) : T {
    val initial = System.err
    return try {
        System.setErr(PrintStream(OutputStream.nullOutputStream()))
        block()
    } finally {
        System.setErr(initial)
    }
}

/**
 * Push the map of [mdcProperties] into the logging MDC, run the code provided in [block] and then remove the [mdcProperties]
 * @param mdcProperties properties to push into mdc and then remove at the end
 * @param block the function to execute whose result is returned
 * @return the result of the [block] function
 */
fun <R> withMDC(mdcProperties: Map<String, String>, block: () -> R) : R {
    try {
        setMDC(mdcProperties)
        return block()
    } finally {
        clearMDC(mdcProperties)
    }
}

/**
 * Push the map of [mdcData] into the logging MDC
 */
fun setMDC(mdcData: Map<String, String>) {
    MDC.setContextMap(mdcData)
}

/**
 * Clear the logging MDC of the set of keys in [mdcDataKeys]
 */
fun clearMDC(mdcDataKeys: Set<String>) {
    MDC.getMDCAdapter().apply {
        mdcDataKeys.forEach {
            remove(it)
        }
    }
}

/**
 * Clear the logging MDC of the data stored in [mdcData]
 */
fun clearMDC(mdcData: Map<String, String>) {
    clearMDC(mdcData.keys)
}

/**
 * Clear the Log4j logging MDC of all data stored there.
 */
fun clearMDC() {
    MDC.clear()
}