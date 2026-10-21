package xyz.felismp.shoparchive.server.net

import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.text
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Slf4jToLogTest {
    @TempDir
    lateinit var root: Path

    @BeforeTest
    fun setUp() {
        prepareRoot(root)
        Log.start(root)
    }

    @AfterTest
    fun tearDown() = Log.close()

    @Test
    fun warningsAndErrorsOfLibrariesReachTheServerLogAndChatterDoesNot() {
        val logger = LoggerFactory.getLogger("io.ktor.test.Thing")

        logger.info("chatter {}", 1)
        logger.warn("careful {}", 2)
        logger.error("broken {}", 3, IllegalStateException("boom"))
        logger.warn("TLS handshake failed:", javax.net.ssl.SSLException("not an SSL/TLS record"))
        val pipeline = LoggerFactory.getLogger("io.netty.channel.DefaultChannelPipeline")
        pipeline.warn("An exceptionCaught() event was fired, and it reached at the tail of the pipeline.", java.net.SocketException("Connection reset"))
        pipeline.warn("tail {}", "other", IllegalStateException("not I/O"))
        Log.close()

        val text = root.text("logs/latest.log")
        assertFalse(logger.isInfoEnabled)
        assertFalse("chatter" in text)
        assertTrue("WARN] Thing: careful 2" in text, text)
        assertTrue("ERROR] Thing: broken 3" in text, text)
        assertFalse("handshake" in text, text)
        assertFalse("Connection reset" in text, text)
        assertTrue("WARN] DefaultChannelPipeline: tail other" in text, text)
    }
}
