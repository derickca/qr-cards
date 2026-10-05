package ca.derickcampbell.qrcards.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import ca.derickcampbell.qrcards.R
import ca.derickcampbell.qrcards.data.CardRepository
import ca.derickcampbell.qrcards.databinding.ActivityMyCardWidgetConfigureBinding
import ca.derickcampbell.qrcards.model.QrCard
import com.google.android.material.button.MaterialButton

/**
 * Widget configuration: pick the number of buttons (1–4), then pick which
 * card each button opens. Cancelled by default — backing out adds nothing.
 */
class MyCardWidgetConfigureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyCardWidgetConfigureBinding
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var cards: List<QrCard> = emptyList()
    private var count = 1
    private val countButtons = mutableListOf<MaterialButton>()
    private val spinners = mutableListOf<Spinner>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyCardWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setResult(RESULT_CANCELED)

        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        cards = CardRepository(this).list()
        if (cards.isEmpty()) {
            binding.countSection.visibility = View.GONE
            binding.cardsSection.visibility = View.GONE
            binding.saveButton.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            return
        }

        buildCountRow()
        buildSpinners()
        binding.saveButton.setOnClickListener { save() }
    }

    private fun buildCountRow() {
        val density = resources.displayMetrics.density
        binding.countRow.removeAllViews()
        countButtons.clear()
        for (n in 1..MyCardWidgetProvider.MAX_BUTTONS) {
            MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = n.toString()
                isCheckable = true
                isChecked = n == count
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (n > 1) marginStart = (8 * density).toInt()
                }
                setOnClickListener {
                    count = n
                    countButtons.forEachIndexed { index, btn ->
                        btn.isChecked = index + 1 == count
                    }
                    updateSpinnerVisibility()
                }
            }.also {
                binding.countRow.addView(it)
                countButtons.add(it)
            }
        }
    }

    private fun buildSpinners() {
        val density = resources.displayMetrics.density
        val names = cards.map { it.name }
        binding.cardPickers.removeAllViews()
        spinners.clear()
        for (index in 0 until MyCardWidgetProvider.MAX_BUTTONS) {
            Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MyCardWidgetConfigureActivity,
                    android.R.layout.simple_spinner_item,
                    names,
                ).also {
                    it.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) topMargin = (8 * density).toInt()
                }
                contentDescription = getString(
                    R.string.widget_card_for_button, index + 1
                )
                // Default each button to a different card where possible.
                setSelection(index % cards.size)
                visibility = if (index < count) View.VISIBLE else View.GONE
            }.also {
                binding.cardPickers.addView(it)
                spinners.add(it)
            }
        }
    }

    private fun updateSpinnerVisibility() {
        spinners.forEachIndexed { index, spinner ->
            spinner.visibility = if (index < count) View.VISIBLE else View.GONE
        }
    }

    private fun save() {
        val cardIds = (0 until count).map { index ->
            cards.getOrNull(spinners[index].selectedItemPosition)?.id
        }
        MyCardWidgetProvider.saveConfig(this, widgetId, cardIds)
        MyCardWidgetProvider.update(
            this, AppWidgetManager.getInstance(this), widgetId
        )
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        )
        finish()
    }
}
