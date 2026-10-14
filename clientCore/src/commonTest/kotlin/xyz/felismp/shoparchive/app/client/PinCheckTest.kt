package xyz.felismp.shoparchive.app.client

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinCheckTest {
    @Test fun spkiIsTheHeaderPlusThePointAsTheJvmEncodesIt() {
        assertContentEquals(VECTOR_SPKI_HEX.hexToBytes(), p256Spki(VECTOR_POINT_HEX.hexToBytes()))
    }

    @Test fun onlyAnUncompressedP256PointGetsAnSpki() {
        val point = VECTOR_POINT_HEX.hexToBytes()
        assertNull(p256Spki(point.copyOfRange(0, 64)))
        assertNull(p256Spki(point.copyOf().also { it[0] = 0x02 }))
        assertNull(p256Spki(ByteArray(0)))
        assertNull(p256Spki(point + 0))
    }

    @Test fun pinDecodesFromHexInAnyCaseWithOrWithoutSpaces() {
        val expected = VECTOR_PIN.hexToBytes()
        assertContentEquals(expected, decodePin(VECTOR_PIN))
        assertContentEquals(expected, decodePin(VECTOR_PIN.lowercase()))
        assertContentEquals(expected, decodePin(formatFingerprint(VECTOR_PIN)))
    }

    @Test fun malformedPinDecodesToNull() {
        assertNull(decodePin(""))
        assertNull(decodePin("nope"))
        assertNull(decodePin(VECTOR_PIN.dropLast(2)))
        assertNull(decodePin(VECTOR_PIN.dropLast(1) + "G"))
    }

    @Test fun hexIsUppercaseAndPaddedAndRoundTrips() {
        assertEquals(VECTOR_PIN, VECTOR_PIN.hexToBytes().toHexUpper())
        assertEquals("000FA0", byteArrayOf(0, 0x0F, 0xA0.toByte()).toHexUpper())
    }

    @Test fun constantTimeEqualsComparesEveryByteAndTheLength() {
        val a = VECTOR_PIN.hexToBytes()
        assertTrue(constantTimeEquals(a, a.copyOf()))
        assertFalse(constantTimeEquals(a, a.copyOf().also { it[31] = (it[31] + 1).toByte() }))
        assertFalse(constantTimeEquals(a, a.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertFalse(constantTimeEquals(a, a.copyOf(31)))
    }
}
