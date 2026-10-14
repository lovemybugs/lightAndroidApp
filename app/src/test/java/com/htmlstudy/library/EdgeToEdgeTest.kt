package com.htmlstudy.library

import android.content.Context
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.htmlstudy.library.ui.EdgeToEdge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：顶部白边一条比一条厚。
 *
 * 起因是辅助函数在**调用时**读 `view.paddingTop` 当作"原始内边距"。
 * 系统栏高度一旦加进去，这个值就变成了"原始 + 系统栏"；下次再算一遍，
 * 就变成"原始 + 2×系统栏"——每显示一次工具栏就多一条状态栏高的白边，
 * 来回几次半屏都糊掉了。
 *
 * 这里直接反复派发同一份 insets，检查内边距永远只加一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeToEdgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val bars100 = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 100, 0, 120))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 30, 0, 0))
        .build()

    /** 系统栏隐藏时的 insets：高度全是 0，但监听器还是会收到一次回调 */
    private val barsHidden = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 0, 0))
        .build()

    private fun dispatch(view: View, insets: WindowInsetsCompat) {
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    @Test
    fun `反复显示工具栏不会累积顶部内边距`() {
        val bar = View(context).apply { setPadding(0, 7, 0, 0) }

        repeat(5) {
            EdgeToEdge.padTopBySafeArea(bar, enabled = true)
            dispatch(bar, bars100)
            // 状态栏 100 与挖孔 30 取高者：7 + 100
            assertEquals(107, bar.paddingTop)
        }
    }

    @Test
    fun `隐藏工具栏后内边距回到最初的值`() {
        val bar = View(context).apply { setPadding(0, 7, 0, 0) }
        EdgeToEdge.padTopBySafeArea(bar, enabled = true)
        dispatch(bar, bars100)
        assertEquals(107, bar.paddingTop)

        EdgeToEdge.padTopBySafeArea(bar, enabled = false)
        assertEquals(7, bar.paddingTop)

        // 而且再开一次也只是回到 107，不是 207
        EdgeToEdge.padTopBySafeArea(bar, enabled = true)
        dispatch(bar, bars100)
        assertEquals(107, bar.paddingTop)
    }

    @Test
    fun `沉浸模式下顶部内边距只剩挖孔`() {
        val bar = View(context).apply { setPadding(0, 7, 0, 0) }
        EdgeToEdge.padTopBySafeArea(bar, enabled = true)
        dispatch(bar, bars100)
        dispatch(bar, barsHidden)
        assertEquals(7, bar.paddingTop)
    }

    @Test
    fun `底部内边距跟着导航栏并会归零`() {
        val area = View(context).apply { setPadding(0, 0, 0, 5) }
        EdgeToEdge.padBottomByNavBar(area, extra = 3)
        dispatch(area, bars100)
        assertEquals(5 + 3 + 120, area.paddingBottom)

        // 再派发一次不会叠
        dispatch(area, bars100)
        assertEquals(5 + 3 + 120, area.paddingBottom)

        dispatch(area, barsHidden)
        assertEquals(5 + 3, area.paddingBottom)
    }
}
