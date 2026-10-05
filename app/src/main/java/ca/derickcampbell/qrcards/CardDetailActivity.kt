package ca.derickcampbell.qrcards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ca.derickcampbell.qrcards.data.CardRepository
import ca.derickcampbell.qrcards.databinding.ActivityCardDetailBinding
import ca.derickcampbell.qrcards.model.CardType
import ca.derickcampbell.qrcards.model.QrCard
import ca.derickcampbell.qrcards.payload.CardPayloads
import ca.derickcampbell.qrcards.qr.QrRenderer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileOutputStream

/**
 * Present mode: the QR code big on screen at maximum brightness, for reliable
 * scanning in sunlight or bad lighting. Handles deep links from app shortcuts
 * ([EXTRA_CARD_ID]) — singleTop, so a second tap re-binds via onNewIntent.
 *
 * Brightness is forced to full while visible and restored on pause. Sensitive
 * cards show a confirm dialog before the code is revealed.
 */
class CardDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "card_id"
        private const val STATE_REVEALED = "revealed"
        private const val STATE_SHOWING_DATA = "showing_data"
        /** Half of the flip animation; the full flip is out + in. */
        private const val FLIP_HALF_MS = 150L
    }

    private lateinit var binding: ActivityCardDetailBinding
    private lateinit var repository: CardRepository
    private var card: QrCard? = null
    private var revealed = false
    /** Which face of the card is up: false = QR, true = encoded data. */
    private var showingData = false
    private var flipAnimating = false
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    /** Tap anywhere on the card toggles the flip, both directions. */
    private val flipTap = View.OnClickListener { flipCard() }

    private val svgExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/svg+xml")
    ) { uri: Uri? ->
        val current = card ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(
                    QrRenderer.renderSvg(
                        current.payload,
                        foreground = current.qrColor ?: Color.BLACK,
                    ).toByteArray(Charsets.UTF_8)
                )
            } ?: throw IllegalStateException("Could not open destination")
            Snackbar.make(binding.root, R.string.svg_saved, Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                getString(R.string.svg_save_failed, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private val vcfExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri: Uri? ->
        val current = card ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(current.payload.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Could not open destination")
            Snackbar.make(binding.root, R.string.vcf_saved, Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                getString(R.string.vcf_save_failed, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = CardRepository(this)
        revealed = savedInstanceState?.getBoolean(STATE_REVEALED) == true
        showingData = savedInstanceState?.getBoolean(STATE_SHOWING_DATA) == true

        // Perspective distance for the 3D flip; without it the rotation
        // looks flat/skewed.
        val cameraDistance = 8000 * resources.displayMetrics.density
        binding.qrImage.cameraDistance = cameraDistance
        binding.qrDataView.cameraDistance = cameraDistance

        binding.toolbar.setNavigationOnClickListener { finish() }
        // Tap toggles both directions, on either face. The back face is
        // rebuilt dynamically (data rows), so the flip tap is attached
        // recursively to every non-interactive view on it — text, rows, and
        // empty space all flip back to the QR. Views that already do
        // something (scrolling, buttons) are left alone.
        binding.qrFlipContainer.setOnClickListener(flipTap)
        attachFlipEverywhere(binding.qrDataView, flipTap)
        binding.sharePngButton.setOnClickListener { sharePng() }
        binding.exportSvgButton.setOnClickListener {
            card?.let { svgExportLauncher.launch(exportFileName(it, "svg")) }
        }
        binding.copyPayloadButton.setOnClickListener { copyPayload() }
        binding.shareVcardButton.setOnClickListener { shareVcard() }
        binding.saveVcardButton.setOnClickListener {
            card?.let { vcfExportLauncher.launch(exportFileName(it, "vcf")) }
        }
        binding.editButton.setOnClickListener {
            card?.let {
                startActivity(
                    Intent(this, CardEditActivity::class.java)
                        .putExtra(CardEditActivity.EXTRA_CARD_ID, it.id)
                )
            }
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }

        loadCard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        revealed = false
        showingData = false
        loadCard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REVEALED, revealed)
        outState.putBoolean(STATE_SHOWING_DATA, showingData)
    }

    override fun onResume() {
        super.onResume()
        // Re-read in case the card was edited (or deleted) while we were away.
        val id = intent.getStringExtra(EXTRA_CARD_ID)
        val fresh = id?.let { repository.get(it) }
        if (fresh == null) {
            if (!isFinishing) finish()
            return
        }
        if (fresh != card) {
            card = fresh
            bindCard()
            if (revealed) renderQr() else maybeReveal()
        }
        // Max brightness while the code is on screen; restored in onPause.
        val attrs = window.attributes
        originalBrightness = attrs.screenBrightness
        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = attrs
    }

    override fun onPause() {
        super.onPause()
        val attrs = window.attributes
        attrs.screenBrightness = originalBrightness
        window.attributes = attrs
    }

    private fun loadCard() {
        val id = intent.getStringExtra(EXTRA_CARD_ID)
        val loaded = id?.let { repository.get(it) }
        if (loaded == null) {
            Toast.makeText(this, R.string.card_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        card = loaded
        bindCard()
        maybeReveal()
    }

    private fun bindCard() {
        val current = card ?: return
        // The toolbar names the card; no second name label below the card.
        binding.toolbar.title = current.name
        // vCard actions only make sense for contact cards.
        val vcardVisibility =
            if (current.type == CardType.CONTACT) View.VISIBLE else View.GONE
        binding.shareVcardButton.visibility = vcardVisibility
        binding.saveVcardButton.visibility = vcardVisibility
        flipAnimating = false
        applyFace()
    }

    /** Shows the current face instantly (no animation): for bind/rotation. */
    private fun applyFace() {
        binding.qrImage.visibility = if (showingData) View.GONE else View.VISIBLE
        binding.qrImage.rotationY = 0f
        binding.qrDataView.visibility = if (showingData) View.VISIBLE else View.GONE
        binding.qrDataView.rotationY = 0f
        binding.qrFlipContainer.contentDescription =
            getString(if (showingData) R.string.flip_to_qr_desc else R.string.flip_to_data_desc)
    }

    private fun maybeReveal() {
        val current = card ?: return
        if (!current.sensitive || revealed) {
            renderQr()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sensitive_title)
            .setMessage(R.string.sensitive_message)
            .setPositiveButton(R.string.show) { _, _ ->
                revealed = true
                renderQr()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun renderQr() {
        val current = card ?: return
        try {
            binding.qrImage.setImageBitmap(
                QrRenderer.renderBitmap(
                    current.payload,
                    qrDisplaySize(),
                    ErrorCorrectionLevel.M,
                    foreground = current.qrColor ?: Color.BLACK,
                )
            )
            // The back of the card: the card's details as labeled fields, for
            // the "check before you open" preview. Populated on reveal, so a
            // sensitive card's payload never sits in the view before
            // confirmation.
            buildDataFace(current)
            // The data rows are rebuilt, so re-attach the flip tap to the
            // new row views: tapping any of them flips back to the QR.
            attachFlipEverywhere(binding.dataRows, flipTap)
            // Top-align the back face with the QR bitmap: the QR is
            // fitCenter'd, so its top edge depends on the container size.
            // Measure the displayed bitmap rect once laid out and start the
            // data face at the same Y (never above the 24dp base padding).
            binding.qrImage.post {
                val d = binding.qrImage.drawable
                if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                    val rect = android.graphics.RectF(
                        0f, 0f,
                        d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()
                    )
                    binding.qrImage.imageMatrix.mapRect(rect)
                    val density = resources.displayMetrics.density
                    val qrTop = rect.top.toInt().coerceAtLeast(0)
                    val basePad = (24 * density).toInt()
                    binding.qrDataView.setPadding(
                        binding.qrDataView.paddingLeft,
                        maxOf(basePad, qrTop),
                        binding.qrDataView.paddingRight,
                        binding.qrDataView.paddingBottom
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.render_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // -- card details (back face) --

    /**
     * The back of the card: clearly labeled fields with pleasant spacing,
     * built from the card's stored fields. Falls back to the raw payload
     * when there's nothing structured to show (e.g. a card restored from
     * an old backup), so the face is never blank.
     */
    private fun buildDataFace(card: QrCard) {
        val container = binding.dataRows
        container.removeAllViews()
        val rows = dataRows(card)
        if (rows.isEmpty()) {
            container.addView(TextView(this).apply {
                text = card.payload
                typeface = android.graphics.Typeface.MONOSPACE
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
            })
            return
        }
        val density = resources.displayMetrics.density
        rows.forEachIndexed { index, dataRow ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) topMargin = (16 * density).toInt()
                }
            }
            rowLayout.addView(TextView(this).apply {
                text = dataRow.label
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelMedium
                )
            })
            rowLayout.addView(TextView(this).apply {
                text = dataRow.value
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                )
                setPadding(0, (2 * density).toInt(), 0, 0)
                applyDataLink(this, dataRow, card)
            })
            container.addView(rowLayout)
        }
    }

    /** How a data-face value row opens when tapped. */
    private enum class DataLink { NONE, WEB, EMAIL, PHONE, SMS, MAPS }

    private data class DataRow(
        val label: String,
        val value: String,
        val link: DataLink = DataLink.NONE,
    )

    /**
     * Makes a data-face value tappable: links open in the browser, emails
     * in the mail app, phone numbers in the dialer, SMS numbers in the
     * messaging app, and the Maps row in Google Maps. Tapping anywhere
     * else on the row still flips the card back.
     */
    private fun applyDataLink(view: TextView, row: DataRow, card: QrCard) {
        when (row.link) {
            DataLink.NONE -> return
            DataLink.WEB -> linkify(view, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
            DataLink.EMAIL -> linkify(view, Linkify.EMAIL_ADDRESSES)
            DataLink.PHONE -> linkify(view, Linkify.PHONE_NUMBERS)
            DataLink.SMS -> {
                val dialable = row.value.filter { it.isDigit() || it == '+' }
                if (dialable.isEmpty()) return
                view.text = SpannableString(row.value).apply {
                    setSpan(
                        URLSpan("smsto:$dialable"),
                        0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                view.movementMethod = LinkMovementMethod.getInstance()
            }
            DataLink.MAPS -> {
                val lat = card.fields["latitude"].orEmpty().trim()
                val lng = card.fields["longitude"].orEmpty().trim()
                if (lat.isEmpty() || lng.isEmpty()) return
                view.text = SpannableString(row.value).apply {
                    setSpan(
                        URLSpan(CardPayloads.mapsUrl(lat, lng)),
                        0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                view.movementMethod = LinkMovementMethod.getInstance()
            }
        }
    }

    private fun linkify(view: TextView, mask: Int) {
        if (Linkify.addLinks(view, mask)) {
            view.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    /**
     * Labeled (label, value) rows for the back face, in display order.
     * Blank values are skipped — the face shows only what's actually there.
     */
    private fun dataRows(card: QrCard): List<DataRow> {
        val f = card.fields
        fun v(key: String) = f[key].orEmpty().trim()
        fun row(label: String, value: String, link: DataLink = DataLink.NONE) =
            DataRow(label, value, link)
        fun rows(vararg rows: DataRow) = rows.filter { it.value.isNotBlank() }
        return when (card.type) {
            CardType.CONTACT -> {
                val name = listOf(v("firstName"), v("lastName"))
                    .filter { it.isNotEmpty() }.joinToString(" ")
                rows(
                    row(getString(R.string.field_name), name),
                    row(getString(R.string.field_organization), v("organization")),
                    row(getString(R.string.field_phone), v("phone"), DataLink.PHONE),
                    row(getString(R.string.field_email), v("email"), DataLink.EMAIL),
                    row(getString(R.string.field_website), v("website"), DataLink.WEB),
                )
            }
            CardType.WIFI -> rows(
                row(getString(R.string.field_ssid), v("ssid")),
                row(getString(R.string.field_password), v("password")),
            )
            CardType.URL -> {
                val service = v("service")
                rows(
                    row(
                        getString(R.string.field_service),
                        service.replaceFirstChar { it.uppercase() },
                    ),
                    row(getString(R.string.field_url), v("url"), DataLink.WEB),
                )
            }
            CardType.LOCATION -> {
                val lat = v("latitude")
                val lng = v("longitude")
                buildList {
                    if (lat.isNotBlank()) {
                        add(row(getString(R.string.field_latitude), lat))
                    }
                    if (lng.isNotBlank()) {
                        add(row(getString(R.string.field_longitude), lng))
                    }
                    if (lat.isNotBlank() && lng.isNotBlank()) {
                        add(
                            row(
                                getString(R.string.field_map),
                                getString(R.string.open_in_maps),
                                DataLink.MAPS,
                            )
                        )
                    }
                }
            }
            CardType.TEXT -> rows(
                row(getString(R.string.field_text), v("content"), DataLink.WEB)
            )
            CardType.EMAIL -> rows(
                row(
                    getString(R.string.field_email_address),
                    v("address"),
                    DataLink.EMAIL,
                ),
                row(getString(R.string.field_subject), v("subject")),
                row(getString(R.string.field_body), v("body")),
            )
            CardType.PHONE -> rows(
                row(getString(R.string.field_number), v("number"), DataLink.PHONE)
            )
            CardType.SMS -> rows(
                row(getString(R.string.field_number), v("number"), DataLink.SMS),
                row(getString(R.string.field_message), v("message")),
            )
            CardType.CALENDAR_EVENT -> rows(
                row(getString(R.string.field_title), v("title")),
                row(getString(R.string.field_starts), formatIsoDateTime(v("start"))),
                row(getString(R.string.field_ends), formatIsoDateTime(v("end"))),
                row(getString(R.string.field_location), v("location")),
            )
        }
    }

    private fun formatIsoDateTime(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            java.time.LocalDateTime.parse(iso).format(
                java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy h:mm a")
            )
        }.getOrDefault(iso)
    }

    // -- card flip --

    /**
     * Attaches the flip tap to every non-interactive view under [root]:
     * labels, value rows, and empty space on the back face all toggle back
     * to the QR. Views that already do something (clickable) are left alone
     * so scrolling keeps working; the action buttons live outside this tree.
     */
    private fun attachFlipEverywhere(root: View, flipTap: View.OnClickListener) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                attachFlipEverywhere(root.getChildAt(i), flipTap)
            }
        }
        if (!root.isClickable && !root.isLongClickable) {
            root.setOnClickListener(flipTap)
        }
    }

    /**
     * Tapping the card flips it between the QR face and the encoded-data
     * face. Active in present mode too — one tap flips back instantly, so a
     * stray tap mid-scan costs nothing. When the system reduced-motion
     * setting is on, the faces swap with no animation.
     */
    private fun flipCard() {
        if (flipAnimating || card == null) return
        showingData = !showingData
        if (reducedMotion()) {
            applyFace()
            return
        }
        val outView = if (showingData) binding.qrImage else binding.qrDataView
        val inView = if (showingData) binding.qrDataView else binding.qrImage
        flipAnimating = true
        outView.animate()
            .rotationY(90f)
            .setDuration(FLIP_HALF_MS)
            .withEndAction {
                outView.visibility = View.GONE
                outView.rotationY = 0f
                inView.rotationY = -90f
                inView.visibility = View.VISIBLE
                inView.animate()
                    .rotationY(0f)
                    .setDuration(FLIP_HALF_MS)
                    .withEndAction { flipAnimating = false }
                    .start()
                binding.qrFlipContainer.contentDescription = getString(
                    if (showingData) R.string.flip_to_qr_desc else R.string.flip_to_data_desc
                )
            }
            .start()
    }

    private fun reducedMotion(): Boolean =
        Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

    private fun copyPayload() {
        val current = card ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Snackbar.make(binding.root, R.string.copy_failed, Snackbar.LENGTH_SHORT).show()
            return
        }
        // Locations copy as a Google Maps link: unlike the geo: payload
        // (which chat apps don't linkify), an https maps URL is tappable
        // everywhere and opens directly in Google Maps.
        val text = if (current.type == CardType.LOCATION) {
            val lat = current.fields["latitude"].orEmpty().trim()
            val lng = current.fields["longitude"].orEmpty().trim()
            if (lat.isNotEmpty() && lng.isNotEmpty()) CardPayloads.mapsUrl(lat, lng)
            else current.payload
        } else {
            current.payload
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(current.name, text))
        Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
    }

    // -- vCard --

    /**
     * Shares the card's vCard 3.0 payload as a real .vcf file, stream-only.
     * The MIME type is text/x-vcard — not text/vcard — deliberately: that is
     * the platform convention the AOSP/Google Contacts app itself uses for
     * ACTION_SEND contact shares, and it is what messaging apps register
     * for. (Verified against the AOSP share path: sharing a contact from the
     * system Contacts app sends ACTION_SEND with typ=text/x-vcard; Google
     * Messages appears for that type but not for text/vcard.) No EXTRA_TEXT:
     * sending both made some apps show a contact embed plus all the text.
     * Only offered for contact cards — for every other type the payload
     * isn't a vCard. Falls back to a text-only share if the file share can't
     * be built.
     */
    private fun shareVcard() {
        val current = card ?: return
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "contact-${current.id}.vcf")
            FileOutputStream(file).use { out ->
                out.write(current.payload.toByteArray(Charsets.UTF_8))
            }
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                // text/x-vcard: the platform convention (see KDoc). A
                // previous text/vcard build hid SMS apps from the chooser.
                type = "text/x-vcard"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, current.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_vcard)))
        } catch (e: Exception) {
            android.util.Log.e("CardDetailActivity", "vCard file share failed", e)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/x-vcard"
                putExtra(Intent.EXTRA_TEXT, current.payload)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_vcard)))
        }
    }

    private fun qrDisplaySize(): Int {
        val metrics = resources.displayMetrics
        val smallerSide =
            if (metrics.widthPixels < metrics.heightPixels) metrics.widthPixels
            else metrics.heightPixels
        return (smallerSide * 0.85).toInt().coerceAtLeast(512)
    }

    private fun sharePng() {
        val current = card ?: return
        try {
            val bitmap: Bitmap =
                QrRenderer.renderBitmap(
                    current.payload, 2048, ErrorCorrectionLevel.H,
                    foreground = current.qrColor ?: Color.BLACK,
                )
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "qr-card-${current.id}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_card)))
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                getString(R.string.share_failed, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun exportFileName(current: QrCard, extension: String): String {
        val base = current.name
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
        return (base.ifEmpty { "qr-card" }) + ".$extension"
    }

    private fun confirmDelete() {
        val current = card ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_card_title)
            .setMessage(getString(R.string.delete_card_message, current.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.delete(current.id)
                Toast.makeText(this, R.string.card_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
