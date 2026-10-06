package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.CurrencyDay
import xyz.felismp.shoparchive.api.DayClosedEvent
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.SessionService
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import xyz.felismp.shoparchive.shared.SessionClosedMessage
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionPreview
import xyz.felismp.shoparchive.shared.SessionOpenedMessage
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import xyz.felismp.shoparchive.shared.WsMessage
import xyz.felismp.shoparchive.shared.closeFigures
import xyz.felismp.shoparchive.shared.isIsoDate
import xyz.felismp.shoparchive.shared.isUuidV7
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.parseAmount
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal class DefaultSessionService(
    private val config: ConfigService,
    private val branches: BranchStore,
    private val store: RecordStore,
    private val sessions: SessionStore,
    private val access: Access,
    private val services: ServiceRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : SessionService {
    override fun open(principal: Principal, branch: String, request: OpenSessionRequest): OpenSessionResponse {
        access.require(principal, DAY_OPEN_NODE)
        access.requireBranch(principal, branch)
        val known = branches.find(branch) ?: throw notFound("There is no branch '$branch'.")
        val float = amounts(request.float, "change")
        val (session, alreadyOpen) = store.writer.run {
            // Two devices opening at once: the second finds the first one's session here and gets that, not an error.
            sessions.openFor(branch)?.let { return@run it to true }
            if (known.archived) throw badRequest("The branch '$branch' is archived.", ErrorReasons.BRANCH_ARCHIVED)
            val by = UserRef(principal.userId, principal.username)
            sessions.save(Session(newUuid7(), branch, businessToday(clock, config), by, stamp(clock, config), float, closed = null)) to false
        }
        if (!alreadyOpen) publish(SessionOpenedMessage(session.id, branch, session.businessDate.toString()), branch)
        return OpenSessionResponse(session.toDto(), if (alreadyOpen) session.openedBy else null)
    }

    override fun current(principal: Principal, branch: String): SessionDto {
        access.requireBranch(principal, branch)
        return sessions.openFor(branch)?.toDto() ?: throw ApiError(404, ErrorCode.NO_OPEN_SESSION, "The branch $branch has no open day.", reason = ErrorReasons.SESSION_NO_OPEN_DAY)
    }

    override fun close(principal: Principal, branch: String, id: String, request: CloseSessionRequest): SessionDto {
        access.require(principal, DAY_CLOSE_NODE)
        access.requireBranch(principal, branch)
        val counted = amounts(request.counted, "count")
        // The summary is worked out in the same turn of the writer as the close, so it holds exactly the entries the close counted.
        val (closed, event) = store.writer.run {
            val session = sessions.find(id)?.takeIf { isUuidV7(id) && it.branch == branch } ?: throw notFound("No such day session.")
            if (session.closed != null) throw ApiError(409, ErrorCode.SESSION_CLOSED, "This day was closed already.", reason = ErrorReasons.SESSION_CLOSED)
            val saved = sessions.save(session.copy(closed = closing(session, counted, request.note.trim(), principal)))
            saved to runCatching { dayEvent(saved) }.onFailure { Log.warn("could not work out the summary of the closed day ${saved.id}: ${it.message}") }.getOrNull()
        }
        publish(SessionClosedMessage(id, branch, closed.businessDate.toString()), branch)
        event?.let(::publishEvent)
        return closed.toDto()
    }

    /** The summary of the closed day [id] of [branch], as the close made it (from the entries as they are now); null if there is no such day or it is still open. */
    fun closedDay(branch: String, id: String): DayClosedEvent? = store.writer.run {
        sessions.find(id)?.takeIf { it.branch == branch && it.closed != null }?.let(::dayEvent)
    }

    /** What the entries of [session] that are not deleted add up to now: counts, and the money by direction and by how it was paid. Only adds up. */
    private fun activityOf(session: Session): Activity {
        val sums = HashMap<Pair<EntryType, TenderMethod>, HashMap<String, Long>>()
        var entryCount = 0
        var slipCount = 0
        for (entry in store.all()) {
            if (entry.session != session.id || entry.deleted) continue
            entryCount++
            slipCount += entry.slips.size
            for (tender in entry.tenders) sums.getOrPut(entry.type to tender.method) { HashMap() }.merge(tender.currency, tender.amount.minor, Math::addExact)
        }
        val exponents = config.currencies.associate { it.code to it.exponent }
        fun of(type: EntryType, method: TenderMethod) = sums[type to method].orEmpty().filterKeys { it in exponents }.mapValues { (c, minor) -> Amount(minor, exponents.getValue(c)) }
        return Activity(entryCount, slipCount, of(EntryType.INCOME, TenderMethod.CASH), of(EntryType.EXPENSE, TenderMethod.CASH), of(EntryType.INCOME, TenderMethod.ONLINE), of(EntryType.EXPENSE, TenderMethod.ONLINE))
    }

    /**
     * What happened on the closed [session]: for each counted currency the sums of the day's entries next to what the close stored. The sums are the ones
     * kept in the session when it was closed, so they always agree with the stored expected and variance; a day closed before they were kept is added up
     * from the entries as they are now and the event says so ([DayClosedEvent.recomputed]).
     */
    private fun dayEvent(session: Session): DayClosedEvent {
        val closed = session.closed ?: error("the session is open")
        val activity = closed.activity ?: activityOf(session)
        fun sum(map: Map<String, Amount>, currency: String) = map[currency]?.minor ?: 0L
        // The counted currencies, and any other the day's entries were in (an amount paid only online is not in the drawer, so it is not counted).
        val seen = listOf(activity.cashIn, activity.cashOut, activity.onlineIn, activity.onlineOut).flatMap { it.keys }.toSet()
        val currencies = config.currencies.filter { it.code in closed.counted || it.code in seen }.map { (currency, exponent) ->
            fun text(minor: Long) = Amount(minor, exponent).text
            val cashIn = sum(activity.cashIn, currency)
            val cashOut = sum(activity.cashOut, currency)
            val onlineIn = sum(activity.onlineIn, currency)
            val onlineOut = sum(activity.onlineOut, currency)
            val counted = closed.counted[currency]?.minor ?: 0L
            val handover = closed.handover[currency]?.minor ?: 0L
            CurrencyDay(
                currency, income = text(cashIn + onlineIn), expense = text(cashOut + onlineOut), net = text(cashIn + onlineIn - cashOut - onlineOut),
                cashIn = text(cashIn), cashOut = text(cashOut), onlineIn = text(onlineIn), onlineOut = text(onlineOut),
                float = text(session.float[currency]?.minor ?: 0L), counted = text(counted), expected = text(closed.expected[currency]?.minor ?: 0L),
                variance = text(closed.variance[currency]?.minor ?: 0L), kept = text(counted - handover), handover = text(handover),
            )
        }
        return DayClosedEvent(session.toDto(), branches.find(session.branch)?.displayName ?: session.branch, currencies, activity.entries, activity.slips, recomputed = closed.activity == null)
    }

    /** Tells the listeners; they cannot fail the close that already happened. */
    private fun publishEvent(event: DayClosedEvent) {
        try {
            services.get(ShopEvents::class.java)?.publish(event)
        } catch (e: Exception) {
            Log.warn("could not publish ${event.type}: ${e.message}")
        }
    }

    override fun preview(principal: Principal, branch: String, id: String): SessionPreview {
        access.require(principal, DAY_CLOSE_NODE)
        access.requireBranch(principal, branch)
        return store.writer.run {
            val session = sessions.find(id)?.takeIf { isUuidV7(id) && it.branch == branch } ?: throw notFound("No such day session.")
            if (session.closed != null) throw ApiError(409, ErrorCode.SESSION_CLOSED, "This day was closed already.", reason = ErrorReasons.SESSION_CLOSED)
            val (cashIn, cashOut) = cashOf(session)
            val rows = config.currencies.map { c ->
                val float = session.float[c.code]?.minor ?: 0
                val expected = closeFigures(float, cashIn[c.code] ?: 0, cashOut[c.code] ?: 0, 0).expected
                c.code to listOf(float, cashIn[c.code] ?: 0, cashOut[c.code] ?: 0, expected).map { Amount(it, c.exponent).text }
            }
            SessionPreview(rows.associate { it.first to it.second[0] }, rows.associate { it.first to it.second[1] }, rows.associate { it.first to it.second[2] }, rows.associate { it.first to it.second[3] })
        }
    }

    /** The cash that came in and went out, per currency, in the entries of [session] that are not deleted: the sums both the close and its preview compare with. */
    private fun cashOf(session: Session): Pair<Map<String, Long>, Map<String, Long>> {
        val cashIn = HashMap<String, Long>()
        val cashOut = HashMap<String, Long>()
        for (entry in store.all()) {
            if (entry.session != session.id || entry.deleted) continue
            for (tender in entry.tenders) {
                if (tender.method != TenderMethod.CASH) continue
                (if (entry.type == EntryType.INCOME) cashIn else cashOut).merge(tender.currency, tender.amount.minor, Math::addExact)
            }
        }
        return cashIn to cashOut
    }

    override fun list(principal: Principal, branch: String, from: String?, to: String?): List<SessionDto> {
        access.requireBranch(principal, branch)
        val today = businessToday(clock, config)
        val start = from?.let(::date) ?: today.withDayOfMonth(1)
        val end = to?.let(::date) ?: today.withDayOfMonth(today.lengthOfMonth())
        if (end.isBefore(start)) throw badRequest("'from' is after 'to'.", ErrorReasons.RANGE_ORDER)
        if (ChronoUnit.DAYS.between(start, end) >= 366) throw badRequest("Ask for at most 366 days at a time.", ErrorReasons.RANGE_TOO_LONG)
        return sessions.inRange(branch, start, end).map { it.toDto() }
    }

    /**
     * The close of [session]: for each currency, expected = change + cash in - cash out of the session's entries that are not deleted,
     * variance = counted - expected, handover = counted - change (not below 0). Runs on the writer, so every entry saved before this
     * close is in the sum and none is saved after it (a closed session takes no more entries).
     */
    private fun closing(session: Session, counted: Map<String, Amount>, note: String, principal: Principal): Closed {
        val (cashIn, cashOut) = cashOf(session)
        // Every currency the drawer should hold something of must be counted; one that is counted anyway is compared with 0.
        val owed = (session.float.keys + cashIn.keys + cashOut.keys).filter { it !in counted }
        if (owed.isNotEmpty()) throw badRequest("Count the ${owed.sorted().joinToString(", ")} in the drawer too.", ErrorReasons.CLOSE_COUNT_MISSING)
        val exponents = config.currencies.associate { it.code to it.exponent }
        val currencies = config.currencies.map { it.code }.filter { it in counted }
        val expected = LinkedHashMap<String, Amount>()
        val variance = LinkedHashMap<String, Amount>()
        val handover = LinkedHashMap<String, Amount>()
        val belowFloor = mutableListOf<String>()
        for (currency in currencies) {
            val exponent = exponents.getValue(currency)
            val figures = closeFigures(
                session.float[currency]?.minor ?: 0, cashIn[currency] ?: 0, cashOut[currency] ?: 0, counted.getValue(currency).minor,
            )
            expected[currency] = Amount(figures.expected, exponent)
            variance[currency] = Amount(figures.variance, exponent)
            handover[currency] = Amount(figures.handover, exponent)
            if (figures.belowFloor) belowFloor += currency
        }
        if (variance.values.any { it.minor != 0L } && note.isEmpty()) {
            val off = variance.filterValues { it.minor != 0L }.entries.joinToString(", ") { "${it.key} ${it.value.text}" }
            throw badRequest("The drawer differs from what it should hold ($off): write a note that says why.", ErrorReasons.CLOSE_NOTE_NEEDED)
        }
        return Closed(
            counted = currencies.associateWith { counted.getValue(it) }, expected = expected, variance = variance, handover = handover,
            belowFloor = belowFloor, note = note, closedBy = UserRef(principal.userId, principal.username), closedAt = stamp(clock, config),
            activity = activityOf(session),
        )
    }

    /** [texts] as amounts: known currencies, plain numbers, 0 allowed (a drawer may hold none of a currency). */
    private fun amounts(texts: Map<String, String>, what: String): Map<String, Amount> {
        val exponents = config.currencies.associate { it.code to it.exponent }
        return texts.mapValues { (currency, text) ->
            val exponent = exponents[currency] ?: throw badRequest("The currency '$currency' is not one this shop uses.", ErrorReasons.CLOSE_CURRENCY_UNKNOWN)
            Amount(parseAmount(text, exponent, allowZero = true) ?: throw badRequest("The $what of $currency, '$text', is not an amount: write 0 or more${if (exponent == 0) ", without decimals" else ", with at most $exponent decimals"}.", ErrorReasons.CLOSE_AMOUNT_INVALID), exponent)
        }
    }

    private fun date(text: String): LocalDate {
        if (!isIsoDate(text)) throw badRequest("'$text' is not a date like 2026-09-27.", ErrorReasons.DATE_INVALID)
        return LocalDate.parse(text)
    }

    private fun publish(message: WsMessage, branch: String) {
        try {
            services.get(EventService::class.java)?.broadcastTo(message) { access.canBranch(it, branch) }
        } catch (e: Exception) {
            Log.warn("could not push ${message::class.simpleName}: ${e.message}")
        }
    }
}
