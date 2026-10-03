package ca.derickcampbell.qrcards

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.snackbar.Snackbar

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

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            CardBackup.exportTo(this, uri)
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
        try {
            val count = CardBackup.importFrom(this, uri)
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
                    exportBackupLauncher.launch("qr-cards-backup.qrcards")
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
