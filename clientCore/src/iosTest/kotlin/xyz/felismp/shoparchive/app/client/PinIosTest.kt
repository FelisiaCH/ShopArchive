@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class, ExperimentalEncodingApi::class)

package xyz.felismp.shoparchive.app.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecPolicyCreateBasicX509
import platform.Security.SecTrustCreateWithCertificates
import platform.Security.SecTrustRef
import platform.Security.SecTrustRefVar
import platform.Security.errSecSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The certificate in SpkiVector.kt, through Apple's Security framework: it must hash to the same pin the JVM computes. */
class PinIosTest {
    private fun vectorCertificate(): SecCertificateRef {
        val der = Base64.decode(VECTOR_CERT_DER_BASE64)
        val data = der.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), der.size.convert()) }
        try {
            return assertNotNull(SecCertificateCreateWithData(null, data))
        } finally {
            CFRelease(data)
        }
    }

    private fun <T> withTrust(block: (SecTrustRef) -> T): T = memScoped {
        val policy = SecPolicyCreateBasicX509()
        val trust = alloc<SecTrustRefVar>()
        assertEquals(errSecSuccess, SecTrustCreateWithCertificates(vectorCertificate(), policy, trust.ptr))
        try {
            block(assertNotNull(trust.value))
        } finally {
            CFRelease(trust.value)
            CFRelease(policy)
        }
    }

    @Test fun securityFrameworkHashesTheKeyToTheJvmPin() {
        assertEquals(VECTOR_PIN, spkiSha256(vectorCertificate())?.toHexUpper())
    }

    @Test fun theFirstCertificateOfTheTrustIsTheLeaf() = withTrust { trust ->
        assertContentEquals(VECTOR_PIN.hexToBytes(), leafSpkiSha256(trust))
    }

    @Test fun theRightPinAllowsTheServerInAnySpelling() = withTrust { trust ->
        assertTrue(pinAllows(trust, decodePin(VECTOR_PIN)))
        assertTrue(pinAllows(trust, decodePin(formatFingerprint(VECTOR_PIN).lowercase())))
    }

    @Test fun anotherKeyMalformedOrMissingPinAllowsNothing() = withTrust { trust ->
        assertFalse(pinAllows(trust, decodePin("00".repeat(32))))
        assertFalse(pinAllows(trust, decodePin(VECTOR_PIN.dropLast(2))))
        assertFalse(pinAllows(trust, decodePin("nope")))
        assertFalse(pinAllows(trust, null))
    }

    @Test fun aPinFailureIsRecognisedThroughWrappingExceptions() {
        assertTrue(isPinFailure(RuntimeException("wrapped", PinMismatchException())))
        assertFalse(isPinFailure(RuntimeException("wrapped", IllegalStateException())))
    }
}
