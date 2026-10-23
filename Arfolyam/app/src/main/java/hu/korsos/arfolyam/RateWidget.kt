package hu.korsos.arfolyam

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class RateWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        val appCtx = context.applicationContext
        renderAll(appCtx, loading = true)
        val pending = goAsync()
        Thread {
            try {
                Updater.refreshBlocking(appCtx)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "hu.korsos.arfolyam.REFRESH"

        fun renderAll(ctx: Context, loading: Boolean) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, RateWidget::class.java))
            if (ids.isEmpty()) return

            val rates = RateStore.load(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_rates)

            v.setTextViewText(R.id.eur_buy, Fmt.value(rates["EUR"]?.buy))
            v.setTextViewText(R.id.eur_sell, Fmt.value(rates["EUR"]?.sell))
            v.setTextViewText(R.id.usd_buy, Fmt.value(rates["USD"]?.buy))
            v.setTextViewText(R.id.usd_sell, Fmt.value(rates["USD"]?.sell))
            v.setTextViewText(R.id.chf_buy, Fmt.value(rates["CHF"]?.buy))
            v.setTextViewText(R.id.chf_sell, Fmt.value(rates["CHF"]?.sell))
            v.setTextViewText(R.id.rsd_buy, Fmt.value(rates["RSD"]?.buy))
            v.setTextViewText(R.id.rsd_sell, Fmt.value(rates["RSD"]?.sell))

            val mid = MnbStore.load(ctx)
            v.setTextViewText(R.id.eur_mid, Fmt.mid(mid["EUR"]))
            v.setTextViewText(R.id.usd_mid, Fmt.mid(mid["USD"]))
            v.setTextViewText(R.id.chf_mid, Fmt.mid(mid["CHF"]))
            v.setTextViewText(R.id.rsd_mid, Fmt.mid(mid["RSD"]))

            val status = when {
                loading -> "Frissítés…"
                RateStore.lastError(ctx) != null ->
                    "Nem sikerült frissíteni · " + Fmt.updated(RateStore.updatedAt(ctx)).lowercase()
                else -> Fmt.updated(RateStore.updatedAt(ctx))
            }
            val mnbDate = MnbStore.dateLabel(ctx)
            v.setTextViewText(
                R.id.updated,
                if (mnbDate.isNotEmpty() && !loading) "$status · MNB $mnbDate" else status
            )

            val refreshIntent = Intent(ctx, RateWidget::class.java).setAction(ACTION_REFRESH)
            v.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    ctx, 0, refreshIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            val openApp = Intent(ctx, MainActivity::class.java)
            v.setOnClickPendingIntent(
                R.id.table,
                PendingIntent.getActivity(
                    ctx, 1, openApp,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            manager.updateAppWidget(ids, v)
        }
    }
}
