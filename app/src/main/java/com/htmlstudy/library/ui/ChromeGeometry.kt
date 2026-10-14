package com.htmlstudy.library.ui

import kotlin.math.max
import kotlin.math.min

/**
 * 侧边把手 / 工具面板的位置换算。
 *
 * 单独拎出来是因为这几步是纯算术，和不存在的模拟器无关 —— 可以老老实实写单元测试。
 * 用的都是"比例"，换算成像素是调用方的事。
 */
object ChromeGeometry {

    const val LEFT = 0
    const val RIGHT = 1
    const val TOP = 2
    const val BOTTOM = 3

    /** 把手贴在上下边时就是横着的 */
    fun isVertical(edge: Int): Boolean = edge == LEFT || edge == RIGHT

    /**
     * 把手中心拖到 (cx, cy) 后吸附到哪条边：离哪条边近就是哪条。
     * 平手时按 左 → 右 → 上 → 下 的顺序取第一个。
     */
    fun snapEdge(cx: Float, cy: Float, width: Float, height: Float): Int {
        val dLeft = cx
        val dRight = width - cx
        val dTop = cy
        val dBottom = height - cy
        val nearest = min(min(dLeft, dRight), min(dTop, dBottom))
        return when (nearest) {
            dLeft -> LEFT
            dRight -> RIGHT
            dTop -> TOP
            else -> BOTTOM
        }
    }

    /**
     * 把手中心点在边上占的比例。存的是这个比例，所以窗口大小变了（转屏、
     * 分屏）之后把手还在原来的相对位置上。
     */
    fun fractionAlong(edge: Int, cx: Float, cy: Float, width: Float, height: Float): Float {
        val along = if (isVertical(edge)) height else width
        if (along <= 0f) return 0.5f
        val pos = if (isVertical(edge)) cy else cx
        return (pos / along).coerceIn(0f, 1f)
    }

    /**
     * 比例 → 把手在这条边上的起始偏移。
     * 比例说的是"把手中心"的位置，减法是为了把把手整体挪进来（不让它有一半探出屏幕）。
     */
    fun offsetAlong(fraction: Float, available: Float, handleLength: Float): Float {
        val room = max(0f, available - handleLength)
        return (fraction * available - handleLength / 2f).coerceIn(0f, room)
    }

    /**
     * 面板沿这条边的起始偏移：面板中心对齐全把手的中心，再夹进屏幕里。
     */
    fun panelAlign(fraction: Float, available: Float, panelLength: Float, handleLength: Float): Float {
        val room = max(0f, available - panelLength)
        val handleCenter = fraction * available
        return (handleCenter - panelLength / 2f).coerceIn(0f, room)
    }
}
