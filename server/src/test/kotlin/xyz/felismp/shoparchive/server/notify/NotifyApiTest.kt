package xyz.felismp.shoparchive.server.notify

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.deletePath
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.errorReason
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postRaw
import xyz.felismp.shoparchive.server.records.closeDay
import xyz.felismp.shoparchive.server.records.createEntry
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.records.newEntry
import xyz.felismp.shoparchive.server.records.openDay
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.NotificationState
import xyz.felismp.shoparchive.shared.PermissionNodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The outbox through HTTP: who may see and send, what the lists show, and that sending again never edits what was sent. */
class NotifyApiTest {
    @TempDir
    lateinit var root: Path

    private val view = PermissionNodes.NOTIFICATIONS_VIEW
    private val send = PermissionNodes.NOTIFICATIONS_SEND

    private fun env(notify: String = ONE_ATTEMPT): AuthEnv = AuthEnv(root, NO_BACKOFF + notify, withNotify = true).also { e ->
        e.records!!.branches.add("main", "Main Shop")
        e.records.branches.add("market", "Market")
    }

    private suspend fun ApplicationTestBuilder.list(token: String, query: String = "") =
        getPath("/api/v1/notifications$query", token).parsed(ListSerializer(NotificationDto.serializer()))

    /** Two entries on branch main, sent by a channel that accepts the first and refuses the second for good. */
    private suspend fun ApplicationTestBuilder.twoMessages(env: AuthEnv, clerk: String) {
        env.services.register(Notifier::class.java, ScriptedNotifier(DeliveryResult.Sent, DeliveryResult.Failed("bot was removed")), 5, "plugin:telegram")
        createEntry(clerk, newEntry(id = "01990000-0000-7000-8000-000000000001"))
        createEntry(clerk, newEntry(id = "01990000-0000-7000-8000-000000000002"))
        env.notify!!.outbox.processDue()
    }

    // --- who may ---

    @Test
    fun withoutASignedInUserEveryNotificationRouteIsRefusedWith401() = env().run {
        api {
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/notifications").status)
            assertEquals(HttpStatusCode.Unauthorized, postRaw("/api/v1/notifications/x/resend", "").status)
            assertEquals(HttpStatusCode.Unauthorized, postRaw("/api/v1/sessions/main/x/notify", "").status)
        }
    }

    @Test
    fun listingNeedsTheViewNodeAndSendingNeedsTheSendNode() = env().run {
        val clerk = login("noy")
        val viewer = login("kham", grant = listOf(view))
        api {
            openDay(clerk)
            twoMessages(this@run, clerk)
            val id = notify!!.outbox.items().first().id

            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/notifications", clerk).status)
            assertEquals(ErrorReasons.PERMISSION_MISSING, getPath("/api/v1/notifications", clerk).errorReason())
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/notifications", viewer).status)
            assertEquals(HttpStatusCode.Forbidden, postRaw("/api/v1/notifications/$id/resend", "", viewer).status, "viewing is not sending")
            assertEquals(HttpStatusCode.Forbidden, postRaw("/api/v1/sessions/main/x/notify", "", viewer).status)
            assertEquals(2, notify.outbox.items().size, "nothing was queued by a refused request")
        }
    }

    @Test
    fun theNodesAreNotGivenByDefault() = env().run {
        val nodes = this.nodes.all().filter { it.node in setOf(view, send) }

        assertEquals(setOf(view, send), nodes.map { it.node }.toSet())
        assertTrue(nodes.none { it.default })
        assertTrue(nodes.all { it.th != null && it.lo != null })
    }

    // --- list ---

    @Test
    fun theListIsNewestFirstFiltersByStateAndLimitsAndShowsWhatHappened() = env().run {
        val clerk = login("noy")
        val viewer = login("kham", grant = listOf(view))
        api {
            openDay(clerk)
            twoMessages(this@run, clerk)

            val all = list(viewer)
            assertEquals(listOf(NotificationState.FAILED, NotificationState.SENT), all.map { it.state })
            assertEquals("bot was removed", all[0].lastError)
            assertEquals(1, all[0].attempts)
            assertEquals("entry.created", all[0].event)
            assertEquals("main", all[0].branch)
            assertEquals("noy", all[0].user)
            assertTrue(all[0].text.startsWith("[${all[0].code}] Main Shop"), all[0].text)
            assertEquals(all[0].id, list(viewer, "?limit=1").single().id)
            assertEquals(listOf(NotificationState.SENT), list(viewer, "?state=sent").map { it.state })
            assertEquals(emptyList(), list(viewer, "?state=queued"))
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/notifications?state=lost", viewer).status)
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/notifications?limit=many", viewer).status)
            assertEquals(2, list(viewer, "?limit=100000").size, "a limit too large is clamped, not refused")
        }
    }

    @Test
    fun aUserSeesOnlyTheMessagesOfTheirOwnBranches() = env().run {
        val main = login("noy")
        val market = login("lek", branches = listOf("market"))
        val clerkOfMarket = login("kham", branches = listOf("market"), grant = listOf(view))
        api {
            openDay(main)
            openDay(market, "market")
            createEntry(main, newEntry())
            createEntry(market, newEntry(branch = "market"))

            val seen = list(clerkOfMarket)

            assertEquals(listOf("market"), seen.map { it.branch })
        }
    }

    // --- resend ---

    @Test
    fun sendingAFailedMessageAgainMakesANewOneWithTheSameCodeAndLeavesTheOldOneAlone() = env().run {
        val clerk = login("noy")
        val admin = login("kham", grant = listOf(view, send))
        api {
            openDay(clerk)
            twoMessages(this@run, clerk)
            val failed = list(admin, "?state=failed").single()
            val before = Files.readString(root.resolve("data/outbox/${failed.id}.yml"))

            val response = postRaw("/api/v1/notifications/${failed.id}/resend", "", admin)

            assertEquals(HttpStatusCode.Created, response.status)
            val copy = response.parsed(NotificationDto.serializer())
            assertNotEquals(failed.id, copy.id)
            assertEquals(failed.code, copy.code)
            assertEquals(failed.id, copy.resendOf)
            assertEquals(NotificationState.QUEUED, copy.state)
            assertEquals(before, Files.readString(root.resolve("data/outbox/${failed.id}.yml")))
            assertEquals(3, list(admin).size)
        }
    }

    @Test
    fun aMessageThatIsStillWaitingCannotBeSentAgainAndAnUnknownIdIs404() = env().run {
        val clerk = login("noy")
        val admin = login("kham", grant = listOf(view, send))
        api {
            openDay(clerk)
            createEntry(clerk, newEntry())
            val waiting = list(admin).single()

            val refused = postRaw("/api/v1/notifications/${waiting.id}/resend", "", admin)

            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals(ErrorReasons.NOTIFICATION_PENDING, refused.errorReason())
            assertEquals(HttpStatusCode.NotFound, postRaw("/api/v1/notifications/nope/resend", "", admin).status)
            assertEquals(1, list(admin).size)
        }
    }

    @Test
    fun aSentMessageCannotBeSentAgainWhileACopyOfItIsStillWaiting() = env().run {
        services.register(Notifier::class.java, ScriptedNotifier(), 5, "plugin:telegram")
        val clerk = login("noy")
        val admin = login("kham", grant = listOf(view, send))
        api {
            openDay(clerk)
            createEntry(clerk, newEntry())
            notify!!.outbox.processDue()
            val original = list(admin).single()
            assertEquals(NotificationState.SENT, original.state)

            assertEquals(HttpStatusCode.Created, postRaw("/api/v1/notifications/${original.id}/resend", "", admin).status)
            val second = postRaw("/api/v1/notifications/${original.id}/resend", "", admin)

            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals(ErrorReasons.NOTIFICATION_PENDING, second.errorReason())
            assertEquals(2, list(admin).size)
            notify.outbox.processDue()
            assertEquals(HttpStatusCode.Created, postRaw("/api/v1/notifications/${original.id}/resend", "", admin).status)
        }
    }

    @Test
    fun aMessageOfAnotherBranchCannotBeSentAgainByThisUser() = env().run {
        val clerk = login("noy")
        val market = login("kham", branches = listOf("market"), grant = listOf(view, send))
        api {
            openDay(clerk)
            twoMessages(this@run, clerk)
            val id = notify!!.outbox.items().first().id

            val response = postRaw("/api/v1/notifications/$id/resend", "", market)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(ErrorReasons.BRANCH_NOT_ALLOWED, response.errorReason())
        }
    }

    // --- the summary of a closed day ---

    @Test
    fun theSummaryOfAClosedDayCanBeSentAgainWithTheCodeOfTheFirstTime() = env().run {
        services.register(Notifier::class.java, ScriptedNotifier(), 5, "plugin:telegram")
        val manager = login("noy", grant = listOf("shoparchive.day.close", view, send))
        api {
            val session = openDay(manager)
            createEntry(manager, newEntry())
            closeDay(manager, session, mapOf("LAK" to "200000"))
            notify!!.outbox.processDue()
            val first = list(manager, "?state=sent").first { it.event == "day.closed" }

            val response = postRaw("/api/v1/sessions/main/${session.id}/notify", "", manager)

            assertEquals(HttpStatusCode.Created, response.status)
            val again = response.parsed(NotificationDto.serializer())
            assertEquals("day.closed", again.event)
            assertEquals(first.code, again.code)
            assertEquals(first.id, again.resendOf)
            assertEquals(NotificationState.QUEUED, again.state)
            // pressing the button twice does not queue two
            val twice = postRaw("/api/v1/sessions/main/${session.id}/notify", "", manager)
            assertEquals(HttpStatusCode.Conflict, twice.status)
            assertEquals(ErrorReasons.NOTIFICATION_PENDING, twice.errorReason())
        }
    }

    @Test
    fun aDayThatIsOpenOrUnknownCannotBeSentAndAnotherBranchIsRefused() = env().run {
        val manager = login("noy", grant = listOf("shoparchive.day.close", send))
        val market = login("kham", branches = listOf("market"), grant = listOf(send))
        api {
            val session = openDay(manager)

            val open = postRaw("/api/v1/sessions/main/${session.id}/notify", "", manager)
            assertEquals(HttpStatusCode.NotFound, open.status)
            assertEquals(ErrorCode.NOT_FOUND, open.errorCode())
            assertEquals(HttpStatusCode.NotFound, postRaw("/api/v1/sessions/main/01990000-0000-7000-8000-000000000009/notify", "", manager).status)
            closeDay(manager, session, mapOf("LAK" to "50000"))
            assertEquals(HttpStatusCode.Forbidden, postRaw("/api/v1/sessions/main/${session.id}/notify", "", market).status)
            assertEquals(HttpStatusCode.NotFound, postRaw("/api/v1/sessions/market/${session.id}/notify", "", market).status, "the day is not in that branch")
        }
    }

    @Test
    fun resendingADaySummaryAfterTheFirstOneFailedLinksToTheLatestAttempt() = env().run {
        services.register(Notifier::class.java, ScriptedNotifier(DeliveryResult.Failed("no")), 5, "plugin:telegram")
        val manager = login("noy", grant = listOf("shoparchive.day.close", view, send))
        api {
            val session = openDay(manager)
            closeDay(manager, session, mapOf("LAK" to "50000"))
            notify!!.outbox.processDue()
            val failed = list(manager, "?state=failed").single()

            val again = postRaw("/api/v1/sessions/main/${session.id}/notify", "", manager).parsed(NotificationDto.serializer())

            assertEquals(failed.id, again.resendOf)
            assertEquals(failed.code, again.code)
        }
    }

    /** A closed day on branch main with a 150000 income and a 20000 expense, counted exactly; returns the session id and the id of the expense. */
    private suspend fun ApplicationTestBuilder.closedDay(env: AuthEnv, manager: String): Pair<String, xyz.felismp.shoparchive.shared.EntryDto> {
        val session = openDay(manager, float = mapOf("LAK" to "50000"))
        createEntry(manager, newEntry(tenders = listOf(xyz.felismp.shoparchive.server.records.cash("LAK", "150000"))))
        val expense = createEntry(manager, newEntry(type = xyz.felismp.shoparchive.shared.EntryType.EXPENSE, tenders = listOf(xyz.felismp.shoparchive.server.records.cash("LAK", "20000"))))
            .parsed(xyz.felismp.shoparchive.shared.EntryDto.serializer())
        closeDay(manager, session, mapOf("LAK" to "180000"))
        return session.id to expense
    }

    @Test
    fun aDaySummarySentAgainKeepsTheFiguresOfTheFirstMessageEvenAfterAnEntryWasDeleted() = env().run {
        services.register(Notifier::class.java, ScriptedNotifier(), 5, "plugin:telegram")
        val manager = login("noy", grant = listOf("shoparchive.day.close", "shoparchive.entry.delete.all", view, send))
        api {
            val (sessionId, expense) = closedDay(this@run, manager)
            notify!!.outbox.processDue()
            val first = notify.outbox.items().single { it.notification.event == "day.closed" }.notification.fields
            assertEquals("LAK 20000", first["expense"])
            assertEquals(HttpStatusCode.OK, deletePath("/api/v1/entries/${expense.date}/${expense.id}", manager).status)

            val again = postRaw("/api/v1/sessions/main/$sessionId/notify", "", manager).parsed(NotificationDto.serializer())

            val copy = notify.outbox.items().single { it.id == again.id }.notification.fields
            assertEquals(first, copy, "the same words and figures, not rebuilt from the entries as they are now")
        }
    }

    @Test
    fun theSumsOfTheDayAreKeptInTheSessionFileSoAnEditNeverChangesWhatTheCloseCompared() = env().run {
        val manager = login("noy", grant = listOf("shoparchive.day.close"))
        api {
            val (sessionId, _) = closedDay(this@run, manager)

            val text = Files.readString(root.resolve("record/sessions/main/$sessionId.yml"))

            assertTrue("entries: 2\n" in text && "slips: 0\n" in text && "cash-in: { LAK: \"150000\" }" in text && "cash-out: { LAK: \"20000\" }" in text && "online-in: {}" in text, text)
        }
    }

    @Test
    fun aDayClosedBeforeTheSumsWereKeptAndNeverMessagedIsBuiltFromTheEntriesAndSaysSo() = env(ONE_ATTEMPT + "  events: [entry.created]\n").run {
        services.register(Notifier::class.java, ScriptedNotifier(), 5, "plugin:telegram")
        val manager = login("noy", grant = listOf("shoparchive.day.close", send))
        api {
            val (sessionId, _) = closedDay(this@run, manager)
            // what a file from before looked like: no sums
            val file = root.resolve("record/sessions/main/$sessionId.yml")
            Files.writeString(file, Files.readAllLines(file).filter { l -> listOf("entries:", "slips:", "cash-in:", "cash-out:", "online-in:", "online-out:").none { l.startsWith(it) } }.joinToString("\n", postfix = "\n"))
            records!!.sessions.load()
            // events does not list day.closed, so there is no message about the day either

            val again = postRaw("/api/v1/sessions/main/$sessionId/notify", "", manager)

            assertEquals(HttpStatusCode.Created, again.status)
            val fields = notify!!.outbox.items().last { it.notification.event == "day.closed" }.notification.fields
            assertEquals("true", fields["recomputed"])
            assertEquals("LAK 150000", fields["income"])
            assertTrue("added up from the entries as they are now" in again.parsed(NotificationDto.serializer()).text)
        }
    }
}
