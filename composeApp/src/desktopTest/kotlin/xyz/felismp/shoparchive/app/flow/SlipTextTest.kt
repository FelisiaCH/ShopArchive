package xyz.felismp.shoparchive.app.flow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlipTextTest {
    private val kip = mapOf("LAK" to 0)

    private fun lak(text: String, amount: Long) = assertEquals("LAK" to amount, slipAmount(text, kip), text)

    @Test fun bcelOneSlipWithWatermarkNoise() = lak(
        "ຊຳລະ ສຳເລັດ 18/09/26 19:18:42 ລາຍລະອຽດ ຮ້ານຄ້າ : ຮ້ານ ໂກ້ໂມບາຍ ຈຳນວນຊຳລະ : 850,000 LAK ບັນຊີທີ່ໃຊ້ຊຳລະ 130-12-xxxxx408 LAK " +
            "ເລກອ້າງອີງ 1 : BAGSTL8O2VVK ເລກອ້າງອີງ 2 : 202 6091 8668 4385 amount 850000 LAK Ref 202 6091 8668 4385 time 18/09/2026 19:18:42",
        850_000,
    )

    @Test fun theFeeIsSmallerThanTheAmount() = lak(
        "23/09/26 17:30:59 ໂກ້ໂມບາຍ ຈຳນວນຊຳລະ : 550,000 LAK ຄ່າທຳນຽມ : 1,000 LAK ບັນຊີທີ່ໃຊ້ຊຳລະ 160-12-xxxxx077 LAK BONEBB29KJQG6OXU 202 6092 3615 3442",
        550_000,
    )

    @Test fun millionsAndAnAccountNumberWithDashes() = lak(
        "27/09/26 19:03:24 ຮ້ານ ໂກ້ໂມບາຍ ຈຳນວນຊຳລະ : 7,350,000 LAK ຄ່າທຳນຽມ : 018-12-00-xxxxx341-001 LAK BB8LMDF8PIUP 202 6092 7552 7406 1555",
        7_350_000,
    )

    @Test fun kipSignAndZeroDecimalsAndACodeBesideAMaskedAccount() = lak(
        "ທຸລະກຳສຳເລັດ 2026-09-24 21:02:09 ເລກອ້າງອີງ: FT26268R1S8X PHONESAVANH KEOKHOUNMUEAN LAK 0313 **** **** 0627 ໂກ້ໂມບາຍ LAK 1011 **** **** 3023 " +
            "ຈຳນວນເງິນ: ₭ 190,000.00 ຄ່າທຳນຽມ: ₭ 1,000.00 ເນື້ອໃນ: 230300",
        190_000,
    )

    @Test fun aKipLetterBeforeTheNumber() = lak(
        "ຊຳລະເງິນ - 100,000.00 LAK ເລກບັນຊີ: 0302-xxxx-xxxx-0335 2026-09-25 12:27:23 FT262681JTY4 KATHING PHOMMAVONG MR LAK 0302 **** **** 0335 K 100,000.00 K 1,000.00",
        100_000,
    )

    @Test fun aLeadingMinusAndAFullStopAfterTheCode() = lak(
        "ຊຳລະເງິນ -80,000.00 LAK. ຊຳລະເງິນ ຮ້ານ: M6355 27/09/26 11:57:51 80,000 LAK 1,000 LAK 018-12-xxxxx323 LAK BONEBBB0E6ZF0ER2 2 0260 9277 8824",
        80_000,
    )

    @Test fun aLetterGluedToTheNumber() = lak(
        "2026-09-30 16:36:39 FT262730PQ97 TOUY VANG MR LAK 0301 **** **** 9313 KO MOBILE. DONGPHOSI, HADXAIF... LAK mch649928756be8a K400,000.00 K 1,000.00 LAK 400,000.00",
        400_000,
    )

    @Test fun aSlipWithOnlyTheAmountAndReferences() = lak(
        "01/10/26 17:34:22 ຮ້ານ ໂກ້ໂມບາຍ 450,000 LAK 018-12-00-xxxxx182-001 LAK BBHAQNK3D999 202 6100 1647 4545",
        450_000,
    )

    @Test fun aCodeRightAfterTheNumber() = lak(
        "Thanh toán thành công 450,000LAK 02/10/2026 18:55:27 PHAN VAN THONG MR 011xxxxxxxx938 - LAK KO MOBILE. Dongphosi N0AQR1K07CXFE3CBJD6E " +
            "Tổng tiền trích nợ 450,000 LAK Thanh toan QR 010LVO126275A4VH",
        450_000,
    )

    @Test fun noConfiguredCodeMeansNoAnswer() {
        assertNull(slipAmount("amount 850,000 THB", kip))
        assertNull(slipAmount("850,000", kip))
    }

    @Test fun twoConfiguredCodesAreNotGuessedBetween() {
        assertNull(slipAmount("850,000 LAK 25.00 USD", mapOf("LAK" to 0, "USD" to 2)))
    }

    @Test fun aCodeInsideAWordIsNotACode() {
        assertNull(slipAmount("BLAKE 5,000", kip))
        assertNull(slipAmount("LAKE 5,000", kip))
    }

    @Test fun plainNumbersAreNotAmounts() {
        assertNull(slipAmount("LAK 850000 18/09/26 19:18:42 202 6091 8668 4385 130-12-xxxxx408", kip))
    }

    @Test fun aGroupedNumberInsideALongerNumberIsNotAnAmount() {
        assertNull(slipAmount("LAK 1234,567 12,3456 1,234,5 1.234,567", kip))
    }

    @Test fun decimalsFollowTheCurrency() {
        assertEquals("USD" to 123_456L, slipAmount("12.50 USD 1,234.56 USD", mapOf("USD" to 2)))
        assertEquals("USD" to 123_400L, slipAmount("1,234 USD", mapOf("USD" to 2)))
        assertEquals("USD" to 123_450L, slipAmount("1,234.5 USD", mapOf("USD" to 2)))
        assertEquals("USD" to 123_400L, slipAmount("1,234.0000 USD", mapOf("USD" to 2)))
    }

    // What ML Kit read off a real Trust slip of 400,000: the watermark once misread as 400,080.
    @Test fun theAmountSeenMostOftenWinsOverALargerMisreadOne() = lak(
        "2026-09-30 16:36:39 be8a - LAK 400,0Db|TOUY VANG MR|LAK 0301 **** ****|9kA00,000.00 o 2026-09-30|be8a - LAK 400,000.00 c|" +
            "beBa- LAK 400,080|S2730PQ97 - Te M|AK 400,000.00 o 2026.09-30|LAK 400,000,00 4|„000.00",
        400_000,
    )

    // A real slip with a rubber band across the amount: only the fee in a European form is left, which is not taken for the amount.
    @Test fun aSlipWhoseAmountWasNotReadGivesNoAnswer() {
        assertNull(slipAmount("2026-09-24 21:02:09|LAK|Umoney LA1011 **** **** 3023|kLAK 1909|LAK 199|* 19|),000.00|LAK|K1.000,00|230300", kip))
    }

    @Test fun decimalsTheCurrencyHasNoRoomForSkipThatNumber() {
        assertNull(slipAmount("1,234.56 LAK", kip))
        assertEquals("LAK" to 1_000L, slipAmount("1,234.56 LAK 1,000.00 LAK", kip))
    }
}
