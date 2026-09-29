package xyz.felismp.shoparchive.spike.server

import org.apache.logging.log4j.LogManager
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

private val log = LogManager.getLogger("fstest")

private class Stat(val name: String) {
    var first = 0
    var retried = 0
    val retryCause = sortedMapOf<String, Int>() // exception class of the FIRST failure, when a retry then succeeded
    val failed = sortedMapOf<String, Int>()     // exception class of the LAST failure, when all 6 tries failed
}

/** First try, then up to 5 retries 20 ms apart. Every exhausted failure is counted by class and its first sample is logged. */
private fun attempt(s: Stat, block: () -> Unit): Boolean {
    var firstFailure: Exception? = null
    for (i in 0..5) {
        try {
            block()
            if (i == 0) s.first++ else {
                s.retried++
                s.retryCause.merge(firstFailure!!.javaClass.name, 1, Int::plus)
            }
            return true
        } catch (e: Exception) {
            if (firstFailure == null) firstFailure = e
            if (i == 5) {
                if (s.failed.merge(e.javaClass.name, 1, Int::plus) == 1) log.warn("{}: gave up after 6 tries: {}", s.name, e.toString())
                return false
            }
            Thread.sleep(20)
        }
    }
    return false
}

private fun writeSynced(path: Path, bytes: ByteArray) =
    FileChannel.open(path, CREATE, WRITE, TRUNCATE_EXISTING).use { it.write(ByteBuffer.wrap(bytes)); it.force(true) }

private fun uuidV7(): String {
    val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val ms = System.currentTimeMillis()
    for (i in 0..5) b[i] = (ms shr (8 * (5 - i))).toByte()
    b[6] = (b[6].toInt() and 0x0F or 0x70).toByte()
    b[8] = (b[8].toInt() and 0x3F or 0x80).toByte()
    return ByteBuffer.wrap(b).let { UUID(it.long, it.long) }.toString()
}

private fun yml(id: String, seq: Int) =
    "id: $id\ntype: income\ncurrency: LAK\namount: \"50000\"\nitem: \"ທົດສອບ ทดสอบ\"\nseq: $seq\n".toByteArray()

private fun makeJpeg(): ByteArray {
    val img = BufferedImage(480, 320, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color(0x2E7D32); g.fillRect(0, 0, 480, 320)
    g.color = Color.WHITE; g.fillRect(20, 20, 440, 280)
    g.color = Color.BLACK; g.drawString("ShopArchive spike slip-1.jpg - keep me open in Photos", 40, 60)
    g.dispose()
    return ByteArrayOutputStream().also { check(ImageIO.write(img, "jpg", it)) }.toByteArray()
}

/** T3: create/replace/rename entry folders under [root] while a slip JPEG is held open by the tester. */
fun fsTest(root: Path, n: Int, readLine: (String) -> String?) {
    val today = LocalDate.now()
    val day = root.resolve("record").resolve("%04d".format(today.year)).resolve("%02d".format(today.monthValue)).resolve("%02d".format(today.dayOfMonth))
    val tmp = root.resolve("tmp")
    Files.createDirectories(day)
    Files.createDirectories(tmp)
    val jpeg = makeJpeg()

    val heldId = uuidV7()
    val a = day.resolve(heldId)
    val b = day.resolve("$heldId-renamed")
    Files.createDirectories(a)
    writeSynced(a.resolve("entry.yml"), yml(heldId, 0))
    writeSynced(a.resolve("slip-1.jpg"), jpeg)
    log.info("Held entry: {}", a)
    log.info("Open this file in Windows Photos and KEEP IT OPEN: {}", a.resolve("slip-1.jpg"))
    log.info("Then press Enter to start {} rounds (Ctrl+C aborts).", n)
    readLine("")

    val build = Stat("create: build in tmp/ + fsync")
    val moveIn = Stat("create: ATOMIC_MOVE tmp/ -> record/")
    val replace = Stat("held entry.yml: tmp + fsync + ATOMIC_MOVE")
    val renameOut = Stat("held folder rename A -> B")
    val renameBack = Stat("held folder rename B -> A")
    var held = a
    val started = System.nanoTime()

    repeat(n) { i ->
        val id = uuidV7()
        val work = tmp.resolve(id)
        val dest = day.resolve(id)
        val built = attempt(build) {
            Files.createDirectories(work)
            writeSynced(work.resolve("entry.yml"), yml(id, i))
            writeSynced(work.resolve("slip-1.jpg"), jpeg)
        }
        if (built) attempt(moveIn) { Files.move(work, dest, ATOMIC_MOVE) }

        attempt(replace) {
            val t = held.resolve("entry.yml.tmp")
            writeSynced(t, yml(heldId, i))
            Files.move(t, held.resolve("entry.yml"), ATOMIC_MOVE, REPLACE_EXISTING)
        }

        if (held == a && attempt(renameOut) { Files.move(a, b, ATOMIC_MOVE) }) held = b
        if (held == b && attempt(renameBack) { Files.move(b, a, ATOMIC_MOVE) }) held = a
    }

    log.info("fstest: {} rounds in {} ms. Held folder is now: {}", n, (System.nanoTime() - started) / 1_000_000, held)
    log.info(String.format("%-42s %8s %8s %8s", "operation", "ok-1st", "ok-retry", "failed"))
    for (s in listOf(build, moveIn, replace, renameOut, renameBack)) {
        log.info(String.format("%-42s %8d %8d %8d", s.name, s.first, s.retried, s.failed.values.sum()))
        s.retryCause.forEach { (k, v) -> log.info("    retry needed {}x, first failure: {}", v, k) }
        s.failed.forEach { (k, v) -> log.info("    failed {}x: {}", v, k) }
    }
    log.info("Close the picture, then delete {} and {} when done.", root.resolve("record"), tmp)
}
