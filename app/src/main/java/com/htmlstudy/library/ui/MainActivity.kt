package com.htmlstudy.library.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.htmlstudy.library.R
import com.htmlstudy.library.app
import com.htmlstudy.library.data.ImportManager
import com.htmlstudy.library.data.Library
import com.htmlstudy.library.data.LibraryItem
import com.htmlstudy.library.data.LibraryRepository
import com.htmlstudy.library.data.Prefs
import com.htmlstudy.library.databinding.ActivityMainBinding
import com.htmlstudy.library.util.FileUtil
import com.htmlstudy.library.web.LibraryPathHandler
import java.io.File
import java.util.concurrent.Executors

/**
 * 学习库首页：浏览目录、创建科目目录、导入离线资料、搜索、收藏、最近阅读。
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { BROWSE, SEARCH, FAVORITES, RECENT }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ItemAdapter
    private lateinit var repo: LibraryRepository
    private lateinit var prefs: Prefs

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var currentDir: File = File("/")
    private var mode = Mode.BROWSE
    private var query = ""
    private var searchView: SearchView? = null

    // ------------------------------------------------------------------ 选择器

    private val pickDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) importDocuments(uris) }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { askFolderImportMode(it) } }

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importZip(it) } }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = app.prefs
        Prefs.applyTheme(prefs.themeMode)
        super.onCreate(savedInstanceState)

        repo = app.repo
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 窗口铺满整屏（含挖孔区）：顶部安全区加在工具栏上，底部让列表和按钮避开导航栏
        EdgeToEdge.apply(this)
        EdgeToEdge.padTopBySafeArea(binding.appbar)
        EdgeToEdge.padBottomByNavBar(binding.recycler)
        EdgeToEdge.liftAboveNavBar(binding.fab)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        adapter = ItemAdapter(
            repo = repo,
            onOpen = { item -> openItem(item) },
            onMenu = { item, anchor -> showItemActions(item, anchor) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { showCreateSheet() }

        binding.pathBar.setOnClickListener { navigateUp() }

        binding.chips.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_favorites -> setMode(Mode.FAVORITES)
                R.id.chip_recent -> setMode(Mode.RECENT)
                else -> setMode(Mode.BROWSE)
            }
        }

        binding.emptyAction.setOnClickListener { showCreateSheet() }

        currentDir = repo.resolveDir(prefs.lastFolder)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    mode != Mode.BROWSE -> {
                        binding.chips.check(R.id.chip_all)
                        setMode(Mode.BROWSE)
                    }

                    currentDir.absolutePath != repo.root.absolutePath -> navigateUp()

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        repo.saveMeta()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ------------------------------------------------------------------ 菜单

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = getString(R.string.search_hint)
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                searchView?.clearFocus()
                return true
            }

            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty().trim()
                if (query.isEmpty()) {
                    if (mode == Mode.SEARCH) {
                        binding.chips.check(R.id.chip_all)
                        setMode(Mode.BROWSE)
                    }
                } else {
                    setMode(Mode.SEARCH)
                }
                return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_show_assets)?.isChecked = prefs.showAssets
        menu.findItem(R.id.action_sort)?.title = getString(
            when (prefs.sortMode) {
                Prefs.SORT_MODIFIED -> R.string.sort_by_modified
                Prefs.SORT_ADDED -> R.string.sort_by_added
                else -> R.string.sort_by_name
            }
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_sort -> {
                Dialogs.singleChoice(
                    this,
                    getString(R.string.sort),
                    listOf(
                        getString(R.string.sort_by_name),
                        getString(R.string.sort_by_modified),
                        getString(R.string.sort_by_added)
                    ),
                    prefs.sortMode
                ) { which ->
                    prefs.sortMode = which
                    invalidateOptionsMenu()
                    refresh()
                }
                return true
            }

            R.id.action_show_assets -> {
                item.isChecked = !item.isChecked
                prefs.showAssets = item.isChecked
                refresh()
                return true
            }

            R.id.action_theme -> {
                Dialogs.singleChoice(
                    this,
                    getString(R.string.theme),
                    listOf(
                        getString(R.string.theme_system),
                        getString(R.string.theme_light),
                        getString(R.string.theme_dark)
                    ),
                    prefs.themeMode
                ) { which ->
                    prefs.themeMode = which
                    Prefs.applyTheme(which)
                }
                return true
            }

            R.id.action_about -> {
                Dialogs.about(this, repo, prefs)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ 列表

    private fun refresh() {
        when (mode) {
            Mode.BROWSE -> {
                val items = repo.list(currentDir, prefs.showAssets, prefs.sortMode)
                adapter.showPath = false
                adapter.submitList(items)
                updateEmptyState(items.isEmpty())
            }

            else -> {
                val snapshotMode = mode
                val snapshotQuery = query
                io.execute {
                    val items = when (snapshotMode) {
                        Mode.SEARCH -> repo.search(snapshotQuery)
                        Mode.FAVORITES -> repo.favorites()
                        Mode.RECENT -> repo.recent()
                        else -> emptyList()
                    }
                    ui.post {
                        if (mode != snapshotMode || query != snapshotQuery) return@post
                        adapter.showPath = true
                        adapter.submitList(items)
                        updateEmptyState(items.isEmpty())
                    }
                }
            }
        }
        updateHeader()
    }

    private fun updateHeader() {
        val title: String
        val subtitle: String?
        when (mode) {
            Mode.BROWSE -> {
                title = if (currentDir.absolutePath == repo.root.absolutePath) {
                    getString(R.string.app_name)
                } else {
                    currentDir.name
                }
                binding.pathText.text = Library.prettyPath(repo.root, LibraryItem.relOf(repo.root, currentDir))
                binding.pathBar.visibility = View.VISIBLE
                subtitle = getString(R.string.summary_docs, repo.countFiles(currentDir))
            }

            Mode.SEARCH -> {
                title = getString(R.string.search_results)
                binding.pathBar.visibility = View.GONE
                subtitle = null
            }

            Mode.FAVORITES -> {
                title = getString(R.string.favorites)
                binding.pathBar.visibility = View.GONE
                subtitle = null
            }

            Mode.RECENT -> {
                title = getString(R.string.recent)
                binding.pathBar.visibility = View.GONE
                subtitle = null
            }
        }
        supportActionBar?.title = title
        supportActionBar?.subtitle = subtitle
    }

    private fun updateEmptyState(empty: Boolean) {
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) return

        val (textRes, showButton) = when (mode) {
            Mode.BROWSE -> if (currentDir.absolutePath == repo.root.absolutePath) {
                R.string.empty_library to true
            } else {
                R.string.empty_folder to true
            }

            Mode.FAVORITES -> R.string.empty_favorites to false
            Mode.RECENT -> R.string.empty_recent to false
            Mode.SEARCH -> R.string.empty_search to false
        }
        binding.emptyText.text = getString(textRes)
        binding.emptyAction.visibility = if (showButton) View.VISIBLE else View.GONE
    }

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        refresh()
    }

    private fun navigateTo(dir: File) {
        if (!dir.isDirectory) return
        currentDir = dir
        prefs.lastFolder = LibraryItem.relOf(repo.root, dir)
        refresh()
        binding.recycler.scrollToPosition(0)
    }

    private fun navigateUp() {
        val parent = currentDir.parentFile ?: return
        if (!parent.absolutePath.startsWith(repo.root.absolutePath)) return
        navigateTo(parent)
    }

    // ------------------------------------------------------------------ 打开条目

    private fun openItem(item: LibraryItem) {
        when (item.type) {
            LibraryItem.Type.FOLDER -> navigateTo(item.file)
            LibraryItem.Type.DOC -> openDoc(item.relPath, item.name)
            LibraryItem.Type.ASSET -> openExternally(item.file)
        }
    }

    private fun openDoc(relPath: String, title: String) {
        repo.meta.markOpened(relPath)
        repo.saveMeta()
        startActivity(ViewerActivity.intent(this, relPath, title))
    }

    private fun openExternally(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, FileUtil.mimeOf(file.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
        }.onFailure {
            Dialogs.toast(this, getString(R.string.no_app_for_file))
        }
    }

    private fun shareFile(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType(FileUtil.mimeOf(file.name))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }.onFailure {
            Dialogs.toast(this, getString(R.string.no_app_for_file))
        }
    }

    // ------------------------------------------------------------------ 操作表

    private fun showCreateSheet() {
        val actions = listOf(
            SheetAction(getString(R.string.new_folder), R.drawable.ic_folder_new) { createFolderDialog() },
            // 只让选择器列出 HTML，图片/视频/PDF/docx 不会再来干扰
            SheetAction(getString(R.string.import_files), R.drawable.ic_import) {
                pickDocuments.launch(FileUtil.DOC_MIME_TYPES)
            },
            SheetAction(getString(R.string.import_folder), R.drawable.ic_folder_open) { pickFolder.launch(null) },
            SheetAction(getString(R.string.import_zip), R.drawable.ic_zip) {
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        )
        Dialogs.actions(this, getString(R.string.add_to_library), actions)
    }

    private fun createFolderDialog() {
        Dialogs.input(
            this,
            title = getString(R.string.new_folder),
            hintText = getString(R.string.folder_name_hint)
        ) { name ->
            val dir = repo.createFolder(currentDir, name)
            if (dir == null) {
                Dialogs.toast(this, getString(R.string.create_folder_failed))
            } else {
                refresh()
                binding.recycler.scrollToPosition(0)
            }
        }
    }

    private fun showItemActions(item: LibraryItem, anchor: View) {
        val actions = ArrayList<SheetAction>()
        val favorite = repo.meta.isFavorite(item.relPath)
        val title = repo.meta.titleOf(item.relPath, item.name)

        if (!item.isFolder) {
            actions.add(
                SheetAction(
                    getString(if (item.isDoc) R.string.read else R.string.open_with),
                    R.drawable.ic_open
                ) {
                    if (item.isDoc) openDoc(item.relPath, item.name) else openExternally(item.file)
                }
            )
        }

        if (item.isDoc) {
            actions.add(
                SheetAction(
                    getString(if (favorite) R.string.unfavorite else R.string.favorite),
                    if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline
                ) {
                    repo.meta.toggleFavorite(item.relPath)
                    repo.saveMeta()
                    refresh()
                }
            )
        }

        actions.add(SheetAction(getString(R.string.rename), R.drawable.ic_edit) {
            Dialogs.input(
                this,
                title = getString(R.string.rename),
                initial = title,
                hintText = getString(R.string.new_name_hint)
            ) { newName ->
                if (repo.rename(item, newName) == null) {
                    Dialogs.toast(this, getString(R.string.rename_failed))
                }
                refresh()
            }
        })

        actions.add(SheetAction(getString(R.string.move_to), R.drawable.ic_move) {
            Dialogs.folderPicker(
                this,
                repo,
                getString(R.string.move_to),
                exclude = if (item.isFolder) item.file else null
            ) { dest ->
                val moved = repo.move(item, dest)
                if (moved == null) {
                    Dialogs.toast(this, getString(R.string.move_failed))
                } else {
                    Dialogs.toast(this, getString(R.string.moved))
                }
                refresh()
            }
        })

        if (!item.isFolder) {
            actions.add(SheetAction(getString(R.string.share), R.drawable.ic_share) { shareFile(item.file) })
        }

        actions.add(SheetAction(getString(R.string.delete), R.drawable.ic_delete) {
            Dialogs.confirm(
                this,
                getString(R.string.delete),
                getString(
                    R.string.delete_confirm,
                    title,
                    if (item.isFolder) repo.countFiles(item.file) else 1
                ),
                getString(R.string.delete)
            ) {
                if (!repo.delete(item)) {
                    Dialogs.toast(this, getString(R.string.delete_failed))
                }
                refresh()
            }
        })

        Dialogs.actions(this, title, actions)
    }

    // ------------------------------------------------------------------ 导入

    private fun currentImportDir(): File {
        if (mode != Mode.BROWSE) {
            binding.chips.check(R.id.chip_all)
            mode = Mode.BROWSE
        }
        return currentDir
    }

    private fun importDocuments(uris: List<Uri>) {
        val dest = currentImportDir()
        runImport(getString(R.string.importing), { onProgress ->
            ImportManager.importDocuments(this, repo, uris, dest, onProgress)
        }) { result ->
            Dialogs.toast(this, result.describe())
        }
    }

    private fun askFolderImportMode(treeUri: Uri) {
        val dest = currentImportDir()
        Dialogs.singleChoice(
            this,
            getString(R.string.import_folder),
            listOf(getString(R.string.import_folder_as_sub), getString(R.string.import_folder_flatten)),
            0
        ) { which ->
            runImport(getString(R.string.importing), { onProgress ->
                ImportManager.importTree(
                    this, repo, treeUri, dest,
                    asSubfolder = which == 0,
                    onProgress = onProgress
                )
            }) { result ->
                Dialogs.toast(this, result.describe())
            }
        }
    }

    private fun importZip(uri: Uri) {
        val dest = currentImportDir()
        runImport(getString(R.string.extracting), { onProgress ->
            ImportManager.importZip(this, repo, uri, dest, onProgress)
        }) { result ->
            Dialogs.toast(this, result.describe())
        }
    }

    /** 「用其它应用打开 HTML」时：复制进库里的「导入」目录并立即阅读 */
    private fun handleViewIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme == null || data.scheme == "http" || data.scheme == "https") return
        // 防止旋转/重建时重复导入
        intent.action = null

        val dest = File(repo.root, getString(R.string.inbox_folder)).also { it.mkdirs() }
        runImport(getString(R.string.importing), { onProgress ->
            ImportManager.importDocuments(this, repo, listOf(data), dest, onProgress)
        }) { result ->
            val file = result.target?.listFiles()
                ?.filter { FileUtil.isDoc(it.name) }
                ?.maxByOrNull { it.lastModified() }
            if (file != null) {
                openDoc(LibraryItem.relOf(repo.root, file), file.name)
            } else {
                Dialogs.toast(this, result.describe())
            }
        }
    }

    /**
     * 统一的"后台干活 + 进度框"包装。拷贝和解压都在子线程做，完成后回主线程回调。
     */
    private fun runImport(
        message: String,
        block: (onProgress: (String) -> Unit) -> ImportManager.Result,
        onDone: (ImportManager.Result) -> Unit
    ) {
        val progress = ProgressHandle.show(this, message)
        io.execute {
            val result = runCatching { block { name -> progress.update(name) } }
                .getOrElse {
                    ImportManager.Result().apply {
                        failed++
                        errors.add(it.message ?: getString(R.string.import_failed))
                    }
                }
            ui.post {
                progress.dismiss()
                if (!isFinishing && !isDestroyed) {
                    repo.saveMeta()
                    refresh()
                    onDone(result)
                }
            }
        }
    }
}
