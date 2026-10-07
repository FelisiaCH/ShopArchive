package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.CommandService
import xyz.felismp.shoparchive.api.RegistryConflictException
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.notify.DefaultShopEvents
import xyz.felismp.shoparchive.server.notify.Notify
import xyz.felismp.shoparchive.server.notify.registerNotifyCommand
import xyz.felismp.shoparchive.server.notify.registerNotifyNodes
import xyz.felismp.shoparchive.server.backup.BackupScheduler
import xyz.felismp.shoparchive.server.backup.BackupService
import xyz.felismp.shoparchive.server.backup.registerBackupCommand
import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.OverrideException
import xyz.felismp.shoparchive.server.config.Overrides
import xyz.felismp.shoparchive.server.auth.Auth
import xyz.felismp.shoparchive.server.auth.registerAuthNodes
import xyz.felismp.shoparchive.server.net.HttpStartException
import xyz.felismp.shoparchive.server.net.Network
import xyz.felismp.shoparchive.server.net.registerIpBanCommands
import xyz.felismp.shoparchive.server.net.registerSayCommand
import xyz.felismp.shoparchive.api.plugin.PluginRouteService
import xyz.felismp.shoparchive.server.net.CORE_SERVICE_PRIORITY
import xyz.felismp.shoparchive.server.plugins.BuildInfo
import xyz.felismp.shoparchive.server.plugins.DefaultPluginRouteService
import xyz.felismp.shoparchive.server.plugins.PluginCommand
import xyz.felismp.shoparchive.server.plugins.PluginManager
import xyz.felismp.shoparchive.server.records.Records
import xyz.felismp.shoparchive.server.records.registerRecordCommands
import xyz.felismp.shoparchive.server.records.registerRecordNodes
import xyz.felismp.shoparchive.server.tls.CertificateException
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.server.users.registerUserCommands
import java.lang.invoke.MethodHandles
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Clock
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Core entry point, invoked reflectively by shoparchive-server.jar as `MainKt.main(args)`
 * after it sets `shoparchive.root`. Top-level so the file compiles to class `MainKt` - do not wrap
 * this in an object or add @JvmName, the launcher depends on that exact class name. `args` are the
 * command-line overrides the launcher forwards (`--port`, `--bind-address`, `--log-level`, `--no-plugins`, `--no-patches`, `--ignore-hotfix`).
 */
fun main(args: Array<String>) {
    val rootProperty = System.getProperty("shoparchive.root")
    if (rootProperty.isNullOrBlank()) {
        System.err.println("ShopArchive core must be started through shoparchive-server.jar")
        exitProcess(1)
    }
    val root: Path = Path.of(rootProperty)

    // Level and timezone are the defaults until the config below is loaded and applied.
    Log.start(root)
    Log.info("Starting ShopArchive ${coreVersion()}" + BuildInfo.load().build?.let { " (build $it)" }.orEmpty())
    Log.info(runtimeLine())
    Log.info("Root: $root")

    // Before anything is written: a bad flag must not leave a server-id or config files behind.
    val overrides = try {
        Overrides.parse(args)
    } catch (e: OverrideException) {
        Log.error("Invalid command-line argument: ${e.message}")
        exitProcess(2)
    }
    if (overrides.flags().isNotEmpty()) Log.info("Command-line overrides (not written to server.properties): ${overrides.flags().joinToString()}")

    val serverId = try {
        readOrCreateServerId(root)
    } catch (e: InvalidServerIdException) {
        Log.error(e.message ?: "data/server-id is invalid")
        exitProcess(1)
    }
    Log.info("Server id: $serverId")

    // Everything outside the record writer that changes business data holds this for a whole change; a backup freezes it (see DataBarrier).
    val barrier = DataBarrier()
    val config = ConfigService(root, overrides, barrier = barrier)
    try {
        config.load()
    } catch (e: ConfigFileException) {
        Log.error(e.message ?: "a config file cannot be read")
        exitProcess(1)
    }
    applyConfig(config)

    // The registries exist before the plugins so that a plugin's onLoad can fill them: its permission nodes must be
    // known before the user files are read, and its service overrides before the core builds on them.
    val permissions = Permissions()
    val services = Services()
    val commands = Commands()
    val plugins = PluginManager(root, config.plugins, services, commands, permissions, safeMode = overrides.noPlugins, noPatches = overrides.noPatches)
    // The core's service for the routes plugins add below /api/v1/x/; registered first so a plugin may replace it in onLoad.
    services.register(PluginRouteService::class.java, DefaultPluginRouteService(plugins.routes, services), CORE_SERVICE_PRIORITY, "core")
    // Also before the plugins, so one can subscribe in onLoad; a plugin may replace it like any service.
    val shopEvents = DefaultShopEvents()
    services.register(ShopEvents::class.java, shopEvents, CORE_SERVICE_PRIORITY, "core")
    Shutdown.register("events") { shopEvents.close() }
    try {
        plugins.loadAll()
    } catch (e: RegistryConflictException) {
        Log.error("Startup failed: ${e.message}")
        exitProcess(1)
    }
    registerAuthNodes(permissions)
    registerRecordNodes(permissions)
    registerNotifyNodes(permissions)
    registerCommandNodes(permissions)
    val users = UserStore(root, permissions, config, barrier = barrier)
    users.load()

    val records = Records(root, config, users, services, barrier = barrier)
    try {
        records.load()
    } catch (e: ConfigFileException) {
        Log.error(e.message ?: "a data file cannot be read")
        exitProcess(1)
    }
    // Registered before the network starts, so it stops after it: a request still being served can finish its write.
    Shutdown.register("records") { records.close() }

    val notify = Notify(root, config, services, records::closedDay, records::eventsSince, barrier = barrier)
    // Listens now so no event is missed, but the worker starts only after the plugins are enabled (below): before that the only notifier is the log one,
    // which would mark a message left from before a restart as sent without the channel plugin ever getting it.
    notify.listen()

    val backup = BackupService(root, { config.backup }, { config.timezone }, records::pauseWrites, ConsoleConfigLog, version = coreVersion(), serverId = serverId.toString(), barrier = barrier)

    val network = Network(root, config, serverId, services, barrier = barrier)
    val auth = Auth(root, config, users, services, serverId, network::pairingEndpoints, records = records, barrier = barrier)
    services.register(CommandService::class.java, DefaultCommandService(commands, auth.audit, services), CORE_SERVICE_PRIORITY, "core")

    try {
        registerCoreCommands(
            commands, config, mapOf("users" to users::load, "devices" to auth.devices::load, "data" to records::loadData),
            { network.statusLines() + auth.statusLines() + records.statusLines() + notify.statusLines() + backup.statusLines() + plugins.statusLines() }, alsoOnReload = setOf("devices", "data"), plugins = plugins,
        )
        commands.register(PluginCommand(plugins), "core")
        registerRecordCommands(commands, records)
        registerNotifyCommand(commands, notify.outbox)
        registerBackupCommand(commands, backup, root)
        registerUserCommands(commands, users, auth.console::show, auth.accounts::reset, auth.accounts::disable)
        auth.accounts.register(commands)
        registerIpBanCommands(commands, network.bans)
        registerSayCommand(commands, services)
        // Last before the network: the core is complete, so a plugin can use its services and add commands and routes.
        plugins.enableAll()
    } catch (e: RegistryConflictException) {
        Log.error("Startup failed: ${e.message}")
        exitProcess(1)
    }
    // Only now: the channel plugins have replaced the core's log notifier, so nothing is sent before they can.
    notify.outbox.start()
    // Registered after the records and before the network starts, so on a stop the plugins end after the network
    // has stopped taking requests and before the records are closed.
    Shutdown.register("plugins") { plugins.disableAll() }
    // After the plugins, so on a stop the worker ends before any of them is disabled: a channel plugin's notifier is still the one
    // that sends until then, and a message being sent is left unknown. The records close after both.
    Shutdown.register("notifications") { notify.close() }

    // Before the network: the owner never has a moment without a PIN in which anyone could log in and choose one.
    val firstRunLogin = firstRunSetup(config, users, records.branches, auth.devices, auth.hasher, auth.sessions)

    try {
        network.start()
    } catch (e: CertificateException) {
        Log.error(e.message ?: "the certificate cannot be loaded")
        exitProcess(1)
    } catch (e: HttpStartException) {
        Log.error("Startup failed: ${e.message}. Is another program (or another ShopArchive) using this port? Change it with port in server.properties or --port.")
        exitProcess(1)
    }

    // Registered after the records' hook, so on a stop it runs before it: a backup in progress ends before the writer closes.
    val backups = BackupScheduler(backup, { config.backup }, { config.timezone }, Clock.systemDefaultZone(), ConsoleConfigLog)
    backups.start()
    Shutdown.register("backup") { backups.stop() }

    Shutdown.register("server") { Log.info("Stopping server") }
    // An uncaught exception on any thread (e.g. Console.run()'s readLine throwing IOException) must not
    // look like a clean stop to the OS - it is reported in crash-reports/ and marks the process failed, so a
    // later Shutdown.stop(0) (console `stop`, SIGTERM) exits 1 and the service templates' restart-on-failure
    // (shoparchive.service, shoparchive-winsw.xml) actually sees a non-zero exit.
    CrashReport.install(root, config.timezone)
    // Only reached once startup above fully succeeded - install() routes SIGTERM/SIGINT through
    // Shutdown.stop(0), which exits 0 unless something already called Shutdown.fail(), so a later
    // failure path that exits non-zero must report itself via fail() first.
    Shutdown.install()

    val startTimeMillis = ManagementFactory.getRuntimeMXBean().startTime
    val elapsedSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000.0
    Log.info(String.format(Locale.ROOT, "Done (%.2fs)! For help, type \"help\"", elapsedSeconds))

    // After "Done", so the owner's name and PIN are the last thing on the screen.
    printFirstRun(firstRunLogin)

    Console(commands).run()
}

/** Hands the settings that other parts of the core read at a fixed point to them. Call again after a reload. */
internal fun applyConfig(config: ConfigService) {
    Log.apply(config.logLevel, config.timezone)
    Shutdown.registryWaitMs = config.shutdownHookTimeoutMs.toLong()
}

/** Implementation-Version from this jar's manifest (set by server/build.gradle.kts), "dev" outside a built jar. */
internal fun coreVersion(): String =
    MethodHandles.lookup().lookupClass().`package`?.implementationVersion ?: "dev"

/** One line naming the Java and OS this runs on; printed at boot and in every crash report. */
internal fun runtimeLine(): String =
    "Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) on " +
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}"
