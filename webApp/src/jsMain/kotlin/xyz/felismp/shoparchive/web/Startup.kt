package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.flow.AppState

private val LANGUAGES = setOf("en", "th", "lo")

/** The language the person chose in the app (stored preference), else the browser's, else English. Only the app's three exist. */
fun chooseLanguage(preferred: String?, browser: String?): String =
    preferred?.takeIf { it in LANGUAGES } ?: browser?.take(2)?.lowercase()?.takeIf { it in LANGUAGES } ?: "en"

/** `host:port` of the page. `location.host` leaves the port out on 443 / 80, and AppFlow only accepts an address with one. */
fun pageAddress(protocol: String, hostname: String, port: String): String =
    "$hostname:" + port.ifEmpty { if (protocol == "http:") "80" else "443" }

/**
 * The page was opened from the server, so there is only one server to add: its own address, added once when the server list first
 * shows. A failure is not retried by itself (no loop): the screen's button calls [AppFlow.addServer] again.
 */
class AutoAdd(private val address: String, private val add: (String) -> Unit) {
    var fired = false
        private set

    fun onState(state: AppState) {
        if (fired || state !is AppState.Servers || state.busy) return
        fired = true
        add(address)
    }
}
