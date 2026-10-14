package com.htmlstudy.library

import com.htmlstudy.library.ui.ChromeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把手拖到别处之后，它停在哪条边、停在这条边的什么位置 —— 纯算术，不用 Robolectric。
 */
class ChromeGeometryTest {

    private val w = 1080f
    private val h = 2400f

    @Test
    fun `吸附到最近的那条边`() {
        assertEquals(ChromeGeometry.LEFT, ChromeGeometry.snapEdge(40f, 1200f, w, h))
        assertEquals(ChromeGeometry.RIGHT, ChromeGeometry.snapEdge(1040f, 1200f, w, h))
        assertEquals(ChromeGeometry.TOP, ChromeGeometry.snapEdge(540f, 30f, w, h))
        assertEquals(ChromeGeometry.BOTTOM, ChromeGeometry.snapEdge(540f, 2380f, w, h))
    }

    @Test
    fun `从左边拖到右边就停在右边`() {
        // 手指把把手从左侧挪到屏幕右侧
        val edge = ChromeGeometry.snapEdge(900f, 1000f, w, h)
        assertEquals(ChromeGeometry.RIGHT, edge)
        // 而且记住的是"高度上的比例"，转屏后还在同一段高度
        assertEquals(1000f / h, ChromeGeometry.fractionAlong(edge, 900f, 1000f, w, h), 1e-4f)
    }

    @Test
    fun `从上边拖到下边就停在下边`() {
        val edge = ChromeGeometry.snapEdge(300f, 2200f, w, h)
        assertEquals(ChromeGeometry.BOTTOM, edge)
        assertEquals(300f / w, ChromeGeometry.fractionAlong(edge, 300f, 2200f, w, h), 1e-4f)
    }

    @Test
    fun `比例换成偏移时把手整条都在屏幕里`() {
        val len = 168f
        // 正中间 → 居中
        assertEquals((h - len) / 2f, ChromeGeometry.offsetAlong(0.5f, h, len), 0.5f)
        // 两端 → 正好贴边，不探出去
        assertEquals(0f, ChromeGeometry.offsetAlong(0f, h, len), 0.5f)
        assertEquals(h - len, ChromeGeometry.offsetAlong(1f, h, len), 0.5f)
        // 超出范围的比例也被夹住
        assertEquals(h - len, ChromeGeometry.offsetAlong(3f, h, len), 0.5f)
        assertEquals(0f, ChromeGeometry.offsetAlong(-3f, h, len), 0.5f)
    }

    @Test
    fun `把手停在哪，换算回比例再放回去还在那`() {
        // 存的是比例、用的是偏移。所以真正要保证的是：屏幕上的一个落点，
        // 换算成比例再换算回来（转屏之后也一样）还是同一个落点。
        val len = 168f
        for (offset in listOf(0f, 100f, (h - len) / 2f, h - len)) {
            val center = offset + len / 2f
            val f = ChromeGeometry.fractionAlong(ChromeGeometry.LEFT, 0f, center, w, h)
            assertEquals(offset, ChromeGeometry.offsetAlong(f, h, len), 0.5f)
        }
    }

    @Test
    fun `面板对齐全把手并留在屏幕内`() {
        val panel = 900f
        val handle = 168f
        assertEquals(0f, ChromeGeometry.panelAlign(0f, h, panel, handle), 0.5f)
        assertEquals(h - panel, ChromeGeometry.panelAlign(1f, h, panel, handle), 0.5f)
        assertEquals((h - panel) / 2f, ChromeGeometry.panelAlign(0.5f, h, panel, handle), 0.5f)
    }

    @Test
    fun `面板比屏幕还长时不会算出负数`() {
        assertEquals(0f, ChromeGeometry.panelAlign(0.5f, 400f, 900f, 168f), 0.5f)
        assertEquals(0f, ChromeGeometry.offsetAlong(0.5f, 100f, 168f), 0.5f)
    }

    @Test
    fun `左右边是竖把手，上下边是横把手`() {
        assertTrue(ChromeGeometry.isVertical(ChromeGeometry.LEFT))
        assertTrue(ChromeGeometry.isVertical(ChromeGeometry.RIGHT))
        assertTrue(!ChromeGeometry.isVertical(ChromeGeometry.TOP))
        assertTrue(!ChromeGeometry.isVertical(ChromeGeometry.BOTTOM))
    }
}
