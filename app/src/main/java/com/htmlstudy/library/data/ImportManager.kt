package com.htmlstudy.library.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.htmlstudy.library.util.FileUtil
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 把手机里的离线资料搬进学习库。
 *
 * 三种入口：
 *  1. 选若干 HTML 文件（散装文档）
 *  2. 选一个文件夹（课件通常是 `xxx.html` + `xxx_files/` 一起的，必须整目录搬）
 *  3. 选一个 zip / 7z 下载包（自动解压，自动去掉多余的顶层目录）
 */
object ImportManager {

    private const val TAG = "ImportManager"

    class Result {
        var imported = 0
        var failed = 0
        var target: File? = null
        val errors = ArrayList<String>()

        fun describe(): String = buildString {
            append("导入 ").append(imported).append(" 个文件")
            if (failed > 0) append("，失败 ").append(failed).append(" 个")
            if (errors.isNotEmpty()) {
                append("\n")
                append(errors.take(3).joinToString("\n"))
                if (errors.size > 3) append("\n…")
            }
        }
    }

    /** 从 SAF 的 Uri 里拿显示名 */
    fun displayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        val value = cursor.getString(idx)
                        if (!value.isNullOrBlank()) return value
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------ 多文件导入

    fun importDocuments(
        context: Context,
        repo: LibraryRepository,
        uris: List<Uri>,
        destDir: File,
        onProgress: (String) -> Unit = {}
    ): Result {
        val result = Result()
        destDir.mkdirs()
        for (uri in uris) {
            val name = displayName(context, uri) ?: "imported_${System.currentTimeMillis()}"
            onProgress(name)
            runCatching {
                val dest = FileUtil.uniqueFile(destDir, name)
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取 $name" }
                    FileOutputStream(dest).use { output -> FileUtil.copyStream(input, output) }
                }
                result.imported++
                repo.registerNew(dest)
            }.onFailure {
                Log.w(TAG, "导入失败 $name", it)
                result.failed++
                result.errors.add("$name: ${it.message ?: "读取失败"}")
            }
        }
        result.target = destDir
        repo.saveMeta()
        return result
    }

    // ------------------------------------------------------------ 文件夹导入

    /**
     * 整目录导入。asSubfolder = true 时会在 destDir 下新建同名子目录，
     * 否则把内容直接铺到 destDir 里。
     */
    fun importTree(
        context: Context,
        repo: LibraryRepository,
        treeUri: Uri,
        destDir: File,
        asSubfolder: Boolean,
        onProgress: (String) -> Unit = {}
    ): Result {
        val result = Result()
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.isDirectory) {
            result.failed++
            result.errors.add("无法读取所选文件夹")
            return result
        }
        val folderName = tree.name ?: "导入的文件夹"
        val container: File = if (asSubfolder) {
            FileUtil.uniqueDir(destDir, folderName).also { it.mkdirs() }
        } else {
            destDir.mkdirs()
            destDir
        }
        copyDocumentTree(context, tree, container, result, onProgress)
        result.target = container
        repo.registerNew(container)
        repo.saveMeta()
        return result
    }

    private fun copyDocumentTree(
        context: Context,
        dir: DocumentFile,
        destDir: File,
        result: Result,
        onProgress: (String) -> Unit
    ) {
        destDir.mkdirs()
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            val rawName = child.name ?: continue
            if (rawName.startsWith(".") || rawName == "__MACOSX") continue
            val name = FileUtil.sanitize(rawName)
            try {
                if (child.isDirectory) {
                    val sub = File(destDir, name)
                    sub.mkdirs()
                    copyDocumentTree(context, child, sub, result, onProgress)
                } else {
                    onProgress(name)
                    val out = FileUtil.uniqueFile(destDir, name)
                    context.contentResolver.openInputStream(child.uri).use { input ->
                        requireNotNull(input) { "无法读取 $name" }
                        FileOutputStream(out).use { output -> FileUtil.copyStream(input, output) }
                    }
                    result.imported++
                }
            } catch (e: Exception) {
                Log.w(TAG, "复制失败 $name", e)
                result.failed++
                if (result.errors.size < 8) result.errors.add("$name: ${e.message ?: "复制失败"}")
            }
        }
    }

    // ------------------------------------------------------------ ZIP 导入

    fun importZip(
        context: Context,
        repo: LibraryRepository,
        uri: Uri,
        destDir: File,
        onProgress: (String) -> Unit = {}
    ): Result {
        val result = Result()
        destDir.mkdirs()
        val zipName = displayName(context, uri) ?: "archive.zip"
        val baseName = zipName.substringBeforeLast('.', zipName).ifBlank { "archive" }

        val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取压缩包" }
                FileOutputStream(tempFile).use { output -> FileUtil.copyStream(input, output) }
            }

            val container = FileUtil.uniqueDir(destDir, baseName)
            container.mkdirs()

            // 中文 zip 常见 GBK 文件名；带 UTF-8 标志位的条目仍会按 UTF-8 解析
            val zip = runCatching { ZipFile(tempFile, Charset.forName("GBK")) }
                .getOrElse { ZipFile(tempFile, Charsets.UTF_8) }

            val containerPath = container.canonicalPath
            zip.use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val rawName = entry.name.replace('\\', '/')
                    if (rawName.startsWith("__MACOSX") || rawName.split('/').any { it.startsWith(".") }) continue
                    val outFile = File(container, rawName)
                    // zip-slip 防护：解压后的路径必须还在目标目录里
                    if (!outFile.canonicalPath.startsWith(containerPath)) continue
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                        continue
                    }
                    outFile.parentFile?.mkdirs()
                    onProgress(outFile.name)
                    zf.getInputStream(entry).use { input ->
                        BufferedInputStream(input).use { buffered ->
                            FileOutputStream(outFile).use { output -> FileUtil.copyStream(buffered, output) }
                        }
                    }
                    result.imported++
                }
            }

            // 压缩包里只有一个顶层目录时，把它提上来，省得目录套目录
            val flattened = flattenSingleRoot(container)
            result.target = flattened
            repo.registerNew(flattened)
            repo.saveMeta()
        } catch (e: Exception) {
            Log.w(TAG, "解压失败", e)
            result.failed++
            result.errors.add(e.message ?: "解压失败")
        } finally {
            tempFile.delete()
        }
        return result
    }

    /**
     * 只保留一层目录：像 物理课件.zip 里套着 course/index.html 这种，
     * 解出来直接是 物理课件/index.html，而不是 物理课件/course/index.html。
     *
     * 注意保留的是压缩包名（用户自己起的，认得出来），内层目录名（常常是
     * course/dist/www 这类下载站默认名）只作为内容来源。
     */
    private fun flattenSingleRoot(container: File): File {
        val children = container.listFiles()?.filter { !it.name.startsWith(".") } ?: return container
        if (children.size != 1 || !children[0].isDirectory) return container
        val inner = children[0]
        inner.listFiles()?.forEach { child ->
            val existing = File(container, child.name)
            val dest = if (existing.exists()) FileUtil.uniqueFile(container, child.name) else existing
            child.renameTo(dest)
        }
        // 只有搬空了才删掉内层目录，搬不动的就留在原地，不丢文件
        if (inner.listFiles()?.isEmpty() == true) inner.delete()
        return container
    }
}
