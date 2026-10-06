package xyz.felismp.shoparchive.api

import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.UpdateFile
import java.nio.file.Path
import java.time.Instant

/** What `GET /api/v1/info` answers. Routes call it through the [ServiceRegistry], so a plugin can replace it. */
interface InfoService {
    fun info(): InfoResponse
}

/** Decides whether a client address may talk to the server at all; a banned address gets 403 on every route. */
interface IpBanService {
    /** [ip] is the connection's address as text (IPv4 or IPv6, no zone id). */
    fun isBanned(ip: String): Boolean
}

/** The server's TLS identity as clients pin it. */
interface CertificateService {
    /** SHA-256 of the certificate's SubjectPublicKeyInfo as uppercase hex in groups of four, e.g. `AB12 CD34 ...`. */
    val fingerprint: String

    /** When the certificate in use stops being valid. */
    val expiresAt: Instant
}

/** The installers the server hands out for the apps (`downloads/` in the default). Routes only call it, so a plugin can replace where they come from. */
interface UpdateService {
    /** The installers [principal] may fetch, newest first. */
    fun list(principal: Principal): List<UpdateFile>

    /** The file to send for [name], which must be the `file` of an entry of [list]. Throws [ApiError] (404) for any other name. */
    fun open(principal: Principal, name: String): Path
}
