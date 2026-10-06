package xyz.felismp.shoparchive.server.records

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.TEST_PIN
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.authService
import xyz.felismp.shoparchive.server.auth.deletePath
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.MoveEntryRequest
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Entries through the real API module: what is accepted, who may do what, and what ends up on disk. */
class EntriesApiTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String = NO_BACKOFF): AuthEnv = AuthEnv(root, config, withRecords = true).also { e ->
        val records = e.records!!
        records.branches.add("main", "Main")
        records.branches.add("market", "Market")
        records.categories.add("supplies", LocalizedName("ວັດສະດຸ", "วัสดุ", "Supplies"), AppliesTo.EXPENSE)
        records.categories.add("sales", LocalizedName("ຂາຍ", "ขาย", "Sales"), AppliesTo.INCOME)
        records.categories.add("misc", LocalizedName("ອື່ນໆ", "อื่นๆ", "Misc"), AppliesTo.BOTH)
    }

    private fun update(entry: EntryDto, item: String = entry.item) =
        UpdateEntryRequest(entry.type, entry.category, item, entry.note, entry.tenders, keepSlips = null)

    // --- create ---

    @Test
    fun anIncomeInCashAndAnExpenseOnlineWithASlipAreRecordedListedAndReadBack() = env().run {
        val noy = login("noy")
        api {
            val day = openDay(noy)
            val coffee = createEntry(noy, newEntry(item = "coffee", category = "sales"))
            val slip = jpeg(seed = 7)
            val ice = createEntry(noy, newEntry(tenders = listOf(online("THB", "120.50")), type = EntryType.EXPENSE, item = "ice", category = "supplies"), listOf(slip))

            assertEquals(HttpStatusCode.Created, coffee.status)
            assertEquals(HttpStatusCode.Created, ice.status)
            val stored = ice.parsed(EntryDto.serializer())
            assertEquals("2026-10-03", stored.date)
            assertEquals(day.id, stored.session)
            assertEquals("120.50", stored.tenders.single().amount)
            assertEquals("slip-1.jpg", stored.slips.single().file)
            assertEquals(listOf("2026/10/03/${stored.id}", "2026/10/03/${coffee.parsed(EntryDto.serializer()).id}").sorted(), root.entryFolders())

            val listed = entries(noy)
            assertEquals(listOf("coffee", "ice"), listed.map { it.item })
            assertEquals(stored, getPath("/api/v1/entries/${stored.date}/${stored.id}", noy).parsed(EntryDto.serializer()))

            val download = getPath("/api/v1/entries/${stored.date}/${stored.id}/slips/1", noy)
            assertEquals("image/jpeg", download.contentType()?.withoutParameters().toString())
            assertContentEquals(slip, download.bodyAsBytes())

            val text = Files.readString(root.entryDir(stored).resolve("entry.yml"))
            assertTrue("session: ${day.id}" in text && "created-by: { id: \"${users.find("noy")!!.id}\", name: \"noy\" }" in text, text)
        }
    }

    @Test
    fun sendingTheSameIdAgainReturnsTheStoredEntryAndMakesNoNewFolder() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val request = newEntry(item = "first")
            val first = createEntry(noy, request)
            val folders = root.entryFolders()

            val again = createEntry(noy, request.copy(item = "a different body, ignored"))
            val slipless = createEntry(noy, request)

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(HttpStatusCode.OK, slipless.status)
            assertEquals(first.parsed(EntryDto.serializer()), again.parsed(EntryDto.serializer()))
            assertEquals("first", again.parsed(EntryDto.serializer()).item)
            assertEquals(folders, root.entryFolders())
            assertEquals(1, entries(noy).size)
        }
    }

    @Test
    fun anIdThatBelongsToSomeoneElseIsNotHandedToAnotherUser() = env().run {
        val noy = login("noy")
        val kham = login("kham")
        api {
            openDay(noy)
            val request = newEntry()
            createEntry(noy, request)

            val response = createEntry(kham, request)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(ErrorCode.CONFLICT, response.errorCode())
        }
    }

    @Test
    fun withoutAnOpenDayNothingIsRecordedUntilOneIsOpenedAndNotAfterItIsClosed() = env().run {
        val boss = login("boss", op = true)
        api {
            val refused = createEntry(boss, newEntry())
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(ErrorCode.NO_OPEN_SESSION, refused.errorCode())

            val day = openDay(boss)
            assertEquals(HttpStatusCode.Created, createEntry(boss, newEntry()).status)
            assertEquals(HttpStatusCode.OK, closeDay(boss, day, mapOf("LAK" to "200000")).status)

            assertEquals(ErrorCode.NO_OPEN_SESSION, createEntry(boss, newEntry()).errorCode())
            assertEquals(1, root.entryFolders().size)
        }
    }

    @Test
    fun anEntryAfterMidnightBelongsToTheDayThatIsStillOpen() = env().run {
        val noy = login("noy")
        api {
            val day = openDay(noy)
            clock.advance(Duration.ofHours(10)) // 01:00 the next day in Vientiane
            val late = login("noy")

            val stored = createEntry(late, newEntry()).parsed(EntryDto.serializer())

            assertEquals(day.businessDate, stored.date)
            assertEquals(day.id, stored.session)
        }
    }

    @Test
    fun withTheOpenDayRuleOffAnEntryNeedsNoDayAndTakesTodayOrTheOpenDay() = env(NO_BACKOFF + "records:\n  require-open-day: false\n").run {
        val noy = login("noy")
        api {
            val free = createEntry(noy, newEntry()).parsed(EntryDto.serializer())
            assertEquals("2026-10-03", free.date)
            assertNull(free.session)

            val day = openDay(noy)
            assertEquals(day.id, createEntry(noy, newEntry()).parsed(EntryDto.serializer()).session)
        }
    }

    @Test
    fun anEntryOnlineNeedsASlipAndACashEntryMayNotHaveOne() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            assertEquals(HttpStatusCode.BadRequest, createEntry(noy, newEntry(tenders = listOf(online("THB", "10")))).status)
            assertEquals(HttpStatusCode.BadRequest, createEntry(noy, newEntry(), listOf(jpeg())).status)
            assertEquals(HttpStatusCode.Created, createEntry(noy, newEntry(tenders = listOf(cash("LAK", "5000"), online("THB", "10"))), listOf(jpeg(), png())).status)
            assertEquals(1, root.entryFolders().size)
        }
    }

    @Test
    fun aCategoryMustExistFitTheTypeAndNotBeArchivedAndIsOnlyDemandedWhenConfigured() = env().run {
        val noy = login("noy")
        records!!.categories.update("misc", null, null, archived = true)
        api {
            openDay(noy)
            assertEquals(HttpStatusCode.Created, createEntry(noy, newEntry(category = null)).status, "no category, not required")
            assertEquals(HttpStatusCode.Created, createEntry(noy, newEntry(category = "sales")).status)
            for (category in listOf("supplies", "nope", "misc")) {
                val response = createEntry(noy, newEntry(category = category))
                assertEquals(HttpStatusCode.BadRequest, response.status, category)
            }
            assertEquals(2, root.entryFolders().size)
        }
    }

    @Test
    fun withCategoriesRequiredAnEntryWithoutOneIsRefused() = env(NO_BACKOFF + "records:\n  require-category: true\n").run {
        val noy = login("noy")
        api {
            openDay(noy)
            assertEquals(HttpStatusCode.BadRequest, createEntry(noy, newEntry(category = null)).status)
            assertEquals(HttpStatusCode.Created, createEntry(noy, newEntry(category = "sales")).status)
        }
    }

    @Test
    fun badAmountsCurrenciesIdsAndBranchesAreRefusedBeforeAnythingIsWritten() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val bad = listOf(
                newEntry(tenders = listOf(cash("LAK", "0"))),
                newEntry(tenders = listOf(cash("LAK", "12.5"))),
                newEntry(tenders = listOf(cash("THB", "1.234"))),
                newEntry(tenders = listOf(cash("LAK", "1,000"))),
                newEntry(tenders = listOf(cash("LAK", "-5"))),
                newEntry(tenders = listOf(cash("LAK", "1e3"))),
                newEntry(tenders = listOf(cash("EUR", "5"))),
                newEntry(tenders = emptyList()),
                newEntry(id = "not-a-uuid"),
                newEntry(id = java.util.UUID.randomUUID().toString()), // version 4
                newEntry(branch = "nowhere"),
            )
            for (request in bad) {
                val response = createEntry(noy, request)
                assertTrue(response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.Forbidden, "$request -> ${response.status}")
            }
            assertEquals(emptyList(), root.entryFolders())
            assertTrue(!Files.exists(root.resolve("data/staging")) || Files.list(root.resolve("data/staging")).use { it.count() } == 0L)
        }
    }

    @Test
    fun aSlipMustBeAJpegOrPngWithinTheSizeAndCountLimits() = env(NO_BACKOFF + "records:\n  slips:\n    max-count: 2\n    max-size-kb: 64\n").run {
        val noy = login("noy")
        api {
            openDay(noy)
            val pay = listOf(online("THB", "10"))
            val gif = "GIF89a".toByteArray() + ByteArray(20)
            assertEquals(HttpStatusCode.BadRequest, createEntry(noy, newEntry(tenders = pay), listOf(gif)).status)
            val big = createEntry(noy, newEntry(tenders = pay), listOf(jpeg(size = 65 * 1024)))
            assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, big.errorCode())
            assertEquals(HttpStatusCode.BadRequest, createEntry(noy, newEntry(tenders = pay), listOf(jpeg(1), jpeg(2), jpeg(3))).status)
            assertEquals(HttpStatusCode.Created, createEntry(noy, newEntry(tenders = pay), listOf(jpeg(1), png(2))).status)
            val stored = entries(noy).single()
            assertEquals(listOf("slip-1.jpg", "slip-2.png"), stored.slips.map { it.file })
        }
    }

    @Test
    fun slipsLargerThanTheNormalRequestLimitAreAccepted() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val threeMb = jpeg(size = 3 * 1024 * 1024)

            val response = createEntry(noy, newEntry(tenders = listOf(online("THB", "10"))), listOf(threeMb))

            assertEquals(HttpStatusCode.Created, response.status)
            val stored = response.parsed(EntryDto.serializer())
            assertContentEquals(threeMb, getPath("/api/v1/entries/${stored.date}/${stored.id}/slips/1", noy).bodyAsBytes())
            // ...while any other route still has the 1 MB limit.
            val huge = client.post("/api/v1/branches") {
                header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
                header(HttpHeaders.Authorization, "Bearer $noy")
                contentType(ContentType.Application.Json)
                setBody("{\"key\":\"x\",\"displayName\":\"" + "y".repeat(2 * 1024 * 1024) + "\"}")
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        }
    }

    // --- who may see and write what ---

    @Test
    fun aClerkOfOneBranchCannotCreateListOrReadInAnother() = env().run {
        val noy = login("noy", listOf("main"))
        val boss = login("boss", op = true)
        api {
            openDay(noy)
            openDay(boss, branch = "market")
            val inMarket = createEntry(boss, newEntry(branch = "market")).parsed(EntryDto.serializer())

            assertEquals(HttpStatusCode.Forbidden, createEntry(noy, newEntry(branch = "market")).status)
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/entries?branch=market", noy).status)
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/entries/${inMarket.date}/${inMarket.id}", noy).status)
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/entries/${inMarket.date}/${inMarket.id}/history", noy).status)
            assertEquals(HttpStatusCode.Forbidden, deletePath("/api/v1/entries/${inMarket.date}/${inMarket.id}", noy).status)
            assertEquals(emptyList(), entries(noy), "the market entry is not in the clerk's list")
            assertEquals(1, entries(boss).size)
            assertEquals(1, root.entryFolders().size)
        }
    }

    @Test
    fun aClerkSeesOnlyTheirOwnEntriesUnlessTheyMayViewAll() = env().run {
        val noy = login("noy")
        val kham = login("kham")
        val lead = login("lead", grant = listOf(ENTRY_VIEW_ALL_NODE))
        api {
            openDay(noy)
            val mine = createEntry(noy, newEntry(item = "noy's")).parsed(EntryDto.serializer())
            val theirs = createEntry(kham, newEntry(item = "kham's")).parsed(EntryDto.serializer())

            assertEquals(listOf("noy's"), entries(noy).map { it.item })
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/entries/${theirs.date}/${theirs.id}", noy).status)
            assertEquals(listOf("noy's", "kham's").sorted(), entries(lead).map { it.item }.sorted())
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/entries/${mine.date}/${mine.id}", lead).status)
        }
    }

    @Test
    fun aClerkWhoWasNotGivenTheNodeCannotCreateAtAll() = env().run {
        users.addUser("nobody", "none", listOf("main"))
        users.setUserPermission("nobody", ENTRY_CREATE_NODE, false)
        val noy = login("noy")
        val nobody = login("nobody")
        api {
            openDay(noy)
            assertEquals(HttpStatusCode.Forbidden, createEntry(nobody, newEntry()).status)
        }
    }

    // --- editing ---

    @Test
    fun aClerkChangesTheirOwnEntryTodayAndEveryChangeIsInTheHistory() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry(item = "coffee", category = "sales")).parsed(EntryDto.serializer())
            clock.advance(Duration.ofMinutes(1))

            val edited = updateEntry(
                noy, e,
                UpdateEntryRequest(EntryType.EXPENSE, "supplies", "tea", "with milk", listOf(cash("LAK", "99000"), cash("THB", "4.50")), keepSlips = null),
            )

            assertEquals(HttpStatusCode.OK, edited.status)
            val now = edited.parsed(EntryDto.serializer())
            assertEquals(listOf("tea", "with milk", "supplies"), listOf(now.item, now.note, now.category))
            assertEquals(listOf("99000", "4.50"), now.tenders.map { it.amount })
            assertEquals(e.createdAt, now.createdAt)
            assertTrue(now.updatedAt > e.updatedAt)
            val history = getPath("/api/v1/entries/${e.date}/${e.id}/history", noy).parsed(ListSerializer(HistoryItem.serializer()))
            assertEquals(listOf("create", "edit"), history.map { it.action })
            assertEquals(setOf("type", "category", "item", "note", "tenders"), history[1].changes.map { it.field }.toSet())
            assertEquals("noy", history[1].by.name)
            assertEquals("coffee" to "tea", history[1].changes.first { it.field == "item" }.let { it.from to it.to })
        }
    }

    @Test
    fun anEditThatChangesNothingWritesNothing() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry()).parsed(EntryDto.serializer())
            val before = Files.readAllBytes(root.entryDir(e).resolve("history.yml"))

            val same = updateEntry(noy, e, update(e))

            assertEquals(HttpStatusCode.OK, same.status)
            assertEquals(e, same.parsed(EntryDto.serializer()))
            assertContentEquals(before, Files.readAllBytes(root.entryDir(e).resolve("history.yml")))
        }
    }

    @Test
    fun slipsCanBeAddedAndTakenOffAndATakenOffSlipStaysOnDisk() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val pay = listOf(online("THB", "10"))
            val e = createEntry(noy, newEntry(tenders = pay), listOf(jpeg(1), jpeg(2))).parsed(EntryDto.serializer())
            val request = update(e).copy(keepSlips = listOf("slip-2.jpg"))

            val one = updateEntry(noy, e, request).parsed(EntryDto.serializer())
            assertEquals(listOf("slip-2.jpg"), one.slips.map { it.file })
            assertTrue(Files.exists(root.entryDir(e).resolve("slip-1.jpg")), "nothing is deleted")

            val added = updateEntry(noy, one, request, listOf(png(3))).parsed(EntryDto.serializer())
            assertEquals(listOf("slip-2.jpg", "slip-3.png"), added.slips.map { it.file })

            val none = updateEntry(noy, added, update(added).copy(keepSlips = emptyList()))
            assertEquals(HttpStatusCode.BadRequest, none.status, "an online payment cannot be left without a slip")
            val unknown = updateEntry(noy, added, update(added).copy(keepSlips = listOf("slip-9.jpg")))
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertEquals(listOf("slip-2.jpg", "slip-3.png"), entries(noy).single().slips.map { it.file })
        }
    }

    @Test
    fun anUnchangedCategoryStaysAcceptableAfterItIsArchived() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry(category = "sales")).parsed(EntryDto.serializer())
            records!!.categories.update("sales", null, null, archived = true)

            val fixed = updateEntry(noy, e, update(e, item = "fixed typo"))

            assertEquals(HttpStatusCode.OK, fixed.status)
            val moved = updateEntry(noy, e, update(e).copy(category = "misc"))
            assertEquals(HttpStatusCode.OK, moved.status)
        }
    }

    @Test
    fun aClerkCannotChangeSomeoneElsesEntryButOnesWithEditAllCan() = env().run {
        val noy = login("noy")
        val kham = login("kham")
        val lead = login("lead", grant = listOf(ENTRY_VIEW_ALL_NODE, ENTRY_EDIT_ALL_NODE))
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry()).parsed(EntryDto.serializer())

            assertEquals(HttpStatusCode.Forbidden, updateEntry(kham, e, update(e, "x")).status)
            assertEquals(HttpStatusCode.OK, updateEntry(lead, e, update(e, "by the lead")).status)
            assertEquals(listOf("noy", "lead"), getPath("/api/v1/entries/${e.date}/${e.id}/history", lead).parsed(ListSerializer(HistoryItem.serializer())).map { it.by.name })
        }
    }

    @Test
    fun aClerkCannotChangeYesterdaysEntryButAnAdminCanAndTheHistoryShowsWho() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry(item = "yesterday")).parsed(EntryDto.serializer())
            clock.advance(Duration.ofDays(1))
            val noyLater = login("noy")
            val bossLater = login("boss", op = true)

            val refused = updateEntry(noyLater, e, update(e, "too late"))
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("yesterday", entries(noyLater, "?from=2026-10-03&to=2026-10-03").single().item)

            assertEquals(HttpStatusCode.OK, updateEntry(bossLater, e, update(e, "fixed by admin")).status)
            val history = getPath("/api/v1/entries/${e.date}/${e.id}/history", bossLater).parsed(ListSerializer(HistoryItem.serializer()))
            assertEquals(listOf("create" to "noy", "edit" to "boss"), history.map { it.action to it.by.name })
        }
    }

    @Test
    fun theEditWindowCanBeWidenedInConfig() = env(NO_BACKOFF + "records:\n  edit-window-days: 1\n").run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry()).parsed(EntryDto.serializer())
            clock.advance(Duration.ofDays(1))
            assertEquals(HttpStatusCode.OK, updateEntry(login("noy"), e, update(e, "next day")).status)
            clock.advance(Duration.ofDays(1))
            assertEquals(HttpStatusCode.Forbidden, updateEntry(login("noy"), e.copy(item = "next day"), update(e, "two days")).status)
        }
    }

    // --- delete and move ---

    @Test
    fun deletingIsSoftAsksForThePinAgainAndKeepsEverythingOnDisk() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val keep = createEntry(noy, newEntry(item = "keep")).parsed(EntryDto.serializer())
            val e = createEntry(noy, newEntry(item = "wrong")).parsed(EntryDto.serializer())
            clock.advance(Duration.ofMinutes(6)) // past the 5 minute window: the token still lives, the PIN is asked again

            val asked = deletePath("/api/v1/entries/${e.date}/${e.id}", noy)
            assertEquals(HttpStatusCode.Unauthorized, asked.status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, asked.errorCode())
            assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(pin = TEST_PIN), noy).status)

            val done = deletePath("/api/v1/entries/${e.date}/${e.id}", noy)
            assertEquals(HttpStatusCode.OK, done.status)
            assertTrue(done.parsed(EntryDto.serializer()).deleted)
            assertEquals(listOf("keep"), entries(noy).map { it.item })
            assertEquals(listOf("keep", "wrong"), entries(noy, "?includeDeleted=true").map { it.item })
            assertTrue("deleted: true" in Files.readString(root.entryDir(e).resolve("entry.yml")))
            assertEquals(listOf("create", "delete"), getPath("/api/v1/entries/${e.date}/${e.id}/history", noy).parsed(ListSerializer(HistoryItem.serializer())).map { it.action })
            assertEquals(HttpStatusCode.OK, deletePath("/api/v1/entries/${e.date}/${e.id}", noy).status, "deleting twice is the same as once")
            assertEquals(HttpStatusCode.Conflict, updateEntry(noy, e, update(e, "revive")).status)
            assertEquals(keep.id, entries(noy).single().id)
        }
    }

    @Test
    fun aClerkCannotDeleteYesterdaysEntry() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry()).parsed(EntryDto.serializer())
            clock.advance(Duration.ofDays(1))

            assertEquals(HttpStatusCode.Forbidden, deletePath("/api/v1/entries/${e.date}/${e.id}", login("noy")).status)
        }
    }

    @Test
    fun anEditOnlyGoesThroughWhenTheEntryIsStillTheOneItStartedFromAndAbsentMeansNoCheck() = env().run {
        val noy = login("noy")
        val lead = login("lead", grant = listOf(xyz.felismp.shoparchive.server.records.ENTRY_EDIT_ALL_NODE, xyz.felismp.shoparchive.server.records.ENTRY_VIEW_ALL_NODE))
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry(item = "coffee")).parsed(EntryDto.serializer())
            clock.advance(Duration.ofMinutes(1))
            val ok = updateEntry(noy, e, update(e, "tea").copy(expectedUpdatedAt = e.updatedAt))
            assertEquals(HttpStatusCode.OK, ok.status)
            val tea = ok.parsed(EntryDto.serializer())
            clock.advance(Duration.ofMinutes(1))
            // Someone else changes it meanwhile; the edit that began from `tea` is refused and nothing is written.
            assertEquals(HttpStatusCode.OK, updateEntry(lead, tea, update(tea, "milk")).status)
            val before = getPath("/api/v1/entries/${e.date}/${e.id}/history", noy).parsed(ListSerializer(HistoryItem.serializer())).size
            val stale = updateEntry(noy, tea, update(tea, "mine").copy(expectedUpdatedAt = tea.updatedAt))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals(ErrorCode.CONFLICT, stale.errorCode())
            assertEquals("milk", entries(noy).single().item)
            assertEquals(before, getPath("/api/v1/entries/${e.date}/${e.id}/history", noy).parsed(ListSerializer(HistoryItem.serializer())).size)
            // No precondition: the old behaviour.
            assertEquals(HttpStatusCode.OK, updateEntry(noy, tea, update(tea, "unchecked")).status)
        }
    }

    @Test
    fun twoChangesInTheSameSecondStillGiveTheEntryTwoVersions() = env().run {
        val noy = login("noy")
        val lead = login("lead", grant = listOf(xyz.felismp.shoparchive.server.records.ENTRY_EDIT_ALL_NODE, xyz.felismp.shoparchive.server.records.ENTRY_VIEW_ALL_NODE))
        api {
            openDay(noy)
            // The clock does not move: create, the other device's edit and the stale edit all happen in one second.
            val e = createEntry(noy, newEntry(item = "coffee")).parsed(EntryDto.serializer())
            val milk = updateEntry(lead, e, update(e, "milk")).parsed(EntryDto.serializer())
            assertNotEquals(e.updatedAt, milk.updatedAt)
            val stale = updateEntry(noy, e, update(e, "mine").copy(expectedUpdatedAt = e.updatedAt))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("milk", entries(noy).single().item)
        }
    }

    @Test
    fun movingAnEntryRenamesItsFolderAsksForThePinAndIsInTheHistory() = env().run {
        val boss = login("boss", op = true)
        api {
            openDay(boss)
            val e = createEntry(boss, newEntry(item = "misfiled")).parsed(EntryDto.serializer())
            clock.advance(Duration.ofMinutes(6))

            val asked = postJson("/api/v1/entries/${e.date}/${e.id}/move", MoveEntryRequest.serializer(), MoveEntryRequest("2026-10-01"), boss)
            assertEquals(ErrorCode.REAUTH_REQUIRED, asked.errorCode())
            postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(password = xyz.felismp.shoparchive.server.auth.TEST_PASSWORD), boss)

            val moved = postJson("/api/v1/entries/${e.date}/${e.id}/move", MoveEntryRequest.serializer(), MoveEntryRequest("2026-10-01"), boss)
            assertEquals(HttpStatusCode.OK, moved.status)
            assertEquals("2026-10-01", moved.parsed(EntryDto.serializer()).date)
            assertEquals(listOf("2026/10/01/${e.id}"), root.entryFolders())
            assertEquals(HttpStatusCode.NotFound, getPath("/api/v1/entries/2026-10-03/${e.id}", boss).status)
            assertEquals("misfiled", entries(boss, "?from=2026-10-01&to=2026-10-01").single().item)
            assertEquals(emptyList(), entries(boss, "?from=2026-10-03&to=2026-10-03"))
            val history = getPath("/api/v1/entries/2026-10-01/${e.id}/history", boss).parsed(ListSerializer(HistoryItem.serializer()))
            assertEquals(listOf("create", "move"), history.map { it.action })
            assertEquals("2026-10-03" to "2026-10-01", history[1].changes.single().let { it.from to it.to })
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/entries/2026-10-01/${e.id}/move", MoveEntryRequest.serializer(), MoveEntryRequest("2099-01-01"), boss).status)
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/entries/2026-10-01/${e.id}/move", MoveEntryRequest.serializer(), MoveEntryRequest("2026-02-30"), boss).status)
        }
    }

    @Test
    fun aClerkCannotMoveAnEntryOutOfTheEditWindow() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry()).parsed(EntryDto.serializer())

            val response = postJson("/api/v1/entries/${e.date}/${e.id}/move", MoveEntryRequest.serializer(), MoveEntryRequest("2026-09-01"), noy)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(listOf("2026/10/03/${e.id}"), root.entryFolders())
        }
    }

    // --- reading ---

    @Test
    fun theListFiltersByBranchTypeCurrencyCategoryAndDay() = env().run {
        val boss = login("boss", op = true)
        api {
            openDay(boss)
            openDay(boss, branch = "market")
            createEntry(boss, newEntry(item = "a", category = "sales"))
            createEntry(boss, newEntry(item = "b", type = EntryType.EXPENSE, category = "supplies", tenders = listOf(cash("THB", "5"))))
            createEntry(boss, newEntry(item = "c", branch = "market"))
            val e = createEntry(boss, newEntry(item = "d")).parsed(EntryDto.serializer())
            deletePath("/api/v1/entries/${e.date}/${e.id}", boss)

            assertEquals(listOf("a", "b", "c"), entries(boss).map { it.item })
            assertEquals(listOf("c"), entries(boss, "?branch=market").map { it.item })
            assertEquals(listOf("b"), entries(boss, "?type=expense").map { it.item })
            assertEquals(listOf("b"), entries(boss, "?currency=THB").map { it.item })
            assertEquals(listOf("a"), entries(boss, "?category=sales").map { it.item })
            assertEquals(listOf("a", "b", "c", "d"), entries(boss, "?includeDeleted=true").map { it.item })
            assertEquals(emptyList(), entries(boss, "?from=2026-10-04&to=2026-10-31"))
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/entries?type=transfer", boss).status)
        }
    }

    @Test
    fun theListDefaultsToThisMonthAndRefusesMoreThanAYear() = env().run {
        val boss = login("boss", op = true)
        api {
            openDay(boss)
            createEntry(boss, newEntry(item = "this month"))
            val september = createEntry(boss, newEntry(item = "last month")).parsed(EntryDto.serializer())
            postJson("/api/v1/entries/${september.date}/${september.id}/move", MoveEntryRequest.serializer(), MoveEntryRequest("2026-09-30"), boss)

            assertEquals(listOf("this month"), entries(boss).map { it.item })
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/entries?from=2025-10-03&to=2026-10-03", boss).status, "366 days")
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/entries?from=2025-10-02&to=2026-10-03", boss).status, "367 days")
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/entries?from=2026-10-05&to=2026-10-03", boss).status)
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/entries?from=yesterday", boss).status)
            assertEquals(2, entries(boss, "?from=2026-09-01&to=2026-10-31").size)
        }
    }

    @Test
    fun anEntryWithDamagedFilesIsReportedBrokenAndTheOthersWorkOn() = env().run {
        val boss = login("boss", op = true)
        api {
            openDay(boss)
            val good = createEntry(boss, newEntry(item = "good")).parsed(EntryDto.serializer())
            val bad = createEntry(boss, newEntry(item = "bad")).parsed(EntryDto.serializer())
            Files.writeString(root.entryDir(bad).resolve("entry.yml"), "this is { not an entry")
            records!!.store.load()

            val response = getPath("/api/v1/entries/${bad.date}/${bad.id}", boss)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(ErrorCode.ENTRY_BROKEN, response.errorCode())
            assertEquals(listOf("good"), entries(boss).map { it.item })
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/entries/${good.date}/${good.id}", boss).status)
            assertEquals(ErrorCode.ENTRY_BROKEN, createEntry(boss, newEntry(id = bad.id)).errorCode())
            assertTrue(records.statusLines().single().contains("1 broken"))
        }
    }

    @Test
    fun unknownEntriesSlipsAndDatesAreNotFoundOrRefused() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry()).parsed(EntryDto.serializer())

            assertEquals(HttpStatusCode.NotFound, getPath("/api/v1/entries/2026-10-03/${newUuid7()}", noy).status)
            assertEquals(HttpStatusCode.NotFound, getPath("/api/v1/entries/2026-10-02/${e.id}", noy).status)
            assertEquals(HttpStatusCode.NotFound, getPath("/api/v1/entries/2026-10-03/not-an-id", noy).status)
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/entries/10-03-2026/${e.id}", noy).status)
            assertEquals(HttpStatusCode.NotFound, getPath("/api/v1/entries/${e.date}/${e.id}/slips/1", noy).status)
            assertEquals(HttpStatusCode.NotFound, getPath("/api/v1/entries/${e.date}/${e.id}/slips/x", noy).status)
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/entries").status)
        }
    }

    // --- pushes ---

    @Test
    fun eventsGoOnlyToTheUsersWhoMayViewTheEntry() = env().run {
        val noy = login("noy")
        val kham = login("kham")
        val lead = login("lead", grant = listOf(ENTRY_VIEW_ALL_NODE))
        val market = login("mai", branches = listOf("market"), grant = listOf(ENTRY_VIEW_ALL_NODE))
        val events = services.get(EventService::class.java)!!
        val heard = mutableMapOf<String, MutableList<String>>()
        for ((name, token) in mapOf("noy" to noy, "kham" to kham, "lead" to lead, "mai" to market)) {
            events.register(authService().authenticate(token)!!) { heard.getOrPut(name) { mutableListOf() } += it; true }
        }
        api {
            openDay(noy)
            val e = createEntry(noy, newEntry()).parsed(EntryDto.serializer())
            updateEntry(noy, e, update(e, "changed"))
            deletePath("/api/v1/entries/${e.date}/${e.id}", noy)
            val types = { name: String -> heard[name].orEmpty().map { Json.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content } }

            assertEquals(listOf("session.opened", "entry.created", "entry.updated", "entry.deleted"), types("noy"))
            assertEquals(listOf("session.opened"), types("kham"), "kham hears of the day, but not of noy's entries")
            assertEquals(listOf("session.opened", "entry.created", "entry.updated", "entry.deleted"), types("lead"))
            assertEquals(emptyList(), types("mai"), "another branch hears nothing")
            val created = Json.parseToJsonElement(heard.getValue("noy")[1]).jsonObject
            assertEquals(e.id, created.getValue("id").jsonPrimitive.content)
            assertEquals("main", created.getValue("branch").jsonPrimitive.content)
            assertEquals("2026-10-03", created.getValue("date").jsonPrimitive.content)
        }
    }

    @Test
    fun aNonJsonBodyAndAnUnknownPartAreBadRequestsNotServerErrors() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val broken = client.post("/api/v1/entries") {
                header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
                header(HttpHeaders.Authorization, "Bearer $noy")
                contentType(ContentType.Application.Json)
                setBody("{not json")
            }
            assertEquals(HttpStatusCode.BadRequest, broken.status, broken.bodyAsText())
            val noEntryPart = client.post("/api/v1/entries") {
                header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
                header(HttpHeaders.Authorization, "Bearer $noy")
                setBody(multipartWithoutEntry())
            }
            assertEquals(HttpStatusCode.BadRequest, noEntryPart.status, noEntryPart.bodyAsText())
        }
    }

    private fun multipartWithoutEntry() = io.ktor.client.request.forms.MultiPartFormDataContent(
        io.ktor.client.request.forms.formData { append("something", "else") },
    )
}
