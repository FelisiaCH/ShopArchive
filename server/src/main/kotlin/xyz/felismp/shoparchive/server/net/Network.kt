package xyz.felismp.shoparchive.server.net

import xyz.felismp.shoparchive.api.CertificateService
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.UpdateService
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.Shutdown
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.coreVersion
import xyz.felismp.shoparchive.server.tls.CertificateException
import xyz.felismp.shoparchive.server.tls.CertificateHosts
import xyz.felismp.shoparchive.server.tls.CertificateManager
import xyz.felismp.shoparchive.server.tls.ServerCertificate
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Priority of the core's own services; a plugin registers with a higher one to replace them. */
internal const val CORE_SERVICE_PRIORITY = 0

/** `/info` from the live config, so `reload` changes the name and message at once. */
internal class DefaultInfoService(private val config: ConfigService, private val serverId: UUID) : InfoService {
    override fun info() = InfoResponse(serverId.toString(), config.serverName, config.motd, coreVersion(), PROTOCOL_VERSION)
}

/**
 * Everything the server shows to the network: certificate, HTTPS listener, IP bans and the LAN announcement.
 * Created before the console commands (they need [bans] and [statusLines]); [start] runs once the config is loaded.
 */
internal class Network(
    private val root: java.nio.file.Path,
    private val config: ConfigService,
    private val serverId: UUID,
    private val services: ServiceRegistry,
    barrier: DataBarrier = DataBarrier(),
    private val onStop: (name: String, hook: () -> Unit) -> Unit = Shutdown::register,
) {
    val bans = IpBans(root, barrier = barrier).also { it.load() }

    private var certificate: ServerCertificate? = null
    private var http: HttpServer? = null
    private var urls: List<String> = emptyList()
    private var settings: HttpSettings? = null
    private var lanAddresses: List<String> = emptyList()

    init {
        services.register(IpBanService::class.java, bans, CORE_SERVICE_PRIORITY, "core")
        services.register(InfoService::class.java, DefaultInfoService(config, serverId), CORE_SERVICE_PRIORITY, "core")
        services.register(UpdateService::class.java, DefaultUpdateService(root.resolve("downloads")), CORE_SERVICE_PRIORITY, "core")
    }

    /**
     * Loads or makes the certificate, starts the HTTPS listener and the announcement, and registers their shutdown.
     * @throws CertificateException the keystore exists but cannot be read
     * @throws HttpStartException the port cannot be listened on
     */
    fun start() {
        val lan = lanInterfaces()
        val hosts = CertificateHosts(lan.flatMap { it.addresses }.map(::hostText).distinct(), config.networkDomains)
        val cert = CertificateManager(root).loadOrCreate(hosts, config.certValidityDays)
        services.register(CertificateService::class.java, cert, CORE_SERVICE_PRIORITY, "core")
        certificate = cert

        val settings = HttpSettings(
            config.bindAddress, config.port, config.maxBodyKb * 1024L, config.requestTimeoutSeconds, config.auth.rateLimitPerMinute,
            entryBodyBytes = { config.records.entryBodyBytes(config.maxBodyKb * 1024L) },
        )
        this.settings = settings
        lanAddresses = hosts.addresses
        if (wsPingSeconds(settings.timeoutSeconds) != WS_PING_SECONDS) {
            Log.warn(
                "network.request-timeout-seconds is ${settings.timeoutSeconds}: WebSocket pings are sent every " +
                    "${wsPingSeconds(settings.timeoutSeconds)} s instead of $WS_PING_SECONDS s, so a quiet socket is not dropped",
            )
        }
        val server = HttpServer(settings, cert, services)
        server.start()
        http = server
        onStop("https") { server.stop() }

        urls = listenHosts(settings.bindAddress, hosts.addresses).map { "https://${urlHost(it)}:${settings.port}" }
        Log.info("HTTPS listening on ${urls.joinToString(", ")} - certificate fingerprint ${cert.fingerprint}")

        if (config.lanDiscovery) {
            val mdns = Mdns()
            mdns.start(MdnsAnnouncement(serverId.toString(), config.serverName, coreVersion(), settings.port))
            onStop("mdns") { mdns.stop() }
        }
    }

    /** The lines `status` adds. */
    fun statusLines(): List<String> {
        val cert = certificate ?: return listOf("HTTPS: not running")
        return listOf(
            "HTTPS: ${urls.joinToString(", ")}",
            "Certificate fingerprint: ${cert.fingerprint}",
            "Certificate expires: ${DATE.format(cert.expiresAt)}",
            "Open connections: ${http?.openConnections ?: 0}",
        )
    }

    /**
     * The up to two `host:port` a pairing tells a device to try: the first configured domain, then the first LAN
     * address. With neither, the address the server is bound to (or localhost).
     */
    fun pairingEndpoints(): List<String> {
        val port = settings?.port ?: config.port
        val bind = settings?.bindAddress ?: config.bindAddress
        val hosts = config.networkDomains.take(1) + listenHosts(bind, lanAddresses).filter { it != "localhost" }.take(1)
        return hosts.ifEmpty { listOf(if (bind == "0.0.0.0" || bind == "::") "localhost" else bind) }.map { "${urlHost(it)}:$port" }
    }

    private fun listenHosts(bindAddress: String, lanAddresses: List<String>): List<String> =
        if (bindAddress == "0.0.0.0" || bindAddress == "::") lanAddresses + "localhost" else listOf(bindAddress)

    private fun urlHost(host: String) = if (host.contains(':')) "[$host]" else host

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)
    }
}
