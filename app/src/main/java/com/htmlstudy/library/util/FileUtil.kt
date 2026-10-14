package com.htmlstudy.library.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

object FileUtil {

    private val DOC_EXTENSIONS = setOf("html", "htm", "xhtml", "mhtml", "mht")

    private val MIME_MAP = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "xhtml" to "application/xhtml+xml",
        "mhtml" to "multipart/related",
        "mht" to "multipart/related",
        "css" to "text/css",
        "js" to "application/javascript",
        "mjs" to "application/javascript",
        "json" to "application/json",
        "map" to "application/json",
        "wasm" to "application/wasm",
        "xml" to "text/xml",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "md" to "text/markdown",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "ogv" to "video/ogg",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "epub" to "application/epub+zip"
    )

    /**
     * 系统文件选择器只列出这几种类型：HTML 文档（含 .mht/.mhtml 单文件网页存档）。
     *
     * 以前传的是任意类型通配符，图片、视频、PDF、docx 全被倒出来干扰选择。
     * 取值从 MIME_MAP 里取（所以必须写在 MIME_MAP 后面），
     * 这样"选择器能选的"和"应用打得开的"永远一致。
     */
    val DOC_MIME_TYPES = arrayOf(
        MIME_MAP.getValue("html"),
        MIME_MAP.getValue("xhtml"),
        MIME_MAP.getValue("mhtml")
    )

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.US)

    fun isDoc(name: String): Boolean = extensionOf(name) in DOC_EXTENSIONS

    fun mimeOf(name: String): String =
        MIME_MAP[extensionOf(name)] ?: "application/octet-stream"

    fun isImage(name: String): Boolean =
        extensionOf(name) in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif")

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private val ILLEGAL_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /** 去掉文件名里在 FAT/exFAT 上非法的字符，避免导入到存储卡时失败 */
    fun sanitize(name: String): String {
        var out = name
        for (c in ILLEGAL_CHARS) out = out.replace(c, '_')
        out = out.trim().trimEnd('.')
        if (out.isEmpty()) out = "untitled"
        if (out.length > 120) {
            val ext = extensionOf(out)
            val base = out.substringBeforeLast('.', out).take(100)
            out = if (ext.isEmpty()) base else "$base.$ext"
        }
        return out
    }

    /** 在 dir 下找一个不冲突的文件名：report.html → report (2).html */
    fun uniqueFile(dir: File, rawName: String): File {
        val name = sanitize(rawName)
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 2
        while (i < 9999) {
            val next = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            candidate = File(dir, next)
            if (!candidate.exists()) return candidate
            i++
        }
        return File(dir, "$base-${System.currentTimeMillis()}${if (ext.isEmpty()) "" else ".$ext"}")
    }

    fun uniqueDir(parent: File, rawName: String): File {
        val name = sanitize(rawName)
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        var i = 2
        while (i < 9999) {
            candidate = File(parent, "$name ($i)")
            if (!candidate.exists()) return candidate
            i++
        }
        return File(parent, "$name-${System.currentTimeMillis()}")
    }

    fun copyStream(input: InputStream, output: OutputStream): Long {
        var total = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
        }
        output.flush()
        return total
    }

    /** 把单个文件复制到目标目录，返回复制后的文件 */
    fun copyFile(src: File, destDir: File): File {
        val dest = uniqueFile(destDir, src.name)
        destDir.mkdirs()
        FileInputStream(src).use { input ->
            FileOutputStream(dest).use { output -> copyStream(input, output) }
        }
        return dest
    }

    /** 递归复制目录，返回新建的目录 */
    fun copyDirectory(src: File, destDir: File, destName: String? = null): File {
        val dest = uniqueDir(destDir, destName ?: src.name)
        dest.mkdirs()
        val children = src.listFiles() ?: return dest
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                copyDirectory(child, dest, child.name)
            } else {
                runCatching {
                    FileInputStream(child).use { input ->
                        FileOutputStream(File(dest, sanitize(child.name))).use { output ->
                            copyStream(input, output)
                        }
                    }
                }
            }
        }
        return dest
    }

    fun deleteRecursively(target: File): Boolean {
        if (target.isDirectory) {
            target.listFiles()?.forEach { deleteRecursively(it) }
        }
        return target.delete()
    }

    /** 递归统计目录下的文件数量 */
    fun countFiles(dir: File): Int {
        var count = 0
        val children = dir.listFiles() ?: return 0
        for (c in children) {
            if (c.name.startsWith(".")) continue
            count += if (c.isDirectory) countFiles(c) else 1
        }
        return count
    }

    fun sizeOf(dir: File): Long {
        var total = 0L
        val children = dir.listFiles() ?: return 0L
        for (c in children) {
            if (c.name.startsWith(".")) continue
            total += if (c.isDirectory) sizeOf(c) else c.length()
        }
        return total
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0L) return "—"
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(java.util.Date(millis))
    }
}
