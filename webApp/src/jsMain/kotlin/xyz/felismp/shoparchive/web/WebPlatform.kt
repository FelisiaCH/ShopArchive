package xyz.felismp.shoparchive.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.files.Blob
import xyz.felismp.shoparchive.app.client.ApiClient
import xyz.felismp.shoparchive.app.client.ServerApi
import xyz.felismp.shoparchive.app.flow.SLIP_MAX_SIDE
import xyz.felismp.shoparchive.app.flow.SLIP_QUALITY
import xyz.felismp.shoparchive.app.flow.SlipCompressor
import xyz.felismp.shoparchive.app.flow.SlipReader
import xyz.felismp.shoparchive.app.flow.ThisDevice

/** The page's own `host:port` (the server answers over https on it: ApiClient always uses https). */
val webAddress: String get() = window.location.let { pageAddress(it.protocol, it.hostname, it.port) }

/** The page was opened from the server, so the server is the page's own origin: its certificate is the browser's business, not the app's. */
fun webConnect(@Suppress("UNUSED_PARAMETER") pin: String, serverId: String, @Suppress("UNUSED_PARAMETER") endpoints: List<String>): ServerApi =
    ApiClient(
        serverId, listOf(webAddress),
        HttpClient(Js) { expectSuccess = false; install(WebSockets) },
        isPinFailure = { false },
        webSocketSubprotocol = true,
    )

/** No pin to confirm in a browser. */
@Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER")
suspend fun webProbe(address: String): String = ""

val webDevice = ThisDevice("Web browser", "web")

/** There is no slip reading in a browser: the amount is typed. */
val webSlipReader = SlipReader { null }

internal fun requireBrowser() {
    check(js("typeof document") != "undefined") { "Slip photos can only be compressed in a browser (this runtime has no DOM / canvas)." }
}

/** The same rule as the other apps: longest side at most [SLIP_MAX_SIDE], JPEG at [SLIP_QUALITY]. null when the browser cannot decode [raw] as an image. */
val webSlipCompressor = SlipCompressor { raw ->
    requireBrowser()
    val bitmap = try {
        window.asDynamic().createImageBitmap(Blob(arrayOf<dynamic>(raw.unsafeCast<Int8Array>()))).unsafeCast<kotlin.js.Promise<dynamic>>().await()
    } catch (_: Throwable) {
        return@SlipCompressor null
    }
    val width: Int = bitmap.width.unsafeCast<Int>()
    val height: Int = bitmap.height.unsafeCast<Int>()
    val scale = minOf(1.0, SLIP_MAX_SIDE.toDouble() / maxOf(width, height))
    val w = maxOf(1, (width * scale).toInt())
    val h = maxOf(1, (height * scale).toInt())
    val canvas = document.createElement("canvas").asDynamic()
    canvas.width = w
    canvas.height = h
    val ctx = canvas.getContext("2d")
    ctx.fillStyle = "#fff" // JPEG has no transparency
    ctx.fillRect(0, 0, w, h)
    ctx.drawImage(bitmap, 0, 0, w, h)
    bitmap.close()
    val blob = suspendCoroutine<dynamic> { cont -> canvas.toBlob({ b: dynamic -> cont.resume(b) }, "image/jpeg", SLIP_QUALITY / 100.0) }
    if (blob == null) {
        null
    } else {
        val buffer: ArrayBuffer = blob.arrayBuffer().unsafeCast<kotlin.js.Promise<ArrayBuffer>>().await()
        Int8Array(buffer).unsafeCast<ByteArray>()
    }
}
