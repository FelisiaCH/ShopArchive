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
import xyz.felismp.shoparchive.shared.wire
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

private const val CSV_TYPE = "text/csv; charset=utf-8"
private const val XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

private val HEADER = listOf("date", "time", "entry id", "type", "branch", "category", "item", "note", "currency", "method", "amount", "created-by", "slip count")
private const val AMOUNT_COLUMN = 10

private val DAILY_HEADER = listOf("date", "branch", "currency", "cash in", "cash out", "online in", "online out", "net")

/** A text that a spreadsheet would read as a formula gets an apostrophe in front. */
private fun safe(text: String) = if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text

/** The lines of an export, one per tender of each entry; [deleted] adds the last column. */
private fun cells(entries: List<EntryDto>, deleted: Boolean): List<List<String>> = entries.flatMap { e ->
    e.tenders.map { t ->
        listOf(
            e.date, e.createdAt.substring(11, 19), e.id, e.type.wire(), e.branch, safe(e.category.orEmpty()), safe(e.item), safe(e.note),
            t.currency, t.method.wire(), t.amount, safe(e.createdBy.name), e.slips.size.toString(),
        ) + if (deleted) listOf(e.deleted.toString()) else emptyList()
    }
}

internal fun csvField(text: String) = if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text

/** UTF-8 with a byte order mark (so Excel reads Lao and Thai), CRLF lines, quoted as RFC 4180 says. */
internal fun csvBytes(entries: List<EntryDto>, deleted: Boolean): ByteArray {
    val lines = listOf(HEADER + if (deleted) listOf("deleted") else emptyList()) + cells(entries, deleted)
    return ("﻿" + lines.joinToString("") { it.joinToString(",", transform = ::csvField) + "\r\n" }).toByteArray(Charsets.UTF_8)
}

/** The sheet "Entries" (the CSV's lines, the amount as a number with the currency's decimals) and the sheet "Daily". */
internal fun xlsxBytes(entries: List<EntryDto>, deleted: Boolean, exponents: Map<String, Int>): ByteArray {
    val out = ByteArrayOutputStream()
    Workbook(out, "ShopArchive", null).use { book ->
        val sheet = book.newWorksheet("Entries")
        val header = HEADER + if (deleted) listOf("deleted") else emptyList()
        header.forEachIndexed { c, text -> sheet.value(0, c, text); sheet.style(0, c).bold().set() }
        cells(entries, deleted).forEachIndexed { r, row ->
            row.forEachIndexed { c, text ->
                if (c == AMOUNT_COLUMN) {
                    sheet.value(r + 1, c, BigDecimal(text))
                    sheet.style(r + 1, c).format(numberFormat(exponents[row[8]] ?: 0)).set()
                } else {
                    sheet.value(r + 1, c, text)
                }
            }
        }

        val daily = book.newWorksheet("Daily")
        DAILY_HEADER.forEachIndexed { c, text -> daily.value(0, c, text); daily.style(0, c).bold().set() }
        // Deleted entries are never part of a day's money, even when the first sheet lists them.
        val sums = sortedMapOf<Triple<String, String, String>, Array<BigDecimal>>(compareBy({ it.first }, { it.second }, { it.third }))
        for (e in entries.filter { !it.deleted }) for (t in e.tenders) {
            val slot = (if (e.type == EntryType.INCOME) 0 else 1) + (if (t.method == TenderMethod.CASH) 0 else 2)
            sums.getOrPut(Triple(e.date, e.branch, t.currency)) { Array(4) { BigDecimal.ZERO } }[slot] += BigDecimal(t.amount)
        }
        var r = 1
        for ((key, sum) in sums) {
            daily.value(r, 0, key.first)
            daily.value(r, 1, key.second)
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
) : ExportService {
    override fun export(principal: Principal, query: EntryQuery, format: String): ExportFile {
        access.require(principal, EXPORT_NODE)
        if (format != "csv" && format != "xlsx") throw badRequest("format is csv or xlsx.")
        if (query.from == null || query.to == null) throw badRequest("from and to are needed, like 2026-09-27.")
        val entries = services.require<EntryService>().list(principal, query)
        val name = "shoparchive-${query.from}_${query.to}.$format"
        return if (format == "csv") ExportFile(csvBytes(entries, query.includeDeleted), CSV_TYPE, name)
        else ExportFile(xlsxBytes(entries, query.includeDeleted, config.currencies.associate { it.code to it.exponent }), XLSX_TYPE, name)
    }
}
