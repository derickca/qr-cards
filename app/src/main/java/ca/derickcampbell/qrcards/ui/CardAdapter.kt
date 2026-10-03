package ca.derickcampbell.qrcards.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
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

/** Library rows: name, type label, optional label-color dot. */
class CardAdapter(private val onClick: (QrCard) -> Unit) :
    RecyclerView.Adapter<CardAdapter.Holder>() {

    private var cards: List<QrCard> = emptyList()

    fun submit(cards: List<QrCard>) {
        this.cards = cards
        notifyDataSetChanged()
    }

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
        }
    }
}
