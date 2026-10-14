package com.htmlstudy.library

import com.htmlstudy.library.util.FileUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 与 Android 无关的纯工具逻辑，直接跑 JVM 测试 */
class FileUtilTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `识别 HTML 文档`() {
        assertTrue(FileUtil.isDoc("index.html"))
        assertTrue(FileUtil.isDoc("a.HTM"))
        assertTrue(FileUtil.isDoc("x.xhtml"))
        assertFalse(FileUtil.isDoc("style.css"))
        assertFalse(FileUtil.isDoc("noextension"))
    }

    @Test
    fun `MIME 类型判断`() {
        assertEquals("text/html", FileUtil.mimeOf("a.html"))
        assertEquals("text/css", FileUtil.mimeOf("a.css"))
        assertEquals("application/javascript", FileUtil.mimeOf("a.js"))
        assertEquals("font/woff2", FileUtil.mimeOf("f.woff2"))
        assertEquals("image/svg+xml", FileUtil.mimeOf("i.svg"))
        assertEquals("application/octet-stream", FileUtil.mimeOf("weird.xyz"))
    }

    @Test
    fun `清理非法文件名`() {
        assertEquals("a_b_c", FileUtil.sanitize("a/b:c"))
        assertEquals("untitled", FileUtil.sanitize("   "))
        assertEquals("a b", FileUtil.sanitize("a b."))
        assertTrue(FileUtil.sanitize("x".repeat(300)).length <= 120)
    }

    @Test
    fun `同名文件自动加序号且保留扩展名`() {
        val dir = tmp.newFolder()
        File(dir, "note.html").writeText("1")
        val second = FileUtil.uniqueFile(dir, "note.html")
        assertEquals("note (2).html", second.name)

        File(dir, "note (2).html").writeText("2")
        assertEquals("note (3).html", FileUtil.uniqueFile(dir, "note.html").name)

        // 没有扩展名的情况
        File(dir, "readme").writeText("x")
        assertEquals("readme (2)", FileUtil.uniqueFile(dir, "readme").name)
    }

    @Test
    fun `整目录复制保留相对结构并跳过隐藏文件`() {
        val src = tmp.newFolder("src")
        File(src, "sub").mkdirs()
        File(src, "index.html").writeText("<h1>hi</h1>")
        File(src, "sub/asset.css").writeText("body{}")
        File(src, ".hidden").writeText("nope")
        File(src, "__MACOSX").mkdirs()

        val dest = tmp.newFolder("dest")
        val copied = FileUtil.copyDirectory(src, dest, "course")

        assertEquals("course", copied.name)
        assertTrue(File(copied, "index.html").exists())
        assertTrue(File(copied, "sub/asset.css").exists())
        assertFalse(File(copied, ".hidden").exists())
        assertEquals("<h1>hi</h1>", File(copied, "index.html").readText())
    }

    @Test
    fun `统计文件数与大小`() {
        val dir = tmp.newFolder()
        File(dir, "a.html").writeText("12345")
        File(dir, "sub").mkdirs()
        File(dir, "sub/b.html").writeText("67")
        assertEquals(2, FileUtil.countFiles(dir))
        assertEquals(7L, FileUtil.sizeOf(dir))
    }

    @Test
    fun `文件选择器只列 HTML 相关类型`() {
        val types = FileUtil.DOC_MIME_TYPES.toList()

        // 不能再是 "*/*"，否则图片、视频、PDF、docx 全会跑出来干扰选择
        assertFalse(types.contains("*/*"))
        assertEquals(listOf("text/html", "application/xhtml+xml", "multipart/related"), types)

        // 和"应用打得开的格式"保持一致
        assertEquals(FileUtil.mimeOf("a.html"), types[0])
        assertEquals(FileUtil.mimeOf("a.htm"), types[0])
        assertEquals(FileUtil.mimeOf("a.xhtml"), types[1])
        assertEquals(FileUtil.mimeOf("a.mht"), types[2])
        assertEquals(FileUtil.mimeOf("a.mhtml"), types[2])

        // 图片 / PDF / Office 文档都不该出现
        assertFalse(types.contains("image/*"))
        assertFalse(types.contains("application/pdf"))
        assertFalse(types.contains("application/octet-stream"))
    }

    @Test
    fun `格式化大小`() {
        assertEquals("512 B", FileUtil.formatSize(512))
        assertEquals("1.0 KB", FileUtil.formatSize(1024))
        assertEquals("1.5 MB", FileUtil.formatSize(1024L * 1024 * 3 / 2))
    }
}
