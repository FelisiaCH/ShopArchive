@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package xyz.felismp.shoparchive.app.flow

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Vision.VNBarcodeObservation
import platform.Vision.VNBarcodeSymbologyQR
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate
import xyz.felismp.shoparchive.app.client.toNSData

internal fun createIosSlipReader(): SlipReader = SlipReader { jpeg -> withContext(Dispatchers.Default) { readWithVision(jpeg) } }

private fun readWithVision(jpeg: ByteArray): SlipScan? {
    val text = VNRecognizeTextRequest().apply {
        recognitionLevel = VNRequestTextRecognitionLevelAccurate
        usesLanguageCorrection = false
    }
    val qr = VNDetectBarcodesRequest().apply { symbologies = listOf(VNBarcodeSymbologyQR) }
    val done = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        VNImageRequestHandler(jpeg.toNSData(), emptyMap<Any?, Any?>()).performRequests(listOf(text, qr), error.ptr)
    }
    if (!done) return null
    val lines = text.results.orEmpty().filterIsInstance<VNRecognizedTextObservation>()
        .mapNotNull { (it.topCandidates(1.convert()).firstOrNull() as? VNRecognizedText)?.string }
    val code = qr.results.orEmpty().filterIsInstance<VNBarcodeObservation>()
        .firstOrNull()?.payloadStringValue?.trim()?.takeIf { it.isNotEmpty() }
    return SlipScan(lines.joinToString("\n"), code)
}
