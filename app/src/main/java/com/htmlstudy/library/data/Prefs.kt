package com.htmlstudy.library.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

/**
 * 轻量偏好存储（SharedPreferences 就够了，不引 DataStore）。
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("htmlstudy_prefs", Context.MODE_PRIVATE)

    /** 目录排序：0=名称，1=最近修改 */
    var sortMode: Int
        get() = sp.getInt(KEY_SORT, SORT_NAME)
        set(v) = sp.edit().putInt(KEY_SORT, v).apply()

    /** 主题：0=跟随系统，1=浅色，2=深色 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME, THEME_SYSTEM)
        set(v) = sp.edit().putInt(KEY_THEME, v).apply()

    /** 上次停留的目录（相对库根目录），重启后回到原处 */
    var lastFolder: String
        get() = sp.getString(KEY_LAST_FOLDER, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_FOLDER, v).apply()

    /** 阅读器字体缩放百分比 */
    var fontSize: Int
        get() = sp.getInt(KEY_FONT, 100)
        set(v) = sp.edit().putInt(KEY_FONT, v).apply()

    /** 阅读器：强制深色（部分老文档可能显示异常，默认关闭） */
    var forceDark: Boolean
        get() = sp.getBoolean(KEY_FORCE_DARK, false)
        set(v) = sp.edit().putBoolean(KEY_FORCE_DARK, v).apply()

    /** 阅读器：桌面版视口（宽表格 / 横向排版课件用得上） */
    var desktopMode: Boolean
        get() = sp.getBoolean(KEY_DESKTOP, false)
        set(v) = sp.edit().putBoolean(KEY_DESKTOP, v).apply()

    /**
     * 阅读器：进入即全屏沉浸。
     * 默认关闭 —— 学习场景里频繁切换科目，工具栏留着更顺手；
     * 需要沉浸阅读时点一下菜单里的「全屏沉浸」即可。
     */
    var immersive: Boolean
        get() = sp.getBoolean(KEY_IMMERSIVE, false)
        set(v) = sp.edit().putBoolean(KEY_IMMERSIVE, v).apply()

    /** 阅读器小把手贴在哪条边：0 左 1 右 2 上 3 下 */
    var handleEdge: Int
        get() = sp.getInt(KEY_HANDLE_EDGE, 0)
        set(v) = sp.edit().putInt(KEY_HANDLE_EDGE, v).apply()

    /** 把手在这条边上的位置比例（0 上/左端，1 下/右端） */
    var handleFraction: Float
        get() = sp.getFloat(KEY_HANDLE_FRACTION, 0.5f)
        set(v) = sp.edit().putFloat(KEY_HANDLE_FRACTION, v.coerceIn(0f, 1f)).apply()

    /** 阅读器小把手的那句提示只弹一次 */
    var seenPanelHint: Boolean
        get() = sp.getBoolean(KEY_PANEL_HINT, false)
        set(v) = sp.edit().putBoolean(KEY_PANEL_HINT, v).apply()

    /** 浏览时是否显示非 HTML 文件（图片、PDF、压缩包等） */
    var showAssets: Boolean
        get() = sp.getBoolean(KEY_SHOW_ASSETS, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_ASSETS, v).apply()

    companion object {
        const val SORT_NAME = 0
        const val SORT_MODIFIED = 1
        const val SORT_ADDED = 2

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        private const val KEY_SORT = "sort"
        private const val KEY_THEME = "theme"
        private const val KEY_LAST_FOLDER = "last_folder"
        private const val KEY_FONT = "font_scale"
        private const val KEY_FORCE_DARK = "force_dark"
        private const val KEY_DESKTOP = "desktop"
        private const val KEY_IMMERSIVE = "immersive"
        private const val KEY_SHOW_ASSETS = "show_assets"
        private const val KEY_PANEL_HINT = "panel_hint_shown"
        private const val KEY_HANDLE_EDGE = "handle_edge"
        private const val KEY_HANDLE_FRACTION = "handle_fraction"

        /** 把偏好里的主题应用到 AppCompat */
        fun applyTheme(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}

/** 库根目录相关的常量 */
object Library {
    const val META_FILE = ".library.json"
    const val NOMEDIA_FILE = ".nomedia"

    /** 面包屑用的可读路径：物理/力学 → 我的学习库 / 物理 / 力学 */
    fun prettyPath(root: File, rel: String): String =
        if (rel.isEmpty()) "我的学习库"
        else "我的学习库 / " + rel.split(File.separatorChar).joinToString(" / ")
}
