package xyz.felismp.shoparchive.server.auth

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.PairingService
import xyz.felismp.shoparchive.shared.PairPayload
import xyz.felismp.shoparchive.shared.RedeemRequest
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.felismp.shoparchive.api.ApiError

/** Reads a QR code back out of pixels, the way a phone would. */
// PURE_BARCODE + TRY_HARDER: the code is a clean, generated image; without the hints the reader now and then
// fails to find some random secrets' patterns, which made the test flaky.
private fun scan(width: Int, height: Int, pixels: IntArray): String =
    QRCodeReader().decode(
        BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))),
        mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.TRY_HARDER to true),
    ).text

class PairingTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String? = null) = AuthEnv(root, config)

    @Test
    fun theManualCodeAlphabetIsRfc8628sAndTenLettersGive43Bits() {
        assertEquals("BCDFGHJKLMNPQRSTVWXZ", CODE_ALPHABET)
        assertEquals(20, CODE_ALPHABET.length)
        assertEquals(10, CODE_LENGTH)
        assertTrue(CODE_LENGTH * (Math.log(20.0) / Math.log(2.0)) >= 40)
        assertTrue(CODE_ALPHABET.none { it in "AEIOUY01" })
    }

    @Test
    fun aTypedCodeIsReadWhateverCaseSpacesAndDashes() {
        assertEquals("BCDFGHJKLM", normalizeCode("BCDFG-HJKLM"))
        assertEquals("BCDFGHJKLM", normalizeCode(" bcdfg hjklm "))
        assertEquals("BCDFGHJKLM", normalizeCode("bcdfghjklm"))
        assertEquals("BCDFG-HJKLM", displayCode("BCDFGHJKLM"))
        for (bad in listOf(null, "", "BCDFG-HJKL", "BCDFG-HJKLMN", "ACDFG-HJKLM", "BCDFG-HJKL1")) assertNull(normalizeCode(bad), bad)
    }

    @Test
    fun aPairingCarriesTheFingerprintTheSecretTheUserAndTwoEndpoints() = env().run {
        addUser("mali")
        val first = pair("mali")
        val second = pair("mali")

        assertTrue(first.link.startsWith("shoparchive://pair?d="))
        assertEquals(1, first.payload.v)
        assertEquals(SERVER_ID.toString(), first.payload.sid)
        assertEquals(FINGERPRINT.replace(" ", ""), first.payload.fp)
        assertEquals(64, first.payload.fp.length)
        assertEquals("mali", first.payload.u)
        assertEquals(2, first.payload.ep.size)
        // 16 random bytes, new every time.
        assertEquals(16, Base64.getUrlDecoder().decode(first.secret).size)
        assertTrue(first.secret != second.secret)
        assertTrue(first.manualCode != second.manualCode)
    }

    @Test
    fun theLinkIsBase64urlWithoutPaddingOfJson() = env().run {
        addUser("mali")
        val link = pair("mali").link

        val data = link.removePrefix("shoparchive://pair?d=")
        assertTrue(data.all { it.isLetterOrDigit() || it == '-' || it == '_' }, data)
        val json = String(Base64.getUrlDecoder().decode(data))
        assertTrue(json.startsWith("{\"v\":1,\"sid\":"), json)
        assertContains(json, "\"ep\":[\"shop.example.com:25655\",\"192.168.1.20:25655\"]")
    }

    @Test
    fun theQrOnTheConsoleScansBackToTheLink() = env().run {
        addUser("mali")
        val link = pair("mali").link

        val rows = qrAsText(link)

        assertTrue(rows.map { it.length }.distinct().size == 1)
        assertTrue(rows.all { row -> row.all { it in "█▀▄ " } })
        // A dark terminal shows the blocks bright and the spaces dark: bright = light module.
        val modules = rows[0].length
        val scale = 4
        val width = modules * scale
        val height = rows.size * 2 * scale
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ch = rows[y / scale / 2][x / scale]
                val light = if ((y / scale) % 2 == 0) ch == '█' || ch == '▀' else ch == '█' || ch == '▄'
                pixels[y * width + x] = if (light) 0xFFFFFF else 0x000000
            }
        }
        assertEquals(link, scan(width, height, pixels))
        // Margin of light blocks all around.
        assertTrue(rows.first().all { it == '█' } && rows.last().first() == '█')
    }

    @Test
    fun theQrImageScansBackToTheLink() = env().run {
        addUser("mali")
        val link = pair("mali").link

        val image = ImageIO.read(ByteArrayInputStream(qrAsPng(link)))

        val pixels = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) }
        assertEquals(link, scan(image.width, image.height, pixels))
    }

    @Test
    fun aDisabledOrUnknownUserCannotGetAPairingFromTheConsoleOrTheService() = env().run {
        addUser("mali")
        users.setEnabled("mali", false)

        assertEquals(400, assertFailsWith<ApiError> { auth.pairing.createPairing(null, "mali", "127.0.0.1") }.status)
        assertEquals(404, assertFailsWith<ApiError> { auth.pairing.createPairing(null, "nobody", "127.0.0.1") }.status)
        assertEquals(0, auth.pairing.pendingCount())
    }

    @Test
    fun theServiceIsTheOneInTheRegistry() = env().run {
        assertTrue(services.get(PairingService::class.java) === auth.pairing)
    }

    @Test
    fun aPairingKeepsOnlyHashesInMemoryAndWritesNothingToDisk() = env().run {
        addUser("mali")
        val pairing = pair("mali")

        val everything = filesUnder(".").values.joinToString("\n")
        assertFalse(pairing.secret in everything)
        assertFalse(pairing.manualCode!! in everything || pairing.manualCode.replace("-", "") in everything)
        assertEquals(1, auth.pairing.pendingCount())
    }

    @Test
    fun anImageIsDeletedWhenItsPairingIsUsedReplacedOrRunsOut() = env().run {
        addUser("mali")
        fun images() = Files.list(root.resolve("tmp")).use { files -> files.toList().map { it.fileName.toString() } }

        // used
        console("user pair mali --png")
        assertEquals(1, images().size)
        val image = root.resolve("tmp").resolve(images().single())
        val link = terminal.first { it.startsWith("Link: ") }.removePrefix("Link: ")
        assertEquals(link, scanFile(image))
        auth.pairing.redeem(RedeemRequest(secret = Pairing(link, null).secret), "127.0.0.1")
        assertEquals(0, images().size)

        // replaced
        console("user pair mali --png")
        assertEquals(1, images().size)
        console("user pair mali")
        assertEquals(0, images().size)

        // run out
        console("user pair mali --png")
        assertEquals(1, images().size)
        clock.advance(Duration.ofMinutes(11))
        assertEquals(0, auth.pairing.pendingCount())
        assertEquals(0, images().size)
    }

    private fun scanFile(file: Path): String {
        val image = ImageIO.read(file.toFile())
        return scan(image.width, image.height, IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) })
    }

    @Test
    fun payloadRoundTripsThroughJson() {
        val payload = PairPayload(1, "sid", "AB", "secret", "mali", listOf("a:1"))
        val link = pairingLink(payload)

        assertEquals(payload, Pairing(link, null).payload)
    }
}
