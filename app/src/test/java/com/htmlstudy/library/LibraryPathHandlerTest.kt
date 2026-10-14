package com.htmlstudy.library

import com.htmlstudy.library.web.LibraryPathHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 虚拟站点 https://study.local/lib/... 的取文件逻辑。
 * 这里既是功能测试，也是目录穿越的防护测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryPathHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `能按相对路径取出文件并给出正确 MIME`() {
        val root = tmp.newFolder("library")
        File(root, "sub").mkdirs()
        File(root, "index.html").writeText("<h1>hi</h1>")
        File(root, "sub/style.css").writeText("body{}")

        val handler = LibraryPathHandler(root)

        val html = handler.handle("/index.html")
        assertNotNull(html)
        assertEquals("text/html", html!!.mimeType)
        assertEquals("<h1>hi</h1>", html.data.reader(Charsets.UTF_8).readText())

        val css = handler.handle("/sub/style.css")
        assertNotNull(css)
        assertEquals("text/css", css!!.mimeType)
    }

    @Test
    fun `中文和空格路径（百分号编码后）能取到`() {
        val root = tmp.newFolder("library")
        File(root, "科目").mkdirs()
        File(root, "科目/第一章.html").writeText("<p>一</p>")
        File(root, "my notes.html").writeText("<p>n</p>")

        val handler = LibraryPathHandler(root)
        val url = LibraryPathHandler.urlFor("科目/第一章.html")

        // urlFor 生成的是可直接交给 WebView 的地址
        assertEquals("https://study.local/lib/%E7%A7%91%E7%9B%AE/%E7%AC%AC%E4%B8%80%E7%AB%A0.html", url)
        assertEquals("my%20notes.html", LibraryPathHandler.urlFor("my notes.html").removePrefix("https://study.local/lib/"))

        // 拿 url 里去掉前缀的那一段喂回去，应该能命中同一个文件
        val path = url.removePrefix("https://study.local/lib/").let { "/$it" }
        val resp = handler.handle(path)
        assertNotNull(resp)
        assertEquals("<p>一</p>", resp!!.data.reader(Charsets.UTF_8).readText())

        assertNotNull(handler.handle("/my%20notes.html"))
    }

    @Test
    fun `目录穿越取不到库外的文件`() {
        val parent = tmp.newFolder("parent")
        val root = File(parent, "library").also { it.mkdirs() }
        File(parent, "secret.txt").writeText("top secret")

        val handler = LibraryPathHandler(root)

        assertNull(handler.handle("/../secret.txt"))
        assertNull(handler.handle("/%2e%2e/secret.txt"))
        assertNull(handler.handle("/sub/../../secret.txt"))
    }

    @Test
    fun `目录和空路径不返回响应`() {
        val root = tmp.newFolder("library")
        File(root, "sub").mkdirs()
        File(root, "a.html").writeText("x")

        val handler = LibraryPathHandler(root)

        assertNull(handler.handle("/"))
        assertNull(handler.handle(""))
        assertNull(handler.handle("/sub"))
        assertNull(handler.handle("/missing.html"))
    }
}
