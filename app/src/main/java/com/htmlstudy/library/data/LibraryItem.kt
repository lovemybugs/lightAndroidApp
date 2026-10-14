package com.htmlstudy.library.data

import com.htmlstudy.library.util.FileUtil
import java.io.File

/**
 * 库里的一个条目：目录 / HTML 文档 / 其它素材文件。
 */
class LibraryItem(
    val file: File,
    val relPath: String,
    val type: Type
) {
    enum class Type { FOLDER, DOC, ASSET }

    val name: String get() = file.name
    val isFolder: Boolean get() = type == Type.FOLDER
    val isDoc: Boolean get() = type == Type.DOC

    companion object {
        fun of(root: File, file: File): LibraryItem {
            val rel = relOf(root, file)
            val type = when {
                file.isDirectory -> Type.FOLDER
                FileUtil.isDoc(file.name) -> Type.DOC
                else -> Type.ASSET
            }
            return LibraryItem(file, rel, type)
        }

        fun relOf(root: File, file: File): String {
            val rootPath = root.absolutePath
            val p = file.absolutePath
            return if (p.startsWith(rootPath)) p.substring(rootPath.length).trimStart(File.separatorChar) else file.name
        }
    }
}
