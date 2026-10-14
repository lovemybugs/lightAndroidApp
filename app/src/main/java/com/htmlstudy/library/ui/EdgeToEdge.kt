package com.htmlstudy.library.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.WeakHashMap

/**
 * 全面屏 / 刘海屏适配。
 *
 * 以前用的是系统默认的"内容自动躲开系统栏"。在把「显示顶部区域」打开的机器上，
 * 系统为了绕开摄像头挖孔，会在窗口顶上留一条用窗口底色填充的空白带 ——
 * 看到的就是正文上方一条白边，而且正文永远铺不进那一块。
 *
 * 现在改成窗口铺满整屏（含挖孔区），系统栏的高度由我们自己作为**内边距**
 * 加在某个有底色的视图上：既不会露出窗口底色，沉浸阅读时正文又能一直画到
 * 屏幕最顶端，和纯 WebView 的阅读器一样是全屏的。
 */
object EdgeToEdge {

    /** 顶部安全区 = 状态栏与挖孔取高者。系统栏隐藏后状态栏算 0，挖孔高度还在 */
    private val TOP_SAFE: Int =
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    /**
     * 每个视图**最初**的内边距 / 外边距。
     *
     * 这里必须缓存，不能在调用时读 `view.paddingTop`：这类辅助函数会被反复
     * 开关（显示工具栏 / 隐藏工具栏、进沉浸 / 出沉浸），读到的是**上一次加过
     * 系统栏高度之后**的值。那样每开关一次就再叠一层，顶部白边越来越厚，
     * 最后能糊掉半屏。缓存原始值后，任何时候算出来的都是 `原始 + 当前系统栏`。
     */
    private val originalPadding = WeakHashMap<View, IntArray>()
    private val originalMargin = WeakHashMap<View, Int>()

    private fun originalPaddingOf(view: View): IntArray =
        originalPadding.getOrPut(view) {
            intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        }

    private fun originalMarginOf(view: View): Int =
        originalMargin.getOrPut(view) {
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        }

    /** 必须在 setContentView 之后调用 */
    fun apply(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // ALWAYS：正文可以画进挖孔区，沉浸阅读时才能真正满屏
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        // 状态栏透明，让工具栏自己的底色铺上去，而不是系统默认的一条纯色
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
    }

    /**
     * 把顶部安全区加进 [view] **自己的**内边距。
     *
     * [view] 必须是那条有底色的条（抽屉头部等）：背景连 padding 一起铺进挖孔区；
     * 套在外层容器上就会露出窗口底色 —— 那正是之前那条白边。
     *
     * @param enabled 隐藏这条时传 false，内边距复位到**最初**的值（不是调用时的值）
     */
    fun padTopBySafeArea(view: View, enabled: Boolean = true) {
        val base = originalPaddingOf(view)[1]
        if (!enabled) {
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
            view.updatePadding(top = base)
            return
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.updatePadding(top = base + insets.getInsets(TOP_SAFE).top)
            // 吃掉：这条本身已经处理过顶部安全区，里面的子视图不用再算一遍
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 底部内边距跟随导航栏：系统栏隐藏时自动归零，全屏阅读不占地方 */
    fun padBottomByNavBar(view: View, extra: Int = 0) {
        val base = originalPaddingOf(view)[3]
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.updatePadding(
                bottom = base + extra +
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 让悬浮按钮浮在导航栏上方 */
    fun liftAboveNavBar(view: View) {
        val base = originalMarginOf(view)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.bottomMargin = base +
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.layoutParams = lp
            }
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(view)
    }
}
