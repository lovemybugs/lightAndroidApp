package com.htmlstudy.library

import com.htmlstudy.library.ui.BackdropMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "活背景"里那几步纯计算。这台机器上没有模拟器（/dev/kvm 不可用），
 * 截图、着色器都没法真跑，所以把最容易错的部分抽出来在 JVM 上钉死：
 * 位移对不对得出来、什么时候该认输、补行会不会越界、条子摆到哪里。
 */
class BackdropMathTest {

    /**
     * 造一条有花纹的亮度曲线：每隔 7 个像素一条深色"文字"，再叠一点起伏。
     * 起伏必须够乱 —— 用 y 的线性函数那种"周期性噪声"会和文字行凑出第二个
     * 完全对得上的位置（周期的最小公倍数），那样的曲线本来就不该指望能量准。
     */
    private fun textured(h: Int, seed: Int): IntArray {
        val out = IntArray(h)
        for (y in 0 until h) {
            val line = if (y % 7 == 0) 40 else 210
            val noise = (mix(y + seed * 1013) and 0x1F) - 16
            out[y] = (line + noise).coerceIn(0, 255)
        }
        return out
    }

    /** 一个够散的整数哈希，让"花纹"没有短周期 */
    private fun mix(x: Int): Int {
        var v = x * 0x9E3779B1.toInt()
        v = v xor (v ushr 15)
        v *= 0x85EBCA6B.toInt()
        v = v xor (v ushr 13)
        return v
    }

    private fun columns(h: Int, seed: Int, n: Int = 4): Array<IntArray> =
        Array(n) { i -> textured(h, seed + i) }

    /** 内容往上走了 [shift] 像素 → 新的一张在第 y 行看到的是旧图第 y+shift 行 */
    private fun shifted(src: Array<IntArray>, shift: Int, h: Int): Array<IntArray> =
        Array(src.size) { i ->
            IntArray(h) { y -> if (y + shift in 0 until h) src[i][y + shift] else 255 }
        }

    @Test
    fun `往下滚时能量出正的位移`() {
        val h = 600
        val prev = columns(h, seed = 3)
        val next = shifted(prev, shift = 120, h = h)
        val rows = IntArray(40) { it + 300 }

        val d = BackdropMath.bestShift(prev, next, rows, guess = 110, window = 60)

        assertEquals(120, d)
    }

    @Test
    fun `往上滚时能量出负的位移`() {
        val h = 600
        val prev = columns(h, seed = 8)
        val next = shifted(prev, shift = -90, h = h)
        val rows = IntArray(40) { it + 300 }

        val d = BackdropMath.bestShift(prev, next, rows, guess = -80, window = 50)

        assertEquals(-90, d)
    }

    @Test
    fun `网页报的估计值差得远时，只要在搜索窗口里照样能量出来`() {
        val h = 700
        val prev = columns(h, seed = 11)
        val next = shifted(prev, shift = 240, h = h)
        val rows = IntArray(48) { it + 380 }

        // 网页报 100、实际 240：窗口给宽一点，真正的位置不能被挡在窗口外
        val d = BackdropMath.bestShift(prev, next, rows, guess = 100, window = 300)

        assertEquals(240, d)
    }

    @Test
    fun `纯色背景量不出来，宁可返回 null`() {
        val h = 400
        val flat = Array(4) { IntArray(h) { 250 } }
        val rows = IntArray(40) { it + 200 }

        assertNull(BackdropMath.bestShift(flat, flat, rows, guess = 0, window = 80))
        // 一模一样的纯色，就算报了位移也不能信
        assertNull(BackdropMath.bestShift(flat, flat, rows, guess = 30, window = 80))
    }

    @Test
    fun `周期花纹对不准，也返回 null`() {
        val h = 400
        // 每 20px 一个周期：位移 20 的整数倍看起来都"对得上"
        val periodic = Array(4) { IntArray(h) { y -> if (y % 20 == 0) 30 else 230 } }
        val rows = IntArray(40) { it + 200 }

        assertNull(BackdropMath.bestShift(periodic, periodic, rows, guess = 10, window = 30))
    }

    @Test
    fun `采样行跑出条子的时候不会给出瞎猜的结果`() {
        val h = 200
        val prev = columns(h, seed = 5)
        val next = shifted(prev, shift = 0, h = h)
        // 行本身就在条子最底下，再往下挪就全出界了
        val rows = IntArray(20) { h - 20 + it }

        val d = BackdropMath.bestShift(prev, next, rows, guess = 150, window = 100)
        assertTrue("要么给 0，要么认输，不能给个越界的数", d == null || d == 0)
    }

    @Test
    fun `补行时越界就夹到边界上`() {
        // 条子高 1000、面板高 300：起点最多到 700
        assertEquals(400, BackdropMath.spliceSource(panelTop = 400, panelH = 300, delta = 0, bandH = 1000))
        assertEquals(520, BackdropMath.spliceSource(400, 300, delta = 120, 1000))
        assertEquals(280, BackdropMath.spliceSource(400, 300, delta = -120, 1000))
        // 往下滚得太多：夹在条子底部
        assertEquals(700, BackdropMath.spliceSource(400, 300, delta = 900, 1000))
        // 往上滚得太多：夹在顶部
        assertEquals(0, BackdropMath.spliceSource(400, 300, delta = -900, 1000))
        // 条子比面板还矮这种极端情况：也不能是负数
        assertEquals(0, BackdropMath.spliceSource(10, 300, delta = 0, bandH = 100))
    }

    @Test
    fun `条子该贴在面板的哪儿：内容往上走，条子就往上挪`() {
        // 条子比面板大一圈：左边多出 80、上面多出 400
        val idle = BackdropMath.glassOffset(
            bandLeft = 100f, bandTop = 50f,
            panelLeft = 180f, panelTop = 450f,
            deltaX = 0f, deltaY = 0f
        )
        assertEquals(-80f, idle[0], 0.001f)
        assertEquals(-400f, idle[1], 0.001f)

        // 往下滚 120px：内容往上走，条子跟着往上挪 120
        val scrolled = BackdropMath.glassOffset(100f, 50f, 180f, 450f, deltaX = 0f, deltaY = 120f)
        assertEquals(-80f, scrolled[0], 0.001f)
        assertEquals(-520f, scrolled[1], 0.001f)

        // 横向同理：内容往左走 30，条子往左挪 30
        val sideways = BackdropMath.glassOffset(100f, 50f, 180f, 450f, deltaX = 30f, deltaY = 0f)
        assertEquals(-110f, sideways[0], 0.001f)
        assertEquals(-400f, sideways[1], 0.001f)
    }
}
