package ca.derickcampbell.qrcards

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import ca.derickcampbell.qrcards.data.CardRepository
import ca.derickcampbell.qrcards.databinding.ActivityCardEditBinding
import ca.derickcampbell.qrcards.model.CardType
import ca.derickcampbell.qrcards.model.FieldOption
import ca.derickcampbell.qrcards.model.QrCard
import ca.derickcampbell.qrcards.payload.CardPayloads
import ca.derickcampbell.qrcards.ui.typeLabel
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Create/edit a card.
 *
 * The card type is picked from icon tabs: the five everyday types are always
 * visible and the rarer four (note, email, phone, text message) sit behind a
 * "More" tab that expands the row in place. On wide screens (tablets,
 * landscape) all nine tabs are shown with no "More" needed. A fixed-height
 * header names the selected type so the tabs never jump around.
 *
 * Contact cards can import from the system contact picker (no contacts
 * permission needed — the user hands us one contact). When the contact has
 * several phone numbers or emails, every value is kept on the card and the
 * user switches the active one with the dropdown chevron; the QR payload
 * only ever carries the selected value.
 *
 * On save the payload is built with [CardPayloads] — never hand-rolled — and
 * the raw inputs are kept in [QrCard.fields] (plus [QrCard.fieldOptions] for
 * the multi-value lists) so editing can pre-fill. Passwords are stored
 * untrimmed (leading/trailing spaces are legal in SSIDs/passwords).
 *
 * Validation reuses CardPayloads' require() messages and shows them inline on
 * the offending field where possible.
 */
class CardEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "card_id"
    }

    private lateinit var binding: ActivityCardEditBinding
    private lateinit var repository: CardRepository
    private var editingCard: QrCard? = null
    private var currentType: CardType = CardType.URL

    // Card-type tabs.
    private val primaryTypes = listOf(
        CardType.URL, CardType.CONTACT, CardType.WIFI,
        CardType.LOCATION, CardType.CALENDAR_EVENT,
    )
    private val nicheTypes = listOf(
        CardType.TEXT, CardType.EMAIL, CardType.PHONE, CardType.SMS,
    )
    private var typesExpanded = false

    // Multi-value field state (contact phone/email): every known value plus
    // which one is currently selected. Keyed by field key ("phone", "email").
    private val optionLists = mutableMapOf<String, MutableList<FieldOption>>()
    private val optionSelection = mutableMapOf<String, Int>()

    // Dynamic form state, rebuilt whenever the type changes.
    private val fieldLayouts = mutableMapOf<String, TextInputLayout>()
    private var hiddenCheck: MaterialCheckBox? = null
    private val dateTimeValues = mutableMapOf<String, LocalDateTime>()
    private val dateTimeButtons = mutableMapOf<String, MaterialButton>()

    private val colorOptions: List<Int?> = listOf(
        null,
        0xFFE53935.toInt(), // red
        0xFFFB8C00.toInt(), // orange
        0xFFFDD835.toInt(), // yellow
        0xFF43A047.toInt(), // green
        0xFF1E88E5.toInt(), // blue
        0xFF8E24AA.toInt(), // purple
    )
    private var selectedColor: Int? = null
    private val colorDots = mutableListOf<ImageView>()

    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) importContact(uri)
                else Toast.makeText(this, R.string.contact_import_failed, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = CardRepository(this)
        typesExpanded = savedInstanceState?.getBoolean("typesExpanded") == true

        val id = intent.getStringExtra(EXTRA_CARD_ID)
        editingCard = id?.let { repository.get(it) }
        if (id != null && editingCard == null) {
            Toast.makeText(this, R.string.card_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.title = getString(
            if (editingCard == null) R.string.new_card else R.string.edit_card
        )
        binding.toolbar.setNavigationOnClickListener { finish() }

        val card = editingCard
        currentType = card?.type ?: CardType.URL
        card?.fieldOptions?.forEach { (key, options) ->
            optionLists[key] = options.toMutableList()
        }
        binding.cardNameInput.setText(card?.name.orEmpty())
        selectedColor = card?.labelColor
        binding.sensitiveCheck.isChecked = card?.sensitive == true

        refreshTypeHeader()
        refreshTypeTabs()
        // Re-measure once laid out: tablets and landscape get all nine tabs.
        binding.typeTabRow.post { refreshTypeTabs() }

        buildForm(prefill = card?.fields)
        buildColorRow()
        binding.saveButton.setOnClickListener { save() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("typesExpanded", typesExpanded)
    }

    // -- card-type tabs --

    private fun refreshTypeHeader() {
        binding.typeTitle.text = typeLabel(currentType, this)
        binding.typeDesc.text = typeDesc(currentType)
    }

    private fun typeDesc(type: CardType): String = when (type) {
        CardType.URL -> getString(R.string.type_desc_url)
        CardType.CONTACT -> getString(R.string.type_desc_contact)
        CardType.WIFI -> getString(R.string.type_desc_wifi)
        CardType.LOCATION -> getString(R.string.type_desc_location)
        CardType.TEXT -> getString(R.string.type_desc_text)
        CardType.EMAIL -> getString(R.string.type_desc_email)
        CardType.PHONE -> getString(R.string.type_desc_phone)
        CardType.SMS -> getString(R.string.type_desc_sms)
        CardType.CALENDAR_EVENT -> getString(R.string.type_desc_calendar_event)
    }

    private fun typeIcon(type: CardType): Int = when (type) {
        CardType.URL -> R.drawable.ic_type_link
        CardType.CONTACT -> R.drawable.ic_type_contact
        CardType.WIFI -> R.drawable.ic_type_wifi
        CardType.LOCATION -> R.drawable.ic_type_location
        CardType.TEXT -> R.drawable.ic_type_text
        CardType.EMAIL -> R.drawable.ic_type_email
        CardType.PHONE -> R.drawable.ic_type_phone
        CardType.SMS -> R.drawable.ic_type_sms
        CardType.CALENDAR_EVENT -> R.drawable.ic_type_event
    }

    /**
     * Wide screens (tablets, landscape) fit all nine tabs; narrow screens
     * show the five everyday types plus a More/Less expander.
     */
    private fun refreshTypeTabs() {
        val row = binding.typeTabRow
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val wide = row.width >= (9 * 64 * density).toInt()
        if (wide) {
            CardType.values().forEach { addTypeTab(it, weighted = true) }
        } else if (typesExpanded || currentType in nicheTypes) {
            typesExpanded = true
            CardType.values().forEach { addTypeTab(it, weighted = false) }
            addMoreLessTab(expanded = true)
        } else {
            primaryTypes.forEach { addTypeTab(it, weighted = true) }
            addMoreLessTab(expanded = false)
        }
    }

    private fun addTypeTab(type: CardType, weighted: Boolean) {
        val density = resources.displayMetrics.density
        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            minimumHeight = (48 * density).toInt()
            layoutParams = if (weighted) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            } else {
                LinearLayout.LayoutParams(
                    (64 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            isClickable = true
            isFocusable = true
            contentDescription = typeLabel(type, this@CardEditActivity)
        }
        val icon = ImageView(this).apply {
            setImageResource(typeIcon(type))
            layoutParams = LinearLayout.LayoutParams(
                (24 * density).toInt(), (24 * density).toInt()
            )
        }
        val label = TextView(this).apply {
            text = typeLabel(type, this@CardEditActivity)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            gravity = Gravity.CENTER
        }
        tab.addView(icon)
        tab.addView(label)
        styleTab(tab, icon, label, selected = type == currentType)
        tab.setOnClickListener {
            if (currentType != type) {
                currentType = type
                onTypeChanged()
            }
        }
        binding.typeTabRow.addView(tab)
    }

    private fun addMoreLessTab(expanded: Boolean) {
        val density = resources.displayMetrics.density
        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            minimumHeight = (48 * density).toInt()
            layoutParams = if (expanded) {
                LinearLayout.LayoutParams(
                    (64 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(if (expanded) R.drawable.ic_less else R.drawable.ic_more)
            layoutParams = LinearLayout.LayoutParams(
                (24 * density).toInt(), (24 * density).toInt()
            )
        }
        val label = TextView(this).apply {
            text = getString(if (expanded) R.string.fewer_types else R.string.more_types)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            gravity = Gravity.CENTER
        }
        tab.addView(icon)
        tab.addView(label)
        styleTab(tab, icon, label, selected = false)
        tab.contentDescription = label.text
        tab.setOnClickListener {
            if (expanded) {
                // Never strand the user on a hidden tab.
                if (currentType in nicheTypes) currentType = CardType.URL
                typesExpanded = false
            } else {
                typesExpanded = true
            }
            onTypeChanged()
        }
        binding.typeTabRow.addView(tab)
    }

    private fun styleTab(
        tab: LinearLayout, icon: ImageView, label: TextView, selected: Boolean,
    ) {
        tab.background = if (selected) getDrawable(R.drawable.tab_selected) else null
        val colorAttr = if (selected) {
            com.google.android.material.R.attr.colorOnPrimaryContainer
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        val color = MaterialColors.getColor(this, colorAttr, Color.GRAY)
        icon.imageTintList = ColorStateList.valueOf(color)
        label.setTextColor(color)
    }

    private fun onTypeChanged() {
        refreshTypeHeader()
        refreshTypeTabs()
        buildForm(prefill = null)
    }

    // -- dynamic form --

    private fun buildForm(prefill: Map<String, String>?) {
        binding.formContainer.removeAllViews()
        fieldLayouts.clear()
        hiddenCheck = null
        dateTimeValues.clear()
        dateTimeButtons.clear()
        binding.wifiNote.visibility =
            if (currentType == CardType.WIFI) View.VISIBLE else View.GONE

        fun f(key: String) = prefill?.get(key)

        when (currentType) {
            CardType.URL -> addTextField(
                "url", getString(R.string.field_url), f("url"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )
            CardType.CONTACT -> {
                addImportButton()
                addTextField("firstName", getString(R.string.field_first_name), f("firstName"))
                addTextField("lastName", getString(R.string.field_last_name), f("lastName"))
                addTextField("organization", getString(R.string.field_organization), f("organization"))
                addOptionField(
                    "phone", getString(R.string.field_phone), f("phone"),
                    InputType.TYPE_CLASS_PHONE
                )
                addOptionField(
                    "email", getString(R.string.field_email), f("email"),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                )
                addTextField(
                    "website", getString(R.string.field_website), f("website"),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                )
            }
            CardType.WIFI -> {
                addTextField("ssid", getString(R.string.field_ssid), f("ssid"))
                // Never trim passwords: leading/trailing spaces are legal.
                addTextField(
                    "password", getString(R.string.field_password), f("password"),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                hiddenCheck = addCheckBox(
                    getString(R.string.field_hidden), f("hidden") == "true"
                )
            }
            CardType.LOCATION -> {
                val numberInput = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
                addTextField("latitude", getString(R.string.field_latitude), f("latitude"), numberInput)
                addTextField("longitude", getString(R.string.field_longitude), f("longitude"), numberInput)
            }
            CardType.TEXT -> addTextField(
                "content", getString(R.string.field_text), f("content"),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, lines = 4
            )
            CardType.EMAIL -> {
                addTextField(
                    "address", getString(R.string.field_email_address), f("address"),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                )
                addTextField("subject", getString(R.string.field_subject), f("subject"))
                addTextField(
                    "body", getString(R.string.field_body), f("body"),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, lines = 3
                )
            }
            CardType.PHONE -> addTextField(
                "number", getString(R.string.field_number), f("number"),
                InputType.TYPE_CLASS_PHONE
            )
            CardType.SMS -> {
                addTextField(
                    "number", getString(R.string.field_number), f("number"),
                    InputType.TYPE_CLASS_PHONE
                )
                addTextField(
                    "message", getString(R.string.field_message), f("message"),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, lines = 3
                )
            }
            CardType.CALENDAR_EVENT -> {
                addTextField("title", getString(R.string.field_title), f("title"))
                addDateTimeField("start", getString(R.string.field_starts), f("start"))
                addDateTimeField("end", getString(R.string.field_ends), f("end"))
                addTextField("location", getString(R.string.field_location), f("location"))
            }
        }
    }

    private fun addTextField(
        key: String,
        hint: String,
        prefill: String?,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        lines: Int = 1,
    ): TextInputEditText {
        val density = resources.displayMetrics.density
        val til = TextInputLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(til.context).apply {
            this.inputType = inputType
            setLines(lines)
            if (lines > 1) gravity = Gravity.TOP or Gravity.START
            setText(prefill.orEmpty())
        }
        til.addView(edit)
        binding.formContainer.addView(til)
        fieldLayouts[key] = til
        edit.doOnTextChanged { _, _, _, _ -> til.error = null }
        return edit
    }

    /**
     * A text field whose value can be switched between several known options
     * (contact phone numbers / emails). The dropdown chevron only appears when
     * there is more than one option; the field stays freely editable and a
     * manual edit updates the selected option on save.
     */
    private fun addOptionField(
        key: String,
        label: String,
        prefill: String?,
        inputType: Int,
    ): TextInputEditText {
        val density = resources.displayMetrics.density
        val til = TextInputLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(til.context).apply {
            this.inputType = inputType
            setText(currentOptionValue(key, prefill))
        }
        til.addView(edit)
        if ((optionLists[key]?.size ?: 0) > 1) {
            til.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            til.setEndIconOnClickListener { showOptionPicker(key, label) }
        }
        binding.formContainer.addView(til)
        fieldLayouts[key] = til
        edit.doOnTextChanged { _, _, _, _ -> til.error = null }
        return edit
    }

    private fun currentOptionValue(key: String, prefill: String?): String {
        val options = optionLists[key] ?: return prefill.orEmpty()
        var selection = optionSelection[key]
        if (selection == null) {
            selection = options.indexOfFirst { it.value == prefill }.takeIf { it >= 0 } ?: 0
            optionSelection[key] = selection
        }
        return options.getOrNull(selection)?.value ?: prefill.orEmpty()
    }

    private fun showOptionPicker(key: String, label: String) {
        val options = optionLists[key] ?: return
        if (options.size <= 1) return
        val items = options.map { "${it.label} · ${it.value}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setSingleChoiceItems(items, optionSelection[key] ?: 0) { dialog, which ->
                optionSelection[key] = which
                fieldLayouts[key]?.editText?.setText(options[which].value)
                dialog.dismiss()
            }
            .show()
    }

    private fun addImportButton() {
        val density = resources.displayMetrics.density
        MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.import_from_contacts)
            setIconResource(R.drawable.ic_person)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                pickContactLauncher.launch(
                    Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
                )
            }
        }.also { binding.formContainer.addView(it) }
    }

    // -- contact import --

    /**
     * Reads the contact the user picked. The system picker grants us one-time
     * access to just this contact, so no READ_CONTACTS permission is needed.
     * Every phone number and email is kept (with its label); the primary one
     * becomes the selected value.
     */
    private fun importContact(contactUri: Uri) {
        try {
            val cr = contentResolver
            cr.query(contactUri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return
                val contactId =
                    c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val displayName =
                    c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)).orEmpty()
                val (firstName, lastName) = splitName(displayName)

                val phones = readContactOptions(
                    cr, contactId,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ) { type, label ->
                    ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, type, label)
                        .toString()
                }
                val emails = readContactOptions(
                    cr, contactId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.LABEL,
                ) { type, label ->
                    ContactsContract.CommonDataKinds.Email.getTypeLabel(resources, type, label)
                        .toString()
                }
                val organization = readContactSingle(
                    cr, contactId,
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Organization.COMPANY,
                )
                val website = readContactSingle(
                    cr, contactId,
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Website.URL,
                )

                if (phones.isNotEmpty()) {
                    optionLists["phone"] = phones.toMutableList()
                    optionSelection["phone"] = 0
                }
                if (emails.isNotEmpty()) {
                    optionLists["email"] = emails.toMutableList()
                    optionSelection["email"] = 0
                }
                buildForm(
                    prefill = mapOf(
                        "firstName" to firstName,
                        "lastName" to lastName,
                        "organization" to organization,
                        "phone" to phones.firstOrNull()?.value.orEmpty(),
                        "email" to emails.firstOrNull()?.value.orEmpty(),
                        "website" to website,
                    )
                )
            } ?: throw IllegalArgumentException("empty contact cursor")
        } catch (_: Exception) {
            Toast.makeText(this, R.string.contact_import_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun readContactOptions(
        cr: android.content.ContentResolver,
        contactId: String,
        mimeType: String,
        valueColumn: String,
        typeColumn: String,
        labelColumn: String,
        labelFor: (Int, CharSequence?) -> String,
    ): List<FieldOption> {
        data class Ranked(val label: String, val value: String, val rank: Int)
        val out = mutableListOf<Ranked>()
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            null,
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId, mimeType),
            null,
        )?.use { cur ->
            val vIdx = cur.getColumnIndexOrThrow(valueColumn)
            val tIdx = cur.getColumnIndex(typeColumn)
            val lIdx = cur.getColumnIndex(labelColumn)
            val spIdx = cur.getColumnIndex(ContactsContract.Data.IS_SUPER_PRIMARY)
            val pIdx = cur.getColumnIndex(ContactsContract.Data.IS_PRIMARY)
            while (cur.moveToNext()) {
                val value = cur.getString(vIdx).orEmpty().trim()
                if (value.isEmpty()) continue
                val type = if (tIdx >= 0) cur.getInt(tIdx) else 0
                val custom = if (lIdx >= 0) cur.getString(lIdx) else null
                val rank = (if (spIdx >= 0 && cur.getInt(spIdx) != 0) 2 else 0) +
                    (if (pIdx >= 0 && cur.getInt(pIdx) != 0) 1 else 0)
                out += Ranked(labelFor(type, custom), value, rank)
            }
        }
        return out.sortedByDescending { it.rank }.map { FieldOption(it.label, it.value) }
    }

    private fun readContactSingle(
        cr: android.content.ContentResolver,
        contactId: String,
        mimeType: String,
        column: String,
    ): String {
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(column),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId, mimeType),
            null,
        )?.use { cur ->
            if (cur.moveToFirst()) return cur.getString(0).orEmpty().trim()
        }
        return ""
    }

    private fun splitName(displayName: String): Pair<String, String> {
        val parts = displayName.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "" to ""
            parts.size == 1 -> parts[0] to ""
            else -> parts.dropLast(1).joinToString(" ") to parts.last()
        }
    }

    private fun addCheckBox(text: String, checked: Boolean): MaterialCheckBox =
        MaterialCheckBox(this).apply {
            this.text = text
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
            binding.formContainer.addView(this)
        }

    private fun addDateTimeField(key: String, label: String, prefillIso: String?) {
        val initial = prefillIso?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?: defaultDateTime(key)
        dateTimeValues[key] = initial

        val density = resources.displayMetrics.density
        val labelView = TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        }
        val button = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
            setOnClickListener { pickDateTime(key) }
        }
        binding.formContainer.addView(labelView)
        binding.formContainer.addView(button)
        dateTimeButtons[key] = button
        refreshDateTimeButton(key)
    }

    private fun defaultDateTime(key: String): LocalDateTime {
        val base = LocalDateTime.now().plusHours(1)
            .withMinute(0).withSecond(0).withNano(0)
        return if (key == "end") base.plusHours(1) else base
    }

    private fun refreshDateTimeButton(key: String) {
        val dt = dateTimeValues[key] ?: return
        dateTimeButtons[key]?.text =
            dt.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy  h:mm a"))
    }

    private fun pickDateTime(key: String) {
        val current = dateTimeValues[key] ?: LocalDateTime.now()
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setSelection(
                current.toLocalDate()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            )
            .build()
        datePicker.addOnPositiveButtonClickListener { millis ->
            val date = Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(current.hour)
                .setMinute(current.minute)
                .build()
            timePicker.addOnPositiveButtonClickListener {
                dateTimeValues[key] =
                    LocalDateTime.of(date, LocalTime.of(timePicker.hour, timePicker.minute))
                refreshDateTimeButton(key)
            }
            timePicker.show(supportFragmentManager, "time_$key")
        }
        datePicker.show(supportFragmentManager, "date_$key")
    }

    // -- label color --

    private fun buildColorRow() {
        binding.colorRow.removeAllViews()
        colorDots.clear()
        val density = resources.displayMetrics.density
        val size = (40 * density).toInt()
        val margin = (8 * density).toInt()
        // Padding shrinks the dot image inside its view so the selection ring
        // (drawn as the view background) stays visible around it instead of
        // hiding behind the opaque dot.
        val pad = (5 * density).toInt()
        colorOptions.forEach { color ->
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                    .apply { setMargins(margin, 0, margin, 0) }
                setPadding(pad, pad, pad, pad)
                setImageResource(if (color == null) R.drawable.dot_none else R.drawable.dot)
                if (color != null) imageTintList = ColorStateList.valueOf(color)
                contentDescription =
                    if (color == null) getString(R.string.color_none) else null
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedColor = color
                    refreshColorSelection()
                }
            }
            binding.colorRow.addView(dot)
            colorDots.add(dot)
        }
        refreshColorSelection()
    }

    private fun refreshColorSelection() {
        colorDots.forEachIndexed { index, dot ->
            dot.background = if (colorOptions[index] == selectedColor) {
                getDrawable(R.drawable.dot_ring)
            } else {
                null
            }
        }
    }

    // -- save --

    private fun save() {
        clearErrors()
        val name = binding.cardNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.cardNameLayout.error = getString(R.string.name_required)
            binding.cardNameInput.requestFocus()
            return
        }
        val fields = collectFields()
        val payload = try {
            buildPayload(currentType, fields)
        } catch (e: Exception) {
            showPayloadError(e.message)
            return
        }
        val existing = editingCard
        val card = QrCard(
            id = existing?.id.orEmpty(), // blank → repository generates one
            name = name,
            type = currentType,
            payload = payload,
            fields = fields,
            fieldOptions = collectFieldOptions(fields),
            labelColor = selectedColor,
            sensitive = binding.sensitiveCheck.isChecked
        )
        repository.save(card)
        Toast.makeText(this, R.string.card_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun collectFields(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        fieldLayouts.forEach { (key, til) ->
            // Deliberately untrimmed: spaces are legal in SSIDs/passwords.
            map[key] = til.editText?.text?.toString().orEmpty()
        }
        hiddenCheck?.let { map["hidden"] = it.isChecked.toString() }
        dateTimeValues.forEach { (key, dt) ->
            map[key] = dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }
        return map
    }

    /**
     * The full option lists for multi-value fields. Only contact cards carry
     * them; a manual edit to the visible text updates the selected option so
     * the list never goes stale.
     */
    private fun collectFieldOptions(fields: Map<String, String>): Map<String, List<FieldOption>> {
        if (currentType != CardType.CONTACT) return emptyMap()
        return optionLists.mapValues { (key, options) ->
            val selection = optionSelection[key] ?: 0
            val currentText = fields[key].orEmpty()
            options.mapIndexed { index, option ->
                if (index == selection) option.copy(value = currentText) else option
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun buildPayload(type: CardType, f: Map<String, String>): String {
        fun v(key: String) = f[key].orEmpty()
        return when (type) {
            CardType.URL -> CardPayloads.url(v("url"))
            CardType.CONTACT -> CardPayloads.contact(
                firstName = v("firstName"),
                lastName = v("lastName"),
                organization = v("organization"),
                phone = v("phone"),
                email = v("email"),
                website = v("website")
            )
            CardType.WIFI -> CardPayloads.wifi(
                ssid = v("ssid"),
                password = v("password"),
                hidden = v("hidden") == "true"
            )
            CardType.LOCATION -> {
                val lat = v("latitude").toDoubleOrNull()
                val lon = v("longitude").toDoubleOrNull()
                if (lat == null) fieldLayouts["latitude"]?.error =
                    getString(R.string.invalid_number)
                if (lon == null) fieldLayouts["longitude"]?.error =
                    getString(R.string.invalid_number)
                if (lat == null || lon == null) {
                    throw IllegalArgumentException(getString(R.string.invalid_number))
                }
                CardPayloads.location(lat, lon)
            }
            CardType.TEXT -> CardPayloads.text(v("content"))
            CardType.EMAIL -> CardPayloads.email(
                address = v("address"),
                subject = v("subject"),
                body = v("body")
            )
            CardType.PHONE -> CardPayloads.phone(v("number"))
            CardType.SMS -> CardPayloads.sms(number = v("number"), message = v("message"))
            CardType.CALENDAR_EVENT -> CardPayloads.calendarEvent(
                summary = v("title"),
                start = checkNotNull(dateTimeValues["start"]) { "Start time missing" },
                end = checkNotNull(dateTimeValues["end"]) { "End time missing" },
                location = v("location")
            )
        }
    }

    private fun showPayloadError(message: String?) {
        val msg = message.orEmpty()
        // invalid_number was already set inline on the offending field(s).
        if (msg == getString(R.string.invalid_number)) return
        val fieldKey: String? = when {
            msg.contains("URL", ignoreCase = true) -> "url"
            msg.contains("SSID") -> "ssid"
            msg.contains("Contact needs a name") -> "firstName"
            msg.contains("Latitude") -> "latitude"
            msg.contains("Longitude") -> "longitude"
            msg.contains("Text must not be empty") -> "content"
            msg.contains("Email address") -> "address"
            msg.contains("Phone number") -> "number"
            msg.contains("Event needs a title") -> "title"
            else -> null // e.g. "Event end must not be before start" → snackbar
        }
        val layout = fieldKey?.let { fieldLayouts[it] }
        if (layout != null) {
            layout.error = msg
            layout.editText?.requestFocus()
        } else {
            Snackbar.make(
                binding.root,
                msg.ifEmpty { getString(R.string.save_failed) },
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun clearErrors() {
        binding.cardNameLayout.error = null
        fieldLayouts.values.forEach { it.error = null }
    }
}
