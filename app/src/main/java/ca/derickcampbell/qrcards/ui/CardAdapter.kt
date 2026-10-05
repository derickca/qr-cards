package ca.derickcampbell.qrcards.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ca.derickcampbell.qrcards.R
import ca.derickcampbell.qrcards.databinding.ItemCardBinding
import ca.derickcampbell.qrcards.model.CardType
import ca.derickcampbell.qrcards.model.QrCard

/** Human-readable label for a card type. */
fun typeLabel(type: CardType, context: Context): String = when (type) {
    CardType.URL -> context.getString(R.string.type_url)
    CardType.CONTACT -> context.getString(R.string.type_contact)
    CardType.WIFI -> context.getString(R.string.type_wifi)
    CardType.LOCATION -> context.getString(R.string.type_location)
    CardType.TEXT -> context.getString(R.string.type_text)
    CardType.EMAIL -> context.getString(R.string.type_email)
    CardType.PHONE -> context.getString(R.string.type_phone)
    CardType.SMS -> context.getString(R.string.type_sms)
    CardType.CALENDAR_EVENT -> context.getString(R.string.type_calendar_event)
}

/** Library rows: name, type label, optional label-color dot, drag handle. */
class CardAdapter(
    private val onClick: (QrCard) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    private var cards: List<QrCard> = emptyList()

    /**
     * Drag handles show only when the full (unfiltered) library is visible —
     * reordering a search result would be meaningless.
     */
    var dragEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(cards: List<QrCard>) {
        this.cards = cards
        notifyDataSetChanged()
    }

    /** Moves a row; returns true when the positions were valid. */
    fun move(from: Int, to: Int): Boolean {
        if (from !in cards.indices || to !in cards.indices || from == to) return false
        val mutable = cards.toMutableList()
        val card = mutable.removeAt(from)
        mutable.add(to, card)
        cards = mutable
        notifyItemMoved(from, to)
        return true
    }

    fun currentIds(): List<String> = cards.map { it.id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(cards[position])

    override fun getItemCount(): Int = cards.size

    inner class Holder(private val binding: ItemCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(card: QrCard) {
            binding.cardName.text = card.name
            binding.cardType.text = typeLabel(card.type, binding.root.context)
            val color = card.labelColor
            if (color == null) {
                // INVISIBLE (not GONE) keeps the text column aligned.
                binding.colorDot.visibility = View.INVISIBLE
            } else {
                binding.colorDot.visibility = View.VISIBLE
                binding.colorDot.imageTintList = ColorStateList.valueOf(color)
            }
            binding.root.setOnClickListener { onClick(card) }
            binding.dragHandle.visibility =
                if (dragEnabled) View.VISIBLE else View.GONE
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
                    onStartDrag(this)
                }
                false
            }
        }
    }
}
