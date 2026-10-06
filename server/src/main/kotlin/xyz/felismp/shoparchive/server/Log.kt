package xyz.felismp.shoparchive.server

import net.minecrell.terminalconsole.TerminalConsoleAppender
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import java.nio.file.Path
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The core's logger: Log4j2 with a coloured console (TerminalConsoleAppender, so log lines do not break the
 * line being typed) and `<root>/logs/latest.log`, which is rolled into `yyyy-MM-dd-N.log.gz` at every start and
 * at midnight of the configured timezone. Before [start] and after [close] it falls back to plain println, so
 * code that runs outside a booted server (tests, the last words of a shutdown) can still log.
 */
internal object Log {
    private const val TERMINAL_LOGGER = "shoparchive.terminal"
    private const val FILE_LOGGER = "shoparchive.file"
    private const val MAX_ROLLED_FILES_PER_DAY = 999

    private class Running(val root: Path, val zone: ZoneId, val context: LoggerContext) {
        val main: Logger = context.rootLogger
        val terminal: Logger = context.getLogger(TERMINAL_LOGGER)
        val fileOnly: Logger = context.getLogger(FILE_LOGGER)
    }

    @Volatile private var running: Running? = null

    /** Starts logging below [root]. The level and timezone are the defaults until [apply] is called with the configured ones. */
    fun start(root: Path, level: String = "info", zone: ZoneId = ZoneId.of("Asia/Vientiane")) {
        close()
        running = install(root, level, zone, rollOnStartup = true)
    }

    /**
     * Applies the configured level, and a changed timezone (which needs the file appender rebuilt: its day
     * boundary is fixed when it is created). Does nothing before [start].
     */
    fun apply(level: String, zone: ZoneId) {
        val current = running ?: return
        if (zone == current.zone) {
            Configurator.setRootLevel(Level.toLevel(level, Level.INFO))
            return
        }
        Configurator.shutdown(current.context)
        // The file just written to is today's; rolling it again at once would split this run's own lines.
        running = install(current.root, level, zone, rollOnStartup = false)
    }

    /** Flushes and closes the log files and the terminal. Shutdown calls this after everything else has logged. */
    fun close() {
        val current = running ?: return
        running = null
        Configurator.shutdown(current.context)
        TerminalConsoleAppender.close()
    }

    fun debug(msg: String) = log(Level.DEBUG, msg, null)
    fun info(msg: String) = log(Level.INFO, msg, null)
    fun warn(msg: String, cause: Throwable? = null) = log(Level.WARN, msg, cause)
    fun error(msg: String, cause: Throwable? = null) = log(Level.ERROR, msg, cause)

    /**
     * Prints [msg] on the console only - never to a file under `logs/`, whatever the log level. For secrets
     * such as pairing codes. (A service wrapper that captures stdout still sees it; that is outside the jar's reach.)
     */
    fun terminalOnly(msg: String) {
        val current = running
        if (current == null) plain(Level.INFO, msg, null) else current.terminal.info(msg)
    }

    /**
     * A command's reply: always on the console, whatever the log level, so a quiet `log-level` does not make
     * the console look dead; in latest.log only when the level lets info through.
     */
    fun output(msg: String) {
        val current = running
        if (current == null) return plain(Level.INFO, msg, null)
        current.terminal.info(msg)
        if (current.main.isInfoEnabled) current.fileOnly.info(msg)
    }

    private fun log(level: Level, msg: String, cause: Throwable?) {
        val current = running
        if (current == null) plain(level, msg, cause) else current.main.log(level, msg, cause)
    }

    private val PLAIN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun plain(level: Level, msg: String, cause: Throwable?) {
        if (level == Level.DEBUG) return
        val line = "[${LocalTime.now().format(PLAIN_TIME)} $level] $msg"
        if (level == Level.ERROR) System.err.println(line) else println(line)
        cause?.printStackTrace()
    }

    private fun install(root: Path, level: String, zone: ZoneId, rollOnStartup: Boolean): Running {
        val context = Configurator.initialize(configuration(root, Level.toLevel(level, Level.INFO), zone, rollOnStartup))
        return Running(root, zone, context)
    }

    private fun configuration(root: Path, level: Level, zone: ZoneId, rollOnStartup: Boolean): Configuration {
        val builder = ConfigurationBuilderFactory.newConfigurationBuilder()
        builder.setConfigurationName("ShopArchive")
        builder.setStatusLevel(Level.ERROR)
        // Shutdown closes the log last, after the other shutdown hooks have written their goodbyes.
        builder.setShutdownHook("disable")

        val coloredLevel = "%highlight{%level}{FATAL=red, ERROR=red, WARN=yellow, INFO=green, DEBUG=cyan, TRACE=blue}"
        builder.add(
            builder.newAppender("Console", TerminalConsoleAppender.PLUGIN_NAME).add(
                builder.newLayout("PatternLayout")
                    .addAttribute("pattern", "[%d{HH:mm:ss}{${zone.id}} $coloredLevel] %msg%n")
                    .addAttribute("disableAnsi", !TerminalConsoleAppender.isAnsiSupported())
                    .addAttribute("charset", "UTF-8")
            )
        )

        val policies = builder.newComponent("Policies").addComponent(builder.newComponent("TimeBasedTriggeringPolicy"))
        if (rollOnStartup) policies.addComponent(builder.newComponent("OnStartupTriggeringPolicy"))
        // Log4j wants '/' in its file patterns even on Windows.
        val logs = root.resolve("logs").toString().replace('\\', '/')
        builder.add(
            builder.newAppender("File", "RollingFile")
                .addAttribute("fileName", "$logs/latest.log")
                .addAttribute("filePattern", "$logs/%d{yyyy-MM-dd}{${zone.id}}-%i.log.gz")
                .add(
                    builder.newLayout("PatternLayout")
                        .addAttribute("pattern", "[%d{yyyy-MM-dd HH:mm:ss}{${zone.id}} %level] %msg%n")
                        .addAttribute("charset", "UTF-8")
                )
                .addComponent(policies)
                .addComponent(builder.newComponent("DefaultRolloverStrategy").addAttribute("max", MAX_ROLLED_FILES_PER_DAY))
        )

        builder.add(builder.newRootLogger(level).add(builder.newAppenderRef("Console")).add(builder.newAppenderRef("File")))
        // Not additive and no file ref: this logger's lines can only reach the console.
        builder.add(builder.newLogger(TERMINAL_LOGGER, Level.INFO).add(builder.newAppenderRef("Console")).addAttribute("additivity", false))
        // The mirror image, file only: output() uses it when the root level lets info through.
        builder.add(builder.newLogger(FILE_LOGGER, Level.INFO).add(builder.newAppenderRef("File")).addAttribute("additivity", false))
        return builder.build()
    }
}
