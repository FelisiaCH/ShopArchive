package xyz.felismp.shoparchive.launcher.hotfix

import xyz.felismp.shoparchive.launcher.ShopArchiveClassLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class HotfixPlannerTest {
    @TempDir
    lateinit var root: Path

    /** Every loader a test opened: each holds its jars open (on Windows the temp folder cannot be deleted until they are closed). */
    private val loaders = mutableListOf<ShopArchiveClassLoader>()

    @AfterTest
    fun closeLoaders() = loaders.forEach { it.close() }

    private fun plan(build: Int = 5, fixed: String = "", noPatches: Boolean = false, ignore: Set<String> = emptySet()): HotfixPlan {
        val core = coreJar(root.resolve("core"), build, fixed)
        return planHotfixes(root, listOf(core), readCoreBuild(listOf(core)), HotfixOptions(noPatches, ignore))
    }

    private fun install(name: String, vararg specs: FixSpec, id: String = "SA-1", severity: String = "security", build: Int = 5, approved: Boolean = true) {
        val sha = hotfixJar(root, name, *specs.ifEmpty { arrayOf(FixSpec()) }, yml = pluginYml(name, id, severity, build))
        if (approved) approve(root, name to sha)
    }

    private fun states(plan: HotfixPlan) = plan.report.associate { it.name to it.state }

    private fun load(plan: HotfixPlan): Any {
        val platform = ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader
        // The core's own libraries (here: the Kotlin runtime the fixture class links against) sit next to it in the real loader.
        val stdlib = Unit::class.java.protectionDomain.codeSource.location
        val urls = ((listOf(root.resolve("core/core.jar").toFile()) + plan.extraJars).map { it.toURI().toURL() } + stdlib).toTypedArray()
        val loader = ShopArchiveClassLoader(urls, platform, plan.patched).also { loaders += it }
        return loader.loadClass(TARGET_CLASS).getDeclaredConstructor().newInstance()
    }

    @Test
    fun anApprovedHotfixPatchesTheMarkedMethodWhenTheClassIsLoaded() {
        install("Fix1")
        val plan = plan()
        assertEquals(mapOf("Fix1" to HotfixState.APPLIED), states(plan))
        val target = load(plan)
        assertEquals("fixed", target.javaClass.getMethod("greet", String::class.java).invoke(target, "x"))
        // another method of the same class is untouched
        assertEquals("unmarked", target.javaClass.getMethod("unmarked").invoke(target))
        // the jar the loader reads is the approved bytes, kept under cache/hotfix
        assertEquals(1, plan.extraJars.size)
        assertTrue(Files.isRegularFile(plan.extraJars.single().toPath()))
        assertTrue(plan.extraJars.single().toPath().startsWith(root.resolve("cache/hotfix")))
    }

    @Test
    fun theOriginalBytesStayReadableAsAResource() {
        install("Fix1")
        val plan = plan()
        val loader = ShopArchiveClassLoader(arrayOf(root.resolve("core/core.jar").toUri().toURL()), ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader, plan.patched).also { loaders += it }
        val bytes = loader.getResourceAsStream("$TARGET_INTERNAL.class")!!.use { it.readBytes() }
        assertEquals(sha256(targetBytes()), sha256(bytes))
        assertNotEquals(sha256(targetBytes()), sha256(plan.patched.getValue(TARGET_CLASS).bytes))
    }

    @Test
    fun aStaticReplacementOnAnInterfaceOwnerIsCalledThroughAnInterfaceMethodReference() {
        val sha = hotfixJar(root, "Fix1", FixSpec(), yml = pluginYml("Fix1"), asInterface = true)
        approve(root, "Fix1" to sha)
        val plan = plan()
        assertEquals(mapOf("Fix1" to HotfixState.APPLIED), states(plan))
        val target = load(plan)
        // an IncompatibleClassChangeError here means INVOKESTATIC was emitted for a class method reference
        assertEquals("fixed", target.javaClass.getMethod("greet", String::class.java).invoke(target, "x"))
    }

    @Test
    fun aPatchWithAnInstanceMethodOfSeveralParametersKeepsLongsAndTheReceiverRight() {
        install("Fix1", FixSpec(method = "sum", targetDescriptor = "(IJ)J", replacementName = "sum", replacementDescriptor = "(L$TARGET_INTERNAL;IJ)J"))
        val target = load(plan())
        assertEquals(777L, target.javaClass.getMethod("sum", Int::class.java, Long::class.java).invoke(target, 1, 2L))
    }

    @Test
    fun aChangedTargetClassIsNotApplied_securityDoesNotStart() {
        install("Fix1", FixSpec(sha = "0".repeat(64)))
        val e = assertFailsWith<HotfixFatal> { plan() }
        assertContains(e.message!!, "security hotfix Fix1")
        assertContains(e.message!!, "has changed")
        assertContains(e.message!!, "--ignore-hotfix SA-1")
    }

    @Test
    fun aHotfixMadeForAnotherBuildIsNotApplied_securityDoesNotStart() {
        install("Fix1", build = 4)
        val e = assertFailsWith<HotfixFatal> { plan(build = 5) }
        assertContains(e.message!!, "made for core build 4, this server is build 5")
    }

    @Test
    fun aBugHotfixThatDoesNotMatchIsSkippedAndTheServerStarts() {
        install("Fix1", FixSpec(sha = "0".repeat(64)), severity = "bug")
        val plan = plan()
        assertEquals(mapOf("Fix1" to HotfixState.SKIPPED), states(plan))
        assertTrue(plan.patched.isEmpty())
        assertTrue(plan.extraJars.isEmpty())
    }

    @Test
    fun anIdTheCoreAlreadyFixesSkipsTheHotfixAndSaysSo() {
        install("Fix1", id = "SA-1")
        val plan = plan(fixed = "SA-1,SA-9")
        assertEquals(mapOf("Fix1" to HotfixState.FIXED_IN_CORE), states(plan))
        assertContains(plan.report.single().detail, "can be removed")
        assertTrue(plan.patched.isEmpty())
    }

    @Test
    fun aFixedHotfixIsSkippedEvenWhenItWouldNotMatchAnyMore() {
        install("Fix1", FixSpec(sha = "0".repeat(64)), id = "SA-1")
        assertEquals(mapOf("Fix1" to HotfixState.FIXED_IN_CORE), states(plan(fixed = "SA-1")))
    }

    @Test
    fun ignoreHotfixLetsAMismatchedSecurityHotfixStartAndIsReported() {
        install("Fix1", FixSpec(sha = "0".repeat(64)))
        val plan = plan(ignore = setOf("SA-1"))
        assertEquals(mapOf("Fix1" to HotfixState.IGNORED), states(plan))
        assertTrue(plan.patched.isEmpty())
    }

    @Test
    fun anIgnoreForAnIdNoHotfixHasIsReported() {
        val plan = plan(ignore = setOf("NOPE"))
        assertEquals(HotfixState.UNUSED_IGNORE, plan.report.single().state)
    }

    @Test
    fun twoHotfixesOnTheSameMethodDoNotStartAndBothAreNamed() {
        install("FixA", id = "SA-1")
        install("FixB", id = "SA-2")
        val shaA = sha256(Files.readAllBytes(root.resolve("plugins/FixA.jar")))
        val shaB = sha256(Files.readAllBytes(root.resolve("plugins/FixB.jar")))
        approve(root, "FixA" to shaA, "FixB" to shaB)
        val e = assertFailsWith<HotfixFatal> { plan() }
        assertContains(e.message!!, "FixA")
        assertContains(e.message!!, "FixB")
        assertContains(e.message!!, "greet")
    }

    @Test
    fun oneHotfixPatchingTheSameMethodTwiceDoesNotStart() {
        install("FixA", FixSpec(replacementName = "one"), FixSpec(replacementName = "two"))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "twice")
    }

    @Test
    fun aPatchThatMatchesNoMethodIsFatal() {
        install("Fix1", FixSpec(method = "nothere"))
        val e = assertFailsWith<HotfixFatal> { plan() }
        assertContains(e.message!!, "matched 0 method(s)")
    }

    @Test
    fun aPatchOfAMethodTheCoreDidNotMarkIsFatal() {
        install("Fix1", FixSpec(method = "unmarked", targetDescriptor = "()Ljava/lang/String;", replacementDescriptor = "(L$TARGET_INTERNAL;)Ljava/lang/String;"))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "not marked @HotfixTarget")
    }

    @Test
    fun suspendAndMangledTargetsAreRefused() {
        val suspendDesc = "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        install("Fix1", FixSpec(method = "waits", targetDescriptor = suspendDesc, replacementDescriptor = "(L$TARGET_INTERNAL;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "suspend")

        val mangled = hotfixfixtures.Target::class.java.declaredMethods.single { it.name.startsWith("mangled") }.name
        install("Fix1", FixSpec(method = mangled, targetDescriptor = "(I)I", replacementDescriptor = "(L$TARGET_INTERNAL;I)I"))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "mangled")
    }

    @Test
    fun aReplacementWithTheWrongSignatureIsFatal() {
        install("Fix1", FixSpec(replacementDescriptor = "(Ljava/lang/String;)Ljava/lang/String;"))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "needs (L$TARGET_INTERNAL;Ljava/lang/String;)Ljava/lang/String;")
    }

    @Test
    fun noPatchesAppliesNone() {
        install("Fix1")
        val plan = plan(noPatches = true)
        assertEquals(mapOf("Fix1" to HotfixState.OFF), states(plan))
        assertTrue(plan.patched.isEmpty())
        assertTrue(plan.extraJars.isEmpty())
        assertTrue(!Files.exists(root.resolve("cache/hotfix")))
    }

    @Test
    fun aHotfixJarThatIsNotApprovedIsNotApplied_evenWhenItWouldNotMatch() {
        install("Fix1", approved = false)
        val plan = plan()
        assertEquals(mapOf("Fix1" to HotfixState.NOT_APPROVED), states(plan))
        assertContains(plan.report.single().detail, "is new")
        assertTrue(plan.patched.isEmpty())
        // a jar approved earlier and then changed
        approve(root, "Fix1" to "1".repeat(64))
        assertContains(plan().report.single().detail, "changed since it was approved")
    }

    @Test
    fun anUnapprovedJarWithTheNameOfAnApprovedOneDoesNotHideTheApprovedOnesCheck() {
        // A.jar sorts first and is not approved; Z.jar is approved under the same plugin name but does not match this server.
        hotfixJar(root, "Same", FixSpec(replacementName = "other"), yml = pluginYml("Same"))
        Files.move(root.resolve("plugins/Same.jar"), root.resolve("plugins/A.jar"))
        val sha = hotfixJar(root, "Z", FixSpec(sha = "0".repeat(64)), yml = pluginYml("Same"))
        approve(root, "Same" to sha)
        val e = assertFailsWith<HotfixFatal> { plan() }
        assertContains(e.message!!, "security hotfix Same (Z.jar)")
        assertContains(e.message!!, "has changed")
    }

    @Test
    fun twoApprovedJarsWithOnePluginNameDoNotStartUnlessTheIdIsIgnored() {
        val sha = hotfixJar(root, "Same", yml = pluginYml("Same"))
        Files.copy(root.resolve("plugins/Same.jar"), root.resolve("plugins/Copy.jar"))
        approve(root, "Same" to sha)
        val e = assertFailsWith<HotfixFatal> { plan() }
        assertContains(e.message!!, "same plugin name")
        assertContains(e.message!!, "--ignore-hotfix SA-1")
        // --ignore-hotfix of the ID: the first jar is ignored like the second, the server starts without the fix
        assertEquals(setOf(HotfixState.IGNORED), plan(ignore = setOf("SA-1")).report.map { it.state }.toSet())
    }

    @Test
    fun twoHotfixJarsWithTheSameReplacementClassDoNotStartAndTheClassAndJarsAreNamed() {
        // different targets, same replacement owner "fix/Shared": the loader would run the first jar's code for both
        val one = jar(root.resolve("plugins/One.jar"), mapOf("plugin.yml" to pluginYml("One", "SA-1"), "fix/Shared.class" to fixClass("fix/Shared", FixSpec())))
        val two = jar(root.resolve("plugins/Two.jar"), mapOf("plugin.yml" to pluginYml("Two", "SA-2"), "fix/Shared.class" to fixClass("fix/Shared", FixSpec(method = "sum", targetDescriptor = "(IJ)J", replacementName = "sum", replacementDescriptor = "(L$TARGET_INTERNAL;IJ)J"))))
        approve(root, "One" to sha256(Files.readAllBytes(one.toPath())), "Two" to sha256(Files.readAllBytes(two.toPath())))
        val e = assertFailsWith<HotfixFatal> { plan() }
        assertContains(e.message!!, "One.jar")
        assertContains(e.message!!, "Two.jar")
        assertContains(e.message!!, "fix.Shared")
        assertContains(e.message!!, "--ignore-hotfix SA-2")
    }

    @Test
    fun aHotfixClassThatTheCoreOrTheLauncherAlreadyHasDoesNotStart() {
        val inCore = jar(root.resolve("plugins/InCore.jar"), mapOf("plugin.yml" to pluginYml("InCore"), "$TARGET_INTERNAL.class" to fixClass(TARGET_INTERNAL, FixSpec())))
        approve(root, "InCore" to sha256(Files.readAllBytes(inCore.toPath())))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "hotfixfixtures.Target, which the server already has")

        val launcherClass = "xyz/felismp/shoparchive/launcher/hotfix/Patcher"
        val inLauncher = jar(root.resolve("plugins/InCore.jar"), mapOf("plugin.yml" to pluginYml("InCore"), "${launcherClass}Kt.class" to fixClass("${launcherClass}Kt", FixSpec()), "xyz/felismp/shoparchive/launcher/hotfix/PatchSpec.class" to ByteArray(1)))
        approve(root, "InCore" to sha256(Files.readAllBytes(inLauncher.toPath())))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "launcher.hotfix.PatchSpec, which the server already has")
    }

    @Test
    fun metaInfAndModuleInfoEntriesDoNotCollide() {
        val entries = mapOf("plugin.yml" to pluginYml("A", "SA-1"), "fix/FixA.class" to fixClass("fix/FixA", FixSpec()), "module-info.class" to ByteArray(1), "META-INF/maven/x/pom.properties" to ByteArray(1))
        val a = jar(root.resolve("plugins/A.jar"), entries)
        val b = jar(root.resolve("plugins/B.jar"), entries - "fix/FixA.class" + ("plugin.yml" to pluginYml("B", "SA-2")) + ("fix/FixB.class" to fixClass("fix/FixB", FixSpec(method = "sum", targetDescriptor = "(IJ)J", replacementName = "sum", replacementDescriptor = "(L$TARGET_INTERNAL;IJ)J"))))
        approve(root, "A" to sha256(Files.readAllBytes(a.toPath())), "B" to sha256(Files.readAllBytes(b.toPath())))
        assertEquals(mapOf("A" to HotfixState.APPLIED, "B" to HotfixState.APPLIED), states(plan()))
    }

    @Test
    fun aMultiReleaseHotfixJarDoesNotStart() {
        val flagged = jar(root.resolve("plugins/Mr.jar"), mapOf("plugin.yml" to pluginYml("Mr"), "fix/FixMr.class" to fixClass("fix/FixMr", FixSpec())), manifestExtra = "Multi-Release: true\r\n")
        approve(root, "Mr" to sha256(Files.readAllBytes(flagged.toPath())))
        val e = assertFailsWith<HotfixFatal> { plan() }
        assertContains(e.message!!, "multi-release hotfix jars are not supported")
        assertContains(e.message!!, "--ignore-hotfix SA-1")

        val versioned = jar(root.resolve("plugins/Mr.jar"), mapOf("plugin.yml" to pluginYml("Mr"), "fix/FixMr.class" to fixClass("fix/FixMr", FixSpec()), "META-INF/versions/9/fix/FixMr.class" to ByteArray(1)))
        approve(root, "Mr" to sha256(Files.readAllBytes(versioned.toPath())))
        assertContains(assertFailsWith<HotfixFatal> { plan() }.message!!, "multi-release")
    }

    @Test
    fun aJarSwappedAfterApprovalIsNotTheApprovedJar() {
        install("Fix1")
        hotfixJar(root, "Fix1", FixSpec(replacementName = "other"), yml = pluginYml("Fix1"))
        assertEquals(HotfixState.NOT_APPROVED, plan().report.single().state)
    }

    @Test
    fun aPluginThatIsNotAHotfixIsNotTouched() {
        jar(root.resolve("plugins/Normal.jar"), mapOf("plugin.yml" to "name: Normal\nversion: 1\nmain: a.B\napi-version: 1\n".toByteArray()))
        val plan = plan()
        assertTrue(plan.report.isEmpty())
        assertTrue(!Files.exists(root.resolve("cache/hotfix")))
    }

    @Test
    fun aHotfixWithAClassPathOrShadedKotlinOrBadFieldsIsRejected() {
        jar(root.resolve("plugins/A.jar"), mapOf("plugin.yml" to pluginYml("A")), manifestClassPath = "other.jar")
        jar(root.resolve("plugins/B.jar"), mapOf("plugin.yml" to pluginYml("B"), "kotlin/X.class" to ByteArray(1)))
        jar(root.resolve("plugins/C.jar"), mapOf("plugin.yml" to pluginYml("C", severity = "meh")))
        jar(root.resolve("plugins/D.jar"), mapOf("plugin.yml" to pluginYml("core")))
        val plan = plan()
        assertEquals(setOf("A", "B", "C", "core"), plan.report.map { it.name }.toSet())
        assertTrue(plan.report.all { it.state == HotfixState.REJECTED })
    }

    @Test
    fun theReportRoundTripsThroughOneLinePerHotfix() {
        install("Fix1")
        val encoded = plan().encodedReport()
        assertEquals(1, encoded.lines().size)
        assertEquals(6, encoded.split('\t').size)
    }

    @Test
    fun coreBuildIsReadFromTheCoreJar() {
        val core = coreJar(root.resolve("c"), 7, "A-1, B-2")
        val build = readCoreBuild(listOf(core))
        assertEquals(7, build.build)
        assertEquals(setOf("A-1", "B-2"), build.fixedIssues)
        assertEquals(null, readCoreBuild(emptyList<File>()).build)
    }
}
