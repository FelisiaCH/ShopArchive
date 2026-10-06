package xyz.felismp.shoparchive.app.ui

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConsoleQrTest {
    @Test fun aPairingLinkBecomesAQrCode() {
        assertNotNull(qrMatrixOrNull("shoparchive://pair?d=" + "A".repeat(300)))
    }

    @Test fun aLinkTooLongForAQrCodeGivesNoCodeInsteadOfThrowing() {
        assertNull(qrMatrixOrNull("shoparchive://pair?d=" + "A".repeat(8000)))
    }
}
