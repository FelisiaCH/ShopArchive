package xyz.felismp.shoparchive.server.backup

import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.FIXED_CLOCK
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.write
import xyz.felismp.shoparchive.server.records.RecordWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipFile

internal const val SERVER_ID = "11111111-2222-7333-8444-555555555555"

/** The folder of this server inside a copy-to folder. */
internal fun Path.copyDir(): Path = resolve("ShopArchive-$SERVER_ID")

/** A root with a config, a service over it and the writer it pauses; [yml] is the `backup:` section's lines (indented under it) or null for the defaults. */
internal class BackupEnv(val root: Path, backupYml: String? = null, val clock: Clock = FIXED_CLOCK) {
    val log = RecordingLog()
    val writer = RecordWriter()
    val barrier = DataBarrier()
    val config = ConfigService(root, log = log, clock = FIXED_CLOCK, barrier = barrier)
    val service: BackupService

    init {
        prepareRoot(root)
        for (dir in listOf("backups", "record", "user", "cache", "logs", "tmp", "libraries", "versions", "exports", "downloads", "data/devices", "plugins")) Files.createDirectories(root.resolve(dir))
        if (backupYml != null) root.write("config/shoparchive.yml", "config-version: 1\n\nbackup:\n$backupYml")
        config.load()
        service = BackupService(root, { config.backup }, { ZoneOffset.UTC as ZoneId }, { block -> writer.run(block) }, log, clock, "9.9.9", SERVER_ID, barrier)
    }

    fun put(relative: String, text: String) = put(relative, text.toByteArray())

    fun put(relative: String, bytes: ByteArray) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    fun reload() = config.reload()

    /** A little of everything a real root holds. */
    fun populate() {
        put("config/extra.yml", "x: 1\n")
        put("user/alice.yml", "name: alice\n")
        put("data/branches.yml", "branches: []\n")
        put("data/server-id", "abc\n")
        put("data/session.lock", "")
        put("record/2026/10/02/e1/entry.yml", "id: e1\n")
        put("record/2026/10/02/e1/history.yml", "history:\n")
        put("record/2026/10/02/e1/slip-1.jpg", ByteArray(100) { it.toByte() })
        put("record/2026/10/02/e1/slip-2.png", ByteArray(50) { (it * 3).toByte() })
        put("certs/keystore.p12", "key")
        put("plugins/approved.yml", "a: b\n")
        put("crash-reports/crash-1.txt", "boom")
        put("shoparchive-server.jar", "jar")
        put("start.sh", "#!/bin/sh")
        put("start.bat", "echo")
        put("cache/records/e1.yml", "cached")
        put("logs/latest.log", "log")
        put("tmp/x", "tmp")
        put("libraries/a/b.jar", "lib")
        put("versions/1/core.jar", "core")
        put("exports/old.csv", "csv")
        put("downloads/app.apk", "apk")
    }
}

internal fun Path.zipNames(): Set<String> = ZipFile(toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }

internal fun Path.zipText(name: String): String = ZipFile(toFile()).use { zip -> zip.getInputStream(zip.getEntry(name)).readBytes().toString(Charsets.UTF_8) }

internal fun Path.manifest(): Manifest = manifestJson.decodeFromString(Manifest.serializer(), zipText(MANIFEST_NAME))

/** The zips (not .part files) in `backups/`. */
internal fun Path.zips(): List<String> = listZips(resolve("backups"))

internal fun Path.parts(): List<String> = Files.walk(resolve("backups")).use { s -> s.filter { it.fileName.toString().endsWith(".part") }.map { it.fileName.toString() }.toList() }

/** A clock that tests move by hand. */
internal class TestClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone() = zone
    override fun withZone(zone: ZoneId) = TestClock(now, zone)
    override fun instant() = now
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}
