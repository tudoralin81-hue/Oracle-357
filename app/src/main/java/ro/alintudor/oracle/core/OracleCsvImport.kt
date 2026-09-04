package ro.alintudor.oracle.core

import java.util.Locale

/** Broker CSV → positions. Header-driven: finds the ticker / quantity /
 *  price columns by name so Trading 212, XTB, Interactive Brokers, Revolut
 *  and most generic exports work without a per-broker template. Rows are
 *  aggregated per ticker (weighted average cost). */
object OracleCsvImport {
    data class Row(val ticker: String, val company: String, val shares: Double, val avgCost: Double)

    private val tickerNames = listOf("ticker", "symbol", "instrument", "stock", "isin/ticker", "ticker symbol")
    private val sharesNames = listOf("shares", "quantity", "qty", "no. of shares", "volume", "units", "amount")
    private val priceNames = listOf("price / share", "price per share", "avg cost", "average cost", "cost basis price", "open price", "purchase price", "price", "cost basis", "average price", "avg price")
    private val nameNames = listOf("name", "company", "description", "instrument name")
    private val actionNames = listOf("action", "type", "side", "transaction type")

    fun parse(text: String): List<Row> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val sep = listOf(',', ';', '\t').maxByOrNull { ch -> lines[0].count { it == ch } } ?: ','
        val header = split(lines[0], sep).map { it.trim().trim('"').lowercase(Locale.US) }
        fun col(names: List<String>): Int = names.map { n -> header.indexOfFirst { it == n } }.firstOrNull { it >= 0 }
            ?: names.map { n -> header.indexOfFirst { it.contains(n) } }.firstOrNull { it >= 0 } ?: -1
        val ti = col(tickerNames); val si = col(sharesNames); val pi = col(priceNames)
        if (ti < 0 || si < 0 || pi < 0) return emptyList()
        val ni = col(nameNames); val ai = col(actionNames)
        val acc = LinkedHashMap<String, Triple<String, Double, Double>>() // ticker -> (company, shares, cost)
        for (line in lines.drop(1)) {
            val c = split(line, sep).map { it.trim().trim('"') }
            if (c.size <= maxOf(ti, si, pi)) continue
            val ticker = c[ti].uppercase(Locale.US).substringBefore(':').substringBefore('.').trim()
            if (ticker.isBlank() || ticker.length > 6 || !ticker.all { it.isLetterOrDigit() }) continue
            var shares = num(c[si]) ?: continue
            val price = num(c[pi]) ?: continue
            if (ai >= 0) { val a = c[ai].lowercase(Locale.US); if (a.contains("sell") || a.contains("v\u00e2nz")) shares = -shares else if (!a.contains("buy") && !a.contains("market") && !a.contains("limit") && !a.contains("cump") && a.isNotBlank() && !a.contains("open")) continue }
            if (shares == 0.0 || price <= 0.0) continue
            val prev = acc[ticker]
            val newShares = (prev?.second ?: 0.0) + shares
            val newCost = (prev?.third ?: 0.0) + (if (shares > 0) shares * price else -(prev?.let { if (it.second > 0) it.third / it.second * -shares else 0.0 } ?: 0.0))
            acc[ticker] = Triple(if (ni >= 0 && c.size > ni && c[ni].isNotBlank()) c[ni] else (prev?.first ?: ""), newShares, newCost)
        }
        return acc.mapNotNull { (t, v) -> if (v.second > 0.0 && v.third > 0.0) Row(t, v.first, v.second, v.third / v.second) else null }
    }

    private fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) { when { ch == '"' -> q = !q; ch == sep && !q -> { out += sb.toString(); sb.setLength(0) }; else -> sb.append(ch) } }
        out += sb.toString(); return out
    }
    private fun num(s: String): Double? = s.replace(Regex("[^0-9.,-]"), "").let { v ->
        val t = if (v.count { it == ',' } == 1 && v.count { it == '.' } == 0) v.replace(',', '.') else v.replace(",", "")
        t.toDoubleOrNull()
    }
}
