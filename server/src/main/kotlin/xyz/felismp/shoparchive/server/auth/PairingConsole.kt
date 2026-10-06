package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.RemoteSender
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Shows a pairing on the console. The QR, link, code and fingerprint go to [out] - the terminal only, never to
 * `logs/` - because the secret in them lets whoever holds it pair as that user for as long as it lives.
 * To a [RemoteSender] (a user in the app) they go in the reply instead, without the QR: the app draws that from the link.
 */
internal class PairingConsole(
    private val root: Path,
    private val config: ConfigService,
    private val pairing: DefaultPairingService,
    private val out: (String) -> Unit = Log::terminalOnly,
) {
    fun show(sender: CommandSender, username: String, png: Boolean) {
        val remote = sender as? RemoteSender
        val created = try {
            // A user in the app is asked like the pairing route asks: its source, the pair node and the re-auth all apply.
            pairing.createPairing(remote?.principal, username, remote?.ip ?: "console")
        } catch (e: ApiError) {
            sender.sendMessage(e.message ?: "Pairing failed")
            return
        }
        val pairingInfo = created.response
        val say: (String) -> Unit = if (remote != null) sender::sendMessage else out
        val until = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(config.timezone).format(Instant.parse(pairingInfo.expiresAt))
        say("Pairing for '$username', valid for ${config.auth.pairingTtlMinutes} minutes (until $until). Scan this with the app, or open the link:")
        if (remote == null) qrAsText(pairingInfo.link).forEach(out)
        say("Link: ${pairingInfo.link}")
        pairingInfo.manualCode?.let { say("Manual code: $it   (the app asks for the user name '$username' and this code)") }
        say("Server fingerprint: ${pairingInfo.fingerprint}   (the app shows it too: check that they match)")
        if (remote != null) {
            if (png) sender.sendMessage("A QR image is only written on the server console.")
            return
        }
        if (png) {
            val file = root.resolve("tmp/pair-${created.id}.png")
            try {
                Files.createDirectories(file.parent)
                writeAtomically(file, qrAsPng(pairingInfo.link))
                pairing.attachImage(created.id, file)
                out("QR image: tmp/pair-${created.id}.png (deleted when the pairing is used or runs out)")
            } catch (e: IOException) {
                sender.sendMessage("Could not write the QR image: ${e.message}")
            }
        }
        sender.sendMessage("Pairing for '$username' is shown on the console only, not in the log.")
    }
}
