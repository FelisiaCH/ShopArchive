package xyz.felismp.shoparchive.spike.server

import com.sun.jna.Native
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import net.minecrell.terminalconsole.TerminalConsoleAppender
import org.apache.logging.log4j.LogManager
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private const val VERSION = "spike-1"
private const val SERVICE_NAME = "ShopArchive spike"
private object Here

private val log = LogManager.getLogger("spike")
private val stdin by lazy { BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)) }
private var lineReader: LineReader? = null

private fun readLine(prompt: String): String? {
    val r = lineReader ?: return stdin.readLine()
    return try { r.readLine(prompt) } catch (_: EndOfFileException) { null } catch (_: UserInterruptException) { "stop" }
}

fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    val rootArg = opt("--root")
    if (rootArg != null && '?' in rootArg) {
        // Verified on Windows (ACP Cp1252): java.exe turns every non-ANSI char (Lao) in argv into '?'.
        log.error("--root lost characters (contains '?'): Windows java.exe cannot pass Lao in command-line args. cd into the folder and omit --root (start.bat does this).")
        exitProcess(2)
    }
    val jarDir = Path.of(Here::class.java.protectionDomain.codeSource.location.toURI()).parent
    val root = (rootArg?.let(Path::of) ?: jarDir).toAbsolutePath().normalize()
    val port = opt("--port")?.toInt() ?: 8443
    Files.createDirectories(root)

    log.info("ShopArchive spike server {} | root={} | port={}", VERSION, root, port)
    log.info("Lao sample: ສະບາຍດີ ຮ້ານຄ້າ ທົດສອບ | Thai sample: สวัสดี ร้านค้า ทดสอบ")
    log.info("Encodings: default={} stdout={} native={} jnu={}", java.nio.charset.Charset.defaultCharset(),
        System.getProperty("stdout.encoding"), System.getProperty("native.encoding"), System.getProperty("sun.jnu.encoding"))
    log.info("JNA native load OK: jna={} jnidispatch={} tmpdir={}", Native.VERSION, Native.VERSION_NATIVE, System.getProperty("jna.tmpdir") ?: System.getProperty("java.io.tmpdir"))
    val terminal: Terminal? = TerminalConsoleAppender.getTerminal()
    log.info("JLine terminal: {}", terminal?.let { "${it.javaClass.name} type=${it.type} size=${it.width}x${it.height}" } ?: "none (stdin not a real terminal -> plain stdin fallback)")
    if (terminal != null) {
        lineReader = LineReaderBuilder.builder().terminal(terminal).build()
        TerminalConsoleAppender.setReader(lineReader)
    }

    val idFile = root.resolve("data").resolve("server-id")
    val serverId = if (Files.exists(idFile)) Files.readString(idFile).trim()
    else UUID.randomUUID().toString().also { Files.createDirectories(idFile.parent); Files.writeString(idFile, it) }

    val identity = loadOrCreateIdentity(root.resolve("certs"))
    val ips = lanIpv4()
    val urls = (ips.map { it.hostAddress } + "localhost").map { "https://$it:$port" }
    log.info("Certificate: {} ({} .. {})", if (identity.created) "created" else "reused from certs/", identity.cert.notBefore.toInstant(), identity.cert.notAfter.toInstant())

    val wsOpen = AtomicInteger()
    val server = embeddedServer(Netty, configure = {
        sslConnector(identity.keyStore, KEY_ALIAS, { KEYSTORE_PASSWORD.toCharArray() }, { KEYSTORE_PASSWORD.toCharArray() }) { this.port = port }
    }) {
        install(WebSockets) { pingPeriod = 15.seconds }
        routing {
            get("/ping") { call.respondText("""{"ok":true,"time":"${Instant.now()}"}""", ContentType.Application.Json) }
            webSocket("/ws") {
                wsOpen.incrementAndGet()
                try {
                    for (frame in incoming) if (frame is Frame.Text) send(Frame.Text(frame.readText()))
                } finally {
                    wsOpen.decrementAndGet()
                }
            }
        }
    }
    try {
        server.start(wait = false)
    } catch (e: Exception) {
        log.error("Cannot listen on port {}: {}", port, e.toString())
        exitProcess(1)
    }

    val mdns = ips.filter { it.isSiteLocalAddress }.mapNotNull { ip ->
        try {
            JmDNS.create(ip, "shoparchive-spike").also {
                it.registerService(ServiceInfo.create("_shoparchive._tcp.local.", SERVICE_NAME, port, 0, 0,
                    mapOf("server-id" to serverId, "name" to SERVICE_NAME, "version" to VERSION)))
                log.info("mDNS: _shoparchive._tcp advertised on {}", ip.hostAddress)
            }
        } catch (e: Exception) {
            log.warn("mDNS on {} failed: {}", ip.hostAddress, e.toString())
            null
        }
    }

    fun shutdown() {
        mdns.forEach { it.close() }
        server.stop(500, 1500)
    }
    Runtime.getRuntime().addShutdownHook(Thread { shutdown() })

    fun status() {
        log.info("PIN (paste into clients): {}", identity.pin)
        log.info("SPKI SHA-256 fingerprint: {}", identity.fingerprint)
        urls.forEach { log.info("URL: {}", it) }
        log.info("WebSocket clients open: {} | server-id: {} | root: {}", wsOpen.get(), serverId, root)
    }
    status()
    log.info("Type 'help' for commands.")

    while (true) {
        val line = readLine("> ")
        if (line == null) {
            log.info("stdin closed; server keeps running (Ctrl+C to stop).")
            Thread.sleep(Long.MAX_VALUE)
            return
        }
        val parts = line.trim().split(Regex("\\s+"), 2)
        val rest = parts.getOrElse(1) { "" }
        when (parts[0].lowercase()) {
            "" -> {}
            "help" -> log.info("help | status | say <text> | fstest [n=1000] | stop")
            "status" -> status()
            "say" -> log.info("say: {}  [{} code points: {}]", rest, rest.codePointCount(0, rest.length),
                rest.codePoints().limit(12).toArray().joinToString(" ") { "U+%04X".format(it) })
            "fstest" -> fsTest(root, rest.ifBlank { "1000" }.toIntOrNull() ?: 1000, ::readLine)
            "stop" -> exitProcess(0) // the shutdown hook closes mDNS + Ktor
            else -> log.info("unknown command '{}' (try help)", parts[0])
        }
    }
}
