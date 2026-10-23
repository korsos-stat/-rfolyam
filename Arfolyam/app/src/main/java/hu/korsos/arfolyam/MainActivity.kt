package hu.korsos.arfolyam

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }

        val pinBtn = findViewById<Button>(R.id.btn_pin)
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            pinBtn.setOnClickListener {
                manager.requestPinAppWidget(ComponentName(this, RateWidget::class.java), null, null)
            }
        } else {
            pinBtn.visibility = View.GONE
        }

        findViewById<TextView>(R.id.source).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RateFetcher.SOURCE_URL)))
        }
    }

    override fun onResume() {
        super.onResume()
        show()
        refresh()
    }

    private fun refresh() {
        status.text = "Frissítés…"
        Thread {
            Updater.refreshBlocking(applicationContext)
            runOnUiThread { show() }
        }.start()
    }

    private fun show() {
        val r = RateStore.load(this)
        setText(R.id.m_eur_buy, r["EUR"]?.buy)
        setText(R.id.m_eur_sell, r["EUR"]?.sell)
        setText(R.id.m_usd_buy, r["USD"]?.buy)
        setText(R.id.m_usd_sell, r["USD"]?.sell)
        setText(R.id.m_chf_buy, r["CHF"]?.buy)
        setText(R.id.m_chf_sell, r["CHF"]?.sell)
        setText(R.id.m_rsd_buy, r["RSD"]?.buy)
        setText(R.id.m_rsd_sell, r["RSD"]?.sell)

        val mid = MnbStore.load(this)
        for ((id, code) in listOf(
            R.id.m_eur_mid to "EUR", R.id.m_usd_mid to "USD",
            R.id.m_chf_mid to "CHF", R.id.m_rsd_mid to "RSD"
        )) {
            findViewById<TextView>(id).text = Fmt.mid(mid[code])
        }

        val err = RateStore.lastError(this)
        val updated = Fmt.updated(RateStore.updatedAt(this))
        val mnbDate = MnbStore.dateLabel(this)
        val mnbLine = if (mnbDate.isNotEmpty()) "\nMNB középárfolyam: $mnbDate" else ""
        status.text = (if (err != null) "Nem sikerült frissíteni ($err).\n$updated" else updated) + mnbLine
    }

    private fun setText(id: Int, v: Double?) {
        findViewById<TextView>(id).text = Fmt.value(v)
    }
}
