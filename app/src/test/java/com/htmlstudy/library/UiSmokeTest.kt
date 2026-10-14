package com.htmlstudy.library

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.htmlstudy.library.data.LibraryRepository
import com.htmlstudy.library.data.Prefs
import com.htmlstudy.library.ui.MainActivity
import com.htmlstudy.library.ui.ViewerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 界面冒烟测试。真机上最怕的就是"一打开就崩"——布局写错、资源名拼错、
 * onCreate 里空指针这类问题只要把 Activity 真正创建一次就能暴露出来。
 * 这里不校验长相，只校验两个界面都能完整走完 onCreate/onStart/onResume。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiSmokeTest {

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

    private fun doc(rel: String, body: String = "<h1>x</h1>"): File =
        File(repo.root, rel).also {
            it.parentFile?.mkdirs()
            it.writeText(body)
        }

    @Test
    fun `主页能启动`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get())
        assertTrue(controller.get().isFinishing.not())
    }

    @Test
    fun `空库时主页也不崩`() {
        // 空目录会走 empty_view 那条分支，单独过一遍
        Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @Test
    fun `阅读器能打开库里的文档`() {
        doc("科目/第一章.html")
        val intent = ViewerActivity.intent(
            ApplicationProvider.getApplicationContext(), "科目/第一章.html", "第一章"
        )
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertNotNull(controller.get())
    }

    @Test
    fun `进入子目录后主页也不崩`() {
        doc("科目/第一章.html")
        val context: Context = ApplicationProvider.getApplicationContext()
        // 模拟"上次停在子目录里"，重启后应该直接回到那一层
        Prefs(context).lastFolder = "科目"
        Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    /**
     * 「设置里开启显示顶部区域后，正文上方一条白边」的根因就是窗口没铺到挖孔区：
     * 系统默认的 DEFAULT 模式会让窗口避开挖孔，那条被避开的地方用窗口底色填充。
     */
    @Test
    fun `阅读器窗口铺满整屏并允许画进挖孔区`() {
        doc("科目/第一章.html")
        val intent = ViewerActivity.intent(
            ApplicationProvider.getApplicationContext(), "科目/第一章.html", "第一章"
        )
        assertFullScreen(Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get())
    }

    /**
     * 阅读器不再用顶部工具栏，而是在左边留一个能拖出来的小面板。
     * 左边缘的手势要留给这个面板，所以目录树改成只能从面板里的「科目目录」按钮打开，
     * 否则同一处的手势会互相打架（想拉面板却滑出了目录）。
     */
    @Test
    fun `阅读器的左边缘留给工具面板把手`() {
        // 故意起一个很长的标题：面板宽度是固定的，标题在真机上会被 ellipsize 截断，
        // 一个长名字不该把这块"细长面板"撑成半屏。
        val longName = "这是一篇名字特别长的课件用来确认面板不会被标题撑开.html"
        doc("科目/$longName")
        val intent = ViewerActivity.intent(
            ApplicationProvider.getApplicationContext(), "科目/$longName", longName
        )
        val activity = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()

        val drawer = activity.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer)
        assertEquals(
            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            drawer.getDrawerLockMode(androidx.core.view.GravityCompat.START)
        )
        // 把手和面板都在（收起状态下把手贴在屏幕左边缘，面板整体在屏幕外）
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tool_handle).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tool_panel).visibility)

        val box = activity.findViewById<View>(R.id.content_area)
        val panel = activity.findViewById<View>(R.id.tool_panel)
        val handle = activity.findViewById<View>(R.id.tool_handle)
        val boxW = (box.width - box.paddingLeft - box.paddingRight).toFloat()
        val boxH = (box.height - box.paddingTop - box.paddingBottom).toFloat()

        // 默认贴在左边、竖着、上下居中
        assertTrue("默认应该是个竖把手", handle.height > handle.width)
        assertEquals(0f, handle.translationX, 0.5f)
        assertEquals((boxH - handle.height) / 2f, handle.translationY, 1f)

        // 面板收在屏幕外：它的右边正好和把手贴着的那条边对齐
        assertEquals(panel.width.toFloat() + panel.translationX, handle.translationX, 1f)

        val widthDp = panel.width / activity.resources.displayMetrics.density
        assertTrue("面板宽度 $widthDp dp，应该是条细长面板而不是半屏", widthDp in 100f..220f)
    }

    @Test
    fun `把手拖到下边之后，下次打开还在下边`() {
        doc("科目/第二章.html")
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.handleEdge = com.htmlstudy.library.ui.ChromeGeometry.BOTTOM
        prefs.handleFraction = 0.25f

        val intent = ViewerActivity.intent(
            ApplicationProvider.getApplicationContext(), "科目/第二章.html", "第二章"
        )
        val activity = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()

        val box = activity.findViewById<View>(R.id.content_area)
        val panel = activity.findViewById<View>(R.id.tool_panel)
        val handle = activity.findViewById<View>(R.id.tool_handle)
        val boxW = (box.width - box.paddingLeft - box.paddingRight).toFloat()
        val boxH = (box.height - box.paddingTop - box.paddingBottom).toFloat()

        // 位置记在 Prefs 里：这次就应该是在下边、横着的把手
        assertTrue("贴在下边就该是个横把手", handle.width > handle.height)
        assertEquals(boxH - handle.height, handle.translationY, 1f)
        assertEquals(0.25f * boxW - handle.width / 2f, handle.translationX, 1f)

        // 面板也跟着跑到下边：收起来时整体在屏幕下方之外
        assertEquals(boxH, panel.translationY, 1f)
    }

    @Test
    fun `主页窗口也铺满整屏`() {
        assertFullScreen(Robolectric.buildActivity(MainActivity::class.java).setup().get())
    }

    private fun assertFullScreen(activity: android.app.Activity) {
        val window = activity.window
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        assertEquals(expected, window.attributes.layoutInDisplayCutoutMode)
        // 状态栏透明：顶部那一条显示的是工具栏自己的底色，不再是系统刷的一条纯色
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
    }
}
