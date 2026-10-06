package xyz.felismp.shoparchive.server.records

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.SessionService
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.authService
import xyz.felismp.shoparchive.server.auth.deletePath
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open day and close day through the real API module. */
class SessionsApiTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String = NO_BACKOFF): AuthEnv = AuthEnv(root, config, withRecords = true).also { e ->
        e.records!!.branches.add("main", "Main")
        e.records.branches.add("market", "Market")
    }

    private fun sessionFiles(branch: String = "main"): List<String> {
        val dir = root.resolve("record/sessions/$branch")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
    }

    // --- open ---

    @Test
    fun openingTheDayStoresTheChangeCountedAndTheBusinessDate() = env().run {
        val noy = login("noy")
        api {
            val response = postJson("/api/v1/sessions/main/open", OpenSessionRequest.serializer(), OpenSessionRequest(mapOf("LAK" to "50000", "THB" to "20.5")), noy)

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.parsed(OpenSessionResponse.serializer())
            assertNull(body.alreadyOpenBy)
            val session = body.session
            assertEquals(SessionStatus.OPEN, session.status)
            assertEquals("2026-10-03", session.businessDate)
            assertEquals(mapOf("LAK" to "50000", "THB" to "20.50"), session.float, "padded to the currency's decimals")
            assertEquals("noy", session.openedBy.name)
            assertEquals(listOf("${session.id}.yml"), sessionFiles())
            assertTrue(isUuidV7Text(session.id))
            val text = Files.readString(root.resolve("record/sessions/main/${session.id}.yml"))
            assertTrue("float: { LAK: \"50000\", THB: \"20.50\" }" in text && "status: open" in text && text.startsWith("file-version: 1\n"), text)
            assertEquals(session, getPath("/api/v1/sessions/main/current", noy).parsed(SessionDto.serializer()))
        }
    }

    private fun isUuidV7Text(id: String) = xyz.felismp.shoparchive.shared.isUuidV7(id)

    @Test
    fun openingAnOpenDayGivesTheOpenOneAndSaysWhoOpenedItNothingIsChanged() = env().run {
        val noy = login("noy")
        val kham = login("kham")
        api {
            val first = openDay(noy, float = mapOf("LAK" to "50000"))
            val before = Files.readAllBytes(root.resolve("record/sessions/main/${first.id}.yml"))

            val second = postJson("/api/v1/sessions/main/open", OpenSessionRequest.serializer(), OpenSessionRequest(mapOf("LAK" to "99999")), kham)

            assertEquals(HttpStatusCode.OK, second.status)
            val body = second.parsed(OpenSessionResponse.serializer())
            assertEquals(first, body.session)
            assertEquals("noy", body.alreadyOpenBy!!.name)
            assertEquals(listOf("${first.id}.yml"), sessionFiles())
            assertTrue(before.contentEquals(Files.readAllBytes(root.resolve("record/sessions/main/${first.id}.yml"))))
        }
    }

    @Test
    fun deviceOpeningAtTheSameTimeMakeOneSessionAndTheLaterOnesGetThatOne() = env().run {
        val principals = (1..8).map { authService().authenticate(login("clerk$it"))!! }
        val service = services.get(SessionService::class.java)!!
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        val answers = principals.map { principal ->
            pool.submit<OpenSessionResponse> {
                start.await()
                service.open(principal, "main", OpenSessionRequest(mapOf("LAK" to "1000")))
            }
        }
        start.countDown()
        val results = answers.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, results.count { it.alreadyOpenBy == null })
        assertEquals(7, results.count { it.alreadyOpenBy != null })
        assertEquals(1, results.map { it.session.id }.toSet().size)
        assertEquals(1, sessionFiles().size)
        val opener = results.single { it.alreadyOpenBy == null }.session.openedBy
        assertTrue(results.filter { it.alreadyOpenBy != null }.all { it.alreadyOpenBy == opener })
    }

    @Test
    fun eachBranchHasItsOwnDayAndAnUnknownOrArchivedOrForeignBranchIsRefused() = env().run {
        val noy = login("noy")
        val boss = login("boss", op = true)
        records!!.branches.update("market", null, archived = true)
        api {
            openDay(noy)

            assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/sessions/market/open", OpenSessionRequest.serializer(), OpenSessionRequest(), noy).status)
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/sessions/market/open", OpenSessionRequest.serializer(), OpenSessionRequest(), boss).status, "archived")
            assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/sessions/nowhere/open", OpenSessionRequest.serializer(), OpenSessionRequest(), boss).status)
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/sessions/market/current", noy).status)
            assertEquals(ErrorCode.NO_OPEN_SESSION, getPath("/api/v1/sessions/market/current", boss).errorCode())
        }
    }

    @Test
    fun aBadChangeIsRefusedAndNeedsTheOpenNode() = env().run {
        users.addUser("locked", "none", listOf("main"))
        users.setUserPermission("locked", DAY_OPEN_NODE, false)
        val noy = login("noy")
        val locked = login("locked")
        api {
            for (float in listOf(mapOf("LAK" to "12.5"), mapOf("LAK" to "-1"), mapOf("EUR" to "5"), mapOf("THB" to "1,5"), mapOf("LAK" to ""))) {
                assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/sessions/main/open", OpenSessionRequest.serializer(), OpenSessionRequest(float), noy).status, float.toString())
            }
            assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/sessions/main/open", OpenSessionRequest.serializer(), OpenSessionRequest(), locked).status)
            assertEquals(emptyList(), sessionFiles())
            assertEquals(HttpStatusCode.Created, postJson("/api/v1/sessions/main/open", OpenSessionRequest.serializer(), OpenSessionRequest(mapOf("LAK" to "0")), noy).status, "a float of 0 is fine")
        }
    }

    // --- close ---

    private val closer = listOf(DAY_CLOSE_NODE)

    @Test
    fun thePreviewIsWhatTheCloseExpectsEvenWithMovedEntriesAndAnotherSessionOnTheSameDay() = env().run {
        val boss = login("boss", op = true)
        api {
            val first = openDay(boss, float = mapOf("LAK" to "50000", "THB" to "20"))
            createEntry(boss, newEntry(tenders = listOf(cash("LAK", "200000"))))
            createEntry(boss, newEntry(type = EntryType.EXPENSE, tenders = listOf(cash("LAK", "30000"), cash("THB", "5.25"))))
            createEntry(boss, newEntry(tenders = listOf(online("LAK", "999")), item = "card"), listOf(jpeg()))
            val moved = createEntry(boss, newEntry(tenders = listOf(cash("LAK", "7000")))).parsed(EntryDto.serializer())
            postJson("/api/v1/entries/${moved.date}/${moved.id}/move", xyz.felismp.shoparchive.shared.MoveEntryRequest.serializer(), xyz.felismp.shoparchive.shared.MoveEntryRequest("2026-10-01"), boss)

            val preview = getPath("/api/v1/sessions/main/${first.id}/preview", boss).parsed(xyz.felismp.shoparchive.shared.SessionPreview.serializer())
            assertEquals(mapOf("LAK" to "227000", "THB" to "14.75", "USD" to "0.00"), preview.expected, "the moved entry still belongs to its session")
            assertEquals(mapOf("LAK" to "50000", "THB" to "20.00", "USD" to "0.00"), preview.float)
            assertEquals(mapOf("LAK" to "207000", "THB" to "0.00", "USD" to "0.00"), preview.cashIn)
            assertEquals(mapOf("LAK" to "30000", "THB" to "5.25", "USD" to "0.00"), preview.cashOut)
            val close = closeDay(boss, first, mapOf("LAK" to "227000", "THB" to "14.75", "USD" to "0.00")).parsed(SessionDto.serializer()).close!!
            assertEquals(close.expected, preview.expected, "one code path")

            assertEquals(HttpStatusCode.Conflict, getPath("/api/v1/sessions/main/${first.id}/preview", boss).status)
            val second = openDay(boss, float = mapOf("LAK" to "100"))
            createEntry(boss, newEntry(tenders = listOf(cash("LAK", "5"))))
            assertEquals(mapOf("LAK" to "105", "THB" to "0.00", "USD" to "0.00"), getPath("/api/v1/sessions/main/${second.id}/preview", boss).parsed(xyz.felismp.shoparchive.shared.SessionPreview.serializer()).expected)
            assertEquals(HttpStatusCode.NotFound, getPath("/api/v1/sessions/main/${newUuid7()}/preview", boss).status)
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/sessions/main/${second.id}/preview", login("clerk")).status)
        }
    }

    @Test
    fun closingComparesTheCountWithChangePlusCashInMinusCashOutPerCurrency() = env().run {
        val lead = login("lead", grant = closer)
        api {
            val day = openDay(lead, float = mapOf("LAK" to "50000", "THB" to "20"))
            createEntry(lead, newEntry(tenders = listOf(cash("LAK", "200000"))))
            createEntry(lead, newEntry(type = EntryType.EXPENSE, tenders = listOf(cash("LAK", "30000"))))
            createEntry(lead, newEntry(tenders = listOf(cash("THB", "120.50"), online("THB", "999")), item = "mixed"), listOf(jpeg()))
            createEntry(lead, newEntry(tenders = listOf(online("LAK", "777777"))), listOf(jpeg()))
            val deleted = createEntry(lead, newEntry(tenders = listOf(cash("LAK", "1000000")))).parsed(EntryDto.serializer())
            deletePath("/api/v1/entries/${deleted.date}/${deleted.id}", lead)

            val response = closeDay(lead, day, mapOf("LAK" to "220000", "THB" to "140.50"))

            assertEquals(HttpStatusCode.OK, response.status)
            val closed = response.parsed(SessionDto.serializer())
            assertEquals(SessionStatus.CLOSED, closed.status)
            val close = closed.close!!
            assertEquals(mapOf("LAK" to "220000", "THB" to "140.50"), close.expected, "online money and deleted entries are not in the drawer")
            assertEquals(mapOf("LAK" to "0", "THB" to "0.00"), close.variance)
            assertEquals(mapOf("LAK" to "170000", "THB" to "120.50"), close.handover)
            assertEquals(emptyList(), close.belowFloor)
            assertEquals("lead", close.closedBy.name)
            val text = Files.readString(root.resolve("record/sessions/main/${day.id}.yml"))
            assertTrue("status: closed" in text && "expected: { LAK: \"220000\", THB: \"140.50\" }" in text && "handover: { LAK: \"170000\", THB: \"120.50\" }" in text, text)
        }
    }

    @Test
    fun aDrawerThatIsShortOrOverNeedsANoteAndTheVarianceHasASign() = env().run {
        val lead = login("lead", grant = closer)
        api {
            val short = openDay(lead)
            createEntry(lead, newEntry(tenders = listOf(cash("LAK", "200000"))))
            // expected 250000
            val noNote = closeDay(lead, short, mapOf("LAK" to "249500"))
            assertEquals(HttpStatusCode.BadRequest, noNote.status)
            assertEquals(ErrorCode.INVALID_REQUEST, noNote.errorCode())
            assertEquals(HttpStatusCode.BadRequest, closeDay(lead, short, mapOf("LAK" to "249500"), note = "   ").status, "a blank note is no note")
            assertEquals(SessionStatus.OPEN, getPath("/api/v1/sessions/main/current", lead).parsed(SessionDto.serializer()).status, "a refused close changes nothing")

            val closed = closeDay(lead, short, mapOf("LAK" to "249500"), note = "gave 500 too much change").parsed(SessionDto.serializer()).close!!
            assertEquals(mapOf("LAK" to "-500"), closed.variance)
            assertEquals(mapOf("LAK" to "199500"), closed.handover)
            assertEquals("gave 500 too much change", closed.note)

            val over = openDay(lead)
            createEntry(lead, newEntry(tenders = listOf(cash("LAK", "1000"))))
            val closedOver = closeDay(lead, over, mapOf("LAK" to "52000"), note = "a customer left a tip").parsed(SessionDto.serializer()).close!!
            assertEquals(mapOf("LAK" to "1000"), closedOver.variance)
            assertEquals(mapOf("LAK" to "2000"), closedOver.handover)
        }
    }

    @Test
    fun countingLessThanTheChangeHandsOverNothingAndWarns() = env().run {
        val lead = login("lead", grant = closer)
        api {
            val day = openDay(lead, float = mapOf("LAK" to "50000"))

            val noNote = closeDay(lead, day, mapOf("LAK" to "20000"))
            assertEquals(HttpStatusCode.BadRequest, noNote.status)

            val close = closeDay(lead, day, mapOf("LAK" to "20000"), note = "took 30000 for the bank").parsed(SessionDto.serializer()).close!!
            assertEquals(mapOf("LAK" to "-30000"), close.variance)
            assertEquals(mapOf("LAK" to "0"), close.handover)
            assertEquals(listOf("LAK"), close.belowFloor)
        }
    }

    @Test
    fun aCurrencyWithMoneyInTheDrawerMustBeCountedAndOneCountedAnywayIsComparedWithNothing() = env().run {
        val lead = login("lead", grant = closer)
        api {
            val day = openDay(lead, float = mapOf("LAK" to "50000"))
            createEntry(lead, newEntry(tenders = listOf(cash("THB", "10"))))

            val missing = closeDay(lead, day, mapOf("LAK" to "50000"))
            assertEquals(HttpStatusCode.BadRequest, missing.status)

            val close = closeDay(lead, day, mapOf("LAK" to "50000", "THB" to "10", "USD" to "3"), note = "a dollar tip, 3 USD").parsed(SessionDto.serializer()).close!!
            assertEquals(listOf("LAK", "THB", "USD"), close.counted.keys.toList(), "in the order of config/currencies.yml")
            assertEquals("3.00", close.variance["USD"])
        }
    }

    @Test
    fun aDayClosesOnceAndNeedsTheCloseNode() = env().run {
        val lead = login("lead", grant = closer)
        val noy = login("noy")
        api {
            val day = openDay(noy)

            assertEquals(HttpStatusCode.Forbidden, closeDay(noy, day, mapOf("LAK" to "50000")).status)
            assertEquals(HttpStatusCode.OK, closeDay(lead, day, mapOf("LAK" to "50000")).status)
            val again = closeDay(lead, day, mapOf("LAK" to "50000"))

            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals(ErrorCode.SESSION_CLOSED, again.errorCode())
            assertEquals(HttpStatusCode.NotFound, closeDay(lead, day.copy(id = newUuid7()), mapOf("LAK" to "50000")).status)
            assertEquals(HttpStatusCode.Forbidden, closeDay(lead, day, mapOf("LAK" to "50000"), branch = "market").status, "lead works in main only")
        }
    }

    @Test
    fun closingEndsTheDayANewOpenStartsAnotherSessionAndTheOldEntriesStayEditable() = env().run {
        val lead = login("lead", grant = closer)
        api {
            val first = openDay(lead)
            val entry = createEntry(lead, newEntry(item = "sold before closing")).parsed(EntryDto.serializer())
            closeDay(lead, first, mapOf("LAK" to "200000"))

            assertEquals(ErrorCode.NO_OPEN_SESSION, getPath("/api/v1/sessions/main/current", lead).errorCode())
            val second = openDay(lead, float = mapOf("LAK" to "10000"))

            assertTrue(first.id != second.id)
            assertEquals(listOf(first.id, second.id), getPath("/api/v1/sessions/main", lead).parsed(ListSerializer(SessionDto.serializer())).map { it.id })
            assertEquals(listOf(SessionStatus.CLOSED, SessionStatus.OPEN), getPath("/api/v1/sessions/main?from=2026-10-03&to=2026-10-03", lead).parsed(ListSerializer(SessionDto.serializer())).map { it.status })
            assertEquals(emptyList(), getPath("/api/v1/sessions/main?from=2026-10-04&to=2026-10-05", lead).parsed(ListSerializer(SessionDto.serializer())))
            assertEquals(HttpStatusCode.OK, updateEntry(lead, entry, change(entry, "fixed after the close")).status, "closing does not lock entries")
            val third = createEntry(lead, newEntry())
            assertEquals(second.id, third.parsed(EntryDto.serializer()).session)
        }
    }

    private fun change(entry: EntryDto, item: String) =
        xyz.felismp.shoparchive.shared.UpdateEntryRequest(entry.type, entry.category, item, entry.note, entry.tenders)

    @Test
    fun theStoredSessionsAreReadBackAfterARestartIncludingWhichOneIsOpen() = env().run {
        val lead = login("lead", grant = closer)
        api {
            val first = openDay(lead)
            closeDay(lead, first, mapOf("LAK" to "50000"))
            val second = openDay(lead, float = mapOf("LAK" to "1234", "THB" to "0.05"))
            val sessions = records!!.sessions

            sessions.load()

            assertEquals(second, sessions.openFor("main")!!.toDto())
            assertNotNull(sessions.find(first.id)!!.closed)
        }
    }

    @Test
    fun aSessionFileThatCannotBeReadIsLoggedAndLeftAloneTheRestStillLoad() = env().run {
        val lead = login("lead", grant = closer)
        val boss = login("boss", op = true)
        api {
            val good = openDay(lead)
            val bad = openDay(boss, branch = "market")
            Files.writeString(root.resolve("record/sessions/market/${bad.id}.yml"), "file-version: 1\nid: [broken")

            records!!.sessions.load()

            assertNotNull(records.sessions.find(good.id))
            assertNull(records.sessions.find(bad.id))
            assertTrue(log.errors.any { bad.id in it && "left alone" in it }, log.errors.toString())
            assertEquals("file-version: 1\nid: [broken", Files.readString(root.resolve("record/sessions/market/${bad.id}.yml")))
        }
    }

    @Test
    fun closingAfterTheDateChangedStillBelongsToTheDateItWasOpened() = env().run {
        val lead = login("lead", grant = closer)
        api {
            val day = openDay(lead)
            createEntry(lead, newEntry(tenders = listOf(cash("LAK", "1000"))))
            clock.advance(Duration.ofDays(2))

            val closed = closeDay(login("lead", grant = closer), day, mapOf("LAK" to "51000")).parsed(SessionDto.serializer())

            assertEquals("2026-10-03", closed.businessDate)
            assertTrue(closed.close!!.closedAt.startsWith("2026-10-05"))
        }
    }
}
