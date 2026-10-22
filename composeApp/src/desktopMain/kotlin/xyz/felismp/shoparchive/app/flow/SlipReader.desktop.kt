package xyz.felismp.shoparchive.app.flow

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.felismp.shoparchive.app.client.appDir
import xyz.felismp.shoparchive.app.client.isWindows
import java.io.ByteArrayInputStream
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

actual fun createSlipReader(): SlipReader = SlipReader { jpeg ->
    withContext(Dispatchers.IO) { SlipScan(if (isWindows) readTextWithWindowsOcr(jpeg) else null, readQr(jpeg)) }
}

private fun readQr(jpeg: ByteArray): String? {
    val image = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return null
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    return try {
        QRCodeReader().decode(bitmap, hints).text.trim().takeIf { it.isNotEmpty() }
    } catch (_: NotFoundException) {
        null
    }
}

// The OCR built into Windows 10/11 (Windows.Media.Ocr) through Windows PowerShell 5.1. The path arrives as $env:SLIP_IMAGE, never as code.
private val OCR_SCRIPT = """
${'$'}ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
${'$'}null = [Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime]
${'$'}null = [Windows.Storage.FileAccessMode, Windows.Storage, ContentType=WindowsRuntime]
${'$'}null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
${'$'}null = [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
${'$'}null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
${'$'}null = [Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime]
${'$'}asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await(${'$'}op, ${'$'}type) {
    ${'$'}task = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op))
    ${'$'}null = ${'$'}task.Wait(-1)
    ${'$'}task.Result
}
${'$'}file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync(${'$'}env:SLIP_IMAGE)) ([Windows.Storage.StorageFile])
${'$'}stream = Await (${'$'}file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
${'$'}decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync(${'$'}stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
${'$'}bitmap = Await (${'$'}decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
${'$'}engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if (-not ${'$'}engine) { ${'$'}engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('en-US')) }
${'$'}result = Await (${'$'}engine.RecognizeAsync(${'$'}bitmap)) ([Windows.Media.Ocr.OcrResult])
[Console]::Out.Write((${'$'}result.Lines | ForEach-Object { ${'$'}_.Text }) -join "`n")
"""

private fun readTextWithWindowsOcr(jpeg: ByteArray): String? {
    val dir = appDir().resolve("tmp")
    val file = dir.resolve("slip-${UUID.randomUUID()}.jpg")
    return try {
        Files.createDirectories(dir)
        Files.write(file, jpeg)
        val script = Base64.getEncoder().encodeToString(OCR_SCRIPT.toByteArray(Charsets.UTF_16LE))
        val builder = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", script)
            .redirectError(Redirect.DISCARD)
        builder.environment()["SLIP_IMAGE"] = file.toString()
        val process = builder.start()
        process.outputStream.close()
        val out = CompletableFuture.supplyAsync { process.inputStream.readBytes() } // drained while we wait, so a full pipe cannot stall the process
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        String(out.get(), Charsets.UTF_8).trimStart('﻿').takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { Files.deleteIfExists(file) }
    }
}
