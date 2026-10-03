package ca.derickcampbell.qrcards.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import ca.derickcampbell.qrcards.CardDetailActivity
import ca.derickcampbell.qrcards.R
import ca.derickcampbell.qrcards.data.CardRepository

/**
 * Dynamic app shortcuts for quick access to cards (long-press the launcher
 * icon). The repository appends re-saved cards to the end of the file, so
 * takeLast() yields the most recently saved cards.
 *
 * Shortcuts are best-effort: failures (e.g. rate limits) are swallowed so a
 * shortcut problem can never break the library.
 */
object CardShortcuts {

    private const val MAX_SHORTCUTS = 4

    fun refresh(context: Context) {
        try {
            val cards = CardRepository(context).list()
                .takeLast(MAX_SHORTCUTS)
                .reversed()
            val shortcuts = cards.map { card ->
                val intent = Intent(context, CardDetailActivity::class.java)
                    .setAction("ca.derickcampbell.qrcards.action.VIEW_CARD")
                    .putExtra(CardDetailActivity.EXTRA_CARD_ID, card.id)
                ShortcutInfoCompat.Builder(context, "card_${card.id}")
                    .setShortLabel(card.name)
                    .setLongLabel(card.name)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_qr))
                    .setIntent(intent)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: Exception) {
            // ignore — shortcuts must never break the app
        }
    }
}
