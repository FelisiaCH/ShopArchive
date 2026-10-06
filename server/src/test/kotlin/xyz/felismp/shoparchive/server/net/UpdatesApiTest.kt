package xyz.felismp.shoparchive.server.net

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.UpdateService
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.UpdateFile
import xyz.felismp.shoparchive.shared.UpdatePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `GET /api/v1/updates`: which files of `downloads/` are offered, as what, and that only those can be fetched. */
class UpdatesApiTest {
    @TempDir
    lateinit var root: Path

    private fun env() = AuthEnv(root, NO_BACKOFF)

    private fun download(name: String, content: String): Path =
        root.resolve("downloads").also { Files.createDirectories(it) }.resolve(name).also { Files.writeString(it, content) }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val principal = Principal("u", "noy", "d", "s")

    @Test
    fun filesNeedASignedInUser() = env().run {
        download("ShopArchive-1.0.1.apk", "apk")
        api {
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/updates").status)
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/updates/ShopArchive-1.0.1.apk").status)
        }
    }

    @Test
    fun listsOnlyNamedInstallersWithPlatformVersionBuildSizeAndHash() = env().run {
        download("ShopArchive-1.0.1.apk", "apk-bytes")
        download("ShopArchive-1.2.0-2.msi", "msi-bytes!")
        download("ShopArchive-1.2.0.MSI", "upper")
        download("notes.txt", "ignored")
        download("ShopArchive-1.0.1.zip", "ignored")
        download("ShopArchive-latest.apk", "ignored")
        download("shoparchive-1.0.1.apk", "ignored")
        val token = login("noy")
        api {
            val files = getPath("/api/v1/updates", token).parsed(ListSerializer(UpdateFile.serializer()))
            assertEquals(
                listOf(
                    UpdateFile("ShopArchive-1.2.0-2.msi", UpdatePlatform.WINDOWS, "1.2.0", 2, 10, sha256("msi-bytes!")),
                    UpdateFile("ShopArchive-1.2.0.MSI", UpdatePlatform.WINDOWS, "1.2.0", 0, 5, sha256("upper")),
                    UpdateFile("ShopArchive-1.0.1.apk", UpdatePlatform.ANDROID, "1.0.1", 0, 9, sha256("apk-bytes")),
                ),
                files,
            )
        }
    }

    @Test
    fun noDownloadsFolderIsAnEmptyList() = env().run {
        Files.deleteIfExists(root.resolve("downloads"))
        assertEquals(emptyList(), services.get(UpdateService::class.java)!!.list(principal))
    }

    @Test
    fun theHashIsKeptWhileSizeAndTimeStayAndWorkedOutAgainWhenTheyChange() = env().run {
        val service = services.get(UpdateService::class.java)!!
        val file = download("ShopArchive-1.0.1.apk", "aaaa")
        val time = Files.getLastModifiedTime(file)
        assertEquals(sha256("aaaa"), service.list(principal).single().sha256)

        // Same size, same time: the cached hash answers, so the changed bytes are not read.
        Files.writeString(file, "bbbb")
        Files.setLastModifiedTime(file, time)
        assertEquals(sha256("aaaa"), service.list(principal).single().sha256)

        Files.setLastModifiedTime(file, FileTime.fromMillis(time.toMillis() + 5_000))
        assertEquals(sha256("bbbb"), service.list(principal).single().sha256)
    }

    @Test
    fun aListedFileIsSentWithItsBytes() = env().run {
        download("ShopArchive-1.0.1.apk", "apk-bytes")
        val token = login("noy")
        api {
            val response = getPath("/api/v1/updates/ShopArchive-1.0.1.apk", token)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("apk-bytes", response.bodyAsBytes().decodeToString())
            assertTrue("ShopArchive-1.0.1.apk" in response.headers[HttpHeaders.ContentDisposition].orEmpty())
        }
    }

    @Test
    fun anythingThatIsNotAListedNameIsNotFound() = env().run {
        download("ShopArchive-1.0.1.apk", "apk-bytes")
        download("secret.txt", "secret")
        Files.createDirectories(root.resolve("config"))
        val token = login("noy")
        api {
            for (name in listOf(
                "secret.txt", "ShopArchive-9.9.9.apk", "..%2Fconfig%2Fshoparchive.yml", "%2e%2e%2fconfig", "..", "ShopArchive-1.0.1.apk%2F..%2F..%2Fsecret.txt",
                "..%5Cconfig", "ShopArchive-1.0.1.apk%00.txt", "..%2Fdownloads%2FShopArchive-1.0.1.apk",
            )) {
                val response = client.get("/api/v1/updates/$name") {
                    header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                assertEquals(HttpStatusCode.NotFound, response.status, name)
                assertEquals(ErrorCode.NOT_FOUND, response.errorCode(), name)
            }
        }
    }

    @Test
    fun theServiceItselfRefusesNamesOutsideTheListing() = env().run {
        val service = services.get(UpdateService::class.java)!!
        download("ShopArchive-1.0.1.apk", "apk-bytes")
        download("secret.txt", "secret")
        for (name in listOf("secret.txt", "../config/shoparchive.yml", "/etc/passwd", "ShopArchive-1.0.1.apk/../secret.txt", "")) {
            val error = runCatching { service.open(principal, name) }.exceptionOrNull()
            assertTrue(error is xyz.felismp.shoparchive.api.ApiError && error.status == 404, name)
        }
        assertEquals("ShopArchive-1.0.1.apk", service.open(principal, "ShopArchive-1.0.1.apk").fileName.toString())
    }

    @Test
    fun aLinkInDownloadsIsNotOffered() = env().run {
        val target = download("elsewhere.bin", "outside")
        val link = root.resolve("downloads/ShopArchive-2.0.0.apk")
        val made = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        if (made) {
            val service = services.get(UpdateService::class.java)!!
            assertEquals(emptyList(), service.list(principal))
            assertNotEquals(null, runCatching { service.open(principal, "ShopArchive-2.0.0.apk") }.exceptionOrNull())
        }
    }
}
