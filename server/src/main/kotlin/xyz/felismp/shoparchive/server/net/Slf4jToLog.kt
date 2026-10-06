package xyz.felismp.shoparchive.server.net

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import xyz.felismp.shoparchive.server.Log
import javax.net.ssl.SSLException

/**
 * Ktor and Netty log through SLF4J. Without a provider SLF4J prints a three-line warning at boot and drops everything,
 * including their warnings and errors; this one hands warnings and errors to [Log] (the console and latest.log).
 * Anything below warning is library chatter and is dropped, and so is a failed TLS handshake. Found by SLF4J through META-INF/services.
 */
class Slf4jToLog : SLF4JServiceProvider {
    private val markers = BasicMarkerFactory()
    private val mdc = NOPMDCAdapter()

    override fun getLoggerFactory() = ILoggerFactory { name -> LogForwarder(name) }
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion() = "2.0.99"
    override fun initialize() = Unit
}

private class LogForwarder(private val name: String) : LegacyAbstractLogger() {
    override fun getName() = name

    override fun isTraceEnabled() = false
    override fun isDebugEnabled() = false
    override fun isInfoEnabled() = false
    override fun isWarnEnabled() = true
    override fun isErrorEnabled() = true

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, messagePattern: String?, arguments: Array<out Any?>?, throwable: Throwable?) {
        // A client that is not TLS, or does not trust the certificate, fails the handshake. On a port open to the internet that is routine, not a warning.
        if (generateSequence(throwable) { it.cause }.any { it is SSLException }) return
        val text = "${name.substringAfterLast('.')}: ${MessageFormatter.arrayFormat(messagePattern, arguments).message}"
        if (level == Level.ERROR) Log.error(text, throwable) else Log.warn(text, throwable)
    }
}
