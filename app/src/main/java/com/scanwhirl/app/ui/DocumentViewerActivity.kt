package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityDocumentViewerBinding
import com.scanwhirl.app.databinding.ItemPageBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.launch
import java.io.File
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentViewerBinding
    private val repo by lazy { (application as ScanWhirlApp).repository }
    private var document: ScanDocument? = null
    private var docId: String? = null

    private var pendingDownload: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
            pendingDownload = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        docId = intent.getStringExtra(EXTRA_DOC_ID)
        if (!reloadDocument()) return

        binding.pageList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.pageList.adapter = PageAdapter(document!!.pageFiles) { index ->
            showPageActions(index)
        }

        binding.btnShare.setOnClickListener { document?.let { ShareUtils.share(this, it) } }
        binding.btnDelete.setOnClickListener { document?.let { confirmDelete(it) } }
    }

    override fun onResume() {
        super.onResume()
        // AppCompat re-syncs the toolbar title from the manifest label during resume;
        // re-apply the document name so it wins.
        document?.let { binding.toolbar.title = it.name }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_rename -> { promptRename(); true }
        R.id.action_save_device -> { promptDownload(); true }
        R.id.action_save_pdf -> { savePdfToDocuments(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Re-reads the document from disk and refreshes all UI. Returns false if it vanished. */
    private fun reloadDocument(): Boolean {
        val doc = repo.listDocuments().find { it.id == docId }
        if (doc == null) {
            Toast.makeText(this, R.string.doc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        document = doc
        binding.toolbar.title = doc.name
        binding.pageCount.text =
            resources.getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
        (binding.pageList.adapter as? PageAdapter)?.submit(doc.pageFiles)
            ?: run { binding.pageList.adapter = PageAdapter(doc.pageFiles) { showPageActions(it) } }
        binding.btnShare.isVisible = doc.pdfFile.exists() || doc.pageFiles.isNotEmpty()
        return true
    }

    /** Copies the document PDF into Documents/ScanWhirl so it shows up in the Files app. */
    private fun savePdfToDocuments() {
        val doc = document ?: return
        if (!doc.pdfFile.exists() || doc.pdfFile.length() == 0L) {
            Toast.makeText(this, R.string.pdf_missing, Toast.LENGTH_SHORT).show()
            return
        }
        withStoragePermission {
            lifecycleScope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        DownloadSaver.savePdf(
                            this@DocumentViewerActivity,
                            doc.pdfFile.readBytes(),
                            DownloadSaver.pdfNameFor(doc.name)
                        )
                    }
                    Toast.makeText(
                        this@DocumentViewerActivity,
                        getString(R.string.pdf_saved_docs, name),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@DocumentViewerActivity,
                        getString(R.string.pdf_save_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showPageActions(index: Int) {
        val doc = document ?: return
        if (index !in doc.pageFiles.indices) return

        val actions = buildList {
            add(getString(R.string.save_this_page) to Action.SAVE_DEVICE)
            add(getString(R.string.action_rotate) to Action.ROTATE)
            if (index > 0) add(getString(R.string.action_move_earlier) to Action.MOVE_EARLIER)
            if (index < doc.pageFiles.size - 1) add(getString(R.string.action_move_later) to Action.MOVE_LATER)
            if (doc.pageFiles.size > 1) add(getString(R.string.action_delete_page) to Action.DELETE)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.page_actions_title, index + 1))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                performPageAction(actions[which].second, index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private enum class Action { SAVE_DEVICE, ROTATE, MOVE_EARLIER, MOVE_LATER, DELETE }

    private fun performPageAction(action: Action, index: Int) {
        if (action == Action.SAVE_DEVICE) {
            downloadPages(listOf(index))
            return
        }
        val id = docId ?: return
        lifecycleScope.launch {
            try {
                when (action) {
                    Action.SAVE_DEVICE -> Unit // handled before the coroutine
                    Action.ROTATE -> repo.rotatePage(id, index)
                    Action.DELETE -> repo.deletePage(id, index)
                    Action.MOVE_EARLIER -> repo.movePage(id, index, index - 1)
                    Action.MOVE_LATER -> repo.movePage(id, index, index + 1)
                }
                reloadDocument()
            } catch (e: Exception) {
                Toast.makeText(
                    this@DocumentViewerActivity,
                    getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun promptRename() {
        val doc = document ?: return
        val input = EditText(this).apply { setText(doc.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()
                if (!newName.isNullOrBlank()) {
                    repo.renameDocument(doc.id, newName.trim())
                    reloadDocument()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptDownload() {
        val doc = document ?: return
        if (doc.pageFiles.isEmpty()) return
        val current = (binding.pageList.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        val items = arrayOf(
            getString(R.string.save_this_page),
            getString(R.string.save_all_pages)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.save_to_device)
            .setItems(items) { _, which ->
                val indices: List<Int> =
                    if (which == 0) listOf(current.coerceAtMost(doc.pageFiles.lastIndex))
                    else doc.pageFiles.indices.toList()
                downloadPages(indices)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadPages(indices: List<Int>) {
        val doc = document ?: return
        withStoragePermission {
            lifecycleScope.launch {
                var count = 0
                var lastName = ""
                var error: String? = null
                for (i in indices) {
                    if (i !in doc.pageFiles.indices) continue
                    val file = doc.pageFiles[i]
                    val name = DownloadSaver.fileNameFor(doc.name, i + 1, doc.pageFiles.size)
                    try {
                        lastName = withContext(Dispatchers.IO) {
                            DownloadSaver.saveJpeg(
                                this@DocumentViewerActivity,
                                file.readBytes(),
                                name
                            )
                        }
                        count++
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
                val toast = when {
                    count == 1 -> getString(R.string.dl_saved, lastName)
                    count > 1 -> getString(R.string.dl_saved_all, count)
                    else -> getString(R.string.dl_failed, error)
                }
                Toast.makeText(
                    this@DocumentViewerActivity,
                    toast,
                    if (count > 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingDownload = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun confirmDelete(doc: ScanDocument) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, doc.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                repo.deleteDocument(doc.id)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_DOC_ID = "doc_id"
        fun intent(context: Context, docId: String): Intent =
            Intent(context, DocumentViewerActivity::class.java).putExtra(EXTRA_DOC_ID, docId)
    }
}

class PageAdapter(
    private var pages: List<File>,
    private val onTap: (Int) -> Unit
) : RecyclerView.Adapter<PageAdapter.VH>() {

    fun submit(newPages: List<File>) {
        pages = newPages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val binding = ItemPageBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(pages[position], position + 1)
    }

    override fun getItemCount() = pages.size

    inner class VH(private val binding: ItemPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File, number: Int) {
            binding.pageImage.setImageURI(null)
            binding.pageImage.setImageURI(android.net.Uri.fromFile(file))
            binding.pageNumber.text = String.format(java.util.Locale.ROOT, "%d", number)
            binding.root.setOnClickListener { onTap(bindingAdapterPosition) }
        }
    }
}
