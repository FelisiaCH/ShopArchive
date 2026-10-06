package xyz.felismp.shoparchive.server.records

import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.TEST_PIN
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.deletePath
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.SlipDto
import xyz.felismp.shoparchive.shared.UserRef
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private fun dto(
    id: String = "id1", date: String = "2026-09-27", type: EntryType = EntryType.INCOME, branch: String = "main", item: String = "coffee", note: String = "",
    category: String? = null, by: String = "noy", deleted: Boolean = false, slips: Int = 0, tenders: List<xyz.felismp.shoparchive.shared.TenderDto> = listOf(cash("LAK", "150000")),
) = EntryDto(
    id, date, type, branch, category, item, note, UserRef("u1", by), "${date}T09:05:07+07:00", "${date}T09:05:07+07:00", deleted, tenders,
    List(slips) { SlipDto("slip-${it + 1}.jpg", "00") },
)

private fun csvText(bytes: ByteArray) = String(bytes, Charsets.UTF_8).removePrefix("﻿")

/** The export: its file, who may ask for it, and the console command. */
class ExportTest {
    @TempDir
    lateinit var root: Path

    private fun env(): AuthEnv = AuthEnv(root, NO_BACKOFF, withRecords = true).also { e ->
        e.records!!.branches.add("main", "Main")
        e.records!!.branches.add("market", "Market")
        e.records!!.categories.add("misc", LocalizedName("ອື່ນໆ", "อื่นๆ", "Misc"), AppliesTo.BOTH)
    }

    // --- the CSV ---

    @Test
    fun anEntryWithTwoTendersMakesTwoRowsUnderTheHeaderInTheAgreedOrderAndStartsWithABom() {
        val bytes = csvBytes(listOf(dto(slips = 2, category = "misc", tenders = listOf(cash("LAK", "150000"), online("THB", "12.50")))), deleted = false)
        assertTrue(bytes.take(3).toByteArray().contentEquals(BOM))
        assertEquals(
            listOf(
                "date,time,entry id,type,branch,category,item,note,currency,method,amount,created-by,slip count",
                "2026-09-27,09:05:07,id1,income,main,misc,coffee,,LAK,cash,150000,noy,2",
                "2026-09-27,09:05:07,id1,income,main,misc,coffee,,THB,online,12.50,noy,2",
            ),
            csvText(bytes).split("\r\n").dropLast(1),
        )
    }

    @Test
    fun aCommaAQuoteAndALineBreakInANoteAreQuotedAsRfc4180Says() {
        val text = csvText(csvBytes(listOf(dto(note = "a,b \"c\"\nd")), deleted = false))
        assertTrue("""coffee,"a,b ""c""
d",LAK""" in text, text)
    }

    @Test
    fun aTextThatWouldBeAFormulaGetsAnApostropheAndLaoAndThaiSurvive() {
        for (bad in listOf("=1+1", "+1", "-1", "@x", "\tx", "\rx")) {
            val line = csvText(csvBytes(listOf(dto(item = bad, note = bad, category = bad, by = bad)), deleted = false)).split("\r\n")[1]
            val quoted = csvField("'$bad")
            assertEquals("2026-09-27,09:05:07,id1,income,main,$quoted,$quoted,$quoted,LAK,cash,150000,$quoted,0", line)
        }
        val local = csvText(csvBytes(listOf(dto(item = "ກາເຟ", note = "กาแฟ", by = "ນ້ອຍ")), deleted = false))
        assertTrue("ກາເຟ,กาแฟ,LAK,cash,150000,ນ້ອຍ" in local)
    }

    @Test
    fun theDeletedColumnIsThereOnlyWhenDeletedEntriesWereAskedFor() {
        val rows = listOf(dto(id = "a"), dto(id = "b", deleted = true))
        assertFalse("deleted" in csvText(csvBytes(rows.filter { !it.deleted }, deleted = false)).lines().first())
        val with = csvText(csvBytes(rows, deleted = true)).split("\r\n")
        assertTrue(with[0].endsWith(",slip count,deleted"))
        assertTrue(with[1].endsWith(",0,false"))
        assertTrue(with[2].endsWith(",0,true"))
    }

    // --- the XLSX ---

    @Test
    fun theWorkbookHasTheEntriesAndTheDailySumsPerBranchAndCurrency() {
        val rows = listOf(
            dto(id = "a", item = "ກາເຟ", tenders = listOf(cash("LAK", "150000"), online("LAK", "50000"))),
            dto(id = "b", type = EntryType.EXPENSE, tenders = listOf(cash("LAK", "20000"), online("THB", "7.50"))),
            dto(id = "c", branch = "market", tenders = listOf(cash("THB", "100.25"))),
            dto(id = "d", deleted = true, tenders = listOf(cash("LAK", "999"))),
            dto(id = "e", date = "2026-09-28", tenders = listOf(cash("LAK", "1"))),
        )
        val bytes = xlsxBytes(rows, deleted = true, exponents = mapOf("LAK" to 0, "THB" to 2))
        ReadableWorkbook(ByteArrayInputStream(bytes)).use { book ->
            assertEquals(listOf("Entries", "Daily"), book.sheets.map { it.name }.toList())
            val entries = book.getSheet(0).get().read()
            assertEquals(1 + 7, entries.size)
            assertEquals("ກາເຟ", entries[1].getCellText(6))
            assertEquals("amount", entries[0].getCellText(10))
            assertEquals("50000", entries[2].getCellAsNumber(10).get().toPlainString())
            assertEquals("true", entries.first { r -> r.getCellText(2) == "d" }.getCellText(13))
            val daily = book.getSheet(1).get().read().map { r -> (0..7).map { r.getCellText(it) } }
            assertEquals(listOf("date", "branch", "currency", "cash in", "cash out", "online in", "online out", "net"), daily[0])
            // date, branch, currency, cash in, cash out, online in, online out, net: the deleted entry is not in any of them
            assertEquals(listOf("2026-09-27", "main", "LAK", "150000", "20000", "50000", "0", "180000"), daily[1].map { it.removeSuffix(".0") })
            assertEquals(listOf("2026-09-27", "main", "THB", "0", "0", "0", "7.50", "-7.50"), daily[2])
            assertEquals(listOf("2026-09-27", "market", "THB", "100.25", "0", "0", "0", "100.25"), daily[3])
            assertEquals("2026-09-28", daily[4][0])
            assertEquals(5, daily.size)
        }
    }

    // --- the route ---

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.record(token: String, item: String, branch: String = "main"): EntryDto =
        createEntry(token, newEntry(item = item, branch = branch, tenders = listOf(cash("LAK", "1000"), cash("THB", "5.00")))).also { check(it.status.value == 201) { it.bodyAsText() } }.parsed(EntryDto.serializer())

    @Test
    fun theRouteNeedsTheNodeAndAFreshPinAndSaysWhatIsWrongWithTheDates() = env().run {
        val plain = login("plain")
        val noy = login("noy", grant = listOf(EXPORT_NODE))
        api {
            openDay(noy)
            val e = record(noy, "coffee")
            val q = "?from=${e.date}&to=${e.date}"
            assertEquals(ErrorCode.FORBIDDEN, getPath("/api/v1/export$q", plain).errorCode())
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/export$q", noy).status)
            for (bad in listOf("", "?from=${e.date}", "?to=${e.date}", "?from=${e.date}&to=2026-01-01", "?from=x&to=${e.date}", "$q&format=pdf")) {
                assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/export$bad", noy).status, bad)
            }
            clock.advance(Duration.ofMinutes(6))
            val asked = getPath("/api/v1/export$q", noy)
            assertEquals(HttpStatusCode.Unauthorized, asked.status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, asked.errorCode())
            postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(pin = TEST_PIN), noy)
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/export$q", noy).status)
        }
    }

    @Test
    fun theFileIsAnAttachmentNamedByTheDatesWithTheRightTypeAndDeletedEntriesOnlyOnRequest() = env().run {
        val noy = login("noy", grant = listOf(EXPORT_NODE))
        api {
            openDay(noy)
            record(noy, "keep")
            val gone = record(noy, "gone")
            assertEquals(HttpStatusCode.OK, deletePath("/api/v1/entries/${gone.date}/${gone.id}", noy).status)
            val q = "?from=${gone.date}&to=${gone.date}"

            val csv = getPath("/api/v1/export$q", noy)
            assertEquals("text/csv; charset=utf-8", csv.headers[HttpHeaders.ContentType])
            assertEquals("shoparchive-${gone.date}_${gone.date}.csv", ContentDisposition.parse(csv.headers[HttpHeaders.ContentDisposition]!!).parameter("filename"))
            assertTrue(csv.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
            val lines = csvText(csv.bodyAsBytes()).split("\r\n").dropLast(1)
            assertEquals(3, lines.size, "header and the two tenders of keep")
            assertTrue(lines.drop(1).all { ",keep," in it })

            val all = csvText(getPath("/api/v1/export$q&includeDeleted=true", noy).bodyAsBytes()).split("\r\n").dropLast(1)
            assertEquals(5, all.size)
            assertTrue(all[0].endsWith(",deleted") && all.drop(1).count { it.endsWith(",true") } == 2)

            val xlsx = getPath("/api/v1/export$q&format=xlsx", noy)
            assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx.headers[HttpHeaders.ContentType])
            assertTrue(xlsx.headers[HttpHeaders.ContentDisposition]!!.contains("shoparchive-${gone.date}_${gone.date}.xlsx"))
            ReadableWorkbook(ByteArrayInputStream(xlsx.bodyAsBytes())).use { assertEquals(listOf("Entries", "Daily"), it.sheets.map { s -> s.name }.toList()) }
        }
    }

    @Test
    fun aUserExportsOnlyWhatTheyCouldListAndNotAnotherBranch() = env().run {
        val noy = login("noy", grant = listOf(EXPORT_NODE))
        val mali = login("mali")
        val boss = login("boss", branches = listOf("main", "market"), grant = listOf(EXPORT_NODE, ENTRY_VIEW_ALL_NODE))
        api {
            openDay(noy)
            val mine = record(noy, "mine")
            record(mali, "hers")
            val q = "?from=${mine.date}&to=${mine.date}"
            assertEquals(listOf("mine"), csvText(getPath("/api/v1/export$q", noy).bodyAsBytes()).lines().drop(1).filter { it.isNotEmpty() }.map { it.split(",")[6] }.distinct())
            assertEquals(setOf("mine", "hers"), csvText(getPath("/api/v1/export$q", boss).bodyAsBytes()).lines().drop(1).filter { it.isNotEmpty() }.map { it.split(",")[6] }.toSet())
            val other = getPath("/api/v1/export$q&branch=market", noy)
            assertEquals(HttpStatusCode.Forbidden, other.status)
            assertEquals(ErrorCode.FORBIDDEN, other.errorCode())
        }
    }

    // --- the console ---

    @Test
    fun theConsoleCommandWritesOneCsvUnderExportsAndSaysWhereAndHowManyRows() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = record(noy, "ກາເຟ")
            val said = console("export ${e.date} ${e.date}")
            val file = Files.list(root.resolve("exports")).use { it.toList() }.single()
            assertTrue(Regex("\\d{8}-\\d{6}\\.csv").matches(file.fileName.toString()), file.toString())
            assertEquals(listOf("Exported 2 rows to $file"), said)
            val text = csvText(Files.readAllBytes(file))
            assertEquals(3, text.split("\r\n").dropLast(1).size)
            assertTrue("ກາເຟ" in text)
            assertTrue(Files.list(root.resolve("exports")).use { it.toList() }.none { it.fileName.toString().endsWith(".tmp") })
            assertEquals(listOf("Exported 0 rows to ${root.resolve("exports")}/"), console("export ${e.date} ${e.date} market").map { it.replace(Regex("exports/.*\\.csv"), "exports/") })
            // A second export (in the same second when the clock is fixed) is a new file; the first one is unchanged.
            assertEquals(2, Files.list(root.resolve("exports")).use { it.toList() }.size)
            assertTrue("ກາເຟ" in csvText(Files.readAllBytes(file)))
            assertTrue(console("export 2026-02-30 2026-03-01").single().contains("not a date"))
            assertTrue(console("export").single().startsWith("Usage"))
        }
    }
}
