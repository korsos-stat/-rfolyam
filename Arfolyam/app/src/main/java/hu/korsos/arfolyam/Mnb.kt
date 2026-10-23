package hu.korsos.arfolyam

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Az MNB hivatalos középárfolyama egy adott napra. */
data class MnbRates(val date: String, val mid: Map<String, Double>)

/**
 * Az MNB hivatalos árfolyamainak lekérése. Elsőként az MNB webszolgáltatását
 * használja, ha az nem válaszol, az mnb.hu árfolyamoldalát olvassa ki.
 */
object MnbFetcher {
    private const val SOAP_URL = "https://www.mnb.hu/arfolyamok.asmx"
    const val PAGE_URL = "https://www.mnb.hu/arfolyamok"

    fun fetch(): MnbRates {
        val first = try {
            parseSoap(soapRequest())
        } catch (e: Exception) {
            null
        }
        if (first != null && first.mid.isNotEmpty()) return first

        val html = get(PAGE_URL)
        val mid = parseHtml(html)
        if (mid.isEmpty()) throw IOException("MNB árfolyam nem található")
        val date = Regex("(20\\d\\d)\\.\\s*(\\w+)\\s*(\\d{1,2})\\.").find(html)?.value ?: ""
        return MnbRates(date, mid)
    }

    private fun soapRequest(): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""" +
            """<soap:Body><GetCurrentExchangeRates xmlns="http://www.mnb.hu/webservices/"/></soap:Body>""" +
            """</soap:Envelope>"""
        val conn = URL(SOAP_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        conn.setRequestProperty(
            "SOAPAction",
            "\"http://www.mnb.hu/webservices/MNBArfolyamServiceSoap/GetCurrentExchangeRates\""
        )
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw IOException("MNB HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ArfolyamWidget/1.1")
        try {
            if (conn.responseCode != 200) throw IOException("MNB HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** A válaszban a tényleges adat escape-elt XML: <Day date=".."><Rate unit="1" curr="EUR">366,52</Rate> */
    fun parseSoap(response: String): MnbRates {
        val xml = response
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
        val date = Regex("<Day\\s+date=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: ""
        val mid = LinkedHashMap<String, Double>()
        val rateRx = Regex("<Rate\\b([^>]*)>\\s*([\\d.,]+)\\s*</Rate>")
        val currRx = Regex("curr=\"([A-Z]{3})\"")
        val unitRx = Regex("unit=\"(\\d+)\"")
        for (m in rateRx.findAll(xml)) {
            val attrs = m.groupValues[1]
            val code = currRx.find(attrs)?.groupValues?.get(1) ?: continue
            if (code !in RateFetcher.CODES) continue
            val unit = unitRx.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val v = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            mid[code] = v / unit
        }
        return MnbRates(date, mid)
    }

    /** Tartalék: az árfolyamoldal táblázata (kód, egység, érték). */
    fun parseHtml(html: String): Map<String, Double> {
        val words = html
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .split(Regex("<[^>]*>"))
            .flatMap { it.replace("&nbsp;", " ").split(Regex("\\s+")) }
            .filter { it.isNotBlank() }
        val num = Regex("^\\(?(\\d{1,6}(?:[.,]\\d{1,4})?)\\)?$")
        val out = LinkedHashMap<String, Double>()
        for (code in RateFetcher.CODES) {
            val i = words.indexOf(code)
            if (i < 0) continue
            val nums = ArrayList<String>()
            var j = i + 1
            while (j < words.size && j <= i + 6 && nums.size < 2) {
                num.find(words[j])?.let { nums.add(it.groupValues[1]) }
                j++
            }
            val value = when {
                nums.size >= 2 && (nums[0] == "1" || nums[0] == "100") ->
                    nums[1].replace(',', '.').toDoubleOrNull()?.div(nums[0].toDouble())
                nums.isNotEmpty() -> nums[0].replace(',', '.').toDoubleOrNull()
                else -> null
            }
            if (value != null && value > 0) out[code] = value
        }
        return out
    }
}

object MnbStore {
    private const val PREFS = "mnb"

    fun save(ctx: Context, r: MnbRates) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for ((code, v) in r.mid) e.putString(code, v.toString())
        e.putString("date", r.date)
        e.apply()
    }

    fun load(ctx: Context): Map<String, Double> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RateFetcher.CODES.mapNotNull { c ->
            p.getString(c, null)?.toDoubleOrNull()?.let { c to it }
        }.toMap()
    }

    /** "2026-10-22" -> "10.22." */
    fun dateLabel(ctx: Context): String {
        val d = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("date", "") ?: ""
        val m = Regex("^\\d{4}-(\\d{2})-(\\d{2})").find(d)
        return if (m != null) "${m.groupValues[1]}.${m.groupValues[2]}." else d
    }
}
