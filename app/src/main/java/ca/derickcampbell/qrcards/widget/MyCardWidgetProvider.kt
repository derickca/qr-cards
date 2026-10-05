package ca.derickcampbell.qrcards.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import ca.derickcampbell.qrcards.CardDetailActivity
import ca.derickcampbell.qrcards.MainActivity
import ca.derickcampbell.qrcards.R
import ca.derickcampbell.qrcards.data.CardRepository

/**
 * "My Card" home-screen widget: 1–4 buttons, each opening one card in
 * present mode. Configured per widget instance (count + card per button)
 * in [MyCardWidgetConfigureActivity]; preferences live in a private
 * SharedPreferences file keyed by widget id.
 *
 * No permissions needed — the provider runs in the app process and reads
 * the same internal card store as the activities. A button whose card was
 * deleted falls back to opening the library instead of a dead card.
 */
class MyCardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context, manager: AppWidgetManager, widgetIds: IntArray
    ) {
        widgetIds.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        widgetIds.forEach { deletePrefs(context, it) }
    }

    companion object {
        const val PREFS = "my_card_widget"
        const val MAX_BUTTONS = 4
        private const val KEY_COUNT = "count_"
        private const val KEY_CARD = "card_"

        private val BUTTON_IDS = listOf(
            R.id.widgetButton1,
            R.id.widgetButton2,
            R.id.widgetButton3,
            R.id.widgetButton4,
        )

        /** Refreshes every widget instance (called from the library screen). */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, MyCardWidgetProvider::class.java)
            )
            ids.forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val count = prefs.getInt(KEY_COUNT + widgetId, 0)
                .coerceIn(0, MAX_BUTTONS)
            val repository = CardRepository(context)
            val views = RemoteViews(context.packageName, R.layout.my_card_widget)

            BUTTON_IDS.forEachIndexed { index, buttonId ->
                if (index < count) {
                    val cardId = prefs.getString(KEY_CARD + widgetId + "_" + index, null)
                    val card = cardId?.let { repository.get(it) }
                    val target = if (card != null) {
                        Intent(context, CardDetailActivity::class.java)
                            .putExtra(CardDetailActivity.EXTRA_CARD_ID, card.id)
                    } else {
                        // Card deleted since configuration: open the library.
                        Intent(context, MainActivity::class.java)
                    }
                    views.setViewVisibility(buttonId, View.VISIBLE)
                    views.setTextViewText(
                        buttonId,
                        card?.name ?: context.getString(R.string.widget_open_library),
                    )
                    val pending = PendingIntent.getActivity(
                        context,
                        widgetId * 10 + index,
                        target,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE,
                    )
                    views.setOnClickPendingIntent(buttonId, pending)
                } else {
                    views.setViewVisibility(buttonId, View.GONE)
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun saveConfig(context: Context, widgetId: Int, cardIds: List<String?>) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_COUNT + widgetId, cardIds.size.coerceIn(1, MAX_BUTTONS))
                .apply {
                    cardIds.forEachIndexed { index, cardId ->
                        if (cardId != null) putString(KEY_CARD + widgetId + "_" + index, cardId)
                        else remove(KEY_CARD + widgetId + "_" + index)
                    }
                }
                .apply()
        }

        private fun deletePrefs(context: Context, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().apply {
                remove(KEY_COUNT + widgetId)
                for (index in 0 until MAX_BUTTONS) {
                    remove(KEY_CARD + widgetId + "_" + index)
                }
            }.apply()
        }
    }
}
