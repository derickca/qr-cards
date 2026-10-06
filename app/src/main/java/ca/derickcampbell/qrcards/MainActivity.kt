package ca.derickcampbell.qrcards

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ca.derickcampbell.qrcards.data.CardBackup
import ca.derickcampbell.qrcards.data.CardRepository
import ca.derickcampbell.qrcards.databinding.ActivityMainBinding
import ca.derickcampbell.qrcards.model.CardFolder
import ca.derickcampbell.qrcards.model.OrderEntry
import ca.derickcampbell.qrcards.model.QrCard
import ca.derickcampbell.qrcards.payload.ShareSniff
import ca.derickcampbell.qrcards.ui.CardAdapter
import ca.derickcampbell.qrcards.ui.CardShortcuts
import ca.derickcampbell.qrcards.ui.DropTarget
import ca.derickcampbell.qrcards.ui.LibraryRow
import ca.derickcampbell.qrcards.ui.typeLabel
import ca.derickcampbell.qrcards.widget.MyCardWidgetProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.abs

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
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var allCards: List<QrCard> = emptyList()
    private var allFolders: List<CardFolder> = emptyList()

    // Drag-landing indicator state. The list never reorders live during a
    // drag; onChildDraw computes where the row would land and paints the
    // indicator, and clearView applies the drop once.
    private var dragTarget: DropTarget = DropTarget.None
    private var dragFromPos: Int = RecyclerView.NO_POSITION
    private var density = 1f
    private lateinit var dropLinePaint: Paint
    private lateinit var dropDotPaint: Paint
    private lateinit var dropHighlightPaint: Paint
    private lateinit var dropHighlightStroke: Paint

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

        adapter = CardAdapter(
            onCardClick = { card ->
                startActivity(
                    Intent(this, CardDetailActivity::class.java)
                        .putExtra(CardDetailActivity.EXTRA_CARD_ID, card.id)
                )
            },
            onFolderToggle = { folder -> toggleFolder(folder) },
            onFolderRename = { folder -> showFolderDialog(folder) },
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) },
        )
        binding.cardList.layoutManager = LinearLayoutManager(this)
        binding.cardList.adapter = adapter

        // Drag paints and indicator state.
        density = resources.displayMetrics.density
        val primary = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, Color.BLUE
        )
        dropLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            strokeWidth = 4 * density
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        dropDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            style = Paint.Style.FILL
        }
        dropHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            alpha = 28
            style = Paint.Style.FILL
        }
        dropHighlightStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            alpha = 170
            strokeWidth = 3 * density
            style = Paint.Style.STROKE
        }

        // Manual ordering: drag by the handle. The list stays put while
        // dragging; a live indicator shows the landing spot — a highlighted
        // folder header means "files inside", a full-width line means a
        // top-level landing (above, below, or between folders), an indented
        // line means a child landing inside a folder at that position.
        // The move is applied once on release and persisted via
        // CardRepository.saveStructure — the JSON order array is the
        // library order, so it survives backup/restore automatically.
        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            // No live reordering: returning true keeps the dragged view
            // floating while the indicator (onChildDraw) shows the target.
            override fun onMove(
                recyclerView: RecyclerView,
                holder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = true

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG &&
                    viewHolder != null
                ) {
                    dragFromPos = viewHolder.adapterPosition
                    dragTarget = DropTarget.None
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                super.onChildDraw(
                    c, recyclerView, viewHolder, dX, dY,
                    actionState, isCurrentlyActive
                )
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG &&
                    isCurrentlyActive
                ) {
                    val slop =
                        ViewConfiguration.get(recyclerView.context).scaledTouchSlop
                    dragTarget = if (abs(dY) < slop) DropTarget.None
                    else computeDropTarget(recyclerView, viewHolder, dY)
                    drawDropIndicator(c, recyclerView, dragTarget)
                }
            }

            override fun clearView(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ) {
                val target = dragTarget
                val fromPos = dragFromPos
                dragTarget = DropTarget.None
                dragFromPos = RecyclerView.NO_POSITION
                super.clearView(recyclerView, viewHolder)
                if (fromPos != RecyclerView.NO_POSITION &&
                    adapter.applyDrop(fromPos, target)
                ) {
                    persistOrderFromRows()
                    allCards = repository.list()
                    allFolders = repository.listFolders()
                    applyFilter(binding.searchInput.text?.toString().orEmpty())
                    CardShortcuts.refresh(this@MainActivity)
                }
            }
        }
        itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(binding.cardList)

        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            applyFilter(text?.toString().orEmpty())
        }

        binding.fab.setOnClickListener {
            startActivity(Intent(this, CardEditActivity::class.java))
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_folder -> {
                    showFolderDialog(null)
                    true
                }
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

        handleShareIntent(intent)
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * Backup-file tap: a .qrcards file opened from a file manager arrives as
     * ACTION_VIEW with the file URI. It goes through the same import flow as
     * the in-app importer (password prompt for encrypted backups) — import
     * replaces the library, exactly like the menu action does.
     */
    private fun handleViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        if (CardBackup.isEncryptedBackup(this, uri)) {
            showImportPasswordDialog(uri)
        } else {
            doImport(uri, null)
        }
    }

    /**
     * Share-sheet entry: shared text becomes a new-card draft. The type is
     * sniffed ([ShareSniff]) but the editor always opens for confirmation —
     * nothing is ever created silently. A blank share (or anything that
     * isn't text) gets a friendly note instead of a broken editor.
     *
     * Contact shares (e.g. from the Google Contacts app) arrive as a .vcf
     * stream with type text/x-vcard rather than EXTRA_TEXT: the stream is
     * read and fed through the same sniff path, so a shared contact lands
     * as a Contact draft with its name pre-filled.
     */
    private fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        var text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            val stream: Uri? = androidx.core.content.IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )
            text = stream?.let { readStreamText(it) }?.trim().orEmpty()
        }
        if (text.isEmpty()) {
            Toast.makeText(
                this, R.string.share_text_only, Toast.LENGTH_LONG
            ).show()
            return
        }
        val draft = ShareSniff.sniff(text)
        startActivity(
            Intent(this, CardEditActivity::class.java)
                .putExtra(CardEditActivity.EXTRA_SHARE_TYPE, draft.type.name)
                .putExtra(
                    CardEditActivity.EXTRA_SHARE_FIELDS,
                    HashMap(draft.fields),
                )
        )
    }

    /** Reads a shared stream (e.g. a .vcf) into text, best-effort. */
    private fun readStreamText(uri: Uri): String =
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // -- folders --

    /** Icon choices offered in the folder dialog: key → drawable. */
    private val folderIcons = listOf(
        "folder" to R.drawable.ic_folder,
        "star" to R.drawable.ic_folder_star,
        "home" to R.drawable.ic_folder_home,
        "work" to R.drawable.ic_folder_work,
    )

    /**
     * New-folder dialog, or rename when [existing] is given. Name field plus
     * an icon picker row; renaming also offers delete (the folder's cards
     * move back to the top level — never deleted).
     */
    private fun showFolderDialog(existing: CardFolder?) {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (4 * density).toInt(), pad, 0)
        }
        val nameLayout = TextInputLayout(this).apply {
            hint = getString(R.string.folder_name_hint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val nameInput = TextInputEditText(nameLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.name.orEmpty())
        }
        nameLayout.addView(nameInput)
        container.addView(nameLayout)

        val iconLabel = android.widget.TextView(this).apply {
            text = getString(R.string.folder_icon_label)
            setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_LabelMedium
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        }
        container.addView(iconLabel)

        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        var selectedIcon = existing?.icon ?: "folder"
        if (folderIcons.none { it.first == selectedIcon }) selectedIcon = "folder"
        val iconButtons = mutableListOf<android.widget.ImageButton>()
        fun refreshIconSelection() {
            iconButtons.forEachIndexed { index, button ->
                val selected = folderIcons[index].first == selectedIcon
                button.alpha = if (selected) 1f else 0.4f
                button.background = if (selected) {
                    getDrawable(R.drawable.dot_ring)
                } else {
                    getDrawable(R.drawable.dot_none)
                }
            }
        }
        folderIcons.forEach { (key, res) ->
            val button = android.widget.ImageButton(this).apply {
                setImageResource(res)
                contentDescription = key
                val size = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    selectedIcon = key
                    refreshIconSelection()
                }
            }
            iconButtons += button
            iconRow.addView(button)
        }
        container.addView(iconRow)
        refreshIconSelection()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing == null) getString(R.string.new_folder_title)
                else getString(R.string.rename_folder_title)
            )
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (existing == null) R.string.create else R.string.save,
                null,
            )
        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                confirmDeleteFolder(existing)
            }
        }
        val dialog = builder.create()
        dialog.show()
        // Validate before dismissing: the dialog stays open on an empty name.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                nameLayout.error = getString(R.string.folder_name_required)
                return@setOnClickListener
            }
            if (existing == null) {
                repository.saveFolder(
                    CardFolder(id = "", name = name, icon = selectedIcon)
                )
            } else {
                repository.saveFolder(
                    existing.copy(name = name, icon = selectedIcon)
                )
            }
            dialog.dismiss()
            allFolders = repository.listFolders()
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }
    }

    private fun confirmDeleteFolder(folder: CardFolder) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_folder_title, folder.name))
            .setMessage(R.string.delete_folder_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.deleteFolder(folder.id)
                allFolders = repository.listFolders()
                allCards = repository.list()
                applyFilter(binding.searchInput.text?.toString().orEmpty())
            }
            .show()
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
        allFolders = repository.listFolders()
        applyFilter(binding.searchInput.text?.toString().orEmpty())
        // Every library change republishes the quick-access shortcuts and
        // refreshes widget buttons (names change, cards get deleted).
        CardShortcuts.refresh(this)
        MyCardWidgetProvider.updateAll(this)
    }

    // -- drag landing indicator --

    /**
     * Figures out where the dragged row would land from its current visual
     * position: onto a folder header (files inside as first child), or into
     * a gap — as a child at that spot, or as a top-level card above, below,
     * or between folders. Folders can't nest, so a dragged folder always
     * lands top-level, between folder blocks.
     */
    private fun computeDropTarget(
        rv: RecyclerView,
        holder: RecyclerView.ViewHolder,
        dY: Float,
    ): DropTarget {
        val rows = adapter.currentRows()
        val fromPos = holder.adapterPosition
        if (fromPos == RecyclerView.NO_POSITION || fromPos !in rows.indices) {
            return DropTarget.None
        }
        val draggingFolder = adapter.isFolderRow(fromPos)
        val dragged = holder.itemView
        val centerY = dragged.top + dY + dragged.height / 2f

        val siblings = (0 until rv.childCount)
            .map { rv.getChildAt(it) }
            .filter { it !== dragged }
        var hovered: View? = null
        for (child in siblings) {
            if (centerY >= child.top && centerY <= child.bottom) {
                hovered = child
                break
            }
        }
        if (hovered == null) {
            if (siblings.isEmpty()) return DropTarget.None
            val first = siblings.first()
            val last = siblings.last()
            // Above everything visible: insert before the topmost visible
            // non-dragged row. That *is* the dragged row's own slot when it
            // hasn't moved (a later no-op), or the true top when it has.
            if (centerY < first.top) {
                val p = rv.getChildAdapterPosition(first)
                return if (p == RecyclerView.NO_POSITION) DropTarget.None
                else DropTarget.AtGap(p, null)
            }
            if (centerY > last.bottom) {
                val p = rv.getChildAdapterPosition(last)
                return if (p == RecyclerView.NO_POSITION) DropTarget.None
                else DropTarget.AtGap(p + 1, null)
            }
            // Over no sibling but inside the list: the dragged view's own
            // slot (it hasn't left home yet). Nearest sibling decides.
            hovered = siblings.minByOrNull {
                abs(centerY - (it.top + it.height / 2f))
            } ?: return DropTarget.None
        }
        val hoveredPos = rv.getChildAdapterPosition(hovered)
        if (hoveredPos == RecyclerView.NO_POSITION || hoveredPos !in rows.indices) {
            return DropTarget.None
        }
        val before = centerY < hovered.top + hovered.height / 2f
        if (draggingFolder) {
            // Folders can't nest: hovering a header lands before it (upper
            // half) or past its whole block (lower half); hovering children
            // lands between blocks, never splitting one.
            if (adapter.isFolderRow(hoveredPos)) {
                return DropTarget.AtGap(
                    if (before) hoveredPos else blockEnd(hoveredPos), null
                )
            }
            return if (adapter.isChildRow(hoveredPos)) {
                DropTarget.AtGap(
                    if (before) blockStart(hoveredPos) else blockEnd(hoveredPos),
                    null
                )
            } else {
                DropTarget.AtGap(if (before) hoveredPos else hoveredPos + 1, null)
            }
        }
        if (adapter.isFolderRow(hoveredPos)) {
            val folderId = (rows[hoveredPos] as LibraryRow.FolderRow).folder.id
            val fraction = (centerY - hovered.top) / hovered.height.coerceAtLeast(1)
            // Top third of a header: land *between* (above it) as top-level;
            // the rest of the header files the card inside the folder.
            return if (fraction < 0.35) DropTarget.AtGap(hoveredPos, null)
            else DropTarget.IntoFolder(folderId, hoveredPos)
        }
        val row = rows[hoveredPos] as? LibraryRow.CardRow ?: return DropTarget.None
        if (!row.inFolder) {
            return DropTarget.AtGap(if (before) hoveredPos else hoveredPos + 1, null)
        }
        val folderId = row.card.folderId
            ?: return DropTarget.AtGap(
                if (before) hoveredPos else hoveredPos + 1, null
            )
        // Past the last child of the block: that's the between-folders
        // gap, a top-level landing. Anything else stays in the folder.
        if (!before && hoveredPos + 1 >= blockEnd(hoveredPos)) {
            return DropTarget.AtGap(hoveredPos + 1, null)
        }
        return DropTarget.AtGap(if (before) hoveredPos else hoveredPos + 1, folderId)
    }

    /** Header position of the folder block containing [pos]. */
    private fun blockStart(pos: Int): Int {
        var h = pos
        while (h > 0 && !adapter.isFolderRow(h)) h--
        return h
    }

    /**
     * Adapter position just past the last visible child of the folder
     * block containing [pos] (a header or one of its children).
     */
    private fun blockEnd(pos: Int): Int {
        val start = blockStart(pos)
        if (!adapter.isFolderRow(start)) return pos + 1
        var i = start + 1
        while (i < adapter.itemCount && adapter.isChildRow(i)) i++
        return i
    }

    /**
     * Paints the landing indicator for [target]: a highlighted folder
     * header for "files inside", a full-width line for a top-level gap,
     * an indented line for a landing inside a folder.
     */
    private fun drawDropIndicator(
        c: Canvas,
        rv: RecyclerView,
        target: DropTarget,
    ) {
        val lm = rv.layoutManager ?: return
        when (target) {
            is DropTarget.None -> Unit
            is DropTarget.IntoFolder -> {
                val header = lm.findViewByPosition(target.headerPos) ?: return
                val rect = RectF(
                    header.left + 4 * density,
                    header.top + 4 * density,
                    header.right - 4 * density,
                    header.bottom - 4 * density
                )
                val radius = 18 * density
                c.drawRoundRect(rect, radius, radius, dropHighlightPaint)
                c.drawRoundRect(rect, radius, radius, dropHighlightStroke)
            }
            is DropTarget.AtGap -> {
                val y = if (target.insertPos >= adapter.itemCount) {
                    (0 until rv.childCount).maxOfOrNull { rv.getChildAt(it).bottom }
                        ?.toFloat() ?: return
                } else {
                    lm.findViewByPosition(target.insertPos)?.top?.toFloat() ?: return
                }
                // In-folder landings indent to the child content; top-level
                // landings run near full width. Both read as "the card goes
                // exactly here" when you let go.
                val startX = if (target.folderId != null) 56 * density else 16 * density
                val endX = rv.width - 16 * density
                c.drawLine(startX, y, endX, y, dropLinePaint)
                c.drawCircle(startX, y, 5 * density, dropDotPaint)
            }
        }
    }

    /**
     * Derives the persisted library structure from the adapter's current
     * row order: the visual sequence of folder headers and top-level
     * cards, the card order, and each visible card's containing folder —
     * taken from the drop target that placed it, never guessed from its
     * neighbors (that's what used to swallow top-level cards into
     * folders). Cards hidden inside collapsed folders keep their folder
     * and relative order — a drag can never unfile or lose them.
     */
    private fun persistOrderFromRows() {
        val rows = adapter.currentRows()
        val folderIds = rows.filterIsInstance<LibraryRow.FolderRow>()
            .map { it.folder.id }.toSet()
        val order = mutableListOf<OrderEntry>()
        val cardIds = mutableListOf<String>()
        val folderOf = mutableMapOf<String, String?>()
        for (row in rows) {
            when (row) {
                is LibraryRow.FolderRow -> order += OrderEntry.Folder(row.folder.id)
                is LibraryRow.CardRow -> {
                    // Normalize cards pointing at vanished folders
                    // (hand-edited backup): they read as top-level, and
                    // this persists that fix.
                    val fid = row.card.folderId?.takeIf { it in folderIds }
                    folderOf[row.card.id] = fid
                    cardIds += row.card.id
                    if (fid == null) order += OrderEntry.Card(row.card.id)
                }
            }
        }
        repository.saveStructure(order, cardIds, folderOf)
    }

    /**
     * Full library as rows: folder headers and top-level cards interleaved
     * per the saved visual order, each folder's (expanded) children under
     * its header in library order.
     */
    private fun buildRows(cards: List<QrCard>): List<LibraryRow> {
        val rows = mutableListOf<LibraryRow>()
        val folderById = allFolders.associateBy { it.id }
        val folderIds = folderById.keys
        val cardById = cards.associateBy { it.id }
        val byFolder = cards.groupBy { it.folderId }
        val emittedFolders = mutableSetOf<String>()
        val emittedCards = mutableSetOf<String>()

        fun emitFolder(folder: CardFolder) {
            if (!emittedFolders.add(folder.id)) return
            rows += LibraryRow.FolderRow(folder)
            if (!folder.collapsed) {
                byFolder[folder.id].orEmpty().forEach { card ->
                    if (emittedCards.add(card.id)) {
                        rows += LibraryRow.CardRow(card, inFolder = true)
                    }
                }
            }
        }

        // Saved visual order first: folders, with top-level cards
        // interleaved above, below, or between them.
        for (entry in repository.readOrder()) {
            when (entry) {
                is OrderEntry.Folder -> folderById[entry.id]?.let(::emitFolder)
                is OrderEntry.Card -> {
                    val card = cardById[entry.id] ?: continue
                    if (card.id in emittedCards) continue
                    // The order only ever positions top-level cards;
                    // children ride along under their folder header.
                    if (card.folderId?.takeIf { it in folderIds } == null) {
                        emittedCards += card.id
                        rows += LibraryRow.CardRow(card, inFolder = false)
                    }
                }
            }
        }
        // Anything the order doesn't mention (new items, old backups):
        // folders in folder order with their children, then the remaining
        // top-level cards.
        for (folder in allFolders) emitFolder(folder)
        for (card in cards) {
            if (card.id in emittedCards) continue
            // Every folder is emitted above, so a child here would mean a
            // vanished folder — it reads as top-level.
            if (card.folderId?.takeIf { it in folderIds } == null) {
                emittedCards += card.id
                rows += LibraryRow.CardRow(card, inFolder = false)
            } else {
                // Child of an already-emitted folder: rendered via byFolder
                // above; just mark it so it can't double-emit.
                emittedCards += card.id
            }
        }
        return rows
    }

    private fun toggleFolder(folder: CardFolder) {
        repository.setFolderCollapsed(folder.id, !folder.collapsed)
        allFolders = repository.listFolders()
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        // Drag handles only make sense on the full, unfiltered library.
        adapter.dragEnabled = q.isEmpty()
        val rows: List<LibraryRow>
        val empty: Boolean
        if (q.isEmpty()) {
            rows = buildRows(allCards)
            empty = allCards.isEmpty() && allFolders.isEmpty()
        } else {
            val filtered = allCards.filter {
                it.name.lowercase().contains(q) ||
                    typeLabel(it.type, this).lowercase().contains(q)
            }
            // Search flattens to matching cards; folders don't filter.
            rows = filtered.map { LibraryRow.CardRow(it, inFolder = false) }
            empty = filtered.isEmpty()
        }
        // Tighter rows once the list gets long — still breathing room.
        adapter.compact = rows.size >= COMPACT_ROW_THRESHOLD
        adapter.submit(rows)
        if (empty) {
            binding.emptyView.visibility = View.VISIBLE
            if (allCards.isEmpty() && allFolders.isEmpty()) {
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

    companion object {
        /** Row count at which the library list switches to compact rows. */
        private const val COMPACT_ROW_THRESHOLD = 8
    }
}
