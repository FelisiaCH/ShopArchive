package xyz.felismp.shoparchive.server.records

import org.dhatim.fastexcel.Workbook
import xyz.felismp.shoparchive.api.EntryQuery
import xyz.felismp.shoparchive.api.EntryService
import xyz.felismp.shoparchive.api.ExportFile
import xyz.felismp.shoparchive.api.ExportService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.net.require
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.TenderMethod
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

private const val CSV_TYPE = "text/csv; charset=utf-8"
private const val XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

private const val AMOUNT_COLUMN = 10

/** A text that a spreadsheet would read as a formula gets an apostrophe in front. */
private fun safe(text: String) = if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text

/** The lines of an export, one per tender of each entry; [deleted] adds the last column. */
private fun cells(entries: List<EntryDto>, deleted: Boolean, words: ExportWords): List<List<String>> = entries.flatMap { e ->
    e.tenders.map { t ->
        listOf(
            e.date, e.createdAt.substring(11, 19), e.id, words.type(e.type), safe(words.branch(e.branch)), safe(words.category(e.category)), safe(e.item), safe(e.note),
            t.currency, words.method(t.method), t.amount, safe(e.createdBy.name), e.slips.size.toString(),
        ) + if (deleted) listOf(words.flag(e.deleted)) else emptyList()
    }
}

internal fun csvField(text: String) = if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text

/** UTF-8 with a byte order mark (so Excel reads Lao and Thai), CRLF lines, quoted as RFC 4180 says. */
internal fun csvBytes(entries: List<EntryDto>, deleted: Boolean, words: ExportWords = ExportWords.DEFAULT): ByteArray {
    val lines = listOf(words.header(deleted)) + cells(entries, deleted, words)
    return ("﻿" + lines.joinToString("") { it.joinToString(",", transform = ::csvField) + "\r\n" }).toByteArray(Charsets.UTF_8)
}

/** The sheet "Entries" (the CSV's lines, the amount as a number with the currency's decimals) and the sheet "Daily", both named in the language of [words]. */
internal fun xlsxBytes(entries: List<EntryDto>, deleted: Boolean, exponents: Map<String, Int>, words: ExportWords = ExportWords.DEFAULT): ByteArray {
    val out = ByteArrayOutputStream()
    Workbook(out, "ShopArchive", null).use { book ->
        val sheet = book.newWorksheet(words.entriesSheet)
        words.header(deleted).forEachIndexed { c, text -> sheet.value(0, c, text); sheet.style(0, c).bold().set() }
        cells(entries, deleted, words).forEachIndexed { r, row ->
            row.forEachIndexed { c, text ->
                if (c == AMOUNT_COLUMN) {
                    sheet.value(r + 1, c, BigDecimal(text))
                    sheet.style(r + 1, c).format(numberFormat(exponents[row[8]] ?: 0)).set()
                } else {
                    sheet.value(r + 1, c, text)
                }
            }
        }

        val daily = book.newWorksheet(words.dailySheet)
        words.dailyHeader.forEachIndexed { c, text -> daily.value(0, c, text); daily.style(0, c).bold().set() }
        // Deleted entries are never part of a day's money, even when the first sheet lists them.
        val sums = sortedMapOf<Triple<String, String, String>, Array<BigDecimal>>(compareBy({ it.first }, { it.second }, { it.third }))
        for (e in entries.filter { !it.deleted }) for (t in e.tenders) {
            val slot = (if (e.type == EntryType.INCOME) 0 else 1) + (if (t.method == TenderMethod.CASH) 0 else 2)
            sums.getOrPut(Triple(e.date, e.branch, t.currency)) { Array(4) { BigDecimal.ZERO } }[slot] += BigDecimal(t.amount)
        }
        var r = 1
        for ((key, sum) in sums) {
            daily.value(r, 0, key.first)
            daily.value(r, 1, safe(words.branch(key.second)))
            daily.value(r, 2, key.third)
            val format = numberFormat(exponents[key.third] ?: 0)
            // sum: cash in, cash out, online in, online out
            (sum.toList() + (sum[0] + sum[2] - sum[1] - sum[3])).forEachIndexed { i, value ->
                daily.value(r, 3 + i, value)
                daily.style(r, 3 + i).format(format).set()
            }
            r++
        }
    }
    return out.toByteArray()
}

private fun numberFormat(exponent: Int) = if (exponent == 0) "0" else "0." + "0".repeat(exponent)

/** `GET /api/v1/export`: the rows are the ones the caller's [EntryService] would list, so nobody exports what they could not see. */
internal class DefaultExportService(
    private val config: ConfigService,
    private val access: Access,
    private val services: ServiceRegistry,
    private val branches: BranchStore,
    private val categories: CategoryStore,
) : ExportService {
    override fun export(principal: Principal, query: EntryQuery, format: String): ExportFile = export(principal, query, format, null)

    override fun export(principal: Principal, query: EntryQuery, format: String, language: String?): ExportFile {
        access.require(principal, EXPORT_NODE)
        if (format != "csv" && format != "xlsx") throw badRequest("format is csv or xlsx.")
        if (query.from == null || query.to == null) throw badRequest("from and to are needed, like 2026-09-27.")
        val entries = services.require<EntryService>().list(principal, query)
        val name = "shoparchive-${query.from}_${query.to}.$format"
        val words = wordsFor(language)
        return if (format == "csv") ExportFile(csvBytes(entries, query.includeDeleted, words), CSV_TYPE, name)
        else ExportFile(xlsxBytes(entries, query.includeDeleted, config.currencies.associate { it.code to it.exponent }, words), XLSX_TYPE, name)
    }

    /** No language: the English of the first versions, raw keys and all. A language: its words, with categories and branches by display name (a key the shop no longer lists stays as it is). */
    private fun wordsFor(language: String?): ExportWords {
        if (language == null) return ExportWords.DEFAULT
        val tag = language.substringBefore('-').lowercase()
        return ExportWords(
            tag,
            { key ->
                val name = categories.find(key)?.name
                // The app's pick(): the language's name, English when that is empty.
                val picked = when (tag) { "lo" -> name?.lo; "th" -> name?.th; else -> name?.en }
                if (name == null) key else picked.orEmpty().ifBlank { name.en }.ifBlank { key }
            },
            { key -> branches.find(key)?.displayName ?: key },
        )
    }
}
