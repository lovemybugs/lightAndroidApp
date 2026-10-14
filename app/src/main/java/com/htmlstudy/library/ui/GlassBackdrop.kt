package com.htmlstudy.library.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 玻璃背后那块**活的**背景。
 *
 * 难点：面板压住的那片内容在屏幕上永远看不到，截图里那块地方拍到的是面板自己。
 * 所以这里存的不是"面板那片"，而是比面板**大一圈的一条**：
 * 面板外面的部分照常每次拍新的，面板压住的那几行用上一张条子按内容位移平移过来补 ——
 * "现在被压住的内容"，上一张里正好露在条子的别处。位移优先用图像自己对出来
 * （相邻两张条子做一维匹配），量不出来再退回网页报来的位置。
 *
 * 于是玻璃能一直跟着正文走：网页一动，条子整块平移（每帧只改一个 Matrix），
 * 条子挪到快出界之前再拍一张，无缝续上。只有页面真的换了（跳转、缩放、转屏）
 * 才需要整张重拍，那时面板会躲开两帧。
 *
 * 坐标系：条子的矩形是**窗口坐标**（`contentArea` 的位置 + 内边距 + 面板位置），
 * 因为 PixelCopy 要的就是窗口坐标；而 [matrixFor] 要的是**面板局部坐标**。
 */
class GlassBackdrop(
    private val activity: Activity,
    private val density: Float,
    private val hidePanel: (Boolean) -> Unit,
    private val onFrame: (Bitmap, Matrix) -> Unit
) {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** 条子比面板上/下各多留的余量：能顶住这么远的滚动才需要重拍下一张 */
    private val marginPx = density * MARGIN_DP

    /** 条子左右各多留一点，横向滚动也有得挪 */
    private val slackPx = density * SLACK_DP

    private var alive = false
    private var generation = 0
    private var pending = false
    private var hideForCapture = false
    private var reseedWanted = false

    // 正文区（去掉内边距那块）在窗口里的位置和大小，面板在这块地方里的位置
    private var boxLeft = 0f
    private var boxTop = 0f
    private var boxW = 0f
    private var boxH = 0f
    private var panelLeft = 0f
    private var panelTop = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var band: Bitmap? = null      // 当前这张（玻璃现在显示的就是它）
    private var spare: Bitmap? = null     // 下一张拍到这儿，拍完两张对调
    private var bandRect = Rect()         // 条子在窗口坐标里的位置
    private var panelInBand = Rect()      // 面板落在条子里的哪一块（条子局部坐标）

    // 网页报来的内容位移：**累积量**，同样是"内容往上/往左走了多少"。
    // 报累积量而不是坐标，是为了页面里换了参照物（内层滚动容器、迷你缩放）时不会跳。
    private var jsNowY = 0f
    private var jsNowX = 0f
    private var jsAnchorY = 0f            // 当前这张条子对应的那个累积量
    private var jsAnchorX = 0f
    private var jsSeenAt = 0L

    /** 网页报的单位 → 真实像素。网页那边给的数可能差了倍数，靠图像匹配自己校准 */
    private var jsScale = 1f

    private var rowBuf: IntArray? = null
    private var lumBuf: IntArray? = null
    private var lastCaptureAt = 0L
    private var heartbeat: Runnable? = null

    val hasFrame: Boolean get() = band != null

    // ------------------------------------------------------------------ 交给外面调

    /** 正文区和面板的几何变了（转屏、拖到别的边、面板尺寸变了） */
    fun setGeometry(
        boxLeft: Float, boxTop: Float, boxW: Float, boxH: Float,
        panelLeft: Float, panelTop: Float, panelW: Float, panelH: Float
    ) {
        val same = this.boxLeft == boxLeft && this.boxTop == boxTop &&
            this.boxW == boxW && this.boxH == boxH &&
            this.panelLeft == panelLeft && this.panelTop == panelTop &&
            this.panelW == panelW && this.panelH == panelH
        if (same) return
        this.boxLeft = boxLeft
        this.boxTop = boxTop
        this.boxW = boxW
        this.boxH = boxH
        this.panelLeft = panelLeft
        this.panelTop = panelTop
        this.panelW = panelW
        this.panelH = panelH
        if (!alive) return
        // 条子得换地方了：拍的时候面板可能压在新位置上，躲开两帧
        reseed()
    }

    /** 网页报来的内容位移（累积量，正数 = 内容往上走了，也就是往下滚） */
    fun onContentMoved(jx: Float, jy: Float) {
        if (!alive) return
        jsNowX = jx
        jsNowY = jy
        jsSeenAt = System.currentTimeMillis()
        val b = band ?: return
        // 每帧只做这一件事：把条子挪一点点，网页滚多快玻璃就跟多快
        val dy = currentDeltaY()
        val dx = currentDeltaX()
        onFrame(b, matrixFor(dy, dx))
        if (!pending && !hideForCapture && needsRecapture(dy, dx)) requestCapture()
    }

    /** 面板拉开：拍第一张（此刻面板整个在屏幕外），然后自己维持下去 */
    fun start() {
        if (alive) return
        alive = true
        // 网页那边的累积量在开始跟踪时也归零，这里跟着归零，两边的基准才对得上
        jsNowY = 0f
        jsNowX = 0f
        jsAnchorY = 0f
        jsAnchorX = 0f
        requestCapture(first = true)
        scheduleHeartbeat()
    }

    /** 面板收起：停掉（截图留在 ImageView 上继续显示，下次拉开重拍） */
    fun stop() {
        if (!alive) return
        alive = false
        generation++
        pending = false
        reseedWanted = false
        heartbeat?.let { ui.removeCallbacks(it) }
        heartbeat = null
        band = null
        spare = null
    }

    /** 同一条边、同一块地方，只是内容变了（面板挪了一点位置）：整张重拍 */
    fun reseed() {
        if (!alive) return
        generation++
        jsAnchorY = jsNowY
        jsAnchorX = jsNowX
        if (pending) {
            reseedWanted = true
            return
        }
        requestCapture(first = true, hide = true)
    }

    /**
     * 页面换了 / 缩放变了：手里的条子整个作废，基准也重来。
     * 重拍的时候面板要躲开两帧，不然拍到的是面板自己。
     */
    fun reset() {
        if (!alive) return
        generation++
        jsNowY = 0f
        jsNowX = 0f
        jsAnchorY = 0f
        jsAnchorX = 0f
        band = null
        spare = null
        if (pending) {
            reseedWanted = true
            return
        }
        requestCapture(first = true, hide = true)
    }

    // ------------------------------------------------------------------ 截图

    private fun requestCapture(first: Boolean = false, hide: Boolean = false) {
        if (!alive || pending) return
        val now = System.currentTimeMillis()
        if (!first && now - lastCaptureAt < MIN_INTERVAL_MS) return
        val rect = bandRectFor()
        if (rect.width() <= 1 || rect.height() <= 1) return

        // 条子尺寸变了就没法拿它做匹配、也没法补行，按新的一张处理
        val resize = spare != null && !spare!!.isRecycled &&
            (spare!!.width != rect.width() || spare!!.height != rect.height())
        val fresh = first || resize

        if (spare == null || spare!!.isRecycled || resize) {
            if (!resize) spare?.recycle()
            spare = runCatching {
                Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return
        }
        val target = spare ?: return
        val gen = generation
        val prev = if (fresh) null else band
        val estimate = currentDeltaY().roundToInt()

        pending = true
        hideForCapture = hide
        lastCaptureAt = now
        if (hide) hidePanel(true)
        bandRect = Rect(rect)

        runCatching {
            PixelCopy.request(activity.window, rect, target, { result ->
                pending = false
                if (hide) {
                    hideForCapture = false
                    hidePanel(false)
                }
                if (reseedWanted && alive) {
                    // 重拍时正好有一张在飞：等它落地再拍，别两张同时往一块位图上写
                    reseedWanted = false
                    reseed()
                    return@request
                }
                if (result != PixelCopy.SUCCESS || gen != generation || !alive) {
                    return@request
                }
                val previous = prev
                worker.execute {
                    val measured = if (previous != null && !fresh) {
                        measureShift(previous, target, estimate)
                    } else {
                        null
                    }
                    val delta = measured ?: estimate
                    if (previous != null && !fresh && !previous.isRecycled) {
                        splice(previous, target, delta)
                    }
                    calibrate(fresh, measured, delta)
                    ui.post {
                        if (gen != generation || !alive) return@post
                        band = target
                        spare = previous
                        panelInBand = panelInBandFor(target)
                        onFrame(target, matrixFor(currentDeltaY(), currentDeltaX()))
                    }
                }
            }, ui)
        }.onFailure {
            pending = false
            if (hide) {
                hideForCapture = false
                hidePanel(false)
            }
        }
    }

    /** 相邻两张条子之间，内容到底平移了多少像素 */
    private fun measureShift(prev: Bitmap, next: Bitmap, estimate: Int): Int? {
        if (prev.width != next.width || prev.height != next.height) return null
        val h = next.height
        val rows = sampleRows(h, panelInBand.top, panelInBand.bottom) ?: return null
        val cols = sampleColumns(next.width)
        if (cols.isEmpty()) return null
        val raw = rawBuf(h)

        // 搜索范围给宽一点：网页报的单位一开始可能是错的（差个倍数），
        // 宽一点才不至于把真正的位移挡在窗口外
        val window = max(MIN_SEARCH_PX, (abs(estimate) * 2f).roundToInt() + 32)
        val a = Array(cols.size) { i -> lumProfile(prev, cols[i], h, raw) }
        val b = Array(cols.size) { i -> lumProfile(next, cols[i], h, raw) }
        return BackdropMath.bestShift(a, b, rows, estimate, window)
    }

    /** 用上一张条子里"露过面"的那几行，补上这一张被面板压住的行 */
    private fun splice(prev: Bitmap, target: Bitmap, delta: Int) {
        val top = panelInBand.top
        val h = panelInBand.height()
        if (top < 0 || h <= 0) return
        val rows = min(h, target.height - top)
        if (rows <= 0) return
        val w = min(prev.width, target.width)
        val reuse = rowBuf
        val buf = if (reuse == null || reuse.size < w * rows) {
            IntArray(w * rows).also { rowBuf = it }
        } else {
            reuse
        }
        val src = BackdropMath.spliceSource(top, rows, delta, target.height)
        runCatching {
            prev.getPixels(buf, 0, w, 0, src, w, rows)
            target.setPixels(buf, 0, w, 0, top, w, rows)
        }
    }

    /**
     * 校准网页报来的单位，并把锚点推到"这张条子对应的那一刻"。
     *
     * 位移是图像自己量出来的（[measured]）就按它往前推，这样两张条子之间预测的位移
     * 和实际一致；量不出来就别猜 —— 锚点直接对到当前位置（这张条子按原位贴上去），
     * 宁可丢掉这一段没量到的位移，也不能把条子摆错地方。
     */
    private fun calibrate(fresh: Boolean, measured: Int?, delta: Int) {
        if (fresh) {
            jsAnchorY = jsNowY
            jsAnchorX = jsNowX
            return
        }
        val reported = jsNowY - jsAnchorY
        if (measured != null && abs(delta) > MIN_CALIBRATION_PIXELS &&
            abs(reported) > MIN_CALIBRATION_REPORT
        ) {
            val k = delta / reported
            if (k.isFinite() && k > 0.05f) {
                jsScale = (jsScale * 0.6f + k * 0.4f).coerceIn(0.02f, 20f)
            }
        }
        if (measured != null) {
            jsAnchorY += measured / jsScale
        } else {
            jsAnchorY = jsNowY
        }
        jsAnchorX = jsNowX
    }

    // ------------------------------------------------------------------ 位置计算

    private fun bandRectFor(): Rect {
        val left = (boxLeft + panelLeft - slackPx).coerceAtLeast(boxLeft)
        val right = (boxLeft + panelLeft + panelW + slackPx).coerceAtMost(boxLeft + boxW)
        val top = (boxTop + panelTop - marginPx).coerceAtLeast(boxTop)
        val bottom = (boxTop + panelTop + panelH + marginPx).coerceAtMost(boxTop + boxH)
        return Rect(
            left.roundToInt(), top.roundToInt(),
            right.roundToInt().coerceAtLeast(left.roundToInt() + 1),
            bottom.roundToInt().coerceAtLeast(top.roundToInt() + 1)
        )
    }

    private fun panelInBandFor(target: Bitmap): Rect {
        // 面板在窗口里的位置减掉条子在窗口里的位置 = 面板在条子里的位置
        val left = (boxLeft + panelLeft - bandRect.left).roundToInt().coerceIn(0, target.width - 1)
        val top = (boxTop + panelTop - bandRect.top).roundToInt().coerceIn(0, target.height - 1)
        return Rect(
            left, top,
            (left + panelW.roundToInt()).coerceAtMost(target.width),
            (top + panelH.roundToInt()).coerceAtMost(target.height)
        )
    }

    /** 面板那块玻璃里该显示条子的哪一段 */
    private fun matrixFor(deltaY: Float, deltaX: Float): Matrix {
        val off = BackdropMath.glassOffset(
            bandRect.left.toFloat(), bandRect.top.toFloat(),
            boxLeft + panelLeft, boxTop + panelTop,
            deltaX, deltaY
        )
        val m = Matrix()
        m.postTranslate(off[0], off[1])
        return m
    }

    /** 条子相对"拍下这一张那会儿"平移了多少像素（正数 = 内容往上走了，即往下滚） */
    private fun currentDeltaY(): Float = (jsNowY - jsAnchorY) * jsScale
    private fun currentDeltaX(): Float = (jsNowX - jsAnchorX) * jsScale

    /** 条子快挪出界了就再拍一张：能挪的余量就是它比面板大出来的那部分 */
    private fun needsRecapture(deltaY: Float, deltaX: Float): Boolean {
        val b = band ?: return false
        // 内容往上/往左走，补行就得从条子的下方/右方取，那边剩下多少就是还能挪多远
        val roomY = if (deltaY >= 0f) {
            (b.height - panelInBand.bottom).toFloat()
        } else {
            panelInBand.top.toFloat()
        }
        if (abs(deltaY) >= max(24f, roomY * RECAPTURE_AT)) return true
        val roomX = if (deltaX >= 0f) {
            (b.width - panelInBand.right).toFloat()
        } else {
            panelInBand.left.toFloat()
        }
        if (abs(deltaX) >= max(24f, roomX * RECAPTURE_AT)) return true
        // 网页没在报位置（钩子没装上）时只能按时间兜底
        val now = System.currentTimeMillis()
        return now - jsSeenAt > JS_STALE_MS && now - lastCaptureAt > STALE_RECAPTURE_MS
    }

    private fun scheduleHeartbeat() {
        val runnable = object : Runnable {
            override fun run() {
                if (!alive) return
                // 没人滚、网页也不报位置的时候（动画、懒加载）也隔一阵拍一张
                if (!pending && System.currentTimeMillis() - lastCaptureAt > HEARTBEAT_MS) {
                    requestCapture()
                }
                ui.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat = runnable
        ui.postDelayed(runnable, HEARTBEAT_MS)
    }

    private fun rawBuf(h: Int): IntArray {
        val reuse = lumBuf
        return if (reuse == null || reuse.size < h) {
            IntArray(h).also { lumBuf = it }
        } else {
            reuse
        }
    }

    /** 某一列整条高度的亮度曲线（匹配就是在这些曲线上做的） */
    private fun lumProfile(band: Bitmap, x: Int, h: Int, raw: IntArray): IntArray {
        runCatching { band.getPixels(raw, 0, 1, x, 0, 1, h) }
        val out = IntArray(h)
        for (i in 0 until h) {
            val c = raw[i]
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            val a = (c ushr 24) and 0xFF
            if (a in 1..254) {
                r = r * 255 / a
                g = g * 255 / a
                b = b * 255 / a
            }
            out[i] = (r * 77 + g * 151 + b * 28) shr 8
        }
        return out
    }

    /** 只拿面板压不到的行走匹配：那些行两张条子都是干净的 */
    private fun sampleRows(h: Int, top: Int, bottom: Int): IntArray? {
        val pool = ArrayList<Int>(BLOCK_ROWS * 2)
        addBlock(pool, 2, top - 4)
        addBlock(pool, bottom + 4, h - 2)
        return if (pool.size >= 8) pool.toIntArray() else null
    }

    /** 从 [a, b) 里取一段**连续**的行：连续的像素才有花纹，隔行采样容易整段都落在白底上 */
    private fun addBlock(pool: ArrayList<Int>, a: Int, b: Int) {
        val len = b - a
        if (len < 4) return
        val take = min(BLOCK_ROWS, len)
        val start = a + (len - take) / 2
        for (y in start until start + take) pool.add(y)
    }

    private fun sampleColumns(w: Int): IntArray {
        if (w < 12) return IntArray(0)
        val n = min(MAX_COLUMNS, max(3, w / 60))
        val out = IntArray(n)
        for (i in 0 until n) {
            out[i] = ((i + 0.5f) * w / n).roundToInt().coerceIn(1, w - 2)
        }
        return out
    }

    private companion object {
        /** 条子比面板上/下各多留的余量（dp）：能顶住这么远的滚动才需要重拍 */
        const val MARGIN_DP = 150f

        /** 条子左右各多留一点，横向滚动也有得挪 */
        const val SLACK_DP = 26f

        /** 挪到余量的这个比例就去重拍下一张 */
        const val RECAPTURE_AT = 0.62f

        /** 两张截图之间至少隔这么久：PixelCopy 一次就是好几 MB，别把 GPU 拖垮 */
        const val MIN_INTERVAL_MS = 64L

        /** 匹配时最少要搜多大范围 */
        const val MIN_SEARCH_PX = 24

        /** 校准一次最少要有这么大的位移（像素 / 网页单位），噪声里不学 */
        const val MIN_CALIBRATION_PIXELS = 12
        const val MIN_CALIBRATION_REPORT = 6f

        /** 匹配的列数，和每块连续采样的行数 */
        const val BLOCK_ROWS = 24
        const val MAX_COLUMNS = 8

        /** 网页不再报位置（钩子没装上），按时间兜底的重拍间隔 */
        const val JS_STALE_MS = 600L
        const val STALE_RECAPTURE_MS = 320L

        /** 网页一直不动也隔一阵拍一张，动画、懒加载能跟上 */
        const val HEARTBEAT_MS = 900L
    }
}
