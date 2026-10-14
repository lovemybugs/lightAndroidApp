package com.htmlstudy.library.ui

import kotlin.math.abs

/**
 * "活的背景"（[GlassBackdrop]）里那些纯计算的部件：
 * 从相邻两张条子对出内容位移、算补行时该从上一张的哪一行取、条子该贴在面板的哪个位置。
 *
 * 单独抽出来是因为这几步最容易错，而这台机器上没有模拟器（/dev/kvm 不可用），
 * 只能在 JVM 上把它们测明白（见 test 下的 BackdropMathTest）。
 */
internal object BackdropMath {

    /**
     * 两张条子之间，内容往上走了多少像素（往下滚为正）。
     *
     * - [prevLum] / [nextLum]：每列一条竖直的亮度曲线（同一个 x、整条高度）；
     * - [rows]：只在"面板压不到"的这些行上比 —— 那些行两张里都是干净正文；
     * - [guess] / [window]：网页报来的估计值，以及允许它差多少（网页那边有延迟，
     *   而且网页报的单位未必就是像素，所以真正的位置得靠图像自己量）。
     *
     * 对不出可信结果就返回 null：纯色背景、周期性花纹、面板换位置时都可能这样，
     * 这时候宁可让调用方退回网页报来的位置。
     */
    fun bestShift(
        prevLum: Array<IntArray>,
        nextLum: Array<IntArray>,
        rows: IntArray,
        guess: Int,
        window: Int
    ): Int? {
        if (prevLum.isEmpty() || prevLum.size != nextLum.size) return null
        if (rows.isEmpty() || window < 1) return null
        val height = nextLum[0].size
        if (height < 8) return null
        for (p in prevLum) if (p.size != height) return null
        for (p in nextLum) if (p.size != height) return null

        // 位移再大也不能让采样行跑出条子
        val lo = maxOf(guess - window, -(height - 1))
        val hi = minOf(guess + window, height - 1)
        if (lo > hi) return null

        val minSamples = maxOf(8, rows.size * 3 / 4)
        val n = hi - lo + 1
        val sad = LongArray(n)
        val counts = IntArray(n)
        for (i in 0 until n) {
            val d = lo + i
            var sum = 0L
            var count = 0
            for (ci in prevLum.indices) {
                val a = prevLum[ci]
                val b = nextLum[ci]
                for (y in rows) {
                    val sy = y + d
                    if (sy < 0 || sy >= height) continue
                    sum += abs(a[sy] - b[y]).toLong()
                    count++
                }
            }
            sad[i] = sum
            counts[i] = count
        }

        var best = -1
        for (i in 0 until n) {
            if (counts[i] < minSamples) continue
            if (best < 0 || sad[i] < sad[best]) best = i
        }
        if (best < 0) return null

        // 次优：离最优至少 3px 之外最小的那个
        var runner = Long.MAX_VALUE
        for (i in 0 until n) {
            if (counts[i] < minSamples) continue
            if (abs(i - best) <= 2) continue
            if (sad[i] < runner) runner = sad[i]
        }
        if (runner == Long.MAX_VALUE) return null
        // 最优要明显好过次优：一样好就说明怎么挪都差不多（纯色），或者花纹是周期的（对不准）
        if (sad[best] * 5L >= runner * 3L) return null
        // 而且这段地方本身得有花纹：整段一个色的时候，任何位移都"对得上"
        if (contrast(nextLum, rows) < MIN_CONTRAST) return null

        return lo + best
    }

    /** 采样行上的亮度跨度，用来判断"这段地方有没有东西可比" */
    private fun contrast(lum: Array<IntArray>, rows: IntArray): Int {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (col in lum) {
            for (y in rows) {
                if (y < 0 || y >= col.size) continue
                val v = col[y]
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        return if (min > max) 0 else max - min
    }

    /**
     * 补被面板压住的那几行时，该从上一张条子的哪一行开始取。
     * 位移超过条子余量的时候夹到边界上 —— 那几行会错开一点，
     * 但绝不能让 getPixels 越界，也不能把整块内容画到别处。
     */
    fun spliceSource(panelTop: Int, panelH: Int, delta: Int, bandH: Int): Int {
        val maxStart = maxOf(0, bandH - panelH)
        return (panelTop + delta).coerceIn(0, maxStart)
    }

    /**
     * 条子左上角该落在面板局部坐标的哪里 —— 也就是面板那块玻璃里显示条子的哪一段。
     *
     * [bandLeft] / [bandTop]：条子在窗口里的位置；[panelLeft] / [panelTop]：面板在窗口里的位置；
     * [deltaX] / [deltaY]：内容从拍下这一张起又走了多远（**正数 = 内容往上 / 往左走**，
     * 也就是往下滚、往右滚）。内容往上走，条子就要往上挪，所以两个方向都是减。
     */
    fun glassOffset(
        bandLeft: Float, bandTop: Float,
        panelLeft: Float, panelTop: Float,
        deltaX: Float, deltaY: Float
    ): FloatArray = floatArrayOf(bandLeft - panelLeft - deltaX, bandTop - panelTop - deltaY)

    /** 采样行上的亮度至少要差这么多，才算"有东西可比" */
    private const val MIN_CONTRAST = 24
}
