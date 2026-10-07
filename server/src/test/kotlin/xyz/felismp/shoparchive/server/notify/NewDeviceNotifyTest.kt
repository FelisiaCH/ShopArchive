package xyz.felismp.shoparchive.server.notify

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.NewDeviceEvent
import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.ShopEventTypes
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.TEST_PIN
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.server.auth.unlock
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OTHER_PIN = "905173"

/** `device.new`: a login that puts a user on a device they were not on is told to the owner through the outbox. */
class NewDeviceNotifyTest {
    @TempDir
    lateinit var root: Path

    private fun env(notify: String = ""): AuthEnv = AuthEnv(root, NO_BACKOFF + notify, withNotify = true).also { e ->
        e.records!!.branches.add("main", "Main Shop")
        e.users.addUser("mali", "none", listOf("main"))
        e.users.addUser("dana", "none", emptyList())
    }

    private fun loginRequest(name: String, pin: String? = null, newPin: String? = null, mode: DeviceMode = DeviceMode.PERSONAL, onto: LoginResponse? = null) =
        LoginRequest(name, "Phone of $name", "android", mode, pin = pin, newPin = newPin, deviceId = onto?.deviceId, deviceCredential = onto?.credential)

    private suspend fun ApplicationTestBuilder.loggedIn(request: LoginRequest): LoginResponse {
        val response = postJson("/api/v1/login", LoginRequest.serializer(), request)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.parsed(LoginResponse.serializer())
    }

    private fun AuthEnv.newDevices() = notify!!.outbox.items().filter { it.notification.event == ShopEventTypes.DEVICE_NEW }

    @Test
    fun aLoginOnANewDeviceQueuesOneMessageWithTheUserAndTheDevice() = env().run {
        val seen = mutableListOf<ShopEvent>()
        events.subscribe(ShopEventTypes.DEVICE_NEW, ShopEventListener { seen += it })
        api {
            val device = loggedIn(loginRequest("mali", newPin = TEST_PIN))

            val event = seen.single() as NewDeviceEvent
            assertEquals("mali", event.username)
            assertEquals(device.deviceId, event.deviceId)
            assertEquals("main", event.branch)
            assertEquals("2026-10-03T15:00:00+07:00", event.at)

            val n = newDevices().single().notification
            assertEquals("mali", n.user)
            assertEquals("main", n.branch)
            assertEquals("20261003-" + device.deviceId.takeLast(5).uppercase(), n.code)
            assertEquals(mapOf("user" to "mali", "device" to "Phone of mali", "platform" to "android", "deviceId" to device.deviceId), n.fields)
            assertEquals("[${n.code}] New device: mali on Phone of mali (android)", describe(n))
            assertEquals(1, notify!!.outbox.items().size)
        }
    }

    @Test
    fun aUserWithoutABranchHasAnEmptyBranch() = env().run {
        api {
            loggedIn(loginRequest("dana", newPin = TEST_PIN))

            assertEquals("", newDevices().single().notification.branch)
        }
    }

    @Test
    fun aSecondUserAddedToASharedDeviceQueuesOneMessage() = env().run {
        api {
            val shared = loggedIn(loginRequest("mali", newPin = TEST_PIN, mode = DeviceMode.SHARED))
            loggedIn(loginRequest("dana", newPin = OTHER_PIN, onto = shared))

            val dana = newDevices().single { it.notification.user == "dana" }.notification
            assertEquals("Phone of mali", dana.fields["device"])
            assertEquals(shared.deviceId, dana.fields["deviceId"])
            assertEquals(2, newDevices().size)
        }
    }

    @Test
    fun theSameUserLoggingInAgainOnTheSameDeviceQueuesNothingNew() = env().run {
        api {
            val device = loggedIn(loginRequest("mali", newPin = TEST_PIN))
            val again = loggedIn(loginRequest("mali", pin = TEST_PIN, onto = device))

            assertEquals(device.deviceId, again.deviceId)
            assertEquals(1, newDevices().size)
        }
    }

    @Test
    fun anUnlockQueuesNothing() = env().run {
        api {
            val device = loggedIn(loginRequest("mali", newPin = TEST_PIN))
            unlock(device, "mali", TEST_PIN)

            assertEquals(1, newDevices().size)
        }
    }

    @Test
    fun notListedInNotifyEventsQueuesNothingButIsStillPublished() = env("notify:\n  events: [day.closed, entry.created]\n").run {
        val seen = mutableListOf<String>()
        events.subscribe(ShopEventTypes.DEVICE_NEW, ShopEventListener { seen += it.type })
        api {
            loggedIn(loginRequest("mali", newPin = TEST_PIN))

            assertEquals(listOf("device.new"), seen)
            assertEquals(emptyList(), notify!!.outbox.items())
        }
    }

    @Test
    fun anEventThatCannotBePublishedDoesNotFailTheLogin() = env().run {
        services.register(ShopEvents::class.java, object : ShopEvents {
            override fun subscribe(type: String, listener: ShopEventListener) = AutoCloseable {}
            override fun publish(event: ShopEvent) = error("broken")
        }, 10, "test")
        api {
            val device = loggedIn(loginRequest("mali", newPin = TEST_PIN))

            assertTrue(device.credential.isNotEmpty())
        }
    }
}
