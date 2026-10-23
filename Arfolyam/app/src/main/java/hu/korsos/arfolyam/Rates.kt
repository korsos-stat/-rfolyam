package hu.korsos.arfolyam

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Rate(val code: String, val buy: Double, val sell: Double)

/** A Correct Change árfolyam oldalának letöltése és feldolgozása. */
object RateFetcher {
    const val SOURCE_URL = "https://correctchange.hu/hu/arfolyamok"
    val CODES = listOf("EUR", "USD", "CHF", "RSD")

    fun fetch(): Map<String, Rate> {
        val conn = URL(SOURCE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ArfolyamWidget/1.0")
        conn.setRequestProperty("Accept-Language", "hu-HU,hu;q=0.9")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val rates = parse(html)
            if (rates.isEmpty()) throw IOException("Nem találhatók árfolyamok az oldalon")
            return rates
        } finally {
            conn.disconnect()
        }
    }

    private val codeRegex = Regex("^[A-Z]{3}$")
    private val numberRegex = Regex("^\\(?(\\d{1,6}(?:[.,]\\d{1,4})?)\\)?$")

    /**
     * Az oldalt szöveges szavakra bontja, majd minden pénznemkód után megkeresi
     * az első értelmes (vétel, eladás) számpárt: eladás >= vétel, és a kettő
     * közti eltérés 50%-on belül van. Így a mértékegység ("(1)", "100") kiesik.
     */
    fun parse(html: String): Map<String, Rate> {
        val noScripts = html.replace(
            Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " "
        )
        val words = noScripts
            .split(Regex("<[^>]*>"))
            .map { decodeEntities(it) }
            .flatMap { it.split(Regex("\\s+")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = LinkedHashMap<String, Rate>()
        for (code in CODES) {
            var i = 0
            while (i < words.size) {
                if (words[i] == code) {
                    val nums = ArrayList<Double>()
                    var j = i + 1
                    while (j < words.size && j <= i + 14 && !codeRegex.matches(words[j])) {
                        val m = numberRegex.find(words[j])
                        if (m != null) {
                            m.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { nums.add(it) }
                        }
                        j++
                    }
                    val pair = firstPlausiblePair(nums)
                    if (pair != null) {
                        result[code] = Rate(code, pair.first, pair.second)
                        break
                    }
                }
                i++
            }
        }
        return result
    }

    private fun firstPlausiblePair(nums: List<Double>): Pair<Double, Double>? {
        for (k in 0 until nums.size - 1) {
            val a = nums[k]
            val b = nums[k + 1]
            if (a > 0 && b >= a && b / a < 1.5) return Pair(a, b)
        }
        return null
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

/** Utolsó sikeres letöltés tárolása, hogy offline is legyen mit mutatni. */
object RateStore {
    private const val PREFS = "rates"
    private const val KEY_UPDATED = "updated"
    private const val KEY_ERROR = "error"

    fun save(ctx: Context, rates: Map<String, Rate>) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (r in rates.values) {
            e.putString("${r.code}_buy", r.buy.toString())
            e.putString("${r.code}_sell", r.sell.toString())
        }
        e.putLong(KEY_UPDATED, System.currentTimeMillis())
        e.remove(KEY_ERROR)
        e.apply()
    }

    fun saveError(ctx: Context, msg: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ERROR, msg).apply()
    }

    fun load(ctx: Context): Map<String, Rate> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = LinkedHashMap<String, Rate>()
        for (code in RateFetcher.CODES) {
            val b = p.getString("${code}_buy", null)?.toDoubleOrNull()
            val s = p.getString("${code}_sell", null)?.toDoubleOrNull()
            if (b != null && s != null) out[code] = Rate(code, b, s)
        }
        return out
    }

    fun updatedAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED, 0L)

    fun lastError(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ERROR, null)
}

object Fmt {
    private val hu: Locale = Locale.forLanguageTag("hu-HU")

    fun value(v: Double?): String {
        if (v == null) return "–"
        return if (v < 10) String.format(hu, "%.2f", v) else String.format(hu, "%.1f", v)
    }

    /** MNB középárfolyam: mindig két tizedes, ahogy az MNB közli. */
    fun mid(v: Double?): String = if (v == null) "–" else String.format(hu, "%.2f", v)

    fun updated(ts: Long): String {
        if (ts == 0L) return "Még nincs adat"
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "MM.dd. HH:mm"
        return "Frissítve: " + SimpleDateFormat(pattern, hu).format(Date(ts))
    }
}

/** Közös frissítés: letölt, elment, és frissíti az összes widgetet. Háttérszálon hívandó. */
object Updater {
    fun refreshBlocking(ctx: Context): Boolean {
        val ok = try {
            RateStore.save(ctx, RateFetcher.fetch())
            true
        } catch (e: Exception) {
            RateStore.saveError(ctx, e.message ?: "Hiba")
            false
        }
        // Az MNB-árfolyam külön forrás: ha nem érhető el, a pénzváltós árak attól még frissülnek.
        try {
            MnbStore.save(ctx, MnbFetcher.fetch())
        } catch (e: Exception) {
            // marad az utolsó elmentett MNB-érték
        }
        RateWidget.renderAll(ctx, loading = false)
        return ok
    }
}
