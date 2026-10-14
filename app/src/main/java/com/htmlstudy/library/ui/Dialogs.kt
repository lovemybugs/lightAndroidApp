package com.htmlstudy.library.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.htmlstudy.library.R
import com.htmlstudy.library.data.Library
import com.htmlstudy.library.data.LibraryRepository
import com.htmlstudy.library.data.Prefs
import com.htmlstudy.library.util.FileUtil
import java.io.File

/** 一个小动作（左侧图标 + 文案），用于底部弹出的操作表 */
class SheetAction(val label: String, @DrawableRes val icon: Int, val onClick: () -> Unit)

object Dialogs {

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun input(
        context: Context,
        title: String,
        initial: String = "",
        hintText: String = "",
        onConfirm: (String) -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (context.resources.displayMetrics.density * 20).toInt()
            setPadding(pad, (pad * 0.4f).toInt(), pad, 0)
        }
        val layout = TextInputLayout(context).apply {
            this.hint = hintText.ifEmpty { null }
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            isSingleLine = true
        }
        layout.addView(edit)
        container.addView(layout)

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val text = edit.text?.toString().orEmpty().trim()
                if (text.isNotEmpty()) onConfirm(text)
            }
            .show()
    }

    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .show()
    }

    /** 单选列表（排序方式、字号、主题等） */
    fun singleChoice(
        context: Context,
        title: String,
        options: List<String>,
        checkedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), checkedIndex) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun actions(context: Context, title: String?, actions: List<SheetAction>) {
        if (actions.isEmpty()) return
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_actions, null)
        val titleView = view.findViewById<TextView>(R.id.sheet_title)
        val container = view.findViewById<LinearLayout>(R.id.sheet_container)
        if (title.isNullOrBlank()) {
            titleView.visibility = View.GONE
        } else {
            titleView.text = title
        }
        for (action in actions) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_sheet_action, container, false)
            row.findViewById<ImageView>(R.id.action_icon).setImageResource(action.icon)
            row.findViewById<TextView>(R.id.action_label).text = action.label
            row.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
            container.addView(row)
        }
        dialog.setContentView(view)
        dialog.show()
    }

    /**
     * 选择目标目录。destinations = 根目录 + 所有子目录。
     * exclude 用来排除「移动到自身内部」这类非法目标。
     */
    fun folderPicker(
        context: Context,
        repo: LibraryRepository,
        title: String,
        exclude: File? = null,
        onPick: (File) -> Unit
    ) {
        val entries = ArrayList<Pair<String, File>>()
        entries.add(context.getString(R.string.library_root) to repo.root)

        val excludePath = exclude?.absolutePath
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (c in children.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }) {
                if (excludePath != null && (c.absolutePath == excludePath ||
                            c.absolutePath.startsWith(excludePath + File.separator))
                ) continue
                entries.add("${"    ".repeat(depth)}📁 ${c.name}" to c)
                walk(c, depth + 1)
            }
        }
        walk(repo.root, 0)

        val labels = entries.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(labels) { _, which -> onPick(entries[which].second) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun about(context: Context, repo: LibraryRepository, prefs: Prefs) {
        val docs = repo.allDocs().size
        val folders = repo.allFolders().size
        val size = FileUtil.formatSize(repo.sizeOf(repo.root))
        val message = context.getString(
            R.string.about_body,
            repo.rootPath,
            docs.toString(),
            folders.toString(),
            size
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.about)
            .setMessage(message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    fun prettyFolderName(repo: LibraryRepository, dir: File): String {
        val rel = LibraryItem_relOf(repo, dir)
        return Library.prettyPath(repo.root, rel)
    }

    private fun LibraryItem_relOf(repo: LibraryRepository, dir: File): String {
        val rootPath = repo.root.absolutePath
        val p = dir.absolutePath
        return if (p.startsWith(rootPath)) p.substring(rootPath.length).trimStart(File.separatorChar) else dir.name
    }
}
