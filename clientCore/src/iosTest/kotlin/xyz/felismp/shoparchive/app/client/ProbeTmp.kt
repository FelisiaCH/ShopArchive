package xyz.felismp.shoparchive.app.client

import kotlin.test.Test
import kotlin.test.assertNotEquals

class ProbeTmp {
    private fun status(): Int = try { KeychainCredentialStore("xyz.felismp.shoparchive.probe", "p").load(); 0 } catch (e: KeychainException) { e.status }
    @Test fun probe_status_m34018() = assertNotEquals(-34018, status())
    @Test fun probe_status_m50() = assertNotEquals(-50, status())
    @Test fun probe_status_m25291() = assertNotEquals(-25291, status())
    @Test fun probe_status_m25308() = assertNotEquals(-25308, status())
    @Test fun probe_status_m25299() = assertNotEquals(-25299, status())
    @Test fun probe_status_m25293() = assertNotEquals(-25293, status())
    @Test fun probe_status_m25295() = assertNotEquals(-25295, status())
    @Test fun probe_status_m108() = assertNotEquals(-108, status())
    @Test fun probe_status_m26275() = assertNotEquals(-26275, status())
    @Test fun probe_status_m67673() = assertNotEquals(-67673, status())
    @Test fun probe_status_m25243() = assertNotEquals(-25243, status())
    @Test fun probe_status_m4() = assertNotEquals(-4, status())
    @Test fun probe_status_m128() = assertNotEquals(-128, status())
    @Test fun probe_status_m25300() = assertNotEquals(-25300, status())
    @Test fun probe_status_m25294() = assertNotEquals(-25294, status())
    @Test fun probe_status_m34000() = assertNotEquals(-34000, status())
    @Test fun probe_status_m25297() = assertNotEquals(-25297, status())
    @Test fun probe_status_zero() = assertNotEquals(0, status())
}
