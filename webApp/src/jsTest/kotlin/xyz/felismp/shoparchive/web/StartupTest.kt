package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupTest {
    private fun servers(busy: Boolean = false, problem: Problem? = null) =
        AppState.Servers(saved = emptyList(), found = emptyList(), searching = false, problem = problem, busy = busy)

    @Test
    fun theChosenLanguageWinsThenTheBrowsersThenEnglish() {
        assertEquals("lo", chooseLanguage("lo", "th-TH"))
        assertEquals("th", chooseLanguage(null, "th-TH"))
        assertEquals("lo", chooseLanguage(null, "LO-la"))
        assertEquals("en", chooseLanguage(null, "en-US"))
        assertEquals("en", chooseLanguage(null, "fr-FR"))
        assertEquals("en", chooseLanguage(null, null))
        assertEquals("th", chooseLanguage("xx", "th")) // a language the app does not have is not a choice
    }

    @Test
    fun theAddressAlwaysHasAPort() {
        assertEquals("192.168.1.20:25655", pageAddress("https:", "192.168.1.20", "25655"))
        assertEquals("shop.example.com:443", pageAddress("https:", "shop.example.com", ""))
        assertEquals("shop.example.com:80", pageAddress("http:", "shop.example.com", ""))
    }

    @Test
    fun theServerIsAddedOnceWhenTheListFirstShows() {
        val added = mutableListOf<String>()
        val auto = AutoAdd("shop:25655") { added += it }
        auto.onState(AppState.Starting)
        assertTrue(added.isEmpty())
        assertFalse(auto.fired)
        auto.onState(servers())
        assertEquals(listOf("shop:25655"), added)
        assertTrue(auto.fired)
    }

    @Test
    fun aFailureDoesNotAddAgainByItself() {
        val added = mutableListOf<String>()
        val auto = AutoAdd("shop:25655") { added += it }
        auto.onState(servers())
        auto.onState(servers(busy = true))
        auto.onState(servers(problem = Problem.Unreachable))
        auto.onState(servers(problem = Problem.Unreachable)) // shown again (e.g. after the search for servers ended)
        assertEquals(1, added.size)
    }

    @Test
    fun otherScreensAddNothing() {
        val added = mutableListOf<String>()
        val auto = AutoAdd("shop:25655") { added += it }
        auto.onState(AppState.Login("shop:25655", "Shop"))
        auto.onState(AppState.ProtocolMismatch(2))
        assertTrue(added.isEmpty())
        auto.onState(servers(busy = true)) // a check already going on: not started twice
        assertTrue(added.isEmpty())
    }
}
