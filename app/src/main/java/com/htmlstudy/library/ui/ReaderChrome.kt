package com.htmlstudy.library.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import com.htmlstudy.library.R
import com.htmlstudy.library.data.Prefs
import com.htmlstudy.library.databinding.ActivityViewerBinding
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** 面板上的一个动作 */
enum class ChromeAction { TOC, FAVORITE, FONT, DARK, DESKTOP, IMMERSIVE, REFRESH, SHARE, OPEN_WITH, COLLAPSE }

/**
 * 阅读器那套"液态玻璃"外壳：屏幕边缘的小把手 + 拉出来的工具面板。
 *
 * 四条设计约束：
 * 1. 把手能拖到任意一条边，**拖到哪儿就记在哪儿**（下次打开还在那条边、那个位置）；
 * 2. 点一下正文可以让把手"滑出去 / 滑回来"；
 * 3. 面板是一条竖向细长的玻璃片，宽度只够并排两个按钮；
 * 4. 玻璃要真的能"看见后面的内容"，还有一点折射感 —— 于是拿窗口截图
 *    （PixelCopy）当背景，再做模糊（RenderEffect）+ 边缘折射（AGSL RuntimeShader）。
 *
 * 第 4 条的具体降级路线（都在运行时探测，失败就退回半透明，绝不糊掉正文）：
 * - Android 13+：截图 → 高斯模糊 → AGSL 按圆角矩形的 SDF 做边缘折射 + 轻微色散；
 * - Android 12：截图 → 高斯模糊；
 * - Android 7~11：截图缩到 1/6 再放大（双线性插值就是模糊），没有 GPU 效果。
 *
 * 背景图从哪来，两档不一样：
 * - 透镜那一档交给 [GlassBackdrop]：**活的**，网页一滚玻璃就跟着折射新的内容
 *   （条子每隔一阵重拍一张，中间每帧只挪一个 Matrix）；
 * - 另两档用 [captureBackdrop] 的一张静态截图 —— 它们在面板滑出来**之前**拍，
 *   那时面板整个在屏幕外，拍到的就是干净正文，不会把面板自己也拍进去。
 */
class ReaderChrome(
    private val activity: Activity,
    private val binding: ActivityViewerBinding,
    private val prefs: Prefs,
    private val onAction: (ChromeAction) -> Unit,
    private val onPanelOpened: () -> Unit,
    /** 开始 / 停止让网页把滚动位置报过来（面板开着才需要，见 [GlassBackdrop]） */
    private val onLiveChanged: (Boolean) -> Unit
) {

    private val panel get() = binding.toolPanel
    private val content get() = binding.panelContent
    private val handle get() = binding.toolHandle
    private val backdrop get() = binding.panelBackdrop

    /** 把手贴在哪条边、在这条边上的比例 */
    private var edge = prefs.handleEdge
    private var fraction = prefs.handleFraction

    private var panelOpen = false
    private var handleHidden = false
    private var dragging = false
    private var suppressed = false

    /** 正文区减去内边距之后那块地方（窗口尺寸变了就跟着变） */
    private var boxW = 0f
    private var boxH = 0f

    private var panelW = 0f
    private var panelH = 0f
    private var handleW = 0f
    private var handleH = 0f

    /** 面板和把手在父容器（正文区）里的位置；把手的位置在拖动时会跟着手指改 */
    private var panelX = 0f
    private var panelY = 0f
    private var handleX = 0f
    private var handleY = 0f

    private lateinit var glass: LiquidGlass

    /**
     * 面板背后那块"活"的背景。只有透镜那一档用得起 —— 它的截图是把条子从屏幕上"抠"下来，
     * 面板自己会被抠进去一块，得靠着色器那圈向内折射盖住；模糊那两档没有这一层，
     * 还是老老实实用"面板滑出来之前"的那张静态截图。
     */
    private lateinit var backdropEngine: GlassBackdrop
    private var liveBackdrop = false
    private var liveBitmap: Bitmap? = null

    private val icons = HashMap<ChromeAction, ImageView>()
    private val cells = ArrayList<View>()
    private var backdropBitmap: Bitmap? = null

    private val ui = Handler(Looper.getMainLooper())
    private val touchSlop by lazy { ViewConfiguration.get(activity).scaledTouchSlop }

    private val shimmer: ObjectAnimator by lazy {
        ObjectAnimator.ofFloat(binding.panelGloss, "translationX", -dpF(7f), dpF(7f)).apply {
            duration = 2800L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = LinearInterpolator()
        }
    }

    // ------------------------------------------------------------------ 初始化

    fun setup() {
        buildCells()
        applyHandleShape()
        setupHandleTouch()

        // 面板整体圆角。透镜那一档由着色器自己算圆角覆盖率（见 LiquidGlass.setup），
        // 另外两档没有着色器，得靠裁剪；scrim / gloss 一直都在裁剪里。
        val radius = dpF(PANEL_RADIUS_DP)
        for (v in listOf(binding.panelBackdrop, binding.panelScrim, binding.panelGloss)) {
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            v.clipToOutline = true
        }

        glass = LiquidGlass(
            activity = activity,
            surface = binding.panelBackdrop,
            scrim = binding.panelScrim,
            radiusDp = PANEL_RADIUS_DP,
            blurDp = 6f,
            bevelDp = 22f,
            refractDp = 15f,
            tintAmount = 0.26f
        ).also { it.setup() }

        backdropEngine = GlassBackdrop(
            activity = activity,
            density = activity.resources.displayMetrics.density,
            // 重拍那两帧要让开：不清干净的话，拍到的就是面板自己
            hidePanel = { hide ->
                if (!suppressed) panel.visibility = if (hide) View.INVISIBLE else View.VISIBLE
            },
            onFrame = { bitmap, matrix -> showLiveFrame(bitmap, matrix) }
        )

        binding.contentArea.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> relayout(animate = false) }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> relayout(animate = panelOpen) }

        relayout(animate = false)
    }

    fun setTitle(title: String) {
        binding.panelTitle.text = title
        // 玻璃是透的，标题也要压得住背后的内容
        binding.panelTitle.setShadowLayer(dpF(4f), 0f, dpF(1f), (0x50 shl 24))
    }

    fun isPanelOpen(): Boolean = panelOpen

    /** 播放网页视频那种"自定义全屏视图"时，整套外壳都要让开 */
    fun setVisible(visible: Boolean) {
        suppressed = !visible
        val v = if (visible) View.VISIBLE else View.GONE
        panel.visibility = v
        handle.visibility = v
        if (!visible) {
            stopShimmer()
            glass.stopLight()
            stopLive()
            panelOpen = false
        } else {
            // 回来时按收起的姿势摆好，别让面板停在刚才拉出来的位置上
            relayout(animate = false)
        }
    }

    // ------------------------------------------------------------------ 面板按钮

    private fun buildCells() {
        val spec = listOf(
            Triple(R.drawable.ic_menu, R.string.label_toc, ChromeAction.TOC),
            Triple(R.drawable.ic_star_outline, R.string.label_favorite, ChromeAction.FAVORITE),
            Triple(R.drawable.ic_font, R.string.label_font, ChromeAction.FONT),
            Triple(R.drawable.ic_dark, R.string.label_dark, ChromeAction.DARK),
            Triple(R.drawable.ic_desktop, R.string.label_desktop, ChromeAction.DESKTOP),
            Triple(R.drawable.ic_fullscreen, R.string.label_fullscreen, ChromeAction.IMMERSIVE),
            Triple(R.drawable.ic_refresh, R.string.label_refresh, ChromeAction.REFRESH),
            Triple(R.drawable.ic_share, R.string.label_share, ChromeAction.SHARE),
            Triple(R.drawable.ic_open, R.string.label_open_with, ChromeAction.OPEN_WITH),
            Triple(R.drawable.ic_chevron, R.string.label_collapse, ChromeAction.COLLAPSE)
        )

        binding.panelActions.removeAllViews()
        icons.clear()
        cells.clear()

        spec.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            pair.forEach { (iconRes, labelRes, action) ->
                val cell = makeCell(iconRes, labelRes, action)
                cells.add(cell)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            // 单数个按钮时补一个占位，免得最后一行被拉宽
            if (pair.size == 1) {
                row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            }
            binding.panelActions.addView(row)
        }
    }

    private fun makeCell(iconRes: Int, labelRes: Int, action: ChromeAction): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(labelRes)
            background = rippleDrawable()
            setOnClickListener { onAction(action) }
        }

        val icon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(iconRes)
            ImageViewCompat.setImageTintList(
                this, ColorStateList.valueOf(themeColor(MaterialR.attr.colorOnSurfaceVariant))
            )
        }
        box.addView(icon, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        icons[action] = icon

        val label = TextView(activity).apply {
            text = activity.getString(labelRes)
            textSize = 9f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(themeColor(MaterialR.attr.colorOnSurface))
            // 玻璃是透的：给字垫一点点阴影，背后是花哨的课件也不至于读不清
            setShadowLayer(dpF(4f), 0f, dpF(1f), (0x50 shl 24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2f) }
        }
        box.addView(label)
        return box
    }

    /** 收藏 / 深色 / 桌面版 / 全屏这几个开着的时候，图标点亮成主题色 */
    fun refreshIcons(favorite: Boolean, desktop: Boolean, dark: Boolean, immersive: Boolean) {
        icons[ChromeAction.FAVORITE]?.setImageResource(
            if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline
        )
        icons[ChromeAction.IMMERSIVE]?.setImageResource(
            if (immersive) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        tint(ChromeAction.FAVORITE, favorite)
        tint(ChromeAction.DESKTOP, desktop)
        tint(ChromeAction.DARK, dark)
        tint(ChromeAction.IMMERSIVE, immersive)
    }

    private fun tint(action: ChromeAction, on: Boolean) {
        val view = icons[action] ?: return
        val color = if (on) {
            themeColor(MaterialR.attr.colorPrimary)
        } else {
            themeColor(MaterialR.attr.colorOnSurface)
        }
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(color))
    }

    // ------------------------------------------------------------------ 布局与位置

    /**
     * 重新算一遍几何：屏幕（正文区）多大、面板要多高、把手在哪、面板该滑到哪。
     * 窗口尺寸变了（转屏、分屏）也会走到这里 —— 因为存的是比例，把手会留在原处。
     */
    private fun relayout(animate: Boolean) {
        if (suppressed) return
        val parent = binding.contentArea
        boxW = (parent.width - parent.paddingLeft - parent.paddingRight).toFloat()
        boxH = (parent.height - parent.paddingTop - parent.paddingBottom).toFloat()
        if (boxW <= 0f || boxH <= 0f) return

        handleW = handle.width.toFloat()
        handleH = handle.height.toFloat()
        if (handleW <= 0f || handleH <= 0f) return

        // 面板高度按屏幕来：屏幕矮（横屏、分屏）就把格子压扁，保证整块面板放得下
        val rows = max(1, binding.panelActions.childCount)
        val room = boxH - dp(16f) - dp(22f)
        val cellH = (room / rows).coerceIn(dpF(40f), dpF(52f))
        cells.forEach { cell ->
            if (cell.layoutParams.height != cellH.roundToInt()) {
                cell.layoutParams = cell.layoutParams.apply { height = cellH.roundToInt() }
            }
        }
        val needed = measureContent(boxW, boxH)
        if (panel.layoutParams.height != needed) {
            panel.layoutParams = panel.layoutParams.apply { height = needed }
            return // 高度一变会再来一次布局，下一次就都算准了
        }
        panelW = panel.width.toFloat()
        panelH = panel.height.toFloat()
        if (panelW <= 0f || panelH <= 0f) return

        val vertical = ChromeGeometry.isVertical(edge)

        // 把手：沿这条边的偏移。存的是比例，所以窗口大小一变也还在原处
        val along = if (vertical) boxH else boxW
        val off = ChromeGeometry.offsetAlong(fraction, along, if (vertical) handleH else handleW)
        handleX = when (edge) {
            ChromeGeometry.RIGHT -> boxW - handleW
            ChromeGeometry.TOP, ChromeGeometry.BOTTOM -> off
            else -> 0f
        }
        handleY = when (edge) {
            ChromeGeometry.BOTTOM -> boxH - handleH
            ChromeGeometry.LEFT, ChromeGeometry.RIGHT -> off
            else -> 0f
        }

        // 面板：贴着手把那条边，另一条方向上跟把手中心对齐。
        // 向外多探出去一个圆角的距离（overhang）——探出去的那半个圆角被屏幕裁掉，
        // 看到的就是"一边直角、一边圆角"的玻璃片，像是从屏幕边上长出来的。
        val over = dpF(PANEL_RADIUS_DP)
        panelX = if (vertical) {
            if (edge == ChromeGeometry.RIGHT) boxW - panelW + over else -over
        } else {
            ChromeGeometry.panelAlign(fraction, boxW, panelW, handleW)
        }
        panelY = if (vertical) {
            ChromeGeometry.panelAlign(fraction, boxH, panelH, handleH)
        } else {
            if (edge == ChromeGeometry.BOTTOM) boxH - panelH + over else -over
        }

        // "活"背景也要知道这两个矩形各在哪：条子是按"面板 + 一圈余量"从窗口里抠出来的
        if (::backdropEngine.isInitialized) {
            val origin = IntArray(2)
            parent.getLocationOnScreen(origin)
            backdropEngine.setGeometry(
                boxLeft = (origin[0] + parent.paddingLeft).toFloat(),
                boxTop = (origin[1] + parent.paddingTop).toFloat(),
                boxW = boxW,
                boxH = boxH,
                panelLeft = panelX,
                panelTop = panelY,
                panelW = panelW,
                panelH = panelH
            )
        }

        applyHandlePos(animate)
        applyPanelPos(animate)
    }

    private fun measureContent(boxW: Float, boxH: Float): Int {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(boxW.toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(boxH.toInt(), View.MeasureSpec.AT_MOST)
        )
        return content.measuredHeight.coerceAtMost(boxH.toInt())
    }

    private fun applyHandlePos(animate: Boolean) {
        val lip = if (handleHidden) {
            when (edge) {
                ChromeGeometry.LEFT -> -handleW
                ChromeGeometry.RIGHT -> handleW
                ChromeGeometry.TOP -> -handleH
                else -> handleH
            }
        } else {
            0f
        }
        val tx = handleX + (if (ChromeGeometry.isVertical(edge)) lip else 0f)
        val ty = handleY + (if (ChromeGeometry.isVertical(edge)) 0f else lip)
        if (animate) {
            handle.animate().translationX(tx).translationY(ty).alpha(if (handleHidden) 0f else 1f)
                .setDuration(220L).setInterpolator(DecelerateInterpolator()).start()
        } else {
            handle.translationX = tx
            handle.translationY = ty
            handle.alpha = if (handleHidden) 0f else 1f
        }
    }

    /** 面板的位置：贴着把手所在的那条边，滑进 / 滑出都是沿垂直于那条边的方向 */
    private fun applyPanelPos(animate: Boolean) {
        val vertical = ChromeGeometry.isVertical(edge)
        // 收起来时就停在屏幕外沿：整个面板正好在正文区之外，不靠"探出去多少"来兜底
        val closedX = when {
            edge == ChromeGeometry.LEFT -> -panelW
            edge == ChromeGeometry.RIGHT -> boxW
            else -> panelX
        }
        val closedY = when {
            edge == ChromeGeometry.TOP -> -panelH
            edge == ChromeGeometry.BOTTOM -> boxH
            else -> panelY
        }
        val tx = if (panelOpen) panelX else closedX
        val ty = if (panelOpen) panelY else closedY
        if (animate) {
            panel.animate().translationX(tx).translationY(ty)
                .setDuration(220L).setInterpolator(DecelerateInterpolator()).start()
        } else {
            panel.translationX = tx
            panel.translationY = ty
        }
    }

    /**
     * 把手的外形跟着它所在的边变：左右边是竖着的（圆角朝屏幕里），
     * 上下边是横着的；箭头永远指向屏幕内侧。
     */
    private fun applyHandleShape() {
        val vertical = ChromeGeometry.isVertical(edge)
        val w = dp(if (vertical) 22f else 64f)
        val h = dp(if (vertical) 64f else 22f)
        if (handle.layoutParams.width != w || handle.layoutParams.height != h) {
            handle.layoutParams = handle.layoutParams.apply { width = w; height = h }
        }

        val fill = intArrayOf(
            themeColor(MaterialR.attr.colorSurface, 0.62f),
            themeColor(MaterialR.attr.colorSurface, 0.34f)
        )
        val outer = dpF(2f)
        val inner = dpF(14f)
        handle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            orientation = GradientDrawable.Orientation.TL_BR
            colors = fill
            gradientType = GradientDrawable.LINEAR_GRADIENT
            setStroke(dpF(1f).toInt(), themeColor(MaterialR.attr.colorOnSurfaceVariant, 0.38f))
            cornerRadii = when (edge) {
                ChromeGeometry.LEFT -> floatArrayOf(outer, outer, inner, inner, inner, inner, outer, outer)
                ChromeGeometry.RIGHT -> floatArrayOf(inner, inner, outer, outer, outer, outer, inner, inner)
                ChromeGeometry.TOP -> floatArrayOf(outer, outer, outer, outer, inner, inner, inner, inner)
                else -> floatArrayOf(inner, inner, inner, inner, outer, outer, outer, outer)
            }
        }

        binding.handleIcon.rotation = when (edge) {
            ChromeGeometry.LEFT -> 0f
            ChromeGeometry.RIGHT -> 180f
            ChromeGeometry.TOP -> 90f
            else -> 270f
        }
        binding.handleIcon.layoutParams = (binding.handleIcon.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = if (vertical) dp(3f) else 0
            marginEnd = if (vertical) dp(5f) else 0
            topMargin = if (vertical) 0 else dp(3f)
            bottomMargin = if (vertical) 0 else dp(5f)
        }
        ImageViewCompat.setImageTintList(
            binding.handleIcon,
            ColorStateList.valueOf(themeColor(MaterialR.attr.colorOnSurfaceVariant, 0.9f))
        )
    }

    // ------------------------------------------------------------------ 拖动 / 点击

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHandleTouch() {
        var downRawX = 0f
        var downRawY = 0f
        var grabDX = 0f
        var grabDY = 0f
        val origin = IntArray(2)
        val onScreen = IntArray(2)

        handle.setOnTouchListener { _, event ->
            if (suppressed) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    binding.contentArea.getLocationOnScreen(origin)
                    handle.getLocationOnScreen(onScreen)
                    // 手指按在把手的哪个位置：拖动时这块地方要一直跟着手指
                    grabDX = event.rawX - onScreen[0]
                    grabDY = event.rawY - onScreen[1]
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop
                    ) {
                        dragging = true
                        // 一拖就是在挪位置，别让面板挂在旁边挡事
                        if (panelOpen) {
                            panelOpen = false
                            applyPanelPos(animate = false)
                            stopShimmer()
                            stopLive()
                        }
                    }
                    if (dragging) {
                        val parent = binding.contentArea
                        val boxW = (parent.width - parent.paddingLeft - parent.paddingRight).toFloat()
                        val boxH = (parent.height - parent.paddingTop - parent.paddingBottom).toFloat()
                        handleX = (event.rawX - grabDX - origin[0] - parent.paddingLeft)
                            .coerceIn(0f, max(0f, boxW - handleW))
                        handleY = (event.rawY - grabDY - origin[1] - parent.paddingTop)
                            .coerceIn(0f, max(0f, boxH - handleH))
                        handle.translationX = handleX
                        handle.translationY = handleY
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val parent = binding.contentArea
                        val boxW = (parent.width - parent.paddingLeft - parent.paddingRight).toFloat()
                        val boxH = (parent.height - parent.paddingTop - parent.paddingBottom).toFloat()
                        val cx = handleX + handleW / 2f
                        val cy = handleY + handleH / 2f
                        val snapped = ChromeGeometry.snapEdge(cx, cy, boxW, boxH)
                        setAnchor(snapped, ChromeGeometry.fractionAlong(snapped, cx, cy, boxW, boxH))
                    } else {
                        togglePanel()
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun setAnchor(newEdge: Int, newFraction: Float) {
        edge = newEdge
        fraction = newFraction
        prefs.handleEdge = edge
        prefs.handleFraction = fraction
        // 换一条边，把手的方向/圆角/箭头朝向就都得跟着换 ——
        // 哪怕是"左 → 右"这种同为竖把手的移动，圆角朝哪一侧也是反的
        applyHandleShape()
        relayout(animate = true)
    }

    // ------------------------------------------------------------------ 开合

    fun togglePanel() {
        if (panelOpen) collapse() else openPanel()
    }

    private fun openPanel() {
        if (suppressed) return
        onPanelOpened()
        // 先拍照再滑出来：此刻面板整个在屏幕外，拍到的是干净的正文
        if (glass.lens) {
            startLive()
        } else {
            captureBackdrop()
        }
        panelOpen = true
        applyPanelPos(animate = true)
        startShimmer()
        glass.startLight() // 面板拉开时高光跟着设备姿态走，收起来就把传感器放掉
    }

    fun collapse() {
        if (!panelOpen) return
        panelOpen = false
        applyPanelPos(animate = true)
        stopShimmer()
        glass.stopLight()
        stopLive()
    }

    // ------------------------------------------------------------------ 活的背景

    /** 面板拉开：从"面板还没滑出来"这一眼开始，之后条子自己跟着网页走 */
    private fun startLive() {
        liveBackdrop = true
        liveBitmap = null
        // 第一张到之前先不显示：免得面板滑出来时闪一下旧内容
        backdrop.alpha = 0f
        onLiveChanged(true)
        backdropEngine.start()
    }

    private fun stopLive() {
        if (!liveBackdrop) return
        liveBackdrop = false
        liveBitmap = null
        onLiveChanged(false)
        backdropEngine.stop()
    }

    /**
     * [GlassBackdrop] 送来的每一帧：换了新的一张就换 drawable，还是同一张就只挪矩阵
     * —— 每帧只改一个 Matrix，滚动才跟得住。位图归引擎所有（两张来回用），这里不回收。
     */
    private fun showLiveFrame(bitmap: Bitmap, matrix: Matrix) {
        if (viewIsGone()) return
        glass.setSurfaceSize(panelW, panelH)
        if (liveBitmap !== bitmap) {
            liveBitmap = bitmap
            glass.rebind(bitmap, matrix)
            if (backdrop.alpha < 1f) {
                backdrop.animate().alpha(1f).setDuration(160L).start()
            }
        } else {
            glass.slide(matrix)
        }
    }

    /** 面板这会儿是不是在跟着正文实时折射 */
    fun isBackdropLive(): Boolean = liveBackdrop

    /** 网页报来的内容位移（累积量：正数 = 内容往上走了，也就是往下滚） */
    fun onContentMoved(jx: Float, jy: Float) {
        if (liveBackdrop) backdropEngine.onContentMoved(jx, jy)
    }

    /**
     * 页面加载完：钩子要重新挂上（网页那边的累积量跟着归零），条子也重拍一张。
     * 正文恢复到上次位置的那一下也会被这次重拍接住。
     */
    fun onPageLoaded() {
        if (!liveBackdrop) return
        onLiveChanged(true)
        backdropEngine.reset()
    }

    /** 缩放变了：渲染出来的东西整个换了一套尺度，条子作废 */
    fun onViewportChanged() {
        if (!liveBackdrop) return
        onLiveChanged(true)
        backdropEngine.reset()
    }

    /** 点一下正文：把手滑出去 / 滑回来（面板开着时先收面板） */
    fun toggleHandle() {
        if (suppressed) return
        if (panelOpen) {
            collapse()
            return
        }
        handleHidden = !handleHidden
        applyHandlePos(animate = true)
    }

    /** 把手滑出屏幕之外了没有（点正文切换） */
    fun isHandleHidden(): Boolean = handleHidden

    // ------------------------------------------------------------------ 液态玻璃

    private fun startShimmer() {
        if (!shimmer.isStarted) shimmer.start()
    }

    private fun stopShimmer() {
        if (shimmer.isStarted) shimmer.cancel()
        binding.panelGloss.translationX = 0f
    }

    /**
     * 把面板**将要滑到**的那块地方从窗口里拷出来。
     * 面板此时还在屏幕外，所以拷贝里只有正文，不会拍到面板自己。
     */
    private fun captureBackdrop() {
        val parent = binding.contentArea
        val origin = IntArray(2)
        parent.getLocationOnScreen(origin)
        val left = origin[0] + parent.paddingLeft + panelX.roundToInt()
        val top = origin[1] + parent.paddingTop + panelY.roundToInt()
        val pad = dp(18f).toInt()
        val rect = Rect(left - pad, top - pad, left + panelW.toInt() + pad, top + panelH.toInt() + pad)

        val window = activity.window
        val decor = window.decorView
        val wl = IntArray(2)
        decor.getLocationOnScreen(wl)
        val windowRect = Rect(wl[0], wl[1], wl[0] + decor.width, wl[1] + decor.height)
        if (!rect.intersect(windowRect)) return

        val bitmap = runCatching {
            Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return

        val offsetX = (rect.left - left).toFloat()
        val offsetY = (rect.top - top).toFloat()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestCopy(window, rect, bitmap, offsetX, offsetY)
        } else {
            // API 24 / 25 只能整窗拷再自己裁
            val full = runCatching {
                Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return
            runCatching {
                @Suppress("DEPRECATION")
                PixelCopy.request(window, full, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        val cropped = runCatching {
                            Bitmap.createBitmap(
                                full, rect.left - wl[0], rect.top - wl[1], rect.width(), rect.height()
                            )
                        }.getOrNull()
                        full.recycle()
                        if (cropped != null) showBackdrop(cropped, offsetX, offsetY)
                    } else {
                        full.recycle()
                    }
                }, ui)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestCopy(
        window: android.view.Window,
        rect: Rect,
        bitmap: Bitmap,
        offsetX: Float,
        offsetY: Float
    ) {
        runCatching {
            PixelCopy.request(window, rect, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    showBackdrop(bitmap, offsetX, offsetY)
                } else {
                    bitmap.recycle()
                }
            }, ui)
        }.onFailure { bitmap.recycle() }
    }

    /**
     * 把截到的这块贴到面板背后：低版本先缩小再放大当模糊，12+ 交给 GPU，
     * 13+ 再加一层 AGSL 的边缘折射。
     */
    private fun showBackdrop(bitmap: Bitmap, offsetX: Float, offsetY: Float) {
        if (viewIsGone()) {
            bitmap.recycle()
            return
        }
        val old = backdropBitmap

        // 老系统上先把截图缩小，放大时的双线性插值就是模糊
        val factor = glass.downscale
        val small = if (factor > 1) {
            runCatching {
                Bitmap.createScaledBitmap(
                    bitmap, max(1, bitmap.width / factor), max(1, bitmap.height / factor), true
                )
            }.getOrNull() ?: bitmap
        } else {
            bitmap
        }
        if (small !== bitmap) bitmap.recycle()
        backdropBitmap = small

        val matrix = Matrix()
        if (factor > 1) matrix.postScale(factor.toFloat(), factor.toFloat())
        matrix.postTranslate(offsetX, offsetY)
        glass.setSurfaceSize(panelW, panelH)
        glass.bind(small, matrix)

        backdrop.alpha = 0f
        backdrop.animate().alpha(1f).setDuration(160L).start()
        old?.takeIf { it !== backdropBitmap && !it.isRecycled }?.recycle()
    }

    private fun viewIsGone(): Boolean = panel.visibility != View.VISIBLE

    // ------------------------------------------------------------------ 小工具

    private fun themeColor(attr: Int, alpha: Float = 1f): Int {
        val color = MaterialColors.getColor(handle, attr)
        return ColorUtils.setAlphaComponent(color, (255 * alpha).roundToInt().coerceIn(0, 255))
    }

    private fun rippleDrawable() = activity.obtainStyledAttributes(
        intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
    ).let { ta ->
        val d = ta.getDrawable(0)
        ta.recycle()
        d
    }

    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private fun dpF(value: Float): Float = value * activity.resources.displayMetrics.density

    companion object {
        /** 面板圆角：玻璃的圆角、探出屏幕的量、着色的 SDF 都用这一个数 */
        private const val PANEL_RADIUS_DP = 18f
    }
}
