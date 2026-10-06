package xyz.felismp.shoparchive.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OverridesTest {
    private fun parse(vararg args: String) = Overrides.parse(arrayOf(*args))

    private fun assertRefused(vararg args: String): String =
        assertFailsWith<OverrideException>(args.joinToString(" ")) { parse(*args) }.message!!

    @Test
    fun noArgsIsNoOverrides() {
        assertEquals(emptyList(), parse().flags())
    }

    @Test
    fun allThreeFlagsAreAcceptedInAnyOrder() {
        val overrides = parse("--log-level", "debug", "--port", "26000", "--bind-address", "127.0.0.1")

        assertEquals(listOf("--port", "--bind-address", "--log-level"), overrides.flags())
    }

    @Test
    fun portOutsideRangeIsRefusedNotClamped() {
        assertRefused("--port", "70000")
        assertRefused("--port", "0")
        assertRefused("--port", "-1")
        assertRefused("--port", "http")
    }

    @Test
    fun theMessageNamesTheFlagAndTheBadValue() {
        val message = assertRefused("--port", "70000")

        assertEquals(true, "--port" in message && "70000" in message && "1 to 65535" in message, message)
    }

    @Test
    fun logLevelMustBeOneOfTheKnownLevels() {
        assertRefused("--log-level", "loud")
        assertEquals(listOf("--log-level"), parse("--log-level", "TRACE").flags())
    }

    @Test
    fun blankBindAddressIsRefused() {
        assertRefused("--bind-address", "  ")
    }

    @Test
    fun unknownFlagMissingValueAndRepeatedFlagAreRefused() {
        assertRefused("--root", "x") // the launcher's own flag never reaches the core
        assertRefused("stop")
        assertRefused("--port")
        assertRefused("--port", "1", "--port", "2")
    }

    @Test
    fun noPluginsIsAFlagWithoutAValueAndCanBeGivenOnce() {
        val overrides = parse("--no-plugins", "--port", "26000")
        assertEquals(true, overrides.noPlugins)
        assertEquals(listOf("--port", "--no-plugins"), overrides.flags())
        assertEquals(false, parse("--port", "26000").noPlugins)
        assertRefused("--no-plugins", "--no-plugins")
    }

    @Test
    fun noPatchesAndIgnoreHotfixAreForTheLauncherAndShownByTheCore() {
        val overrides = parse("--no-patches", "--ignore-hotfix", "SA-1", "--ignore-hotfix", "SA-2", "--port", "26000")
        assertEquals(true, overrides.noPatches)
        assertEquals(listOf("SA-1", "SA-2"), overrides.ignoreHotfix)
        assertEquals(listOf("--port", "--no-patches", "--ignore-hotfix SA-1", "--ignore-hotfix SA-2"), overrides.flags())
        assertEquals(false, parse().noPatches)
        assertRefused("--no-patches", "--no-patches")
        assertRefused("--ignore-hotfix")
        assertRefused("--ignore-hotfix", "a b")
    }
}
