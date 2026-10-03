package ca.derickcampbell.qrcards

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import ca.derickcampbell.qrcards.data.CardBackup
import ca.derickcampbell.qrcards.data.CardRepository
import ca.derickcampbell.qrcards.databinding.ActivityMainBinding
import ca.derickcampbell.qrcards.model.QrCard
import ca.derickcampbell.qrcards.ui.CardAdapter
import ca.derickcampbell.qrcards.ui.CardShortcuts
import ca.derickcampbell.qrcards.ui.typeLabel
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Library screen: searchable list of cards, FAB to create, overflow menu for
 * encrypted backup export/import (Storage Access Framework — no permissions).
 *
 * Refresh happens in onResume, which covers every library change: create,
 * edit, delete, and backup import all return here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: CardRepository
    private lateinit var adapter: CardAdapter
    private var allCards: List<QrCard> = emptyList()

    // Password chosen in the export dialog; consumed by the launcher below.
    private var pendingExportPassword: CharArray? = null

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val pw = pendingExportPassword
        pendingExportPassword = null
        if (uri == null) {
            pw?.fill('\u0000')
            return@registerForActivityResult
        }
        try {
            CardBackup.exportTo(this, uri, pw)
            Snackbar.make(binding.root, R.string.backup_exported, Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                getString(R.string.backup_export_failed, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        if (CardBackup.isEncryptedBackup(this, uri)) {
            showImportPasswordDialog(uri)
        } else {
            doImport(uri, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = CardRepository(this)

        adapter = CardAdapter { card ->
            startActivity(
                Intent(this, CardDetailActivity::class.java)
                    .putExtra(CardDetailActivity.EXTRA_CARD_ID, card.id)
            )
        }
        binding.cardList.layoutManager = LinearLayoutManager(this)
        binding.cardList.adapter = adapter

        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            applyFilter(text?.toString().orEmpty())
        }

        binding.fab.setOnClickListener {
            startActivity(Intent(this, CardEditActivity::class.java))
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export_backup -> {
                    showExportDialog()
                    true
                }
                R.id.action_import_backup -> {
                    // Only our own backup MIME type: letting the picker show
                    // every file makes it easy to grab a PNG/SVG by mistake,
                    // which then fails decryption with a cryptic error.
                    importBackupLauncher.launch(arrayOf("application/octet-stream"))
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // -- backup export/import --

    /**
     * Export starts with a choice: plain backup (default — always restorable)
     * or password-encrypted (portable, but the password is then required).
     */
    private fun showExportDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (4 * density).toInt(), pad, 0)
        }
        val encryptCheck = MaterialCheckBox(this).apply {
            text = getString(R.string.backup_encrypt_check)
        }
        val passwordLayout = TextInputLayout(this).apply {
            hint = getString(R.string.backup_password)
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        val passwordInput = TextInputEditText(passwordLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordLayout.addView(passwordInput)
        val confirmLayout = TextInputLayout(this).apply {
            hint = getString(R.string.backup_password_confirm)
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        val confirmInput = TextInputEditText(confirmLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        confirmLayout.addView(confirmInput)
        encryptCheck.setOnCheckedChangeListener { _, checked ->
            passwordLayout.isEnabled = checked
            confirmLayout.isEnabled = checked
            if (!checked) {
                passwordLayout.error = null
                confirmLayout.error = null
            }
        }
        container.addView(encryptCheck)
        container.addView(passwordLayout)
        container.addView(confirmLayout)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_export_title)
            .setMessage(R.string.backup_export_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_label, null)
            .create()
        dialog.show()
        // Validate before dismissing: the dialog stays open on errors.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (encryptCheck.isChecked) {
                val p1 = passwordInput.text?.toString().orEmpty()
                val p2 = confirmInput.text?.toString().orEmpty()
                when {
                    p1.isEmpty() -> {
                        passwordLayout.error = getString(R.string.password_required)
                        return@setOnClickListener
                    }
                    p1 != p2 -> {
                        confirmLayout.error = getString(R.string.passwords_dont_match)
                        return@setOnClickListener
                    }
                }
                pendingExportPassword = p1.toCharArray()
            } else {
                pendingExportPassword = null
            }
            dialog.dismiss()
            exportBackupLauncher.launch("qr-cards-backup.qrcards")
        }
    }

    private fun showImportPasswordDialog(uri: Uri) {
        val density = resources.displayMetrics.density
        val passwordLayout = TextInputLayout(this).apply {
            hint = getString(R.string.backup_password)
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val passwordInput = TextInputEditText(passwordLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passwordLayout.addView(passwordInput)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_password_title)
            .setMessage(R.string.backup_password_message)
            .setView(passwordLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_label, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pw = passwordInput.text?.toString().orEmpty()
            if (pw.isEmpty()) {
                passwordLayout.error = getString(R.string.password_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            doImport(uri, pw.toCharArray())
        }
    }

    private fun doImport(uri: Uri, password: CharArray?) {
        try {
            val count = CardBackup.importFrom(this, uri, password)
            Snackbar.make(
                binding.root,
                getString(R.string.backup_imported, count),
                Snackbar.LENGTH_SHORT
            ).show()
            refresh()
        } catch (e: Exception) {
            Snackbar.make(
                binding.root,
                getString(R.string.backup_import_failed, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        } finally {
            password?.fill('\u0000')
        }
    }

    private fun refresh() {
        allCards = repository.list()
        applyFilter(binding.searchInput.text?.toString().orEmpty())
        // Every library change republishes the quick-access shortcuts.
        CardShortcuts.refresh(this)
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allCards
        } else {
            allCards.filter {
                it.name.lowercase().contains(q) ||
                    typeLabel(it.type, this).lowercase().contains(q)
            }
        }
        adapter.submit(filtered)
        if (filtered.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            if (allCards.isEmpty()) {
                binding.emptyTitle.setText(R.string.no_cards)
                binding.emptyHint.setText(R.string.no_cards_hint)
            } else {
                binding.emptyTitle.setText(R.string.no_results)
                binding.emptyHint.text = ""
            }
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }
}
