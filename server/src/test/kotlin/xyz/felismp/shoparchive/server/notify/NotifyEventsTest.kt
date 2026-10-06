package xyz.felismp.shoparchive.server.notify

import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.DayClosedEvent
import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.EntryCreatedEvent
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.ShopEventTypes
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.records.cash
import xyz.felismp.shoparchive.server.records.closeDay
import xyz.felismp.shoparchive.server.records.createEntry
import xyz.felismp.shoparchive.server.records.entries
import xyz.felismp.shoparchive.server.records.jpeg
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.records.newEntry
import xyz.felismp.shoparchive.server.records.online
import xyz.felismp.shoparchive.server.records.openDay
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.NotificationState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What records publish and what the core turns into outbox messages, through the real API on a temporary root. */
class NotifyEventsTest {
    @TempDir
    lateinit var root: Path

    private fun env(notify: String = ""): AuthEnv = AuthEnv(root, NO_BACKOFF + notify, withNotify = true).also { e ->
        e.records!!.branches.add("main", "Main Shop")
        e.records.branches.add("market", "Market")
        e.records.categories.add("drinks", xyz.felismp.shoparchive.shared.LocalizedName("ເຄື່ອງດື່ມ", "เครื่องดื่ม", "Drinks"), xyz.felismp.shoparchive.shared.AppliesTo.BOTH)
    }

    // --- entry.created ---

    @Test
    fun aNewEntryQueuesAMessageWithItsWordsAndAFixedCode() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val created = createEntry(noy, newEntry(category = "drinks", item = "coffee"))
            assertEquals(HttpStatusCode.Created, created.status)

            val item = notify!!.outbox.items().single()
            val n = item.notification
            assertEquals(NotificationState.QUEUED, item.state)
            assertEquals("entry.created", n.event)
            assertEquals("main", n.branch)
            assertEquals("noy", n.user)
            assertEquals("lo", n.locale)
            assertTrue(Regex("20261003-[0-9A-F]{5}").matches(n.code), n.code)
            assertEquals(
                mapOf(
                    "type" to "income", "category" to "ເຄື່ອງດື່ມ", "category.key" to "drinks", "category.lo" to "ເຄື່ອງດື່ມ", "category.th" to "เครื่องดื่ม", "category.en" to "Drinks",
                    "item" to "coffee", "amount" to "150000", "currency" to "LAK", "branch" to "Main Shop", "user" to "noy", "time" to n.createdAt, "date" to "2026-10-03",
                ),
                n.fields,
            )
            assertTrue(n.createdAt.startsWith("2026-10-03T15:00:00"), n.createdAt)
        }
    }

    @Test
    fun anEntryWithoutACategoryHasAnEmptyCategoryAndSeveralCurrenciesAreListedInStep() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry(tenders = listOf(cash("LAK", "1000"), cash("THB", "20.5"))))

            val fields = notify!!.outbox.items().single().notification.fields
            assertEquals("", fields["category"])
            assertEquals("1000, 20.50", fields["amount"])
            assertEquals("LAK, THB", fields["currency"])
        }
    }

    @Test
    fun theMessageIsGivenToTheNotifierInUseWithItsCodeAndSentOnce() = env().run {
        val channel = ScriptedNotifier()
        services.register(Notifier::class.java, channel, 5, "plugin:telegram")
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry())

            notify!!.outbox.processDue()
            notify.outbox.processDue()

            val got = channel.got.single()
            assertEquals("entry.created", got.event)
            assertEquals(NotificationState.SENT, notify.outbox.items().single().state)
        }
    }

    @Test
    fun anEntrySentAgainWithTheSameIdQueuesNothingNew() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val request = newEntry()
            assertEquals(HttpStatusCode.Created, createEntry(noy, request).status)
            assertEquals(HttpStatusCode.OK, createEntry(noy, request).status, "the second is the stored one")

            assertEquals(1, notify!!.outbox.items().size)
        }
    }

    @Test
    fun anEntryThatIsRefusedPublishesNothing() = env().run {
        val seen = mutableListOf<String>()
        events.subscribe("entry.created", ShopEventListener { seen += it.type })
        val noy = login("noy")
        api {
            // no open day: refused with 409
            assertEquals(HttpStatusCode.Conflict, createEntry(noy, newEntry()).status)
            openDay(noy)
            // no amount: refused with 400
            assertEquals(HttpStatusCode.BadRequest, createEntry(noy, newEntry(tenders = emptyList())).status)

            assertEquals(emptyList(), seen)
            assertEquals(emptyList(), notify!!.outbox.items())
        }
    }

    @Test
    fun theListenersRunAfterTheEntryIsSavedSoTheyCanReadIt() = env().run {
        var foundWhenCalled: Boolean? = null
        events.subscribe("entry.created", ShopEventListener { event ->
            foundWhenCalled = records!!.store.find((event as EntryCreatedEvent).entry.id) != null
        })
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry())

            assertEquals(true, foundWhenCalled)
        }
    }

    @Test
    fun aListenerThatThrowsNeitherFailsTheWriteNorStopsTheOtherListeners() = env().run {
        val after = mutableListOf<String>()
        events.subscribe("entry.created", ShopEventListener { error("listener broke") })
        events.subscribe("entry.created", ShopEventListener { after += it.type })
        val noy = login("noy")
        api {
            openDay(noy)
            val created = createEntry(noy, newEntry())

            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(listOf("entry.created"), after)
            assertEquals(1, entries(noy).size)
            assertEquals(1, notify!!.outbox.items().size, "the core's own listener, subscribed first, still ran")
        }
    }

    @Test
    fun aMessageThatCannotBeWrittenDoesNotFailTheEntry() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            // The outbox folder is a file: the message cannot be written. The entry is what matters.
            java.nio.file.Files.list(root.resolve("data/outbox")).use { l -> l.toList() }.forEach(java.nio.file.Files::delete)
            java.nio.file.Files.delete(root.resolve("data/outbox"))
            java.nio.file.Files.writeString(root.resolve("data/outbox"), "in the way")

            assertEquals(HttpStatusCode.Created, createEntry(noy, newEntry()).status)
            assertEquals(1, entries(noy).size)
        }
    }

    @Test
    fun anEventTypeNotListedInNotifyEventsQueuesNothingButIsStillPublished() = env("notify:\n  events: [day.closed]\n").run {
        val seen = mutableListOf<String>()
        events.subscribe("entry.created", ShopEventListener { seen += it.type })
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry())

            assertEquals(listOf("entry.created"), seen)
            assertEquals(emptyList(), notify!!.outbox.items())
        }
    }

    // --- day.closed ---

    @Test
    fun closingTheDayPublishesTheSummaryAndQueuesIt() = env().run {
        val closed = mutableListOf<DayClosedEvent>()
        events.subscribe(ShopEventTypes.DAY_CLOSED, ShopEventListener { closed += it as DayClosedEvent })
        val noy = login("noy", grant = listOf("shoparchive.day.close"))
        api {
            val session = openDay(noy, float = mapOf("LAK" to "50000"))
            createEntry(noy, newEntry(tenders = listOf(cash("LAK", "150000"))))
            createEntry(noy, newEntry(tenders = listOf(online("THB", "20.5"))), slips = listOf(jpeg()))
            createEntry(noy, newEntry(type = EntryType.EXPENSE, tenders = listOf(cash("LAK", "20000"))))
            // expected in the drawer 50000 + 150000 - 20000 = 180000; counted 179000: 1000 short
            val response = closeDay(noy, session, mapOf("LAK" to "179000"), note = "gave change twice")
            assertEquals(HttpStatusCode.OK, response.status)

            val event = closed.single()
            assertEquals("Main Shop", event.branchName)
            assertEquals(3, event.entryCount)
            assertEquals(1, event.slipCount)
            val lak = event.currencies.first { it.currency == "LAK" }
            assertEquals(
                listOf("150000", "20000", "130000", "150000", "20000", "0", "0", "50000", "179000", "180000", "-1000", "50000", "129000"),
                listOf(lak.income, lak.expense, lak.net, lak.cashIn, lak.cashOut, lak.onlineIn, lak.onlineOut, lak.float, lak.counted, lak.expected, lak.variance, lak.kept, lak.handover),
            )
            val thb = event.currencies.first { it.currency == "THB" }
            assertEquals(listOf("20.50", "0.00", "20.50", "0.00", "20.50", "0.00"), listOf(thb.income, thb.expense, thb.net, thb.cashIn, thb.onlineIn, thb.counted))

            // the first message of the day's entries, then the day
            val message = notify!!.outbox.items().last { it.notification.event == "day.closed" }.notification
            assertEquals("noy", message.user)
            assertEquals("main", message.branch)
            assertEquals("LAK 150000; THB 20.50", message.fields["income"])
            assertEquals("LAK -1000; THB 0.00", message.fields["variance"])
            assertEquals("LAK 50000; THB 0.00", message.fields["kept"])
            assertEquals("LAK 129000; THB 0.00", message.fields["handover"])
            assertEquals("3", message.fields["entries"])
            assertEquals("1", message.fields["slips"])
            assertEquals("gave change twice", message.fields["note"])
            assertTrue(Regex("20261003-[0-9A-F]{5}").matches(message.code))
        }
    }

    @Test
    fun aDayThatIsRefusedBecauseItIsClosedAlreadyPublishesNothingTheSecondTime() = env().run {
        val closed = mutableListOf<DayClosedEvent>()
        events.subscribe(ShopEventTypes.DAY_CLOSED, ShopEventListener { closed += it as DayClosedEvent })
        val noy = login("noy", grant = listOf("shoparchive.day.close"))
        api {
            val session = openDay(noy)
            assertEquals(HttpStatusCode.OK, closeDay(noy, session, mapOf("LAK" to "50000")).status)
            assertEquals(HttpStatusCode.Conflict, closeDay(noy, session, mapOf("LAK" to "50000")).status)
            // a close that needs a note is refused too
            val other = openDay(noy)
            assertEquals(HttpStatusCode.BadRequest, closeDay(noy, other, mapOf("LAK" to "1")).status)

            assertEquals(1, closed.size)
        }
    }

    // --- the notifier in use ---

    @Test
    fun theHighestPriorityNotifierIsUsedAndRemovingItsPluginFallsBackToTheLog() = env().run {
        val low = ScriptedNotifier()
        val high = ScriptedNotifier()
        services.register(Notifier::class.java, low, 3, "plugin:discord")
        services.register(Notifier::class.java, high, 9, "plugin:telegram")
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000001"))
            notify!!.outbox.processDue()
            assertEquals(1, high.got.size)
            assertEquals(0, low.got.size)

            services.unregisterOwner("plugin:telegram")
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000002"))
            notify.outbox.processDue()
            assertEquals(1, low.got.size, "the next one down takes over")

            services.unregisterOwner("plugin:discord")
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000003"))
            notify.outbox.processDue()
            assertEquals(3, notify.outbox.items().count { it.state == NotificationState.SENT }, "the core's log notifier sends")
            assertTrue("via LogNotifier" in notify.statusLines().first(), notify.statusLines().toString())
        }
    }

    @Test
    fun aMessageThatFailsItsLastAllowedAttemptIsShownByStatusWithTheReason() = env("notify:\n  max-attempts: 2\n").run {
        services.register(
            Notifier::class.java,
            ScriptedNotifier(DeliveryResult.Retry("chat is slow"), DeliveryResult.Failed("bot was removed"), DeliveryResult.Sent, DeliveryResult.Failed("bot was removed")),
            5, "plugin:telegram",
        )
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000001"))
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000002"))
            notify!!.outbox.processDue()
            assertTrue(notify.statusLines()[0].startsWith("Notifications: 2 queued"), "both wait for their second try")
            clock.advance(java.time.Duration.ofHours(2))
            notify.outbox.processDue()

            val lines = notify.statusLines()
            assertTrue(lines[0].startsWith("Notifications: 0 queued, 0 unknown"), lines.toString())
            assertTrue("1 failed, 1 sent" in lines[0], lines.toString())
            val failed = notify.outbox.items().single { it.state == NotificationState.FAILED }
            assertTrue(lines[1].startsWith("Last notification failure: ${failed.id}") && "bot was removed" in lines[1], lines.toString())
        }
    }

    @Test
    fun theOutboxResumesAfterARestartOnTheSameRoot() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry())
        }
        val restarted = AuthEnv(root, NO_BACKOFF, withNotify = true)
        val channel = ScriptedNotifier()
        restarted.services.register(Notifier::class.java, channel, 5, "plugin:telegram")

        assertEquals(1, restarted.notify!!.outbox.processDue())

        assertEquals("entry.created", channel.got.single().event)
        assertNotNull(restarted.notify.outbox.items().single().sentAt)
    }
}
