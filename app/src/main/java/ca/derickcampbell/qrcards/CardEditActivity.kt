package ca.derickcampbell.qrcards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.ContextThemeWrapper
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
import ca.derickcampbell.qrcards.ui.HsvColorPickerDialog
import ca.derickcampbell.qrcards.ui.typeLabel
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
 * Layout order: the type title sits at the top of the page, the icon-only
 * type tabs sit below it, and the card name follows — so the card name and
 * all fillable fields stay together instead of being split apart. There is
 * no back arrow (in some UIs it implies "save"); explicit Cancel and Save
 * buttons are pinned to the bottom of the screen.
 *
 * The strip holds all nine type tabs and scrolls horizontally; a thick
 * arrow button fixed at the right — always snug against the strip — slides
 * it to the rarer types (note, email, phone, text message) and back. Tabs
 * are icon-only (the header names the selected type, so tapping one
 * identifies it immediately) and share one fixed size, so nothing jumps or
 * rebuilds when the strip scrolls. A fixed-height header names the selected
 * type so the tabs never jump around.
 *
 * Everything the user types is stashed per card type before switching, so
 * moving between types — or rotating the phone — never loses data, and
 * types that share a field keep each other's entries. Cancel asks for
 * confirmation when there are unsaved edits.
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
        /** Share-sheet entry: pre-selected type name ([CardType.name]). */
        const val EXTRA_SHARE_TYPE = "share_type"
        /** Share-sheet entry: prefill fields for the type. */
        const val EXTRA_SHARE_FIELDS = "share_fields"
        /** Six across: five type tabs beside the fixed arrow button. */
        private const val TAB_SLOTS = 6
    }

    private lateinit var binding: ActivityCardEditBinding
    private lateinit var repository: CardRepository
    private var editingCard: QrCard? = null
    private var currentType: CardType = CardType.CONTACT

    // Card-type tabs: one scrolling strip. tabSlotPx is measured after
    // layout; pendingTabScrollX restores the strip position on rotation.
    private var tabSlotPx = 0
    private var pendingTabScrollX = -1

    // Everything the user typed, kept per card type so switching types (or
    // rotating) never loses data. collectFields() already captures text
    // fields, the hidden-network checkbox, and the date/time pickers.
    private val typeFieldCache = mutableMapOf<CardType, MutableMap<String, String>>()

    // True once the user has edited anything (typed, picked, or imported),
    // so Cancel can confirm before discarding. Prefills and programmatic
    // setText calls never set it — only real user actions do.
    private var formDirty = false

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
        0xFF000000.toInt(), // black
        0xFFE53935.toInt(), // red
        0xFFFB8C00.toInt(), // orange
        0xFFFDD835.toInt(), // yellow
        0xFF43A047.toInt(), // green
        0xFF1E88E5.toInt(), // blue
        0xFF8E24AA.toInt(), // purple
    )
    private var selectedColor: Int? = null
    private val colorDots = mutableListOf<ImageView>()

    /**
     * QR module color. Dark-only palette: light QR modules don't scan
     * reliably. The "none" dot is default black; the plus dot opens a
     * visual color picker (any color, user's responsibility to keep it
     * dark).
     */
    private val qrColorOptions: List<Int?> = listOf(
        null,
        0xFF0B6E4F.toInt(), // teal
        0xFF1A237E.toInt(), // navy
        0xFF0D47A1.toInt(), // blue
        0xFF1B5E20.toInt(), // green
        0xFF4A148C.toInt(), // purple
        0xFFB71C1C.toInt(), // red
        0xFF3E2723.toInt(), // brown
        0xFF424242.toInt(), // gray
    )
    private var selectedQrColor: Int? = null
    private val qrColorDots = mutableListOf<ImageView>()

    /**
     * Social profile templates: URL cards with a per-service prefix and a
     * "service" field tag so the list/detail can hint at the service.
     */
    private data class SocialTemplate(
        val id: String,
        val displayName: String,
        val prefix: String,
        val icon: Int,
    )

    private val socialTemplates = listOf(
        SocialTemplate("spotify", "Spotify", "https://open.spotify.com/", R.drawable.ic_social_spotify),
        SocialTemplate("instagram", "Instagram", "https://www.instagram.com/", R.drawable.ic_social_instagram),
        SocialTemplate("whatsapp", "WhatsApp", "https://wa.me/", R.drawable.ic_social_whatsapp),
        SocialTemplate("linkedin", "LinkedIn", "https://www.linkedin.com/in/", R.drawable.ic_social_linkedin),
    )
    private var serviceTemplate: String? = null

    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Fresh import attempt: the permission fallback hasn't been used.
            contactsPermissionAsked = false
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) importContact(uri)
                else Toast.makeText(this, R.string.contact_import_failed, Toast.LENGTH_SHORT).show()
            }
        }

    // The system picker grants one-time access to the picked contact, which
    // is enough on most devices — but when the grant doesn't cover the
    // contact's data rows, fall back to asking for the contacts permission
    // once and retry. The app stays permission-free until Import is used.
    //
    // contactsPermissionAsked guards the retry: a repeat SecurityException
    // after the grant is a genuine failure, never another permission
    // request — the permission is not re-requested in a loop.
    private var pendingContactUri: Uri? = null
    private var contactsPermissionAsked = false
    /** Full text of the last import failure, shown in the on-page error card. */
    private var lastImportError: String? = null
    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val uri = pendingContactUri
            pendingContactUri = null
            if (granted && uri != null) {
                // contactsPermissionAsked stays true: if the retry still
                // can't read, that's a genuine failure, not a re-request.
                importContact(uri)
            } else {
                contactsPermissionAsked = false
                if (!granted) {
                    Toast.makeText(this, R.string.contact_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    // Current-location lookup asks for the location permission only when the
    // button is tapped; the app is otherwise fully permission-free.
    private var locationListener: android.location.LocationListener? = null
    /** The "Current location" button, while it's on screen — for busy state. */
    private var locationButton: MaterialButton? = null
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) fetchCurrentLocation()
            else Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show()
        }

    override fun onDestroy() {
        locationListener?.let { listener ->
            runCatching {
                getSystemService(android.location.LocationManager::class.java)
                    ?.removeUpdates(listener)
            }
            locationListener = null
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {        super.onCreate(savedInstanceState)
        binding = ActivityCardEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = CardRepository(this)

        val id = intent.getStringExtra(EXTRA_CARD_ID)
        editingCard = id?.let { repository.get(it) }
        if (id != null && editingCard == null) {
            Toast.makeText(this, R.string.card_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.toolbar.title = ""

        val card = editingCard
        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        } else {
            // Share-sheet entry: the sniffed type is pre-selected and its
            // fields pre-filled — the user still confirms before saving.
            val shareType = intent.getStringExtra(EXTRA_SHARE_TYPE)
                ?.let { runCatching { CardType.valueOf(it) }.getOrNull() }
            @Suppress("UNCHECKED_CAST")
            val shareFields =
                intent.getSerializableExtra(EXTRA_SHARE_FIELDS) as? HashMap<String, String>
            if (card == null && shareType != null) {
                currentType = shareType
                val fields = HashMap(shareFields ?: emptyMap())
                // A shared vCard carries the contact's name for the card
                // name field; it isn't an editor field, so lift it out.
                val sharedName = fields.remove("name")
                typeFieldCache[shareType] = fields
                if (!sharedName.isNullOrBlank()) binding.cardNameInput.setText(sharedName)
            } else {
                // New cards default to Contact — the most common card.
                currentType = card?.type ?: CardType.CONTACT
            }
            card?.fieldOptions?.forEach { (key, options) ->
                optionLists[key] = options.toMutableList()
            }
            binding.cardNameInput.setText(card?.name.orEmpty())
            selectedColor = card?.labelColor
            selectedQrColor = card?.qrColor
            serviceTemplate = card?.takeIf { it.type == CardType.URL }?.fields?.get("service")
            binding.sensitiveCheck.isChecked = card?.sensitive == true
        }

        refreshTypeHeader()
        buildTypeTabs()
        binding.typeMoreButton.setOnClickListener { onArrowClicked() }
        binding.typeTabStrip.setOnScrollChangeListener { _, _, _, _, _ -> syncArrowIcon() }

        buildForm(currentType, prefillFor(currentType))
        buildColorRow()
        buildQrColorRow()
        binding.cancelButton.setOnClickListener { onCancel() }
        binding.saveButton.setOnClickListener { save() }
        // Registered last so prefills and restores never mark the form dirty.
        binding.cardNameInput.doOnTextChanged { _, _, _, _ -> formDirty = true }
        binding.sensitiveCheck.setOnCheckedChangeListener { _, _ -> formDirty = true }
        // Import diagnostics: the error card is hidden until an import fails.
        binding.importErrorHeader.setOnClickListener { toggleImportErrorDetail() }
        binding.importErrorCopy.setOnClickListener { copyImportError() }
        binding.importErrorDismiss.setOnClickListener {
            lastImportError = null
            refreshImportErrorCard()
        }
        refreshImportErrorCard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        stashForm()
        outState.putString("currentType", currentType.name)
        outState.putInt("tabScrollX", binding.typeTabStrip.scrollX)
        if (selectedColor != null) outState.putInt("selectedColor", selectedColor!!)
        if (selectedQrColor != null) outState.putInt("selectedQrColor", selectedQrColor!!)
        outState.putString("cardName", binding.cardNameInput.text?.toString().orEmpty())
        outState.putBoolean("sensitive", binding.sensitiveCheck.isChecked)
        outState.putBoolean("formDirty", formDirty)
        outState.putString("lastImportError", lastImportError)
        val cache = HashMap<String, HashMap<String, String>>()
        typeFieldCache.forEach { (type, fields) -> cache[type.name] = HashMap(fields) }
        outState.putSerializable("typeFieldCache", cache)
        val lists = HashMap<String, ArrayList<FieldOption>>()
        optionLists.forEach { (key, options) -> lists[key] = ArrayList(options) }
        outState.putSerializable("optionLists", lists)
        outState.putSerializable("optionSelection", HashMap(optionSelection))
        super.onSaveInstanceState(outState)
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreState(state: Bundle) {
        currentType = runCatching { CardType.valueOf(state.getString("currentType")!!) }
            .getOrDefault(CardType.CONTACT)
        pendingTabScrollX = state.getInt("tabScrollX", -1)
        selectedColor = if (state.containsKey("selectedColor")) state.getInt("selectedColor") else null
        selectedQrColor = if (state.containsKey("selectedQrColor")) state.getInt("selectedQrColor") else null
        binding.cardNameInput.setText(state.getString("cardName").orEmpty())
        binding.sensitiveCheck.isChecked = state.getBoolean("sensitive")
        formDirty = state.getBoolean("formDirty")
        val cache = state.getSerializable("typeFieldCache") as? HashMap<String, HashMap<String, String>>
        cache?.forEach { (typeName, fields) ->
            runCatching { CardType.valueOf(typeName) }.getOrNull()?.let {
                typeFieldCache[it] = fields
            }
        }
        val lists = state.getSerializable("optionLists") as? HashMap<String, ArrayList<FieldOption>>
        lists?.forEach { (key, options) -> optionLists[key] = options.toMutableList() }
        val sel = state.getSerializable("optionSelection") as? HashMap<String, Int>
        sel?.forEach { (key, index) -> optionSelection[key] = index }
        lastImportError = state.getString("lastImportError")
        binding.importErrorText.text = lastImportError.orEmpty()
    }

    // -- form data preservation --

    /** Keep everything the user typed for the current type before leaving it. */
    private fun stashForm() {
        typeFieldCache[currentType] = collectFields().toMutableMap()
    }

    /** Prefill for a type: the user's cached entries first, else the saved card's. */
    private fun prefillFor(type: CardType): Map<String, String>? =
        typeFieldCache[type] ?: editingCard?.takeIf { it.type == type }?.fields

    private fun switchType(newType: CardType) {
        if (newType == currentType) return
        stashForm()
        carrySharedFields(currentType, newType)
        currentType = newType
        refreshTypeHeader()
        refreshTypeTabs()
        buildForm(newType, prefillFor(newType))
    }

    /**
     * The web address is the same idea on links and contacts: when switching
     * between them, carry it over — but only into a blank field, so a value
     * the user typed deliberately is never clobbered.
     */
    private fun carrySharedFields(from: CardType, to: CardType) {
        val fromCache = typeFieldCache[from] ?: return
        val toCache = typeFieldCache.getOrPut(to) { mutableMapOf() }
        if (from == CardType.URL && to == CardType.CONTACT) {
            val url = fromCache["url"].orEmpty()
            if (url.isNotBlank() && toCache["website"].isNullOrBlank()) {
                toCache["website"] = url
            }
        } else if (from == CardType.CONTACT && to == CardType.URL) {
            val site = fromCache["website"].orEmpty()
            if (site.isNotBlank() && toCache["url"].isNullOrBlank()) {
                toCache["url"] = site
            }
        }
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

    /** Example card name, updated whenever the type changes. */
    private fun cardNameHint(type: CardType): String = when (type) {
        CardType.URL -> getString(R.string.card_name_hint_url)
        CardType.CONTACT -> getString(R.string.card_name_hint_contact)
        CardType.WIFI -> getString(R.string.card_name_hint_wifi)
        CardType.LOCATION -> getString(R.string.card_name_hint_location)
        CardType.TEXT -> getString(R.string.card_name_hint_text)
        CardType.EMAIL -> getString(R.string.card_name_hint_email)
        CardType.PHONE -> getString(R.string.card_name_hint_phone)
        CardType.SMS -> getString(R.string.card_name_hint_sms)
        CardType.CALENDAR_EVENT -> getString(R.string.card_name_hint_event)
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
     * One horizontally scrolling row, always. The strip holds all nine tabs
     * at 1/6 of the row width each; the arrow button is fixed at the right,
     * snug against the strip, and slides it to the niche types and back.
     * Tabs never resize or rebuild when the arrow is pressed — the strip
     * just scrolls.
     */
    private fun buildTypeTabs() {
        val row = binding.typeTabRow
        row.removeAllViews()
        CardType.values().forEach { row.addView(makeTypeTab(it)) }
        // Size once the outer row is measured (re-run after layout).
        binding.typeTabRowOuter.post { sizeTabs() }
    }

    /**
     * One icon-only type tab. No label: the header above names the selected
     * type, so tapping a tab identifies it immediately.
     */
    private fun makeTypeTab(type: CardType): LinearLayout {
        val density = resources.displayMetrics.density
        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = (64 * density).toInt()
            val margin = (2 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, 0, margin, 0) }
            isClickable = true
            isFocusable = true
            tag = type
            contentDescription = typeLabel(type, this@CardEditActivity)
        }
        val icon = ImageView(this).apply {
            setImageResource(typeIcon(type))
            layoutParams = LinearLayout.LayoutParams(
                (30 * density).toInt(), (30 * density).toInt()
            )
        }
        tab.addView(icon)
        styleTab(tab, icon, selected = type == currentType)
        // A tab tap must never drop the user out of the editor: contain the
        // unexpected here so a failed switch leaves them in Edit, swapping
        // the field set in place, instead of glitching back to the List.
        tab.setOnClickListener {
            runCatching { switchType(type) }.onFailure { e ->
                android.util.Log.e("CardEditActivity", "Type switch failed", e)
                Toast.makeText(this, R.string.switch_type_failed, Toast.LENGTH_SHORT).show()
            }
        }
        return tab
    }

    /** Re-styles the tabs for the new selection without rebuilding them. */
    private fun refreshTypeTabs() {
        val row = binding.typeTabRow
        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i) as LinearLayout
            val icon = tab.getChildAt(0) as ImageView
            styleTab(tab, icon, selected = tab.tag == currentType)
        }
    }

    /**
     * Sizes every tab to 1/6 of the row (six across: five tabs beside the
     * fixed arrow) and puts the selected type in view. Called after layout.
     */
    private fun sizeTabs() {
        val outer = binding.typeTabRowOuter
        val row = binding.typeTabRow
        val density = resources.displayMetrics.density
        val measured = outer.width
        if (measured == 0) {
            outer.post { sizeTabs() }
            return
        }
        if (row.childCount == 0) return
        val margin = (2 * density).toInt()
        val tabSize = measured / TAB_SLOTS - 2 * margin
        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i)
            (tab.layoutParams as LinearLayout.LayoutParams).width = tabSize
            tab.requestLayout()
        }
        val arrow = binding.typeMoreButton
        (arrow.layoutParams as LinearLayout.LayoutParams).width = tabSize + 2 * margin
        arrow.requestLayout()
        binding.typeMoreIcon.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.GRAY,
            )
        )
        tabSlotPx = tabSize + 2 * margin
        val restored = pendingTabScrollX
        pendingTabScrollX = -1
        if (restored >= 0) {
            binding.typeTabStrip.scrollTo(restored.coerceAtMost(maxScrollX()), 0)
        } else {
            scrollToType(currentType, smooth = false)
        }
        syncArrowIcon()
    }

    /** Slides the strip so the given type is visible. */
    private fun scrollToType(type: CardType, smooth: Boolean) {
        if (tabSlotPx == 0) return
        val index = CardType.values().indexOf(type)
        // Five tabs fit beside the arrow; niche types live at the far end.
        val target = if (index >= TAB_SLOTS - 1) maxScrollX() else 0
        val strip = binding.typeTabStrip
        if (smooth) strip.smoothScrollTo(target, 0) else strip.scrollTo(target, 0)
    }

    private fun maxScrollX(): Int {
        // Analytic: nine tabs, five visible beside the arrow. (Measured
        // widths aren't re-laid-out yet when sizeTabs() runs.)
        if (tabSlotPx > 0) return (CardType.values().size - (TAB_SLOTS - 1)) * tabSlotPx
        val strip = binding.typeTabStrip
        return (binding.typeTabRow.width - strip.width).coerceAtLeast(0)
    }

    /** The arrow slides the strip: right for more types, left to go back. */
    private fun onArrowClicked() {
        val strip = binding.typeTabStrip
        val maxScroll = maxScrollX()
        val slop = (4 * resources.displayMetrics.density).toInt()
        if (strip.scrollX >= maxScroll - slop) strip.smoothScrollTo(0, 0)
        else strip.smoothScrollTo(maxScroll, 0)
    }

    /** Arrow points toward the hidden tabs: right until the end, then left. */
    private fun syncArrowIcon() {
        val strip = binding.typeTabStrip
        val slop = (4 * resources.displayMetrics.density).toInt()
        val atEnd = strip.scrollX >= maxScrollX() - slop
        binding.typeMoreIcon.setImageResource(
            if (atEnd) R.drawable.ic_arrow_left else R.drawable.ic_arrow_right
        )
        binding.typeMoreButton.contentDescription =
            getString(if (atEnd) R.string.fewer_types else R.string.more_types)
    }

    private fun styleTab(tab: LinearLayout, icon: ImageView, selected: Boolean) {
        tab.background = if (selected) getDrawable(R.drawable.tab_selected) else null
        val colorAttr = if (selected) {
            com.google.android.material.R.attr.colorOnPrimaryContainer
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        icon.imageTintList =
            ColorStateList.valueOf(MaterialColors.getColor(this, colorAttr, Color.GRAY))
    }

    // -- location --

    /** "Current location" button above the latitude/longitude fields. */
    private fun addLocationButton() {
        val density = resources.displayMetrics.density
        MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.use_current_location)
            setIconResource(R.drawable.ic_type_location)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener { onCurrentLocationClicked() }
        }.also {
            binding.formContainer.addView(it)
            locationButton = it
        }
    }

    private fun onCurrentLocationClicked() {
        val pm = android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasLocation =
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == pm ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == pm
        if (hasLocation) {
            fetchCurrentLocation()
        } else {
            // Android 12+ wants both requested together; either grant proceeds.
            requestLocationPermission.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    /**
     * Fills latitude/longitude from GPS. Prefers a fix from the last two
     * minutes; otherwise takes one fresh reading, with a 15-second timeout
     * that falls back to any last-known fix.
     */
    private fun fetchCurrentLocation() {
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return
        val pm = android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasLocation =
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == pm ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == pm
        if (!hasLocation) return
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
        fun lastKnown(): android.location.Location? = providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        val recent = lastKnown()
        if (recent != null && System.currentTimeMillis() - recent.time < 120_000) {
            applyLocation(recent.latitude, recent.longitude)
            return
        }
        val provider = providers.firstOrNull()
        if (provider == null) {
            Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.locating, Toast.LENGTH_SHORT).show()
        setLocationButtonBusy(true)
        var done = false
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (done) return
                done = true
                runCatching { lm.removeUpdates(this) }
                locationListener = null
                applyLocation(location.latitude, location.longitude)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        locationListener = listener
        val requested = runCatching {
            lm.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
        }.isSuccess
        if (!requested) {
            locationListener = null
            setLocationButtonBusy(false)
            Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!done) {
                done = true
                runCatching { lm.removeUpdates(listener) }
                locationListener = null
                val fallback = lastKnown()
                if (fallback != null) applyLocation(fallback.latitude, fallback.longitude)
                else {
                    setLocationButtonBusy(false)
                    Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }, 15_000)
    }

    private fun applyLocation(latitude: Double, longitude: Double) {
        // US locale: some locales use a comma as the decimal separator.
        fieldLayouts["latitude"]?.editText
            ?.setText(String.format(java.util.Locale.US, "%.6f", latitude))
        fieldLayouts["longitude"]?.editText
            ?.setText(String.format(java.util.Locale.US, "%.6f", longitude))
        formDirty = true
        setLocationButtonBusy(false)
    }

    /**
     * While a fresh GPS fix is pending, the button says so and can't be
     * re-tapped — previously nothing on screen showed it was still working.
     */
    private fun setLocationButtonBusy(busy: Boolean) {
        locationButton?.apply {
            isEnabled = !busy
            text = getString(
                if (busy) R.string.locating else R.string.use_current_location
            )
        }
    }

    // -- dynamic form --

    private fun buildForm(type: CardType, prefill: Map<String, String>?) {
        binding.formContainer.removeAllViews()
        locationButton = null
        refreshImportErrorCard()
        fieldLayouts.clear()
        hiddenCheck = null
        dateTimeValues.clear()
        dateTimeButtons.clear()
        binding.cardNameLayout.hint = cardNameHint(type)
        binding.wifiNote.visibility =
            if (type == CardType.WIFI) View.VISIBLE else View.GONE

        fun f(key: String) = prefill?.get(key)

        when (type) {
            CardType.URL -> {
                // Social profile templates: one tap pre-fills the service's
                // URL prefix. The card stays a plain URL card; "service" is
                // just a metadata tag derived again on save.
                addSocialTemplates(f("url"))
                val urlField = addTextField(
                    "url", getString(R.string.field_url), f("url"),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                )
                urlField.doOnTextChanged { text, _, _, _ ->
                    serviceTemplate = socialTemplates.firstOrNull {
                        text.toString().startsWith(it.prefix, ignoreCase = true)
                    }?.id
                    refreshTemplateSelection()
                }
            }
            CardType.CONTACT -> {
                addImportButton()
                addTextField("firstName", getString(R.string.field_first_name), f("firstName"))
                addTextField("lastName", getString(R.string.field_last_name), f("lastName"))
                addTextField("organization", getString(R.string.field_organization), f("organization"))
                addTextField("jobTitle", getString(R.string.field_job_title), f("jobTitle"))
                addTextField("address", getString(R.string.field_address), f("address"))
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
                addLocationButton()
                val latField = addTextField(
                    "latitude", getString(R.string.field_latitude), f("latitude")
                )
                addTextField("longitude", getString(R.string.field_longitude), f("longitude"))
                // Pasting "lat, lng" (e.g. copied from Google Maps) splits
                // into both fields.
                var splitting = false
                latField.doOnTextChanged { text, _, _, _ ->
                    if (splitting) return@doOnTextChanged
                    val parts = text?.split(",") ?: return@doOnTextChanged
                    if (parts.size == 2) {
                        val lat = parts[0].trim()
                        val lng = parts[1].trim()
                        if (lat.toDoubleOrNull() != null && lng.toDoubleOrNull() != null) {
                            splitting = true
                            latField.setText(lat)
                            latField.setSelection(lat.length)
                            fieldLayouts["longitude"]?.editText?.setText(lng)
                            splitting = false
                        }
                    }
                }
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
        edit.doOnTextChanged { _, _, _, _ ->
            til.error = null
            formDirty = true
        }
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
    ): MaterialAutoCompleteTextView {
        val density = resources.displayMetrics.density
        val hasOptions = (optionLists[key]?.size ?: 0) > 1
        val til = TextInputLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            // Set BEFORE the EditText is attached: the end icon changes the
            // content padding, and setting it after addView leaves the text
            // inset wrong (it used to crash outright on a plain EditText).
            if (hasOptions) endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        // MaterialAutoCompleteTextView, not TextInputEditText: the exposed
        // dropdown end icon (shown when an import yields 2+ numbers/emails)
        // throws RuntimeException on a plain EditText. It stays freely
        // editable — the dropdown is only opened via our own picker dialog.
        // The theme overlay pins the outlined-box style: the TextInputLayout
        // overlay only styles editTextStyle, so without this the field
        // renders smaller than the plain text fields.
        val edit = MaterialAutoCompleteTextView(
            ContextThemeWrapper(
                til.context,
                R.style.ThemeOverlay_QrCards_AutoCompleteOutlinedBox
            )
        ).apply {
            this.inputType = inputType
            setText(currentOptionValue(key, prefill))
        }
        til.addView(edit)
        if (hasOptions) {
            til.setEndIconOnClickListener { showOptionPicker(key, label) }
        }
        binding.formContainer.addView(til)
        fieldLayouts[key] = til
        edit.doOnTextChanged { _, _, _, _ ->
            til.error = null
            formDirty = true
        }
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
                formDirty = true
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

    /** Step-scoped import logging: every phase logs under one tag so a
     * failed import is diagnosable from logcat ("contact-import [step]"). */
    private fun logImport(step: String, msg: String, e: Throwable? = null) {
        if (e == null) android.util.Log.d("CardEditActivity", "contact-import [$step] $msg")
        else android.util.Log.e("CardEditActivity", "contact-import [$step] $msg", e)
    }

    private fun hasReadContactsPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Imports the contact the user picked. The read and the form update are
     * separate steps: the contact is read fully first, then applied to the
     * Contact form in one go — so a successful import always lands every
     * mapped field on screen, and a failed read never leaves a half-built
     * form behind.
     *
     * The system picker grants one-time access to just this contact's URI.
     * That grant covers ONLY the exact returned URI (URI grants are
     * exact-match; there is no prefix grant unless the picker sets
     * FLAG_GRANT_PREFIX_URI_PERMISSION, which it doesn't). So the contact
     * row reads fine, but the derived /data URI may throw SecurityException
     * on strict devices — that is the expected trigger for the permission
     * fallback below, not a malfunction.
     *
     * Every phone number and email is kept (with its label); the primary one
     * becomes the selected value.
     */
    private fun importContact(contactUri: Uri) {
        val hasPermission = hasReadContactsPermission()
        var step = "start"
        // Fresh attempt clears any previous failure from the diagnostics card.
        lastImportError = null
        refreshImportErrorCard()
        logImport(
            "start",
            "uri=$contactUri permissionAsked=$contactsPermissionAsked " +
                "hasReadContacts=$hasPermission"
        )
        try {
            step = "read"
            val imported = readContact(contactUri, hasPermission)
            step = "apply"
            applyImportedContact(imported)
            logImport(
                "done",
                "applied phones=${imported.phones.size} emails=${imported.emails.size}"
            )
        } catch (e: SecurityException) {
            // A SecurityException from the READ step means the picker's
            // one-time grant didn't cover the data rows: ask for the
            // contacts permission once and retry the same contact. One from
            // the APPLY step (or after the permission was already
            // granted/asked) is a genuine failure — never re-request in a
            // loop, and never request for a non-read failure.
            logImport("blocked", "step=$step uri=$contactUri hasReadContacts=$hasPermission", e)
            if (step == "read" && !contactsPermissionAsked && !hasPermission) {
                contactsPermissionAsked = true
                pendingContactUri = contactUri
                requestContactsPermission.launch(android.Manifest.permission.READ_CONTACTS)
            } else {
                showImportError("blocked", e, contactUri)
            }
        } catch (e: Exception) {
            showImportError(step, e, contactUri)
        }
    }

    /**
     * The failure toast names the step and the exception, and the full
     * detail (step, exception, message, URI, stack trace) lands on the
     * on-page diagnostics card — hidden until tapped, with a copy button —
     * so a truncated toast never hides the actual error again.
     */
    private fun showImportError(step: String, e: Throwable, uri: Uri?) {
        logImport("failed", "step=$step ${e.javaClass.simpleName}: ${e.message}", e)
        contactsPermissionAsked = false
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .format(LocalDateTime.now())
        lastImportError = buildString {
            appendLine("step: $step")
            appendLine("exception: ${e.javaClass.name}")
            appendLine("message: ${e.message ?: "?"}")
            if (uri != null) appendLine("uri: $uri")
            appendLine("time: $time")
            appendLine()
            appendLine(android.util.Log.getStackTraceString(e))
        }.trimEnd()
        binding.importErrorText.text = lastImportError
        binding.importErrorDetail.visibility = View.GONE
        binding.importErrorChevron.setImageResource(R.drawable.ic_chevron_down)
        refreshImportErrorCard()
        Toast.makeText(
            this,
            getString(
                R.string.contact_import_failed_detail,
                step, e.javaClass.simpleName, e.message ?: "?"
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * The diagnostics card only belongs on the Contact form: it appears
     * when an import fails and hides on type switch, dismiss, or a fresh
     * import attempt.
     */
    private fun refreshImportErrorCard() {
        val show = currentType == CardType.CONTACT && lastImportError != null
        binding.importErrorCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) binding.importErrorDetail.visibility = View.GONE
    }

    private fun toggleImportErrorDetail() {
        val detail = binding.importErrorDetail
        val expanding = detail.visibility != View.VISIBLE
        detail.visibility = if (expanding) View.VISIBLE else View.GONE
        binding.importErrorChevron.setImageResource(
            if (expanding) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
    }

    private fun copyImportError() {
        val text = binding.importErrorText.text.toString()
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("contact import error", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** One contact's imported data, read fully before touching the form. */
    private data class ImportedContact(
        val firstName: String,
        val lastName: String,
        val organization: String,
        val jobTitle: String,
        val address: String,
        val phones: List<FieldOption>,
        val emails: List<FieldOption>,
        val website: String,
    )

    private fun readContact(contactUri: Uri, hasPermission: Boolean): ImportedContact {
        val cr = contentResolver
        // With READ_CONTACTS, resolve the concrete contact URI first via
        // lookupContact(): no grant needed, so device-specific picker-URI
        // quirks can't bite. Without it, use the picker URI exactly as
        // returned — the grant covers that URI and nothing derived from it.
        val resolved: Uri
        if (hasPermission) {
            val lookedUp = runCatching {
                ContactsContract.Contacts.lookupContact(cr, contactUri)
            }.onFailure { e ->
                logImport("lookup", "lookupContact threw for $contactUri", e)
            }.getOrNull()
            if (lookedUp == null) {
                logImport("lookup", "lookupContact returned null for $contactUri; using picker URI")
            }
            resolved = lookedUp ?: contactUri
        } else {
            resolved = contactUri
        }
        logImport("resolve", "resolved=$resolved")
        val dataUri =
            Uri.withAppendedPath(resolved, ContactsContract.Contacts.Data.CONTENT_DIRECTORY)
        logImport("query", "contact row: $resolved")
        cr.query(
            resolved,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) {
                logImport("query", "empty cursor for $resolved")
                throw IllegalArgumentException("empty contact cursor for $resolved")
            }
            val displayName = c.getString(1).orEmpty()
            val (firstName, lastName) = splitName(displayName)
            logImport("query", "name='$displayName' data: $dataUri")

            val phones = dedupeOptions(
                readContactOptions(
                    cr, dataUri,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ) { type, label ->
                    ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, type, label)
                        .toString()
                }
            ) { normalizePhone(it.value) }
            val emails = dedupeOptions(
                readContactOptions(
                    cr, dataUri,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.LABEL,
                ) { type, label ->
                    ContactsContract.CommonDataKinds.Email.getTypeLabel(resources, type, label)
                        .toString()
                }
            ) { it.value.trim().lowercase() }
            val organization = readContactSingle(
                cr, dataUri,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.COMPANY,
            )
            val jobTitle = readContactSingle(
                cr, dataUri,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.TITLE,
            )
            val address = readContactSingle(
                cr, dataUri,
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
            )
            val website = readContactSingle(
                cr, dataUri,
                ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Website.URL,
            )
            return ImportedContact(
                firstName, lastName, organization, jobTitle, address, phones, emails, website
            )
        } ?: run {
            logImport("query", "null cursor for $resolved")
            throw IllegalArgumentException("null cursor for $resolved")
        }
    }

    /**
     * Lands the imported contact on the Contact form: the type is set
     * explicitly and the form is rebuilt for it, so the full contact field
     * set is what's on screen — with every mapped value filled in — no
     * matter what was showing before. Stale multi-value options from a
     * previous import are cleared, not left behind.
     *
     * The apply is atomic: everything it mutates is snapshotted first, so a
     * failure at any point (including inside buildForm, which starts with
     * removeAllViews()) restores the previous form intact instead of leaving
     * the fields wiped. The error toast still shows via importContact.
     */
    private fun applyImportedContact(imported: ImportedContact) {
        contactsPermissionAsked = false
        val prevType = currentType
        val prevPhoneOptions = optionLists["phone"]?.toMutableList()
        val prevPhoneSelection = optionSelection["phone"]
        val prevEmailOptions = optionLists["email"]?.toMutableList()
        val prevEmailSelection = optionSelection["email"]
        val prevContactCache = typeFieldCache[CardType.CONTACT]?.toMutableMap()
        try {
            if (imported.phones.isNotEmpty()) {
                optionLists["phone"] = imported.phones.toMutableList()
                optionSelection["phone"] = 0
            } else {
                optionLists.remove("phone")
                optionSelection.remove("phone")
            }
            if (imported.emails.isNotEmpty()) {
                optionLists["email"] = imported.emails.toMutableList()
                optionSelection["email"] = 0
            } else {
                optionLists.remove("email")
                optionSelection.remove("email")
            }
            // Cache under CONTACT so switching types and back keeps the import.
            typeFieldCache[CardType.CONTACT] = mutableMapOf(
                "firstName" to imported.firstName,
                "lastName" to imported.lastName,
                "organization" to imported.organization,
                "jobTitle" to imported.jobTitle,
                "address" to imported.address,
                "phone" to imported.phones.firstOrNull()?.value.orEmpty(),
                "email" to imported.emails.firstOrNull()?.value.orEmpty(),
                "website" to imported.website,
            )
            currentType = CardType.CONTACT
            refreshTypeHeader()
            refreshTypeTabs()
            buildForm(CardType.CONTACT, prefillFor(CardType.CONTACT))
            // A fresh import names the card from the contact when the name
            // field is still empty — never overwrites a typed name.
            if (binding.cardNameInput.text?.toString()?.isBlank() == true) {
                val fullName = listOf(imported.firstName, imported.lastName)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                if (fullName.isNotEmpty()) binding.cardNameInput.setText(fullName)
            }
            scrollToType(CardType.CONTACT, smooth = true)
            formDirty = true
        } catch (e: Exception) {
            logImport("apply", "apply failed; restoring $prevType form", e)
            if (prevPhoneOptions != null) {
                optionLists["phone"] = prevPhoneOptions
                prevPhoneSelection?.let { optionSelection["phone"] = it }
                    ?: optionSelection.remove("phone")
            } else {
                optionLists.remove("phone")
                optionSelection.remove("phone")
            }
            if (prevEmailOptions != null) {
                optionLists["email"] = prevEmailOptions
                prevEmailSelection?.let { optionSelection["email"] = it }
                    ?: optionSelection.remove("email")
            } else {
                optionLists.remove("email")
                optionSelection.remove("email")
            }
            if (prevContactCache != null) typeFieldCache[CardType.CONTACT] = prevContactCache
            else typeFieldCache.remove(CardType.CONTACT)
            currentType = prevType
            refreshTypeHeader()
            refreshTypeTabs()
            // Best-effort restore of the previous form; never throws out.
            runCatching { buildForm(prevType, prefillFor(prevType)) }
                .onFailure { re -> logImport("apply", "restore also failed", re) }
            throw e
        }
    }

    /**
     * Normalized phone form for dedupe: digits only, keeping a leading +.
     * "(306) 555-1234", "306-555-1234" and "3065551234" all become
     * "3065551234", so minor formatting differences don't produce duplicate
     * dropdown entries. The first (best-ranked) original formatting is kept
     * for display.
     */
    private fun normalizePhone(raw: String): String {
        val t = raw.trim()
        val digits = t.filter { it.isDigit() }
        return if (t.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
    }

    /**
     * Drops options that are duplicates under [key], keeping the first
     * occurrence (callers pass rank-sorted lists, so the primary number or
     * email wins). An empty normalized key falls back to the raw value so
     * digit-less entries never collapse into each other.
     */
    private fun dedupeOptions(
        options: List<FieldOption>,
        key: (FieldOption) -> String,
    ): List<FieldOption> {
        val seen = HashSet<String>()
        return options.filter { option ->
            val k = key(option).ifEmpty { option.value }
            seen.add(k)
        }
    }

    private fun readContactOptions(
        cr: android.content.ContentResolver,
        dataUri: Uri,
        mimeType: String,
        valueColumn: String,
        typeColumn: String,
        labelColumn: String,
        labelFor: (Int, CharSequence?) -> String,
    ): List<FieldOption> {
        data class Ranked(val label: String, val value: String, val rank: Int)
        val out = mutableListOf<Ranked>()
        cr.query(
            dataUri,
            null,
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(mimeType),
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
        dataUri: Uri,
        mimeType: String,
        column: String,
    ): String {
        cr.query(
            dataUri,
            arrayOf(column),
            "${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(mimeType),
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
            // Registered after the initial set so the prefill never marks dirty.
            setOnCheckedChangeListener { _, _ -> formDirty = true }
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
                formDirty = true
            }
            timePicker.show(supportFragmentManager, "time_$key")
        }
        datePicker.show(supportFragmentManager, "date_$key")
    }

    // -- social templates --

    /**
     * Row of social-profile template buttons above the URL field. Tapping
     * one pre-fills the service's URL prefix and tags the card with the
     * service name (re-derived from the final URL on save, so a manual
     * edit can't leave a stale tag).
     */
    private fun addSocialTemplates(prefillUrl: String?) {
        val density = resources.displayMetrics.density
        val label = TextView(this).apply {
            text = getString(R.string.social_templates)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        binding.formContainer.addView(label)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        }
        socialTemplates.forEach { template ->
            MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                setIconResource(template.icon)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = 0
                text = null
                contentDescription = getString(R.string.social_template_desc, template.displayName)
                isCheckable = true
                minimumWidth = 0
                minWidth = 0
                minimumHeight = 0
                minHeight = 0
                val size = (52 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8 * density).toInt()
                }
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    val urlField = fieldLayouts["url"]?.editText
                    urlField?.setText(template.prefix)
                    urlField?.setSelection(template.prefix.length)
                    urlField?.requestFocus()
                    serviceTemplate = template.id
                    // Name the card after the service — when the name field is
                    // blank, or when it still holds a name a social template
                    // placed there (so switching services updates it). Never
                    // overwrite a name the user typed themselves.
                    val currentName = binding.cardNameInput.text?.toString().orEmpty()
                    val autoNames = socialTemplates.map { it.displayName }.toSet()
                    if (currentName.isBlank() || currentName in autoNames) {
                        binding.cardNameInput.setText(template.displayName)
                    }
                    formDirty = true
                    refreshTemplateSelection()
                }
                tag = template.id
            }.also { row.addView(it) }
        }
        binding.formContainer.addView(row)
        row.post { refreshTemplateSelection() }
    }

    private fun refreshTemplateSelection() {
        val container = binding.formContainer
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val btn = row.getChildAt(j) as? MaterialButton ?: continue
                val service = btn.tag as? String ?: continue
                btn.isChecked = service == serviceTemplate
            }
        }
    }

    // -- label color --

    private fun buildColorRow() {
        binding.colorRow.removeAllViews()
        colorDots.clear()
        colorOptions.forEach { color ->
            val dot = ImageView(this).apply {
                // Sized in layoutColorDots() so all eight dots always fit on screen.
                setImageResource(if (color == null) R.drawable.dot_none else R.drawable.dot)
                if (color != null) imageTintList = ColorStateList.valueOf(color)
                contentDescription =
                    if (color == null) getString(R.string.color_none) else null
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedColor = color
                    refreshColorSelection()
                    formDirty = true
                }
            }
            binding.colorRow.addView(dot)
            colorDots.add(dot)
        }
        binding.colorRow.post { layoutColorDots() }
        refreshColorSelection()
    }

    /**
     * Sizes the dots from the row's measured width so all seven always fit
     * horizontally, staying round instead of stretching.
     */
    private fun layoutColorDots() {
        val rowWidth = binding.colorRow.width
        if (rowWidth <= 0 || colorDots.isEmpty()) return
        val density = resources.displayMetrics.density
        val margin = (4 * density).toInt()
        val pad = (5 * density).toInt()
        val size = (rowWidth / colorDots.size - margin * 2)
            .coerceAtLeast((24 * density).toInt())
        colorDots.forEach { dot ->
            dot.layoutParams = LinearLayout.LayoutParams(size, size)
                .apply { setMargins(margin, 0, margin, 0) }
            dot.setPadding(pad, pad, pad, pad)
        }
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

    // -- QR code color --

    private fun buildQrColorRow() {
        binding.qrColorRow.removeAllViews()
        qrColorDots.clear()
        // Palette dots: index i maps to qrColorOptions[i].
        qrColorOptions.forEachIndexed { index, color ->
            val dot = ImageView(this).apply {
                setImageResource(if (color == null) R.drawable.dot_none else R.drawable.dot)
                if (color != null) imageTintList = ColorStateList.valueOf(color)
                contentDescription =
                    if (color == null) getString(R.string.qr_color_default)
                    else getString(R.string.qr_color_custom, "#%06X".format(0xFFFFFF and color))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedQrColor = color
                    refreshQrColorSelection()
                    formDirty = true
                }
                tag = index
            }
            binding.qrColorRow.addView(dot)
            qrColorDots.add(dot)
        }
        // Custom-color dot: opens the visual color picker.
        val customDot = ImageView(this).apply {
            setImageResource(R.drawable.ic_custom_color)
            contentDescription = getString(R.string.qr_color_custom_entry)
            isClickable = true
            isFocusable = true
            setOnClickListener { showVisualQrColorPicker() }
            tag = -1
        }
        binding.qrColorRow.addView(customDot)
        qrColorDots.add(customDot)
        binding.qrColorRow.post { layoutQrColorDots() }
        refreshQrColorSelection()
    }

    /**
     * Sizes the dots from the row's measured width so they always fit
     * horizontally, staying round instead of stretching.
     */
    private fun layoutQrColorDots() {
        val rowWidth = binding.qrColorRow.width
        if (rowWidth <= 0 || qrColorDots.isEmpty()) return
        val density = resources.displayMetrics.density
        val margin = (4 * density).toInt()
        val pad = (5 * density).toInt()
        val size = (rowWidth / qrColorDots.size - margin * 2)
            .coerceAtLeast((24 * density).toInt())
        qrColorDots.forEach { dot ->
            dot.layoutParams = LinearLayout.LayoutParams(size, size)
                .apply { setMargins(margin, 0, margin, 0) }
            dot.setPadding(pad, pad, pad, pad)
        }
    }

    private fun refreshQrColorSelection() {
        qrColorDots.forEach { dot ->
            val index = dot.tag as? Int ?: return@forEach
            val isSelected = if (index == -1) {
                // Custom dot is "selected" when the color isn't a palette one.
                selectedQrColor != null && !qrColorOptions.contains(selectedQrColor)
            } else {
                qrColorOptions[index] == selectedQrColor
            }
            dot.background = if (isSelected) getDrawable(R.drawable.dot_ring) else null
        }
    }

    /**
     * Custom color picker: a large saturation/value plane plus hue bar you
     * glide a finger over, with a live hex readout and Cancel/OK. Any color
     * is accepted — the user keeps it dark enough to scan; the palette is
     * the safe default.
     */
    private fun showVisualQrColorPicker() {
        HsvColorPickerDialog.show(this, selectedQrColor ?: Color.BLACK) { color ->
            selectedQrColor = color
            refreshQrColorSelection()
            formDirty = true
        }
    }

    // -- save --

    /**
     * Cancel leaves without saving. When the user has unsaved edits,
     * confirm first so a stray tap can't silently discard them.
     */
    private fun onCancel() {
        if (!formDirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.discard) { _, _ -> finish() }
            .show()
    }

    private fun save() {
        clearErrors()
        val name = binding.cardNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.cardNameLayout.error = getString(R.string.name_required)
            binding.cardNameInput.requestFocus()
            return
        }
        val fields = collectFields().toMutableMap()
        val payload = try {
            buildPayload(currentType, fields)
        } catch (e: Exception) {
            showPayloadError(e.message)
            return
        }
        val existing = editingCard
        if (currentType == CardType.URL) {
            // Tag the service from the final URL, never from the button
            // state: a manual edit after tapping a template can't leave a
            // stale tag.
            val url = fields["url"].orEmpty()
            val matched = socialTemplates.firstOrNull {
                url.startsWith(it.prefix, ignoreCase = true)
            }
            if (matched != null) fields["service"] = matched.id
            else fields.remove("service")
        }
        // Renaming an existing card is a choice: replace the old card with
        // the edited version (under the new name), or keep the old card
        // untouched and create a new one. Only asked when the name actually
        // changed — otherwise saving stays a single tap.
        if (existing != null && name != existing.name) {
            askReplaceOrCreate(existing.name, name) { createNew ->
                persistCard(name, fields, payload, if (createNew) null else existing)
            }
        } else {
            persistCard(name, fields, payload, existing)
        }
    }

    /**
     * "Replace" updates the existing card with all edits, including the new
     * name. "Create" leaves the old card alone and saves the edited content
     * as a brand-new card (blank id → the repository generates one).
     */
    private fun askReplaceOrCreate(
        oldName: String,
        newName: String,
        onChoice: (createNew: Boolean) -> Unit,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setPositiveButton(getString(R.string.rename_replace, oldName)) { _, _ ->
                onChoice(false)
            }
            .setNegativeButton(getString(R.string.rename_create, newName)) { _, _ ->
                onChoice(true)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun persistCard(
        name: String,
        fields: Map<String, String>,
        payload: String,
        existing: QrCard?,
    ) {
        val card = QrCard(
            id = existing?.id.orEmpty(), // blank → repository generates one
            name = name,
            type = currentType,
            payload = payload,
            fields = fields,
            fieldOptions = collectFieldOptions(fields),
            labelColor = selectedColor,
            qrColor = selectedQrColor,
            sensitive = binding.sensitiveCheck.isChecked,
            // Editing never unfiles: a card keeps its folder.
            folderId = existing?.folderId
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
                jobTitle = v("jobTitle"),
                address = v("address"),
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
