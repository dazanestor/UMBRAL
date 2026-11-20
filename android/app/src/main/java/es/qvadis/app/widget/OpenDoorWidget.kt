package es.qvadis.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import es.qvadis.app.R
import es.qvadis.app.ui.QuickOpenActivity

/**
 * Widget de inicio con botón "Abrir puerta". Un toque lanza [QuickOpenActivity], que pide
 * huella/cara (o PIN del móvil) antes de abrir. Nunca abre con un toque accidental.
 */
class OpenDoorWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> mgr.updateAppWidget(id, buildViews(ctx)) }
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, OpenDoorWidget::class.java))
            ids.forEach { id -> mgr.updateAppWidget(id, buildViews(ctx)) }
        }

        private fun buildViews(ctx: Context): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_open_door)
            val intent = Intent(ctx, QuickOpenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_open_btn, pi)
            return views
        }
    }
}
