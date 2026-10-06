package xyz.felismp.shoparchive.server.net

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.UpdateService
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.UpdateFile
import xyz.felismp.shoparchive.shared.parseUpdateFileName
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The installers in [folder] (`<root>/downloads/`) named by the rule in [parseUpdateFileName]; any other file is not there as far as
 * the API is concerned. The SHA-256 of a file is worked out once and kept while its size and modification time stay the same.
 * Only names that [list] would show can be opened, so no path from a request ever reaches the file system.
 */
internal class DefaultUpdateService(private val folder: Path) : UpdateService {
    private class Hashed(val size: Long, val modified: Long, val sha256: String)

    private val hashes = ConcurrentHashMap<String, Hashed>()

    override fun list(principal: Principal): List<UpdateFile> {
        if (!Files.isDirectory(folder)) return emptyList()
        val found = Files.newDirectoryStream(folder).use { entries ->
            entries.mapNotNull { path ->
                val name = path.fileName.toString()
                val parsed = parseUpdateFileName(name) ?: return@mapNotNull null
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
                val version = parsed.version
                val size = try { Files.size(path) } catch (_: java.io.IOException) { return@mapNotNull null }
                UpdateFile(name, parsed.platform, "${version.major}.${version.minor}.${version.patch}", version.build, size, sha256Of(path, name))
            }
        }
        hashes.keys.retainAll(found.map { it.file }.toSet())
        return found.sortedWith(compareByDescending<UpdateFile> { it.appVersion() }.thenBy { it.file })
    }

    override fun open(principal: Principal, name: String): Path {
        val path = if (parseUpdateFileName(name) != null) folder.resolve(name) else null
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiError(404, ErrorCode.NOT_FOUND, "No such update file.")
        }
        return path
    }

    private fun sha256Of(path: Path, name: String): String {
        val size = Files.size(path)
        val modified = Files.getLastModifiedTime(path).toMillis()
        hashes[name]?.takeIf { it.size == size && it.modified == modified }?.let { return it.sha256 }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        hashes[name] = Hashed(size, modified, hex)
        return hex
    }
}

/** `GET /updates` and `GET /updates/{file}`: for a signed-in user, whatever their permissions; the [UpdateService] decides what exists. */
internal fun Route.updateRoutes(services: ServiceRegistry) {
    get("/updates") {
        call.respond(blocking { services.require<UpdateService>().list(call.attributes[PrincipalKey]) })
    }

    get("/updates/{file}") {
        val name = call.parameters["file"].orEmpty()
        val path = blocking { services.require<UpdateService>().open(call.attributes[PrincipalKey], name) }
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, path.fileName.toString()).toString())
        call.respond(LocalFileContent(path.toFile(), ContentType.Application.OctetStream))
    }
}
