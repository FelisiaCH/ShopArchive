package xyz.felismp.shoparchive.server.records

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.builtins.ListSerializer
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.DEVICES_REVOKE_NODE
import xyz.felismp.shoparchive.server.auth.USERS_MANAGE_NODE
import xyz.felismp.shoparchive.server.auth.enroll
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.server.auth.putJson
import xyz.felismp.shoparchive.server.auth.testJson
import xyz.felismp.shoparchive.server.auth.token
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import java.nio.file.Files
import java.nio.file.Path

/** A JPEG as far as the server can tell: the first bytes are the JPEG marker. The rest is [seed] so two slips differ. */
internal fun jpeg(seed: Int = 0, size: Int = 64) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(size) { (it + seed).toByte() }

internal fun png(seed: Int = 0) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { (it + seed).toByte() }

internal fun cash(currency: String, amount: String) = TenderDto(currency, TenderMethod.CASH, amount)

internal fun online(currency: String, amount: String) = TenderDto(currency, TenderMethod.ONLINE, amount)

internal fun newEntry(
    tenders: List<TenderDto> = listOf(cash("LAK", "150000")),
    type: EntryType = EntryType.INCOME,
    branch: String = "main",
    category: String? = null,
    item: String = "coffee",
    note: String = "",
    id: String = newUuid7(),
) = CreateEntryRequest(id, type, branch, category, item, note, tenders)

/** A clerk's token, made (user, device, PIN) the first time. [grant] are extra nodes; an op or a manager of users and branches signs in with a password. */
internal fun AuthEnv.login(name: String, branches: List<String> = listOf("main"), op: Boolean = false, grant: List<String> = emptyList()): String {
    if (name !in users.userNames()) {
        users.addUser(name, "none", branches)
        if (op) users.setOp(name, true)
        grant.forEach { users.setUserPermission(name, it, true) }
    }
    val device = loginDevices.getOrPut(name) { enroll(name) }
    val needsPassword = op || grant.any { it == USERS_MANAGE_NODE || it == BRANCHES_MANAGE_NODE || it == DEVICES_REVOKE_NODE }
    return token(device, name, secretIsPassword = needsPassword)
}

/** An open day on branch main with the given float, opened by [token]'s user; the session. */
internal suspend fun ApplicationTestBuilder.openDay(token: String, branch: String = "main", float: Map<String, String> = mapOf("LAK" to "50000")): SessionDto {
    val response = postJson("/api/v1/sessions/$branch/open", OpenSessionRequest.serializer(), OpenSessionRequest(float), token)
    check(response.status.value in 200..201) { "open day failed: ${response.status} ${response.bodyText()}" }
    return response.parsed(OpenSessionResponse.serializer()).session
}

private suspend fun HttpResponse.bodyText() = bodyAsText()

internal suspend fun ApplicationTestBuilder.createEntry(token: String, request: CreateEntryRequest, slips: List<ByteArray> = emptyList()): HttpResponse =
    if (slips.isEmpty()) {
        postJson("/api/v1/entries", CreateEntryRequest.serializer(), request, token)
    } else {
        client.post("/api/v1/entries") {
            header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(multipart(testJson.encodeToString(CreateEntryRequest.serializer(), request), slips))
        }
    }

internal suspend fun ApplicationTestBuilder.updateEntry(token: String, entry: EntryDto, request: UpdateEntryRequest, slips: List<ByteArray> = emptyList()): HttpResponse =
    if (slips.isEmpty()) {
        putJson("/api/v1/entries/${entry.date}/${entry.id}", UpdateEntryRequest.serializer(), request, token)
    } else {
        client.put("/api/v1/entries/${entry.date}/${entry.id}") {
            header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(multipart(testJson.encodeToString(UpdateEntryRequest.serializer(), request), slips))
        }
    }

internal fun multipart(entryJson: String, slips: List<ByteArray>) = MultiPartFormDataContent(
    formData {
        append("entry", entryJson, Headers.build { append(HttpHeaders.ContentType, "application/json") })
        slips.forEachIndexed { index, bytes ->
            append(
                "slip-${index + 1}", bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"slip-${index + 1}.jpg\"")
                },
            )
        }
    },
)

internal suspend fun ApplicationTestBuilder.entries(token: String, query: String = ""): List<EntryDto> =
    getPath("/api/v1/entries$query", token).parsed(ListSerializer(EntryDto.serializer()))

internal suspend fun ApplicationTestBuilder.closeDay(token: String, session: SessionDto, counted: Map<String, String>, note: String = "", branch: String = session.branch): HttpResponse =
    postJson("/api/v1/sessions/$branch/${session.id}/close", CloseSessionRequest.serializer(), CloseSessionRequest(counted, note), token)

/** Every entry folder below `record/`, as `yyyy/MM/dd/<id>` paths. */
internal fun Path.entryFolders(): List<String> {
    val base = resolve("record")
    if (!Files.isDirectory(base)) return emptyList()
    return Files.walk(base, 4).use { paths ->
        paths.filter { Files.isDirectory(it) && base.relativize(it).nameCount == 4 && !base.relativize(it).startsWith("sessions") }
            .map { base.relativize(it).toString().replace('\\', '/') }.sorted().toList()
    }
}

internal fun Path.entryDir(entry: EntryDto): Path = resolve("record/${entry.date.replace('-', '/')}/${entry.id}")
