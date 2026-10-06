package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.BranchService
import xyz.felismp.shoparchive.api.CategoryService
import xyz.felismp.shoparchive.api.DashboardService
import xyz.felismp.shoparchive.api.EntryService
import xyz.felismp.shoparchive.api.ExportService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.SessionService
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.auth.ClientRecordsConfig
import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import xyz.felismp.shoparchive.server.net.CORE_SERVICE_PRIORITY
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.RecordsPolicy
import java.nio.file.Path
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Branches, categories, day sessions and entries: their files, the one writer, and the services the routes call.
 * Registers its services with the core priority, so a plugin can still replace any of them.
 */
internal class Records(
    private val root: Path,
    private val config: ConfigService,
    private val users: UserStore,
    services: ServiceRegistry,
    private val log: ConfigLog = ConsoleConfigLog,
    private val clock: Clock = Clock.systemUTC(),
    private val files: FileOps = FileOps(),
    barrier: DataBarrier = DataBarrier(),
) : ClientRecordsConfig {
    private val writer = RecordWriter()
    private val exponents = { config.currencies.associate { it.code to it.exponent } }

    val branches = BranchStore(DataFileStore(root, BranchesFile, log, clock, barrier))
    val categories = CategoryStore(DataFileStore(root, CategoriesFile, log, clock, barrier))
    val store = RecordStore(root, exponents, writer, files, log, clock)
    val sessions = SessionStore(root, exponents, writer, files, log)

    private val access = Access(services)
    private val branchService = DefaultBranchService(branches, access)
    private val entryService = DefaultEntryService(config, branches, categories, store, sessions, access, services, clock)
    private val sessionService = DefaultSessionService(config, branches, store, sessions, access, services, clock)

    init {
        services.register(BranchService::class.java, branchService, CORE_SERVICE_PRIORITY, "core")
        services.register(CategoryService::class.java, DefaultCategoryService(categories, access), CORE_SERVICE_PRIORITY, "core")
        services.register(SessionService::class.java, sessionService, CORE_SERVICE_PRIORITY, "core")
        services.register(EntryService::class.java, entryService, CORE_SERVICE_PRIORITY, "core")
        services.register(ExportService::class.java, DefaultExportService(config, access, services), CORE_SERVICE_PRIORITY, "core")
        services.register(DashboardService::class.java, DefaultDashboardService(config, store, access, services, clock), CORE_SERVICE_PRIORITY, "core")
    }

    /** The console's export: every entry of the dates (and of [branch], if given), as CSV in `exports/<time>.csv`. Returns the file and the number of lines. */
    fun exportCsv(from: String, to: String, branch: String?): Pair<Path, Int> {
        val (first, last) = dateRange(from, to)
        val entries = store.all().filter { it.date in first..last && !it.deleted && (branch == null || it.branch == branch) }
            .sortedWith(compareBy({ it.date }, { it.createdAt }, { it.id })).map { it.toDto() }
        val folder = root.resolve("exports")
        Files.createDirectories(folder)
        val stamp = LocalDateTime.now(clock.withZone(config.timezone)).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        // Two exports in one second get -2, -3, ...: an earlier export is never replaced.
        val file = generateSequence(1) { it + 1 }.map { folder.resolve(if (it == 1) "$stamp.csv" else "$stamp-$it.csv") }.first { !Files.exists(it) }
        files.writeAtomic(file, csvBytes(entries, false))
        return file to entries.sumOf { it.tenders.size }
    }

    /** Reads branches, categories, sessions and every entry. @throws xyz.felismp.shoparchive.server.config.ConfigFileException a data file cannot be understood */
    fun load() {
        loadData()
        sessions.load()
        store.load()
    }

    /** `reload data`: the two data files again, then the warnings about the users that depend on them. */
    fun loadData() {
        branches.load()
        categories.load()
        if (branches.all().isEmpty()) log.info("No branches yet. Add the first one with: branch add main Main")
        for (name in users.userNames()) {
            for (key in users.user(name).branches) {
                if (branches.find(key) == null) log.warn("user '$name': the branch '$key' is not in data/branches.yml")
            }
        }
    }

    /** The summary of the closed day [id] of [branch], for sending it again; null if there is no such day or it is still open. */
    fun closedDay(branch: String, id: String) = sessionService.closedDay(branch, id)

    /**
     * The events of the entries made and the days closed at or after [since], oldest first (entries that are deleted are left out): what a message
     * should exist for, to compare with the outbox after a stop or a failure. Runs on the writer so each is seen whole.
     */
    fun eventsSince(since: java.time.Instant): List<xyz.felismp.shoparchive.api.ShopEvent> = store.writer.run {
        fun at(text: String) = runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
        val entries = store.all().filter { !it.deleted && at(it.createdAt)?.let { t -> !t.isBefore(since) } == true }.sortedBy { it.createdAt }.map { entryService.createdEvent(it) }
        val days = sessions.all().filter { s -> s.closed?.let { c -> at(c.closedAt)?.let { t -> !t.isBefore(since) } } == true }.sortedBy { it.closed!!.closedAt }.mapNotNull { sessionService.closedDay(it.branch, it.id) }
        entries + days
    }

    /**
     * Runs [block] while no record or session is being written: the writes in progress finish first and new ones wait their turn
     * (they are not refused). Keep it short, every request that saves something waits for it.
     */
    fun pauseWrites(block: () -> Unit) = writer.run(block)

    /** Lets the writes in progress finish; the shutdown hook calls it after the network has stopped. */
    fun close() = writer.close()

    /** The lines `status` adds. */
    fun statusLines(): List<String> {
        val open = sessions.all().count { it.closed == null }
        return listOf("Records: ${store.all().size} entries, ${store.brokenEntries().size} broken; $open open ${if (open == 1) "day" else "days"}")
    }

    override fun branches(principal: Principal): List<BranchDto> = branchService.list(principal)

    override fun categories(): List<CategoryDto> = categories.all().map { it.toDto() }

    override fun policy() = config.records.let { RecordsPolicy(it.requireOpenDay, it.requireCategory, it.slipMaxCount, it.slipMaxSizeKb, it.editWindowDays) }
}
