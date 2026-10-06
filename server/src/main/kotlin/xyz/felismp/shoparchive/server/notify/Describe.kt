package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.CurrencyDay
import xyz.felismp.shoparchive.api.DayClosedEvent
import xyz.felismp.shoparchive.api.EntryCreatedEvent
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventTypes
import xyz.felismp.shoparchive.shared.wire
import java.util.Locale

/**
 * The words of a notification, by name (see [Notification]). One place makes them from an event, so what the log line, the outbox
 * and every channel plugin see is the same; null for an event type that has no notification.
 */
internal fun notificationFields(event: ShopEvent, locale: Locale): Map<String, String>? = when (event) {
    is EntryCreatedEvent -> entryFields(event, locale)
    is DayClosedEvent -> dayFields(event)
    else -> null
}

private fun entryFields(event: EntryCreatedEvent, locale: Locale): Map<String, String> {
    val entry = event.entry
    val names = event.categoryName
    val inLocale = when (locale.language) {
        "th" -> names?.th
        "en" -> names?.en
        else -> names?.lo
    }
    return linkedMapOf(
        "type" to entry.type.wire(),
        "category" to (inLocale ?: ""),
        "category.key" to (entry.category ?: ""),
        "category.lo" to (names?.lo ?: ""),
        "category.th" to (names?.th ?: ""),
        "category.en" to (names?.en ?: ""),
        "item" to entry.item,
        "amount" to entry.tenders.joinToString(", ") { it.amount },
        "currency" to entry.tenders.joinToString(", ") { it.currency },
        "branch" to event.branchName,
        "user" to entry.createdBy.name,
        "time" to entry.createdAt,
        "date" to entry.date,
    )
}

private fun dayFields(event: DayClosedEvent): Map<String, String> {
    val close = event.session.close
    fun each(value: (CurrencyDay) -> String) = event.currencies.joinToString("; ") { "${it.currency} ${value(it)}" }
    val fields = linkedMapOf(
        "type" to ShopEventTypes.DAY_CLOSED,
        "category" to "",
        "item" to "",
        "amount" to each { it.net },
        "currency" to event.currencies.joinToString(", ") { it.currency },
        "branch" to event.branchName,
        "user" to (close?.closedBy?.name ?: ""),
        "time" to event.at,
        "date" to event.session.businessDate,
        "closedBy" to (close?.closedBy?.name ?: ""),
        "entries" to event.entryCount.toString(),
        "slips" to event.slipCount.toString(),
        "note" to (close?.note ?: ""),
        "income" to each { it.income },
        "expense" to each { it.expense },
        "net" to each { it.net },
        "cashIn" to each { it.cashIn },
        "cashOut" to each { it.cashOut },
        "onlineIn" to each { it.onlineIn },
        "onlineOut" to each { it.onlineOut },
        "float" to each { it.float },
        "counted" to each { it.counted },
        "expected" to each { it.expected },
        "variance" to each { it.variance },
        "kept" to each { it.kept },
        "handover" to each { it.handover },
    )
    // Only said when true; the figures of such a day were added up from the entries as they are now.
    if (event.recomputed) fields["recomputed"] = "true"
    return fields
}

/** The short code a message carries: the business date and the last five characters of the entry's or the day's id, so it names one subject and a repeat has the same one. */
internal fun notificationCode(date: String, subjectId: String): String = date.replace("-", "") + "-" + subjectId.takeLast(5).uppercase(Locale.ROOT)

/** One line for the log, `notify list` and the app: what the message is about, whatever channel it goes to. */
internal fun describe(n: Notification): String {
    val f = n.fields
    return when (n.event) {
        ShopEventTypes.ENTRY_CREATED -> buildString {
            append("[${n.code}] ${f["branch"]} ${f["time"]}: ${n.user} recorded ${f["type"]} ${f["amount"]} ${f["currency"]}")
            f["category"]?.takeIf { it.isNotEmpty() }?.let { append(" ($it)") }
            f["item"]?.takeIf { it.isNotEmpty() }?.let { append(" $it") }
        }
        ShopEventTypes.DAY_CLOSED ->
            "[${n.code}] ${f["branch"]} ${f["date"]}: day closed by ${n.user}; net ${f["net"]}; counted ${f["counted"]}; variance ${f["variance"]}; handover ${f["handover"]}; ${f["entries"]} entries" +
                if (f["recomputed"] == "true") " (figures added up from the entries as they are now: the day was closed before they were kept)" else ""
        else -> "[${n.code}] ${n.event} ${f["branch"] ?: n.branch}"
    }
}

/** The message for [event], or null if the core makes none for that type. [createdAt] is the time the message is made, [resendOf] the message it repeats. */
internal fun draftFor(event: ShopEvent, locale: Locale, createdAt: String, resendOf: String? = null): Draft? {
    val fields = notificationFields(event, locale) ?: return null
    val (code, subject) = when (event) {
        is EntryCreatedEvent -> notificationCode(event.entry.date, event.entry.id) to event.entry.id
        is DayClosedEvent -> notificationCode(event.session.businessDate, event.session.id) to event.session.id
        else -> return null
    }
    return Draft(code, event.type, createdAt, event.branch, fields.getValue("user"), locale.toLanguageTag(), fields, resendOf, subject)
}
