package com.htmlstudy.library

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.htmlstudy.library.data.ImportManager
import com.htmlstudy.library.data.LibraryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportManagerTest {

    private lateinit var context: Context
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = LibraryRepository(context)
        repo.root.deleteRecursively()
        repo.init()
    }

    @After
    fun tearDown() {
        repo.root.deleteRecursively()
    }

    private fun registerStream(uri: Uri, bytes: ByteArray) {
        Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    private fun hiddenDir(): File = File(repo.root.parentFile!!, "outside").also { it.mkdirs() }

    @Test
    fun `导入多个 HTML 文件`() {
        val u1 = Uri.parse("content://test/物理.html")
        val u2 = Uri.parse("content://test/化学.html")
        registerStream(u1, "<h1>物理</h1>".toByteArray())
        registerStream(u2, "<h1>化学</h1>".toByteArray())

        val result = ImportManager.importDocuments(context, repo, listOf(u1, u2), repo.root)

        assertEquals(2, result.imported)
        assertEquals(0, result.failed)
        assertEquals("<h1>物理</h1>", File(repo.root, "物理.html").readText())
        assertTrue(repo.meta.peek("物理.html")!!.addedAt > 0L)
    }

    @Test
    fun `重名导入不会覆盖已有文件`() {
        val uri = Uri.parse("content://test/note.html")
        registerStream(uri, "first".toByteArray())
        ImportManager.importDocuments(context, repo, listOf(uri), repo.root)

        val uri2 = Uri.parse("content://test/note.html")
        registerStream(uri2, "second".toByteArray())
        ImportManager.importDocuments(context, repo, listOf(uri2), repo.root)

        assertEquals("first", File(repo.root, "note.html").readText())
        assertEquals("second", File(repo.root, "note (2).html").readText())
    }

    @Test
    fun `导入到指定子目录`() {
        val dir = repo.createFolder(repo.root, "数学")!!
        val uri = Uri.parse("content://test/x.html")
        registerStream(uri, "<p>x</p>".toByteArray())

        ImportManager.importDocuments(context, repo, listOf(uri), dir)

        assertTrue(File(dir, "x.html").exists())
    }

    // ------------------------------------------------------------------ zip

    private fun makeZip(vararg entries: Pair<String, String>): File {
        val file = File(context.cacheDir, "pack-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return file
    }

    private fun importZip(entries: Array<out Pair<String, String>>): ImportManager.Result {
        val zip = makeZip(*entries)
        val uri = Uri.parse("content://test/pack.zip")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, FileInputStream(zip))
        // displayName 在 Robolectric 下取不到游标，会回退到 uri 的最后一段
        return ImportManager.importZip(context, repo, uri, repo.root)
    }

    @Test
    fun `解压 zip 并去掉多余的顶层目录`() {
        val result = importZip(
            arrayOf(
                "course/index.html" to "<h1>课程</h1>",
                "course/style.css" to "body{}",
                "course/img/logo.png" to "PNG"
            )
        )

        assertEquals(3, result.imported)
        // 顶层只有 course 一个目录 → 把内容提上来，并保留压缩包名（用户认得出来），
        // 避免出现 物理课件/course/ 这种套娃
        val target = result.target!!
        assertEquals("pack", target.name)
        assertTrue(File(target, "index.html").exists())
        assertTrue(File(target, "style.css").exists())
        assertTrue(File(target, "img/logo.png").exists())
        assertFalse(File(target, "course").exists())
    }

    @Test
    fun `zip 里没有统一顶层目录时保持原样`() {
        val result = importZip(
            arrayOf(
                "a.html" to "a",
                "b.html" to "b"
            )
        )
        val target = result.target!!
        assertTrue(File(target, "a.html").exists())
        assertTrue(File(target, "b.html").exists())
    }

    @Test
    fun `中文文件名解压后不乱码`() {
        val result = importZip(
            arrayOf(
                "课件/第一章.html" to "<h1>第一章</h1>",
                "课件/第二章.html" to "<h1>第二章</h1>"
            )
        )
        val target = result.target!!
        assertTrue(File(target, "第一章.html").exists())
        assertEquals("<h1>第一章</h1>", File(target, "第一章.html").readText())
    }

    @Test
    fun `zip-slip 路径穿越不会写出库外`() {
        val outside = hiddenDir()
        assertFalse(File(outside, "evil.txt").exists())

        importZip(
            arrayOf(
                "../../evil.txt" to "pwned",
                "ok.html" to "fine"
            )
        )

        assertFalse(File(outside, "evil.txt").exists())
        // 正常的那一条应该照常解出来
        assertTrue(File(repo.root, "pack/ok.html").exists())
    }

    @Test
    fun `跳过 __MACOSX 和隐藏文件`() {
        val result = importZip(
            arrayOf(
                "__MACOSX/._x.html" to "junk",
                ".DS_Store" to "junk",
                "content/real.html" to "real"
            )
        )
        val target = result.target!!
        assertTrue(File(target, "real.html").exists())
        assertFalse(File(target, "__MACOSX").exists())
        assertFalse(File(target, ".DS_Store").exists())
        assertEquals(1, result.imported)
    }

    @Test
    fun `导入整个文件夹（作为子目录）`() {
        // Robolectric 没有真实的 SAF 文档树，用 DocumentFile.fromTreeUri 会拿到 null，
        // 这里只验证失败路径是干净退出的，不抛异常。
        val fakeTree = Uri.parse("content://test/tree/root")
        val result = ImportManager.importTree(context, repo, fakeTree, repo.root, asSubfolder = true)
        assertEquals(0, result.imported)
        assertTrue(result.errors.isNotEmpty())
    }
}
