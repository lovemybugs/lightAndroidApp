package com.htmlstudy.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.htmlstudy.library.data.LibraryRepository
import com.htmlstudy.library.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 库的增删改查 + 元数据迁移。这是整个 APP 的地基，必须靠得住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        repo = LibraryRepository(context)
        repo.root.deleteRecursively()
        repo.init()
    }

    @After
    fun tearDown() {
        repo.root.deleteRecursively()
    }

    private fun html(name: String, body: String = "<h1>$name</h1>"): File =
        File(repo.root, name).also {
            it.parentFile?.mkdirs()
            it.writeText(body)
        }

    @Test
    fun `新建目录`() {
        val dir = repo.createFolder(repo.root, "高等数学")
        assertNotNull(dir)
        assertEquals("高等数学", dir!!.name)

        // 重名自动加序号，不会互相覆盖
        val second = repo.createFolder(repo.root, "高等数学")
        assertEquals("高等数学 (2)", second!!.name)
    }

    @Test
    fun `列表里目录排在文档前面`() {
        html("b.html")
        html("a.html")
        repo.createFolder(repo.root, "zzz科目")

        val items = repo.list(repo.root, showAssets = true, sortMode = Prefs.SORT_NAME)
        assertTrue(items[0].isFolder)
        assertEquals("zzz科目", items[0].name)
        assertEquals(listOf("a.html", "b.html"), items.drop(1).map { it.name })
    }

    @Test
    fun `可以隐藏非 HTML 素材`() {
        html("a.html")
        File(repo.root, "pic.png").writeText("x")
        File(repo.root, "notes.pdf").writeText("x")

        val all = repo.list(repo.root, showAssets = true, sortMode = Prefs.SORT_NAME)
        assertEquals(3, all.size)

        val docsOnly = repo.list(repo.root, showAssets = false, sortMode = Prefs.SORT_NAME)
        assertEquals(1, docsOnly.size)
        assertEquals("a.html", docsOnly[0].name)
    }

    @Test
    fun `重命名会把收藏和进度一起搬过去`() {
        val file = html("old.html")
        val item = repo.list(repo.root, true, Prefs.SORT_NAME).first()
        repo.meta.setFavorite("old.html", true)
        repo.meta.setScroll("old.html", 0.5f)

        val renamed = repo.rename(item, "new.html")

        assertNotNull(renamed)
        assertEquals("new.html", renamed!!.name)
        assertFalse(file.exists())
        assertTrue(repo.meta.isFavorite("new.html"))
        assertEquals(0.5f, repo.meta.peek("new.html")!!.scroll, 0.001f)
        assertFalse(repo.meta.isFavorite("old.html"))
    }

    @Test
    fun `重命名目录会连带迁移子项的记录`() {
        html("科目/课件.html")
        repo.meta.setFavorite("科目/课件.html", true)

        val folder = repo.list(repo.root, true, Prefs.SORT_NAME).first()
        repo.rename(folder, "新科目")

        assertTrue(repo.meta.isFavorite("新科目/课件.html"))
        assertFalse(repo.meta.isFavorite("科目/课件.html"))
    }

    @Test
    fun `重命名遇到重名会失败`() {
        html("a.html")
        html("b.html")
        val a = repo.list(repo.root, true, Prefs.SORT_NAME).first { it.name == "a.html" }
        assertNull(repo.rename(a, "b.html"))
        assertTrue(File(repo.root, "a.html").exists())
    }

    @Test
    fun `移动到别的目录`() {
        html("a.html")
        val dest = repo.createFolder(repo.root, "数学")!!
        val item = repo.list(repo.root, true, Prefs.SORT_NAME).first { it.name == "a.html" }

        val moved = repo.move(item, dest)

        assertEquals("数学", moved!!.parentFile!!.name)
        assertFalse(File(repo.root, "a.html").exists())
        assertTrue(File(dest, "a.html").exists())
    }

    @Test
    fun `不允许把目录移动到自己的子目录里`() {
        val parent = repo.createFolder(repo.root, "父")!!
        val child = repo.createFolder(parent, "子")!!
        val item = repo.list(repo.root, true, Prefs.SORT_NAME).first { it.name == "父" }

        assertNull(repo.move(item, child))
        assertTrue(parent.exists())
    }

    @Test
    fun `删除目录会清掉整个子树和记录`() {
        html("科目/课件.html")
        repo.meta.setFavorite("科目/课件.html", true)
        val folder = repo.list(repo.root, true, Prefs.SORT_NAME).first()

        assertTrue(repo.delete(folder))
        assertFalse(File(repo.root, "科目").exists())
        assertEquals(0, repo.meta.allFavorites().size)
    }

    @Test
    fun `搜索能按文件名和自定义标题命中`() {
        html("lesson01.html")
        html("other.html")
        repo.meta.setTitle("other.html", "线性代数 第三章")

        assertEquals(1, repo.search("lesson").size)
        assertEquals(1, repo.search("线性代数").size)
        assertEquals(1, repo.search("第三章").size)
        assertEquals(0, repo.search("不存在的关键词").size)
        // 纯空白查询不能把整个库都倒出来
        assertEquals(0, repo.search("   ").size)
    }

    @Test
    fun `收藏和最近打开按时间倒序`() {
        html("a.html")
        html("b.html")
        html("c.html")

        repo.meta.markOpened("a.html")
        Thread.sleep(5)
        repo.meta.markOpened("c.html")
        repo.meta.setFavorite("b.html", true)

        assertEquals(listOf("c.html", "a.html"), repo.recent().map { it.relPath })
        assertEquals(listOf("b.html"), repo.favorites().map { it.relPath })
    }

    @Test
    fun `元数据能落盘并重新读回来`() {
        html("a.html")
        repo.meta.setFavorite("a.html", true)
        repo.meta.setScroll("a.html", 0.42f)
        repo.meta.setTitle("a.html", "自定义名字")
        repo.saveMeta()

        val fresh = LibraryRepository(ApplicationProvider.getApplicationContext())
        fresh.init()

        assertTrue(fresh.meta.isFavorite("a.html"))
        assertEquals(0.42f, fresh.meta.peek("a.html")!!.scroll, 0.001f)
        assertEquals("自定义名字", fresh.meta.titleOf("a.html", "a.html"))
    }

    @Test
    fun `registerNew 会把新导入的文档记进索引`() {
        val file = html("fresh.html")
        repo.registerNew(file)
        repo.saveMeta()

        assertTrue(repo.meta.peek("fresh.html")!!.addedAt > 0L)
        // 按加入时间排序时它应该在最前面
        assertEquals("fresh.html", repo.list(repo.root, true, Prefs.SORT_ADDED).first().name)
    }
}
