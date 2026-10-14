package com.htmlstudy.library.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.htmlstudy.library.R
import com.htmlstudy.library.app
import com.htmlstudy.library.data.LibraryItem
import com.htmlstudy.library.data.LibraryRepository
import com.htmlstudy.library.data.Prefs
import com.htmlstudy.library.databinding.ActivityViewerBinding
import com.htmlstudy.library.util.FileUtil
import com.htmlstudy.library.web.LibraryPathHandler
import java.io.File
import kotlin.math.hypot

/**
 * 阅读器。核心是那个"虚拟站点"式的轻量 WebView：
 * 用 https://study.local/lib/<相对路径> 打开库里的文件，
 * 于是同目录的 css / js / 图片 / 字体都能正常加载，localStorage 也能用。
 *
 * 左侧抽屉是整库的目录树 —— 这就是"在同一个 APP 里切换学科"的地方。
 */
class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_REL = "rel_path"
        private const val EXTRA_TITLE = "title"

        /** 超过这个时长的按压不算"点一下"（长按多半是在选字） */
        private const val TAP_MAX_MS = 400L

        /** 兜底路径等这么久再问页面（给网页那边留出处理这次触摸的时间） */
        private const val TAP_HOOK_GRACE_MS = 160L

        /**
         * 注进课件页里的一个小钩子：每次触摸都记一下"这一下点到了什么"，
         * 松手时如果没怎么移动、落点又不是可交互的元素，就把
         * `window.__hsBlankTap` 置为 true。Kotlin 那边抬手后读这个值。
         *
         * 可交互的判定：A / BUTTON / INPUT / SELECT / TEXTAREA / LABEL / SUMMARY /
         * DETAILS / VIDEO / AUDIO / IFRAME / CANVAS 这些标签，contenteditable、
         * role 是按钮链接之类的、带 onclick 属性、class 里带 btn/quiz/option/answer
         * 这种词的祖先节点，以及光标是手型的元素 —— 课件里的"+""显示答案"
         * 大多是 div/span 加监听，靠这些特征能认出来。
         */
        private const val TAP_HOOK_JS = """
            (function(){
              if (window.__hsTapHook) return;
              window.__hsTapHook = true;
              var sx = 0, sy = 0, t0 = 0, moved = false, onControl = false;
              function interactive(el){
                for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
                  var tag = n.tagName;
                  if (/^(A|BUTTON|INPUT|SELECT|TEXTAREA|LABEL|SUMMARY|DETAILS|VIDEO|AUDIO|IFRAME|CANVAS|OBJECT|EMBED)$/.test(tag)) return true;
                  if (n.isContentEditable) return true;
                  var role = n.getAttribute && n.getAttribute('role');
                  if (role && /button|link|checkbox|radio|tab|menuitem|option|switch|slider/i.test(role)) return true;
                  if (n.hasAttribute && (n.hasAttribute('onclick') || n.hasAttribute('data-toggle'))) return true;
                  var cls = (typeof n.className === 'string') ? n.className : '';
                  if (/btn|button|quiz|option|choice|answer|toggle|switch|clickable|control/i.test(cls)) return true;
                  try { if (getComputedStyle(n).cursor === 'pointer') return true; } catch (e) {}
                  if (n === document.body || n === document.documentElement) break;
                }
                return false;
              }
              document.addEventListener('touchstart', function(e){
                var t = e.touches[0];
                if (!t) return;
                sx = t.clientX; sy = t.clientY; t0 = Date.now(); moved = false;
                onControl = interactive(e.target);
              }, true);
              document.addEventListener('touchmove', function(e){
                var t = e.touches[0];
                if (!t) return;
                if (Math.abs(t.clientX - sx) > 8 || Math.abs(t.clientY - sy) > 8) moved = true;
              }, true);
              document.addEventListener('touchend', function(e){
                if (moved || onControl || Date.now() - t0 > 400) return;
                if (window.hsChrome && window.hsChrome.blankTap) window.hsChrome.blankTap();
              }, true);
            })();
        """

        /**
         * 注进课件页里的"滚动追踪"钩子：把**内容在屏幕上走了多远**累计起来报给 APP，
         * 面板那块液态玻璃就靠它每帧跟着正文走（见 [GlassBackdrop]）。
         *
         * 报累积量而不是绝对坐标，是因为参照物会换 —— 页面可能先滚文档、再滚某个内层
         * 容器，迷你缩放平移时 `clientY` 又不动。累积量在两个参照物之间也能接上，
         * 换参照物的那一帧只是不累加而已。
         *
         * 基准在 `__hsTrack(true)` 时归零，APP 那边同时在归零，两边才对得上。
         */
        private const val SCROLL_HOOK_JS = """
            (function(){
              if (window.__hsScrollHook) return;
              window.__hsScrollHook = true;
              var raf = 0, on = false, el = null, last = null, el0 = null, tx = 0, ty = 0;
              function pick(){ return el || document.scrollingElement || document.documentElement; }
              function kick(){
                if (!on || raf) return;
                raf = requestAnimationFrame(function(){ raf = 0; tick(); });
              }
              function tick(){
                if (!on) return;
                var d = pick();
                if (!d || !d.getBoundingClientRect) return;
                var r = d.getBoundingClientRect();
                var vv = window.visualViewport;
                var k = window.devicePixelRatio || 1;
                var s = (vv && vv.scale) ? vv.scale : 1;
                var oy = (vv && vv.offsetTop) ? vv.offsetTop : 0;
                var ox = (vv && vv.offsetLeft) ? vv.offsetLeft : 0;
                var px = (r.left - ox) * k * s, py = (r.top - oy) * k * s;
                if (!last || d !== el0) { el0 = d; last = { x: px, y: py }; }
                tx += last.x - px;
                ty += last.y - py;
                last.x = px; last.y = py;
                if (window.hsChrome && window.hsChrome.moved) window.hsChrome.moved(tx, ty);
              }
              document.addEventListener('scroll', function(e){
                var t = e.target;
                if (t && t.nodeType === 1) {
                  el = (t === document || t === document.documentElement || t === document.body) ? null : t;
                }
                kick();
              }, true);
              if (window.visualViewport) {
                window.visualViewport.addEventListener('scroll', kick);
                window.visualViewport.addEventListener('resize', kick);
              }
              window.addEventListener('resize', kick);
              window.__hsTrack = function(enable){
                on = !!enable;
                if (raf) { cancelAnimationFrame(raf); raf = 0; }
                if (!on) return;
                last = null; el0 = null; tx = 0; ty = 0;
                kick();
              };
            })();
        """

        /** 页面加载完先让正文落到上次的位置，等一下再重拍条子 */
        private const val LIVE_AFTER_LOAD_MS = 200L

        fun intent(context: Context, relPath: String, title: String): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_REL, relPath)
                .putExtra(EXTRA_TITLE, title)
    }

    private lateinit var binding: ActivityViewerBinding
    private lateinit var repo: LibraryRepository
    private lateinit var prefs: Prefs
    private lateinit var treeAdapter: DrawerTreeAdapter
    private lateinit var assetLoader: WebViewAssetLoader

    private var relPath: String = ""
    private var displayTitle: String = ""
    private var pendingScroll: Float = 0f
    private var pendingScrollApply = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = app.prefs
        Prefs.applyTheme(prefs.themeMode)
        super.onCreate(savedInstanceState)

        repo = app.repo
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 窗口铺满整屏（含挖孔区）；系统栏高度由我们自己加到工具栏和正文上，
        // 这样"显示顶部区域"打开时不会在正文上方留白，沉浸模式能真正满屏。
        EdgeToEdge.apply(this)
        EdgeToEdge.padBottomByNavBar(binding.contentArea)
        EdgeToEdge.padTopBySafeArea(binding.drawerHeader)
        // 侧栏里的科目树也要躲开导航栏，否则最后一项会被挡住点不到
        EdgeToEdge.padBottomByNavBar(binding.drawerList)

        setupDrawer()
        setupWebView()
        setupChrome()
        setupTapToToggleHandle()

        val fromIntent = intent.getStringExtra(EXTRA_REL)
        if (fromIntent.isNullOrEmpty() || !repo.fileOf(fromIntent).isFile) {
            finish()
            return
        }
        relPath = if (savedInstanceState != null) {
            savedInstanceState.getString(EXTRA_REL, fromIntent) ?: fromIntent
        } else {
            fromIntent
        }
        displayTitle = repo.meta.titleOf(relPath, intent.getStringExtra(EXTRA_TITLE) ?: File(relPath).name)

        loadDoc(relPath, restoreScroll = savedInstanceState != null)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // 面板开着就先收面板，别一路退出去
                    chrome.isPanelOpen() -> chrome.collapse()

                    binding.drawer.isDrawerOpen(GravityCompat.START) ->
                        binding.drawer.closeDrawer(GravityCompat.START)

                    binding.webview.canGoBack() -> binding.webview.goBack()

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        applyImmersive(prefs.immersive)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_REL, relPath)
    }

    override fun onPause() {
        super.onPause()
        persistScroll()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.webview.destroy()
    }

    // ------------------------------------------------------------------ 抽屉目录树

    private fun setupDrawer() {
        treeAdapter = DrawerTreeAdapter(repo) { item -> onDrawerDocClicked(item) }
        binding.drawerList.layoutManager = LinearLayoutManager(this)
        binding.drawerList.adapter = treeAdapter
        binding.drawerList.setHasFixedSize(false)

        // 左侧边缘留给"拉出工具栏"的把手：目录树改成只能从面板里的按钮打开，
        // 否则同一个位置的手势会互相打架 —— 想拉面板却滑出了目录。
        binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        binding.drawerHeaderTitle.text = getString(R.string.app_name)
        binding.drawerHeaderSubtitle.text = getString(R.string.drawer_hint)
        binding.drawerBack.setOnClickListener { finish() }
        binding.drawerFavorites.setOnClickListener { quickNav(getString(R.string.favorites), repo.favorites(), null) }
        binding.drawerRecent.setOnClickListener { quickNav(getString(R.string.recent), repo.recent(30), null) }

        // 沉浸模式下打开侧栏时，先把系统栏亮出来，免得看不见当前在哪儿
        binding.drawer.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // 侧栏一滑，正文区整块跟着平移 —— 面板那套窗口坐标当场失效，
                // 趁它还在动先把面板收起来，别让玻璃照着旧位置去折射
                if (slideOffset > 0f && chrome.isPanelOpen()) chrome.collapse()
            }

            override fun onDrawerOpened(drawerView: View) {
                ui.removeCallbacks(hideBarsRunnable)
                chrome.collapse()
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }

            override fun onDrawerClosed(drawerView: View) {
                if (prefs.immersive) ui.postDelayed(hideBarsRunnable, 400)
            }

            override fun onDrawerStateChanged(newState: Int) = Unit
        })
    }

    private fun onDrawerDocClicked(item: LibraryItem) {
        binding.drawer.closeDrawer(GravityCompat.START)
        if (item.relPath == relPath) return
        loadDoc(item.relPath, restoreScroll = true)
    }

    /**
     * 抽屉头部的"收藏 / 最近"快捷入口：直接用对话框列出来，跳过去。
     * 比再开一层页面轻，也更符合"随手切换"的用法。
     */
    private fun quickNav(title: String, items: List<LibraryItem>, emptyRes: Int?) {
        if (items.isEmpty()) {
            Dialogs.toast(this, getString(emptyRes ?: R.string.nothing_here))
            return
        }
        val labels = items.map { item ->
            val t = repo.meta.titleOf(item.relPath, item.name)
            val folder = item.relPath.substringBeforeLast('/', "")
            if (folder.isEmpty()) t else "$t\n$folder"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val item = items[which]
                binding.drawer.closeDrawer(GravityCompat.START)
                loadDoc(item.relPath, restoreScroll = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webview.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.textZoom = prefs.fontSize
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = false
        }

        applyDesktopMode(prefs.desktopMode)
        applyForceDark(prefs.forceDark)

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(LibraryPathHandler.DOMAIN)
            .addPathHandler(LibraryPathHandler.PREFIX, LibraryPathHandler(repo.root))
            .build()

        binding.webview.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == LibraryPathHandler.DOMAIN) return false

                return when (url.scheme?.lowercase()) {
                    "http", "https", "mailto", "tel", "sms", "geo", "market" -> {
                        openExternally(url); true
                    }

                    // 库内的 file:// 链接映射回虚拟站点继续在本 APP 里读；
                    // 库外的交给系统处理
                    "file", "content" -> {
                        if (!openInsideLibrary(url)) openExternally(url)
                        true
                    }

                    "intent" -> {
                        runCatching { startActivity(Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)) }
                            .onFailure { Dialogs.toast(this@ViewerActivity, getString(R.string.no_app_for_link)) }
                        true
                    }

                    else -> true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                binding.errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 判断"这一次点的是不是空白处"得在网页里做：
                // hitTestResult 只认得链接和输入框，课件里的"+""显示答案"这类按钮
                // 往往是 div/span 加个点击监听，光看 hitTest 会当成空白，于是点按钮
                // 也会收起把手。
                view.evaluateJavascript(TAP_HOOK_JS, null)
                // 滚动追踪的钩子也得跟着新页面重挂；面板开着的话，等正文落到上次的
                // 位置之后再重拍条子（不然拍到的是复位前那一帧，条子会摆错地方）
                view.evaluateJavascript(SCROLL_HOOK_JS, null)
                binding.progress.visibility = View.GONE
                if (pendingScrollApply) {
                    pendingScrollApply = false
                    applyScroll(pendingScroll)
                }
                ui.postDelayed({
                    if (::chrome.isInitialized) chrome.onPageLoaded()
                }, LIVE_AFTER_LOAD_MS)
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                // 捏合缩放之后，整页东西是按新的尺度渲染出来的，条子整个作废
                if (::chrome.isInitialized) chrome.onViewportChanged()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                binding.progress.visibility = View.GONE
                binding.errorView.visibility = View.VISIBLE
            }
        }

        binding.webview.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                // 页面自己的 <title> 只在用户没自定义名字、且文件名很潦草时兜底
                if (repo.meta.peek(relPath)?.title != null) return
                if (!title.isNullOrBlank() && title.length <= 40 && displayTitle.startsWith("index.")) {
                    displayTitle = title
                    chrome.setTitle(displayTitle)
                }
            }

            // 课件里的 alert / confirm 要能用（很多自测题靠它反馈）
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                MaterialAlertDialogBuilder(this@ViewerActivity)
                    .setMessage(message)
                    .setPositiveButton(R.string.confirm) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                MaterialAlertDialogBuilder(this@ViewerActivity)
                    .setMessage(message)
                    .setPositiveButton(R.string.confirm) { _, _ -> result.confirm() }
                    .setNegativeButton(R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                chrome.setVisible(false)
                binding.contentArea.visibility = View.GONE
                (window.decorView as FrameLayout).addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                val view = customView ?: return
                (window.decorView as FrameLayout).removeView(view)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                binding.contentArea.visibility = View.VISIBLE
                chrome.setVisible(true)
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean = true
        }

        binding.webview.addJavascriptInterface(PageBridge(), "hsChrome")
        binding.errorRetry.setOnClickListener { loadDoc(relPath, restoreScroll = false) }
    }

    private fun loadDoc(newRel: String, restoreScroll: Boolean) {
        relPath = newRel
        displayTitle = repo.meta.titleOf(newRel, File(newRel).name)
        chrome.setTitle(displayTitle)

        repo.meta.markOpened(newRel)
        repo.saveMeta()

        pendingScroll = if (restoreScroll) repo.meta.peek(newRel)?.scroll ?: 0f else 0f
        pendingScrollApply = pendingScroll > 0.01f

        binding.errorView.visibility = View.GONE
        binding.webview.loadUrl(LibraryPathHandler.urlFor(newRel))

        treeAdapter.currentPath = newRel
        treeAdapter.revealPath(newRel, binding.drawerList)
        updateChromeState()
    }

    // ------------------------------------------------------------------ 阅读状态

    private fun applyScroll(fraction: Float) {
        val js = "(function(){var d=document.documentElement,b=document.body;" +
                "var h=Math.max(d.scrollHeight,b?b.scrollHeight:0)-window.innerHeight;" +
                "if(h>40){window.scrollTo(0,h*$fraction);}return h;})()"
        binding.webview.evaluateJavascript(js, null)
    }

    private fun persistScroll() {
        if (relPath.isEmpty()) return
        val target = relPath
        binding.webview.evaluateJavascript(
            "(function(){var d=document.documentElement,b=document.body;" +
                    "var h=Math.max(d.scrollHeight,b?b.scrollHeight:0)-window.innerHeight;" +
                    "return h>60?(window.scrollY||d.scrollTop||0)/h:0;})()"
        ) { value ->
            val fraction = value?.trim('"')?.toFloatOrNull() ?: 0f
            repo.meta.setScroll(target, fraction)
            repo.saveMeta()
        }
    }

    private fun applyDesktopMode(enabled: Boolean) {
        val settings = binding.webview.settings
        if (enabled) {
            val ua = settings.userAgentString.orEmpty()
                .replace(" Mobile", "")
                .replace("Android ", "Android-desktop ")
            settings.userAgentString = ua
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
        } else {
            settings.userAgentString = null
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }
        binding.webview.reload()
    }

    private fun applyForceDark(enabled: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            if (enabled) Dialogs.toast(this, getString(R.string.force_dark_unsupported))
            return
        }
        WebSettingsCompat.setForceDark(
            binding.webview.settings,
            if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)
        ) {
            WebSettingsCompat.setForceDarkStrategy(
                binding.webview.settings,
                WebSettingsCompat.DARK_STRATEGY_USER_AGENT_DARKENING_ONLY
            )
        }
    }

    // ------------------------------------------------------------------ 侧边小把手 + 工具面板

    /** 阅读器的外壳：贴在屏幕边上的小把手，和它拉出来的那块液态玻璃面板 */
    private lateinit var chrome: ReaderChrome

    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    /**
     * 面板和把手都浮在 WebView 上层、只靠平移挪位置，不占布局、不改内边距 ——
     * 所以反复拉出 / 收回也不会堆出白边（v1.1 那个 bug 就是这么来的）。
     * 具体怎么摆、怎么磨玻璃，都在 [ReaderChrome] 里。
     */
    private fun setupChrome() {
        chrome = ReaderChrome(
            activity = this,
            binding = binding,
            prefs = prefs,
            onAction = { onChromeAction(it) },
            onPanelOpened = { showToolPanelHint() },
            // 面板拉开时才让网页报滚动位置：玻璃要跟着正文实时折射
            onLiveChanged = { on -> trackScroll(on) }
        )
        chrome.setup()
        updateChromeState()
    }

    /** 开 / 关网页里的滚动追踪（钩子还没挂上时，等页面加载完会自动再接上） */
    private fun trackScroll(on: Boolean) {
        binding.webview.evaluateJavascript(
            "window.__hsTrack&&window.__hsTrack($on);",
            null
        )
    }

    private fun onChromeAction(action: ChromeAction) {
        when (action) {
            ChromeAction.TOC -> {
                chrome.collapse()
                if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
                    binding.drawer.closeDrawer(GravityCompat.START)
                } else {
                    binding.drawer.openDrawer(GravityCompat.START)
                }
            }

            ChromeAction.FAVORITE -> toggleFavorite()
            ChromeAction.FONT -> chooseFontSize()

            ChromeAction.DARK -> {
                prefs.forceDark = !prefs.forceDark
                applyForceDark(prefs.forceDark)
                binding.webview.reload()
                updateChromeState()
            }

            ChromeAction.DESKTOP -> {
                prefs.desktopMode = !prefs.desktopMode
                applyDesktopMode(prefs.desktopMode)
                updateChromeState()
            }

            ChromeAction.IMMERSIVE -> applyImmersive(!prefs.immersive)
            ChromeAction.REFRESH -> binding.webview.reload()
            ChromeAction.SHARE -> shareDoc()
            ChromeAction.OPEN_WITH -> openWithOtherApp()
            ChromeAction.COLLAPSE -> chrome.collapse()
        }
    }

    /** 收藏 / 深色 / 桌面版 / 全屏这几个开着的时候，面板上的图标要点亮 */
    private fun updateChromeState() {
        chrome.refreshIcons(
            favorite = repo.meta.isFavorite(relPath),
            desktop = prefs.desktopMode,
            dark = prefs.forceDark,
            immersive = prefs.immersive
        )
    }

    /**
     * 点一下正文：把侧边那个小把手滑出去 / 滑回来。
     *
     * 监听只是"旁听"，一律返回 false，滚动手势照旧交给 WebView。
     * 松手后问网页"这一下落在什么元素上"（[TAP_HOOK_JS] 记的），
     * 落在按钮 / 链接 / 输入框 / 手型光标 / canvas 这些可交互的东西上就不抢这次点击；
     * 网页里问不到（脚本没注入上）时才退回 hitTestResult。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToToggleHandle() {
        var downX = 0f
        var downY = 0f
        var moved = false

        binding.webview.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > touchSlop) moved = true
                }

                MotionEvent.ACTION_UP -> {
                    val quick = event.eventTime - event.downTime < TAP_MAX_MS
                    if (!moved && quick && hypot(event.x - downX, event.y - downY) <= touchSlop) {
                        // 正常情况由页面里的钩子直接回调（见 PageBridge），
                        // 这里只是"钩子没装上"时的兜底，所以慢一点问没关系
                        view.postDelayed({ blankTapFallback() }, TAP_HOOK_GRACE_MS)
                    }
                }
            }
            false
        }
    }

    /**
     * 钩子没装上时的兜底：确认网页里确实没有钩子，再退回 hitTestResult。
     * （钩子在的话，[PageBridge] 已经处理过了，这里不能重复触发。）
     */
    private fun blankTapFallback() {
        binding.webview.evaluateJavascript("String(!!window.__hsTapHook)") { value ->
            if (value?.trim('"') != "false") return@evaluateJavascript
            val hit = binding.webview.hitTestResult?.type
            if (hit == WebView.HitTestResult.UNKNOWN_TYPE) chrome.toggleHandle()
        }
    }

    /**
     * 页面里的钩子回调。用桥而不是"抬手后回头问页面"，是因为网页在另一个进程里，
     * 抬手那一瞬间页面还没处理完这次触摸，问到的会是上一次的结果 —— 那样点"+""显示答案"
     * 这类按钮时就会误收起把手。
     *
     * 暴露给课件页的只有两个能力：切一下把手、报一下滚动位置，没有别的。
     * 这两个回调都在 WebView 的 JS 线程上，视图相关的事一律 post 回主线程。
     */
    private inner class PageBridge {
        @JavascriptInterface
        fun blankTap() {
            ui.post { if (::chrome.isInitialized) chrome.toggleHandle() }
        }

        /** 内容在屏幕上走了多远（累积量，正数 = 往上 / 往左），面板玻璃跟着它走 */
        @JavascriptInterface
        fun moved(x: Double, y: Double) {
            val fx = x.toFloat()
            val fy = y.toFloat()
            ui.post { if (::chrome.isInitialized) chrome.onContentMoved(fx, fy) }
        }
    }

    private fun toggleFavorite() {
        val now = repo.meta.toggleFavorite(relPath)
        repo.saveMeta()
        updateChromeState()
        Dialogs.toast(this, getString(if (now) R.string.added_favorite else R.string.removed_favorite))
    }

    private fun chooseFontSize() {
        val options = listOf(80, 100, 120, 150, 180, 220)
        val labels = options.map { if (it == 100) "$it% (默认)" else "$it%" }
        val current = options.indexOfFirst { it == prefs.fontSize }.coerceAtLeast(0)
        Dialogs.singleChoice(this, getString(R.string.font_size), labels, current) { which ->
            prefs.fontSize = options[which]
            binding.webview.settings.textZoom = options[which]
        }
    }

    /** 第一次拉出面板时提示一下，之后就靠那个把手自己找了 */
    private fun showToolPanelHint() {
        if (prefs.seenPanelHint) return
        prefs.seenPanelHint = true
        binding.hint.visibility = View.VISIBLE
        binding.hint.alpha = 0f
        binding.hint.animate().alpha(1f).setDuration(200).withEndAction {
            binding.hint.postDelayed({
                binding.hint.animate().alpha(0f).setDuration(400).withEndAction {
                    binding.hint.visibility = View.GONE
                }.start()
            }, 2600)
        }.start()
    }

    private fun applyImmersive(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        ui.removeCallbacks(hideBarsRunnable)
        if (enabled) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            chrome.collapse()
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        prefs.immersive = enabled
        updateChromeState()
    }

    /** 沉浸模式下从目录树回到正文时，把系统栏再收回去 */
    private val hideBarsRunnable = Runnable {
        if (!prefs.immersive || customView != null) return@Runnable
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) return@Runnable
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------------ 链接

    private fun openExternally(uri: Uri) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Dialogs.toast(this, getString(R.string.no_app_for_link))
        }
    }

    /** 文档里若写了 file:// 绝对路径，尝试映射回库内地址，仍在本 APP 里打开 */
    private fun openInsideLibrary(uri: Uri): Boolean {
        val rawPath = when (uri.scheme) {
            "file" -> uri.path
            else -> uri.toString()
        } ?: return false
        val rootPath = repo.root.absolutePath
        if (!rawPath.startsWith(rootPath)) return false
        val rel = rawPath.substring(rootPath.length).trimStart('/')
        if (rel.isEmpty()) return false
        val file = repo.fileOf(rel)
        if (!file.isFile) return false
        if (FileUtil.isDoc(file.name)) {
            loadDoc(rel, restoreScroll = false)
        } else {
            openExternally(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
        }
        return true
    }

    // ------------------------------------------------------------------ 交给别的应用

    /** 用系统浏览器 / 别的阅读器打开当前这篇（有些课件在本应用里就是排不好） */
    private fun openWithOtherApp() {
        val file = repo.fileOf(relPath)
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, FileUtil.mimeOf(file.name))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.open_with)
                )
            )
        }.onFailure { Dialogs.toast(this, getString(R.string.no_app_for_file)) }
    }

    private fun shareDoc() {
        val file = repo.fileOf(relPath)
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType(FileUtil.mimeOf(file.name))
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.share)
                )
            )
        }.onFailure { Dialogs.toast(this, getString(R.string.no_app_for_file)) }
    }
}
