package com.htmlstudy.library.data

import android.content.Context
import com.htmlstudy.library.util.FileUtil
import java.io.File
import java.util.Locale

/**
 * 学习库的“文件系统 + 索引”门面。
 *
 * 目录结构就是真实的文件夹结构，用户用电脑/文件管理器看到的就是这个结构；
 * 只有收藏、阅读进度、自定义标题这些额外信息存在 .library.json 里。
 */
class LibraryRepository(context: Context) {

    /**
     * 库根目录。优先放在应用专属外部存储（免权限、可被 USB / 文件管理器读取），
     * 外部存储不可用时退回内部存储。
     */
    val root: File = run {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "library")
    }

    val meta = MetaStore(root)

    /** 库所在的实际路径，用于「关于」里给用户看 */
    val rootPath: String get() = root.absolutePath

    fun init() {
        if (!root.exists()) root.mkdirs()
        runCatching { if (!File(root, Library.NOMEDIA_FILE).exists()) File(root, Library.NOMEDIA_FILE).createNewFile() }
        meta.load()
    }

    fun fileOf(relPath: String): File = if (relPath.isEmpty()) root else File(root, relPath)

    fun resolveDir(relPath: String): File {
        val f = fileOf(relPath)
        return if (f.isDirectory) f else root
    }

    // ---------------------------------------------------------------- 列表

    fun list(dir: File, showAssets: Boolean = true, sortMode: Int = Prefs.SORT_NAME): List<LibraryItem> {
        val children = dir.listFiles() ?: return emptyList()
        val items = children
            .filter { !it.name.startsWith(".") }
            .filter { showAssets || it.isDirectory || FileUtil.isDoc(it.name) }
            .map { LibraryItem.of(root, it) }

        val folders = items.filter { it.isFolder }
        val docs = items.filter { it.isDoc }
        val assets = items.filter { !it.isFolder && !it.isDoc }

        return folders.sortedBy { it.name.lowercase(Locale.getDefault()) } +
                sortDocs(docs, sortMode) +
                assets.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    private fun sortDocs(docs: List<LibraryItem>, sortMode: Int): List<LibraryItem> = when (sortMode) {
        Prefs.SORT_MODIFIED -> docs.sortedByDescending { it.file.lastModified() }
        Prefs.SORT_ADDED -> docs.sortedByDescending { meta.peek(it.relPath)?.addedAt ?: 0L }
        else -> docs.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    /** 库中所有目录（深度优先，用于「移动到…」和导航树） */
    fun allFolders(): List<LibraryItem> {
        val out = ArrayList<LibraryItem>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }) {
                out.add(LibraryItem.of(root, c))
                walk(c)
            }
        }
        walk(root)
        return out
    }

    /** 库中所有 HTML 文档 */
    fun allDocs(): List<LibraryItem> {
        val out = ArrayList<LibraryItem>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.name.startsWith(".")) continue
                if (c.isDirectory) walk(c)
                else if (FileUtil.isDoc(c.name)) out.add(LibraryItem.of(root, c))
            }
        }
        walk(root)
        return out.sortedBy { it.relPath.lowercase(Locale.getDefault()) }
    }

    fun search(query: String): List<LibraryItem> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return emptyList()
        return allDocs().filter { item ->
            val title = meta.titleOf(item.relPath, "")
            item.name.lowercase(Locale.getDefault()).contains(q) ||
                    title.lowercase(Locale.getDefault()).contains(q) ||
                    item.relPath.lowercase(Locale.getDefault()).contains(q)
        }
    }

    fun favorites(): List<LibraryItem> =
        meta.allFavorites()
            .map { it to fileOf(it) }
            .filter { it.second.isFile }
            .map { LibraryItem.of(root, it.second) }
            .sortedBy { it.relPath.lowercase(Locale.getDefault()) }

    fun recent(limit: Int = 60): List<LibraryItem> =
        meta.allOpened()
            .asSequence()
            .map { it.first to fileOf(it.first) }
            .filter { it.second.isFile }
            .map { LibraryItem.of(root, it.second) }
            .take(limit)
            .toList()

    /** 当前目录下没有已收录的 HTML 时，给个提示（判断目录是否为空） */
    fun isEmptyDir(dir: File): Boolean {
        val children = dir.listFiles() ?: return true
        return children.none { !it.name.startsWith(".") }
    }

    fun countFiles(dir: File): Int = FileUtil.countFiles(dir)

    fun sizeOf(dir: File): Long = FileUtil.sizeOf(dir)

    // ---------------------------------------------------------------- 写入

    fun createFolder(parent: File, name: String): File? {
        val clean = FileUtil.sanitize(name.trim())
        if (clean.isEmpty()) return null
        val dir = FileUtil.uniqueDir(parent, clean)
        return if (dir.mkdirs()) dir else null
    }

    fun rename(item: LibraryItem, newName: String): File? {
        val clean = FileUtil.sanitize(newName.trim())
        if (clean.isEmpty() || clean == item.name) return null
        val dest = File(item.file.parentFile, clean)
        if (dest.exists()) return null
        if (!item.file.renameTo(dest)) return null
        val newRel = LibraryItem.relOf(root, dest)
        meta.renamePath(item.relPath, newRel)
        meta.save()
        return dest
    }

    fun delete(item: LibraryItem): Boolean {
        val ok = FileUtil.deleteRecursively(item.file)
        if (ok) {
            meta.removePath(item.relPath)
            meta.save()
        }
        return ok
    }

    /** 把条目移动到目标目录（不允许移动到自身或自己的子目录） */
    fun move(item: LibraryItem, destDir: File): File? {
        if (item.file.parentFile?.absolutePath == destDir.absolutePath) return null
        if (destDir.absolutePath.startsWith(item.file.absolutePath + File.separator)) return null
        if (!destDir.isDirectory) return null
        val dest = FileUtil.uniqueFile(destDir, item.name)
        if (!item.file.renameTo(dest)) return null
        val newRel = LibraryItem.relOf(root, dest)
        meta.renamePath(item.relPath, newRel)
        meta.save()
        return dest
    }

    /** 导入/新建之后刷新索引里的时间戳 */
    fun registerNew(file: File) {
        val rel = LibraryItem.relOf(root, file)
        if (file.isDirectory) {
            file.listFiles()?.forEach { registerNew(it) }
        } else if (FileUtil.isDoc(file.name)) {
            meta.markAdded(rel)
        }
    }

    fun saveMeta() = meta.save()
}
