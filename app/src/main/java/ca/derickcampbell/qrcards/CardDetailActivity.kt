package ca.derickcampbell.qrcards

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import ca.derickcampbell.qrcards.data.CardRepository
import ca.derickcampbell.qrcards.databinding.ActivityCardDetailBinding
import ca.derickcampbell.qrcards.model.QrCard
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
    }

    private lateinit var binding: ActivityCardDetailBinding
    private lateinit var repository: CardRepository
    private var card: QrCard? = null
    private var revealed = false
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private val svgExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/svg+xml")
    ) { uri: Uri? ->
        val current = card ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(QrRenderer.renderSvg(current.payload).toByteArray(Charsets.UTF_8))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = CardRepository(this)
        revealed = savedInstanceState?.getBoolean(STATE_REVEALED) == true

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.sharePngButton.setOnClickListener { sharePng() }
        binding.exportSvgButton.setOnClickListener {
            card?.let { svgExportLauncher.launch(svgFileName(it)) }
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
        loadCard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REVEALED, revealed)
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
        binding.toolbar.title = current.name
        binding.cardName.text = current.name
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
                QrRenderer.renderBitmap(current.payload, qrDisplaySize(), ErrorCorrectionLevel.M)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.render_failed, Toast.LENGTH_LONG).show()
            finish()
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
                QrRenderer.renderBitmap(current.payload, 2048, ErrorCorrectionLevel.H)
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

    private fun svgFileName(current: QrCard): String {
        val base = current.name
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
        return (base.ifEmpty { "qr-card" }) + ".svg"
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
