# 学习库 · HTMLStudy —— 架构与实现说明

这份文档写给**接手这个项目的人**：它是什么样的应用、代码怎么分层、每一处"看起来奇怪"
的实现是为什么这么写的、以及哪些地方**还没有在真机上验证过**。

代码里的注释风格是尽量写"为什么"而不是"做了什么"，本文档尽量不重复那些注释，
重点放在**跨模块的约定**和**踩过的坑**上。

- 当前版本：`versionCode 6` / `versionName 1.5`
- 语言 / 框架：Kotlin + Android Views（**没有 Compose**、**没有协程**）
- AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.7 / JDK 17 / compileSdk & targetSdk 34 / minSdk 24
- 依赖只有 androidx + Material 3：core-ktx、appcompat、recyclerview、constraintlayout、
  webkit、documentfile、material，测试用 junit + robolectric
- 只有一个权限：`INTERNET`（给课件里的在线资源和用户主动点开的外链用；离线阅读不需要）
- release APK 约 1.9 MB

---

## 0. 这个应用要解决什么

把手机上零散下载的**离线 HTML 课件**（各科目、各来源）收进一个库里，按目录管理，
点开就能读。约束是：

1. **不联网也能用**，尽量不申请权限（所以库放在应用专属外部存储，见 §2.1）；
2. **课件要"像原来那样"能跑**：相对路径的 CSS/JS/图片/字体、`fetch`/XHR、
   `localStorage`、自带测验的 `alert`、内嵌视频全屏，都要能用；
3. **界面要轻**：没有顶栏，正文满屏（含刘海区）；工具是一条贴在屏幕边上的小把手，
   拉出来是**液态玻璃**面板；
4. 发布上只发 APK，仓库里没有历史包袱。

---

## 1. 全局地图

```
MainActivity ──浏览/导入/搜索/收藏/最近──> LibraryRepository ──> MetaStore (.library.json)
     │                                            │
     │  ACTION_VIEW（别的应用"打开方式"）           └──> 真实文件系统（库目录）
     ▼
ViewerActivity ── WebView ── WebViewAssetLoader ── LibraryPathHandler
     │                                   （https://study.local/lib/...）
     ├── ReaderChrome（把手 + 面板 + 液态玻璃 + 活的背景）
     │       ├── LiquidGlass        截图之外的所有 GPU 效果
     │       ├── GlassBackdrop      "活"背景：条子的拍照 / 平移 / 补行
     │       ├── BackdropMath       上面那两步的纯计算（可测）
     │       ├── ChromeGeometry     把手 / 面板的位置换算（纯算术，可测）
     │       └── EdgeToEdge         全面屏 / 刘海适配
     └── PageBridge（addJavascriptInterface "hsChrome"）
             ├── blankTap()  网页判断"这一下点的是空白"
             └── moved(x, y) 网页报"内容滚了多远"（累积量）
```

**线程模型**（全项目只有三种线程，没有协程）：

| 场景 | 线程 |
| --- | --- |
| 界面、动画、View 属性 | 主线程（`Handler(Looper.getMainLooper())`，各处的 `ui`） |
| 导入、复制、解压、搜索、图像匹配 | `Executors.newSingleThreadExecutor()`（`io` / `worker`） |
| `@JavascriptInterface` 回调 | WebView 的 JS 线程 —— **一律 `ui.post {}` 回主线程**再碰 View |
| `PixelCopy` 结果 | 回调到传入的 `Handler`（我们传的是 `ui`） |

**包结构**

```
com.htmlstudy.library
├── App.kt                   Application：建库 + 建偏好，`Context.app` 扩展
├── data/
│   ├── LibraryRepository.kt 库的门面（列表 / 排序 / 搜索 / 改名 / 移动 / 删除）
│   ├── MetaStore.kt         .library.json 的读写（收藏、进度、标题、时间戳）
│   ├── ImportManager.kt     三个导入入口（多文件 / 文件夹 / ZIP）
│   ├── LibraryItem.kt       条目模型（FOLDER / DOC / ASSET）
│   └── Prefs.kt             SharedPreferences + 库路径常量（`Library`）
├── util/FileUtil.kt         纯文件工具，**不依赖 Android**（所以好测）
├── web/LibraryPathHandler.kt 虚拟站点（MIME / 编码 / 目录穿越防护）
└── ui/
    ├── MainActivity.kt      首页
    ├── ViewerActivity.kt    阅读器（把外壳的动作接到业务上）
    ├── ReaderChrome.kt      把手 + 面板 + 玻璃的装配与交互
    ├── LiquidGlass.kt       GPU 部分：AGSL 透镜 / 模糊 / 缩放三档
    ├── GlassBackdrop.kt     活的背景：条子、补行、重拍节奏
    ├── BackdropMath.kt      活的背景里的纯计算
    ├── ChromeGeometry.kt    把手 / 面板几何
    ├── EdgeToEdge.kt        全面屏适配
    ├── ItemAdapter.kt / DrawerTreeAdapter.kt / Dialogs.kt / ProgressDialog.kt
```

---

## 2. 数据层

### 2.1 库放在哪

```kotlin
context.getExternalFilesDir(null) ?: context.filesDir   // 再拼 "library"
```

优先应用专属外部存储：**不需要任何存储权限**，但用 USB 线接电脑或文件管理器能直接看到
`Android/data/com.htmlstudy.library/files/library/`，用户可以直接往里拖文件。
取不到外部存储时退回内部存储。库里第一件事是放一个 `.nomedia`，
免得课件的图片把系统相册塞满。

### 2.2 `.library.json`

文件系统只存**内容**，管理用的信息都在库根目录的 `.library.json`：

```json
{ "version": 1,
  "files": { "物理/力学/ch1.html": { "title": "第一章", "added": 1699…, "opened": 1699…,
                                    "fav": true, "scroll": 0.42, "count": 7 } } }
```

`MetaStore` 的几条规矩：

- 内存里一份 `HashMap<String, MetaEntry>`，**脏标记 + 原子落盘**：先写 `.library.json.tmp`
  再 `renameTo`，`renameTo` 失败就直接覆盖（部分设备上 tmp 与目标不同卷）；
- 条目全是默认值（没收藏、没标题、没时间、没进度）时**不写进文件**，避免索引里全是空壳；
- 读写都 `@Synchronized`，`App.onCreate` 里 `load()` 一次，主线程外的搜索也能读；
- **改名 / 移动 / 删除会迁移路径**：`renamePath(old, new)` 把它自己和它下面所有子项的
  记录整体搬过去（前缀匹配），`removePath(rel)` 连子项一起清。不迁移就会出现
  "收藏还在，路径已经不存在"的僵尸记录。

### 2.3 导入（`ImportManager`）

三个入口都走 SAF（`OpenMultipleDocuments` / `OpenDocumentTree` / `OpenDocument`），
所以同样**不需要存储权限**：

| 入口 | 关键点 |
| --- | --- |
| 多选文件 | 选择器只列 `text/html`、`application/xhtml+xml`、`multipart/related`（`FileUtil.DOC_MIME_TYPES`，从 `MIME_MAP` 里取，保证"能选的"和"打得开的"一致）。重名走 `uniqueFile` 自动加序号，**不覆盖** |
| 整个文件夹 | 课件通常是 `xxx.html` + `xxx_files/`，必须整目录搬。可选"作为子目录导入"或"平铺展开" |
| ZIP | 先落到 `cacheDir` 再解；`ZipFile(tmp, GBK)`，失败退回 UTF-8（带 UTF-8 标志位的条目 `ZipFile` 会自动按 UTF-8 解析）；**zip-slip 防护**用 `canonicalPath` 校验每个条目仍在目标目录内；跳过 `__MACOSX` 和隐藏文件 |

两个额外的规矩：

- **`flattenSingleRoot`**：ZIP 里只有一个顶层目录时（`物理课件.zip` 里套 `course/index.html`），
  把内容提上来，避免套娃。注意保留的是**压缩包名**（用户自己起的，认得出来），
  内层目录名（常常是 `course` / `dist` / `www`）只当内容来源；搬不动的留在原地，不丢文件。
- **文件名清理**（`FileUtil.sanitize`）：把 FAT/exFAT 非法字符换掉、去尾部点、限长 120 字符。
  拷贝到存储卡上失败大多是这一步没做。

`ImportManager.Result` 会把成功/失败数和前几条错误信息拼成人话给 `Toast`。

### 2.4 偏好（`Prefs`）

一个 `SharedPreferences`，字段：排序方式、主题、上次停留的目录、正文字号、
强制深色、桌面版、沉浸模式、把手的边与比例、是否提示过面板、是否显示非 HTML 文件。
`Prefs.applyTheme` 负责把主题映射到 `AppCompatDelegate.setDefaultNightMode`，
两个 Activity 都在 `super.onCreate` **之前**调用它。

---

## 3. 阅读器与虚拟站点

### 3.1 为什么不用 `file://`

直接 `file://` 打开课件有三个坑，都在 `LibraryPathHandler` 的类注释里写了：
跨域导致 `fetch`/XHR 失败、`localStorage` 同源身份不稳、MIME 类型没法保证。
所以用 `WebViewAssetLoader` + 自定义 `PathHandler` 把库目录映射成一个虚拟站点：

```
https://study.local/lib/物理/力学/ch1.html
```

路径按段 `Uri.encode`（中文、空格都安全），响应头带 `Access-Control-Allow-Origin: *`
和 `Cache-Control: no-cache`。

三个必须记住的实现细节：

1. **目录穿越防护**：`canonicalPath` 必须等于库根或以其 + 分隔符开头，否则返回 null；
2. **`encoding` 传 `null`**：中文老课件大量是 GBK，而 WebView 默认 UTF-8 会乱码。
   传 null 让 WebView 自己读页面里的 `<meta charset>`，这是唯一稳妥的做法；
3. **MIME 表在 `FileUtil.MIME_MAP`**：`.woff2`、`.wasm`、`.mhtml` 这些不写对就会被当成
   二进制流。`DOC_EXTENSIONS` 与 `DOC_MIME_TYPES` 也从这里派生，保持"选得到的能打开"。

### 3.2 WebSettings 里那些开关是为什么

| 设置 | 为什么 |
| --- | --- |
| `javaScriptEnabled` / `domStorageEnabled` / `databaseEnabled` | 课件基本都用得到 |
| `loadWithOverviewMode` + `useWideViewPort` | 手机上看为桌面排的课件不至于横向溢出 |
| `builtInZoomControls` + `setSupportZoom`、`displayZoomControls=false` | 允许双指缩放但不显示那对老按钮 |
| `mediaPlaybackRequiresUserGesture = false` | 课件里自动播放的音频/视频 |
| `mixedContentMode = ALWAYS_ALLOW` | 有的课件会引用 http 资源 |
| `allowFileAccessFromFileURLs = true` | 少数课件里硬编了 `file://` 路径 |
| `textZoom = prefs.fontSize` | 「字号」那一格改的就是它 |
| `safeBrowsingEnabled = false` | 本地文件被安全浏览拦下来过 |

### 3.3 进度保存 / 恢复

按**滚动比例**存（`MetaStore.scroll`，0…1）：

- 进阅读器 / 切文档前读出来放到 `pendingScroll`，`onPageFinished` 里注入一段 JS 执行
  `window.scrollTo(0, h * fraction)`（`h` 是 `scrollHeight - innerHeight`，太短的页面不滚）；
- `onPause` 时反过来问页面要比例，写回索引。

比例法的已知不准：动态加载内容很长的页面，恢复位置会有偏差（见 §9）。

### 3.4 链接策略

`shouldOverrideUrlLoading` 按 scheme 分流：

- `study.local` → 交给自己处理（站内跳转，比如课件里点 `ch2.html`）；
- `http/https/mailto/tel/sms/geo/market` → 丢给系统（`Intent.ACTION_VIEW`），失败提示"没有应用"；
- `file:` / `content:` → 如果路径在库内，映射回虚拟站点**继续在本应用里读**
  （很多老课件写死了绝对路径）；库外的交给系统；
- `intent:` → 按 `URI_INTENT_SCHEME` 解析后直接 `startActivity`；
- 其它 scheme 一律拦掉。

`alert` / `confirm` 用 Material 对话框接管（自测题靠这个反馈）；视频全屏走
`onShowCustomView`，此时整个外壳（`chrome.setVisible(false)`）和正文区一起让开。

---

## 4. 外壳：把手与面板

### 4.1 三个坐标系（最容易搞混的地方）

| 坐标系 | 原点 | 谁在用 |
| --- | --- | --- |
| **窗口坐标** | 窗口左上 | `PixelCopy` 的矩形、`getLocationOnScreen` |
| **正文区坐标** | `content_area` 的内容区（减掉 padding） | `panelX/panelY`、`handleX/handleY`（`ReaderChrome` 内部） |
| **面板局部坐标** | 面板自己的左上角 | `LiquidGlass` 的 `ImageMatrix`、AGSL 的 `coord` |

换算只在两处发生：`relayout()` 里把正文区坐标加上 `getLocationOnScreen` + padding 变成窗口坐标，
交给 `GlassBackdrop.setGeometry(...)`；`ReaderChrome.matrixFor` / `GlassBackdrop.bandRectFor`
把窗口坐标换成面板局部坐标。

> ⚠️ 用的是 `getLocationOnScreen` 而不是 `getLocationInWindow`。全屏（edge-to-edge）窗口里
> 两者相同，这也是它一直在真机上正常的原因；**分屏 / 自由窗口模式下会有窗口偏移**，
> 条子的截图位置会偏。见 §9。

### 4.2 `ChromeGeometry`（纯算术，有单测）

把手的状态只有两个数：**贴在哪条边**（`edge`）+ **在这条边上的比例**（`fraction`）。
存比例而不是像素，所以转屏、分屏、换设备之后把手还在同一段位置上。

- `snapEdge(cx, cy, w, h)`：离哪条边近算哪条，平手按 左→右→上→下；
- `fractionAlong(edge, …)`：中心点在这条边上的比例；
- `offsetAlong(fraction, available, handleLength)`：比例 → 起始偏移，减半个把手长度
  是为了不让把手有一半探出屏幕；
- `panelAlign(fraction, available, panelLength, handleLength)`：面板中心对齐全把手中心，再夹进屏幕。

### 4.3 手势状态机

把手（`ReaderChrome.setupHandleTouch`）：

```
ACTION_DOWN  → 记下 rawX/rawY、把手在屏幕上的位置、手指落在把手里的相对位置（grab）
ACTION_MOVE  → 超过 touchSlop 才进入 dragging；一进入 dragging 就把面板收起来
              （面板挂在旁边会挡事，而且拖动中还要跟着算位置）
              位置 = 手指位置 - grab - 正文区原点，夹在正文区内
ACTION_UP    → dragging ? 吸附到最近的边并持久化 : 单击（开/合面板）
```

用 `event.rawX/rawY`（屏幕坐标）而不是 `event.x/y`，因为拖动过程中 View 自己也在被平移，
`x/y` 是相对 View 的，会把平移量算进去 —— 手指停下时把手会自己漂。

**面板位置**（`applyPanelPos`）：贴着把手那条边，另一方向上跟把手中心对齐；
向外多探出去一个圆角距离（`PANEL_RADIUS_DP = 18dp` 的 `over`），探出去的半个圆角被窗口裁掉，
玻璃片看起来就像"从屏幕边上长出来"。收起来时停在屏幕外沿（`-panelW` / `boxW` 之类），
不是靠"探出去多少"来兜底。

面板高度按屏幕算（`relayout` 里）：`cellH = (可用高度 / 行数)` 夹在 40…52dp，
屏幕矮（横屏、分屏）就把格子压扁，保证整块面板放得下。改高度会让布局再来一次，
所以 `relayout` 里改了高度就 `return`，下一次布局再算位置。

### 4.4 "点一下空白才收把手"

需求：点正文空白 → 把手滑出去 / 滑回来；点"＋""显示答案"这类按钮**不能**触发。

`WebView.hitTestResult` 只认 `ANCHOR`/`EDIT_TEXT`/`IMAGE`，课件里的按钮多是 `div`/`span`
挂监听，全报 `UNKNOWN_TYPE`，跟点空白一样 —— 靠它区分必然误触发。所以判断放到网页里做：

- `TAP_HOOK_JS`（`ViewerActivity` 常量）在 `onPageFinished` 注入：
  `touchstart` 记起点并**向上遍历祖先**判断是不是可交互元素（标签白名单、`contenteditable`、
  `role=button|link|…`、`onclick` / `data-toggle`、类名里带 `btn|quiz|option|answer|toggle|…`、
  `cursor:pointer`）；`touchend` 时没移动 (>8px)、没超时 (>400ms)、又不在可交互元素上，
  才算"点了空白"。
- 判定结果通过 `addJavascriptInterface` 的对象 `hsChrome` **直接回调** Kotlin
  （`PageBridge.blankTap() → chrome.toggleHandle()`）。

为什么不"抬手后回头问页面"：网页跑在**另一个进程**里，`ACTION_UP` 那一瞬间它还没处理完
这次触摸，`evaluateJavascript` 问到的是**上一次**的结果 —— 点按钮照样误触发（v1.3 的 bug）。
现在这条老路只在"钩子没装上"时兜底（`blankTapFallback`：先确认 `window.__hsTapHook` 不存在，
再退回 `hitTestResult`），所以它慢 160ms 也没关系。

`hsChrome` 只暴露两个方法（`blankTap` / `moved`），课件页拿不到别的东西。

### 4.5 全面屏适配（`EdgeToEdge`）

- 窗口铺满整屏（`setDecorFitsSystemWindows(false)` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`），
  状态栏透明；
- 系统栏高度**由我们自己作为 padding 加到有底色的那个 View 上**：加在外层容器上会露出
  窗口底色 —— 那正是 v1.0 那条白边；
- **原始 padding / margin 用 `WeakHashMap` 缓存**（`originalPadding` / `originalMargin`）。
  不能读 `view.paddingTop` 现算：这类函数会被反复开关（显示/隐藏工具栏、进/出沉浸），
  读到的是"上一次已经加过系统栏高度"的值，每开一次再叠一层，白边越来越厚。
  缓存了原始值，任何时候都是 `原始 + 当前系统栏`。`EdgeToEdgeTest` 盯着这一点。

底部用 `padBottomByNavBar` / `liftAboveNavBar`：系统栏一隐藏，内边距自动归零，全屏阅读不占地方。

---

## 5. 液态玻璃（静态部分）

### 5.1 为什么只能截图

Android 没有"模糊身后的内容"的公开 API：

- `Window.setBackgroundBlurRadius` 模糊的是窗口**背后**的桌面 / 上一个应用，不是本窗口内的内容；
- `View.setRenderEffect` 模糊的是**自己**的内容。

所以走截图：面板拉开前把面板**将要占住**的那块窗口像素用 `PixelCopy` 拷出来，贴到面板背后再做 GPU 效果。
此刻面板整个还在屏幕外，拍到的是干净正文，不会把面板自己拍进去。

三档降级（`LiquidGlass.Tier`，`setup()` 里按系统版本 + 着色器能否编译决定）：

| 档 | 条件 | 做法 |
| --- | --- | --- |
| `LENS` | Android 13+ 且 `RuntimeShader` 编译成功 | 截图 → 高斯模糊 → AGSL 单 pass 透镜 |
| `BLUR` | Android 12（API 31/32） | 截图 → 高斯模糊 |
| `DOWNSCALE` | Android 7~11（API 24~30） | 截图缩到 1/6 再放大（双线性插值就是模糊） |

任何一步失败都 `runCatching` 兜住、退回半透明底色（`panel_scrim`），绝不糊掉正文。

### 5.2 装配（`ReaderChrome`）

- `panel_backdrop` 是 `scaleType="matrix"` 的 `ImageView` —— **用矩阵而不是像素矩形**
  定位背景：每帧只改一个 `Matrix` 就能让背景跟着正文走（`slide`），滚动才跟得住；
- `panel_scrim` 是半透明底色 + 描边，也是拿不到截图时的兜底。透镜档把它压到 28%
  （着色器会逐像素调明暗），另两档保持满不透明；
- `panel_gloss` 是一道来回晃动的高光（`ObjectAnimator` 无限往返，面板开着才跑）；
- 圆角：透镜档由着色器自己算覆盖率（`surface.clipToOutline = false`，多一层裁剪会把折射带切掉），
  另两档靠 `ViewOutlineProvider` 裁剪。

`LiquidGlass` 的三个入口是有区别的，别用错：

| 方法 | 重建 `RenderEffect`？ | 用途 |
| --- | --- | --- |
| `bind(bitmap, matrix)` | 是 | 换一张尺寸可能不同的背景（静态档） |
| `rebind(bitmap, matrix)` | 否 | 换一张**同尺寸**的背景（活的背景换新条子） |
| `slide(matrix)` | 否 | 只挪位置（滚动时的每一帧） |

效果只跟面板尺寸和光照方向有关，跟位图无关 —— 所以滚动时重建 `RenderEffect` 是纯浪费。

### 5.3 `RenderEffect` 链的顺序

```kotlin
RenderEffect.createChainEffect(lensEffect, blur)   // 先糊，再折射
```

`createChainEffect(outer, inner)` 里 **inner 先生效**：所以 blur 是内层，
AGSL 着色器 `eval` 到的 `input` 拿到的已经是模糊后的背景。
模糊只给 6dp：折射位移（15dp）远大于模糊半径，糊太狠位移就被抹平，看起来只是块磨砂板。

### 5.4 AGSL 透镜逐段说明（`LiquidGlass.LENS_AGSL`）

参数（`ReaderChrome` 传进来的）：`radius 18dp`、`bevel 22dp`、`refract 15dp`、
`blur 6dp`、`tintAmount 0.26`、`falloff 1.8`、`dispersion 0.06`、`rimSoft 3dp`。

1. **SDF**：`sdRoundedBox(p, halfSize, radius)`，`p` 是相对面板中心的坐标；
2. **覆盖率**：`cov = clamp(0.5 - d/1.5, 0, 1)`，1.5px 羽化，圆角外直接返回透明
   —— 所以这一档不需要额外裁剪；
3. **法线**：对 SDF 做中心差分（±1px）求梯度，归一化成屏幕空间外法线 `n`；
4. **厚度剖面**：`t = clamp(-d / bevel, 0, 1)`（1 = 面板内部，0 = 贴边），
   `slope = ((1+4t)^-falloff - 5^-falloff) / (1 - 5^-falloff)` —— 逆幂衰减，
   越贴边斜率越大（折得越狠）；
5. **折射**：采样点沿法线**往面板内侧**移 `slope * refractPx`
   —— 边缘于是出现内侧背景的压缩镜像，这是"厚玻璃"观感的来源；
6. **色散**：RGB 三个通道的位移量差 `±dispersion * slope`，边上一圈彩边；
7. **`rimSoft` 抹匀**：沿法线取三点平均，压缩带从一条硬边变成一段渐变（那点"晕染"）；
8. **自适应染色**：算背景亮度 `lum`，`e = smoothstep(0.35, 0.75, lum)`，
   `col = mix(col, 1-e, tintAmount * (0.70 + 0.40e))` —— 背景亮就压暗、暗就提亮，
   面板上的图标文字在任何背景上都读得清，又不用整体糊一层厚底；
9. **光照**：同一套法线驱动，`facing = dot(n, -lightDir)`，迎光侧 `pow(max(facing,0), 4.5)`、
   背光侧 `pow(max(-facing,0), 4.5)`（内壁反射）；贴边 1px 内一道**细高光**加迎光侧的
   柔和辉光，**没有**与方向无关的常亮描边 —— 有的话看起来就像"描了个边"。

> 参考了 GitHub 上的 `QWEA0/Liquid-Glass-Android`（同样是 View 体系里做液态玻璃的项目）
> 的光学模型与 AGSL 写法，按本项目规模砍成单输入、无触摸凸起、无形状合并。

### 5.5 跟着重力走的光源

只在**面板开着**的时候注册传感器（`startLight` / `stopLight`，收起就注销，别一直占着）：

- `TYPE_GRAVITY`，取不到退回加速度计；
- 按当前屏幕方向把重力重映射到屏幕坐标系（x 向右、y 向上）；
- 屏幕平面内的重力分量 `planar`，`tilt = clamp(planar / GRAVITY_EARTH, 0, 1)`；
  `planar > 0.5` 时用重力方向，"平放"时渐变回默认方向（左上 0.866/0.5），
  躺着用的时候高光不会乱抖；
- 低通滤波（系数 0.14）+ 变化阈值 0.006 才重绘 —— 传感器噪声直接喂给高光会闪。

---

## 6. 活的背景（v1.5）：让玻璃跟着正文走

### 6.1 问题

面板压住的那片内容**在屏幕上永远看不到** —— 截图里那块地方拍到的是面板自己。
所以"每隔几帧重新截一张"是行不通的。而且 `PixelCopy` 一张就是几 MB，
按帧率截图会把 GPU 拖垮。

### 6.2 方案：一条比面板大一圈的"条子"

`GlassBackdrop` 存的不是"面板那一片"，而是一条**条子**（band）：

```
条子 = 面板矩形 + 左右各 26dp（slack）+ 上下各 150dp（margin），夹在正文区内
```

流程：

1. **面板拉开前拍第一张**：那一刻面板整个在屏幕外，条子是干净的；
2. 之后网页每报一次滚动，**只改一个 Matrix** 把条子整体平移（`LiquidGlass.slide`）
   —— 一帧的代价就是一次矩阵赋值；
3. 条子会被平移出界，所以趁还有余量就提前拍下一张。新条子里"面板压住的那几行"
   从**上一张条子**按内容位移平移过来补齐（`splice`）—— 现在被压住的内容，
   上一张里正好露在条子的别处；
4. 相邻两张条子之间"内容走了多少像素"用**一维相关**量出来
   （只取面板压不到的那些行：两张里都是干净正文）。量出来的位移还能**校准网页报来的单位**。

### 6.3 符号约定（改代码前务必看这一节）

全部统一成一句话：**"内容位移"是正数表示内容往上 / 往左走了**（也就是用户往下 / 往右滚）。

| 量 | 含义 | 代码 |
| --- | --- | --- |
| `tx, ty` | 网页报的**累积**位移（正 = 内容往上/往左） | `SCROLL_HOOK_JS` 的 `ty += last.y - py` |
| `jsNowY` / `jsAnchorY` | 网页当前累积量 / 当前这张条子对应的累积量 | — |
| `currentDeltaY()` | 条子相对"拍下这一张那会儿"平移了多少像素 | `(jsNowY - jsAnchorY) * jsScale` |
| `matrixFor` | 条子该画在哪（面板局部坐标） | `glassOffset(bandRect.left, bandRect.top, boxLeft+panelLeft, boxTop+panelTop, dx, dy)`，两个方向都**减** |
| `splice` 取行 | 新条子第 `panelTop` 行 ← 旧条子第 `panelTop + delta` 行 | `spliceSource(top, rows, delta, bandH)`，夹到 `[0, bandH - rows]` |

为什么报**累积量**而不是绝对坐标：页面里的"参照物"会换 —— 先滚文档、再滚某个内层容器、
迷你缩放平移时 `clientY` 又不动。累积量在两个参照物之间也能接上（换参照物的那一帧只是不累加）。
同理，`jsAnchorY` 记的是"这张条子是哪一刻的"，不是坐标。

### 6.4 相关匹配（`BackdropMath.bestShift`）

输入：每列一条竖直亮度曲线（同一个 x、整条高度）的 `prevLum` / `nextLum`，
以及只取"面板压不到的连续行"的 `rows`。

- 搜索范围 `d ∈ [max(guess - window, -(h-1)), min(guess + window, h-1)]`，
  `window = max(24, 2|estimate| + 32)`（网页报的单位一开始可能是错的，窗口宽一点才不至于
  把真正的位置挡在外面）；
- 代价函数是 SAD（`sum |prev[y+d] - next[y]|`），只统计两张都在界内的行；
- 有效样本不足（`< max(8, rows.size * 3/4)`）的位置直接跳过；
- **判定要足够自信**，三条防线缺一不可：
  1. 最优要明显好过**次优**（`sad[best] * 5 >= runner * 3` 就认输）—— 一样好说明
     怎么挪都差不多；
  2. 采样行上的**亮度跨度**要 ≥ 24（`MIN_CONTRAST`）—— 整段一个色的时候任何位移都"对得上"；
  3. 采样行必须真的取到（面板把正文区占满时 `sampleRows` 返回 null）。
- 任何一条不满足 → **返回 `null`，不是返回一个勉强的值**。

返回 `null` 时调用方的动作很关键：**把基准硬对齐到当前这一张条子**
（`jsAnchorY = jsNowY`，等效于 `deltaY = 0`），也就是"这张条子就按它拍下来的位置贴"。
宁可丢掉这一段没量到的位移，也不能把条子摆错地方 —— 摆错了会一路错到下一次成功匹配。

### 6.5 单位校准（`jsScale`）

网页报的数未必就是设备像素（页面缩放、`devicePixelRatio`、迷你缩放都会差倍数）。
每次成功匹配到 `delta` 像素、而网页那边报了 `reported` 单位，就更新一个比例：

```
k = delta / reported                       // 只在 |delta|>12px 且 |reported|>6 时才学
jsScale = clamp(jsScale * 0.6 + k * 0.4, 0.02, 20)
```

然后 `jsAnchorY += measured / jsScale`（用**量出来的**位移往前推锚点，
这样两张条子之间预测的位移和实际一致）。
另外 JS 那边报的是 `(rect - visualViewport.offset) * devicePixelRatio * visualViewport.scale`，
所以正常情况下 `jsScale ≈ 1`，校准只做小幅修正。

### 6.6 什么时候重拍

`needsRecapture(deltaY, deltaX)`（每次网页报位置都会问一次）：

- 内容往上/往左走 → 补行要从条子的**下方/右方**取，那边剩下多少就是还能挪多远；
  挪到余量的 **62%**（`RECAPTURE_AT`）就重拍。反方向同理看 `panelInBand.top/left`；
- 两张截图至少隔 `MIN_INTERVAL_MS = 64ms`；
- 网页**不再报位置**（钩子没装上）时，按时间兜底：`JS_STALE_MS = 600ms` 没收到消息、
  且距上次拍照 > `STALE_RECAPTURE_MS = 320ms`，就拍；
- 网页一直不动也隔 `HEARTBEAT_MS = 900ms` 拍一张（动画、懒加载能跟上）。
  这一条也保证了"错过的位移"最多存在 900ms。

重拍时**面板要躲开两帧**（`hidePanel(true)` → `PixelCopy` → 恢复），否则拍到的就是面板自己。
代价是偶尔能看到玻璃闪一下（见 §9）。

### 6.7 网页侧：`SCROLL_HOOK_JS`

`onPageFinished` 时注入（和 `TAP_HOOK_JS` 一样是幂等的）：

- 监听 `scroll`（**捕获阶段**，`e.target` 是元素就把它记成参照元素；
  是 document/documentElement/body 就置空）、`visualViewport` 的 `scroll`/`resize`、
  `window` 的 `resize`；
- 所有事件只置一个 `requestAnimationFrame` 标记（`kick`），一帧最多算一次；
- `tick()` 里算参照元素的位置 = `(getBoundingClientRect() - visualViewport.offset) *
  devicePixelRatio * visualViewport.scale`，与上次比较后**累加**到 `tx/ty`，
  再 `hsChrome.moved(tx, ty)`；
- 参照物换了（`d !== el0`）就重设 `last` 但不累加 —— 换参照物那一帧的位移算不出来，丢掉它比算错好；
- `window.__hsTrack(enable)`：开关追踪，**开的时候把 `tx/ty/last/el0` 全部归零**。
  Kotlin 侧在 `start()` / `reset()` 里同时把自己的基准归零，两边的零点才对得上；
- 打开 / 关闭由 `ReaderChrome.onLiveChanged` → `ViewerActivity.trackScroll(on)` 驱动，
  只有面板开着才让网页干活。

### 6.8 线程、所有权与生命周期

- **两张位图来回用**：`band`（当前显示）和 `spare`（在拍的 / 刚才那张）。
  `PixelCopy` 往 `spare` 里写、`splice` 从 `band` 读并写进 `spare`，
  回调到主线程后才把两张对调（`band = target; spare = previous`）。
  **没有任何一处 `recycle()`** —— 位图可能在 `ImageView` 的 drawable 里还被引用着，
  回收的收益远小于踩空的风险，交给 GC。
- **`generation`**：`stop` / `reseed` / `reset` 都会 `generation++`；拍照回调、
  worker 结果、`ui.post` 三处都校验 `gen == generation && alive`，过期的一律丢弃。
- **`reseedWanted` 闩锁**：重拍请求如果撞上"已经有一张在飞"，置这个标记而不是并发再发一张
  —— 两张 `PixelCopy` 同时往一块位图上写会出花屏。
- **`hideForCapture`**：拍照期间面板不可见，回调（包括失败分支）里一定会恢复；
  `suppressed`（视频全屏）时不改可见性。
- 句柄：`start()`（拉开面板时）、`stop()`（收起 / 视频全屏时，同时清掉 `band`/`spare`）、
  `reseed()`（几何变了、内容变了）、`reset()`（换页 / 缩放变了，基准归零）。

### 6.9 各档的取舍

实时背景**只在 `LENS` 档开**（`ReaderChrome.openPanel` 里 `if (glass.lens) startLive()`）：
折射的向内采样正好把"条子里被面板遮挡那一小块"盖住；退化两档没有这一层，
面板底色又是满的，实时刷新根本看不出来，所以它们继续用 `captureBackdrop()` 的静态截图。

---

## 7. 测试

`app/src/test/` 下 62 个 JVM 测试（Robolectric + JUnit4），**不需要真机或模拟器**：

```
FileUtilTest            8   文件名清理、去重编号、MIME、整目录复制、大小统计
LibraryRepositoryTest  13   增删改查、重命名时迁移收藏与进度、搜索、排序、元数据落盘
ImportManagerTest       9   多文件导入、重名不覆盖、ZIP 解压、GBK 中文名、zip-slip 防护
ChromeGeometryTest      8   把手吸附、比例↔偏移、面板对齐与夹取
EdgeToEdgeTest          4   系统栏高度只加一次（反复显示/隐藏不会越堆越厚）
LibraryPathHandlerTest  4   MIME、目录穿越防护、中文/空格路径
UiSmokeTest             8   两个 Activity 能真正启动；把手默认位置、拖到下边后下次还在下边
BackdropMathTest        8   活的背景：位移量得准不准、该认输的时候认不认输、补行越界
```

```bash
./gradlew testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

**测试边界的自觉**：这台开发机没有 `/dev/kvm`，起不了模拟器，所以
`PixelCopy`、`RenderEffect`/AGSL、`SensorManager`、`WebView` 的真实渲染**都没有自动化覆盖**。
策略是把"最容易错、又纯计算"的部分抠出来单独成模块（`ChromeGeometry`、`BackdropMath`、
`FileUtil`）钉死在 JVM 上，其余部分靠"凡是可能失败的调用都包 `runCatching` + 优雅降级"来兜底。

`BackdropMathTest` 里那个 `mix()` 哈希噪声是有来历的：第一版夹具用 `(y*31) % 23` 做噪声，
和"每 7px 一条文字行"凑出了 161px 的重复匹配（`lcm(7,23)`），
`bestShift` **正确地**返回了 `null`。修的是夹具（换成无短周期的整数哈希），不是算法
—— 那种曲线本来就不该指望能量准。

---

## 8. 构建与发布

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/android-sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

- 国内网络下仓库已配好镜像（`settings.gradle` 里的阿里云三个仓库，
  `gradle/wrapper/gradle-wrapper.properties` 里的腾讯 Gradle 发行包，
  Robolectric 的 `android-all` 也指向阿里云，见 `app/build.gradle` 的 `testOptions`）；
- release 开了 R8（`minifyEnabled` + `shrinkResources`）；
- **签名**：根目录放一个 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` /
  `keyPassword`）就会用它签名，没有这个文件也能构建，只是产出未签名 APK。
  `keystore.properties`、`*.jks`、`*.keystore`、`local.properties` 都在 `.gitignore` 里，
  **不要提交、更不要发布**（已发布的 APK 用的是本地一份演示签名，见 README「签名」一节）。
- 发布流程（只发 APK）：`gh release create vX.Y --title … --notes-file … <改名后的 apk>`。
  `gh` 的 `assets#label` 只改展示名、不改文件名，所以**先把 APK 复制成目标文件名**再上传。
  上传后用 `gh api repos/<owner>/<repo>/releases/tags/vX.Y` 核对 asset 大小与本地一致。
- 版本号规则：每发一版 `versionCode + 1`、`versionName` 用 `x.y`。

---

## 9. 已知问题与未验证项

**没有真机验证**是这一节的大前提：开发机没有 `/dev/kvm`，起不了模拟器；
验证手段是"62 个 JVM 测试全绿 + release（R8）构建通过 + `aapt2`/`apksigner` 核对产物"。
下面凡是标"真机上"的，都请以实际运行为准。

### 未在真机上跑过的路径（按风险排序）

1. **AGSL 透镜着色器**（Android 13+）：`RuntimeShader` 编译失败会 `runCatching` 兜住、退回模糊，
   但编译**成功之后**的光学效果（折射、色散、自适应染色、双瓣高光）没人肉眼确认过。
   着色器里任何一处语法/类型问题都会在真机上表现成"只是模糊了"。改这个着色器请务必整段替换、
   别改一半。
2. **活的背景**（`GlassBackdrop`）：条子的坐标换算、补行、重拍节奏都只验证了纯计算部分。
   真机上可能出现：跟手速度不对（`jsScale` 学歪）、玻璃里出现接缝、重拍时闪一下。
   怀疑跟手不准时，先看 `jsScale` 的钳位范围与 `SCROLL_HOOK_JS` 报的单位。
3. **重力光源**：方向重映射（四种屏幕方向）与滤波系数只能实测调。
4. **`PixelCopy` 在实时路径上的开销**：每张条子约 `(面板宽+2×26dp) × (面板高+2×150dp)`
   的 ARGB_8888 位图（粗略 3 MB 量级），两张常驻。低端机上如果发热/掉帧，
   先把 `MARGIN_DP` 降下来；`MIN_INTERVAL_MS` 是第二道闸。

### 已知缺陷 / 取舍

- **重拍时面板要躲开两帧**：`hidePanel(true)` → `PixelCopy` → 恢复，所以
  拉开面板后的第一次重拍、以及换页 / 缩放 / 转屏 / 拖到另一条边之后的重拍，
  可能看到玻璃**闪一下**。想消掉就得让面板别进入截图（`PopupWindow` 或独立 Surface），
  那是另一套结构，收益不值当。
- **面板压住的那几行来自上一张条子**（最坏 900ms 的陈旧度）：正文里有正在播的视频/动画时，
  玻璃里那一块会慢半拍；纯文字页面看不出来。
- **位移只精确到 1px**：亚像素滚动时，补行的交界处可能有极细的接缝。
- **面板大到几乎占满正文区时**，条子里没有"面板压不到的行"，匹配永远拿不到结果，
  只能退回网页报的位置：玻璃不会错位（每次重拍都硬对齐），但滚动过程中预测的位移会滞后。
- **用 `getLocationOnScreen` 而不是 `getLocationInWindow`** 算窗口坐标：
  全屏时两者相同，**分屏 / 自由窗口模式下条子的截图位置会偏**。
- **把手背后没有真的玻璃**：把手是一直显示着的，按它的位置截图会把把手自己拍进去
  （然后越叠越深），所以它只是一枚调过透明度的玻璃色药丸，折射只做在临时拉开的面板上。
- **阅读进度按滚动比例记**：动态加载内容很长的页面，恢复位置不够准。
- **强制深色依赖系统 WebView 版本**（`WebSettingsCompat.setForceDark` 在旧版上无效，会 Toast 提示）。
- **部分课件引用在线 CDN 或需要联网的接口**，离线打开会缺样式 —— 那是课件本身的问题，
  应用只能在有网时正常加载。
- **`useWideViewPort` + `loadWithOverviewMode`** 对某些自适应课件会让初始缩放偏小，
  用户得自己捏合；「桌面版」那一格是给宽表格课件的补偿手段。

### 下一步值得做的

- 把手也做真玻璃：把外壳挪到一个独立的 `SurfaceView`/`PopupWindow` 里，就能连把手一起截；
- 条子的**纵向匹配**加一点亚像素插值，消掉补行接缝；
- 重拍改为"面板平移出截图范围"（面板滑到条子外面两帧）而不是 `INVISIBLE`，视觉上更稳；
- 把 `jsScale` 的初值用 `devicePixelRatio × pageScale` 预置，减少开头几帧的滞后。

---

## 10. 改这个项目时的检查清单

- **像素 API 只有数组在前的重载**：
  `getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height)`
  / `setPixels(…)` 同理，别按 `x, y, w, h, int[]` 的顺序写。
- **位图所有权**：`GlassBackdrop` 的两张位图由引擎独占并在主线程对调；
  `ReaderChrome` 只读、不回收。给它们加 `recycle()` 之前先想清楚 `ImageView` 还引用着谁。
- **坐标系**：碰 `panelX/panelY`（正文区）与 `bandRect`（窗口）之间的换算，
  记得 `content_area` 的 padding 和 `getLocationOnScreen`；`matrixFor` 是**面板局部**坐标。
- **符号**：`deltaY/deltaX` 的正方向是"内容往上/往左"（= 往下/往右滚）。
  想加新的一维位移逻辑，先回读 §6.3。
- **幂等注入**：`TAP_HOOK_JS` / `SCROLL_HOOK_JS` 都有 `if (window.__hsXxxHook) return;` 守卫，
  新加注入脚本请照做 —— `onPageFinished` 会在站内跳转时反复触发。
- **JS 桥**：`@JavascriptInterface` 的参数用 `Double`/`String` 这类确定支持的型别
  （`moved` 就是 `Double` 进来再转 `Float`），回调里**一律先 `ui.post`**。
- **`EdgeToEdge` 的 padding**：任何"反复开关"的界面都别直接读写 `view.paddingTop`，
  走 `originalPadding` 缓存那套。
- **面板高度是写死的**：`tool_panel` 的 `layout_height` 由 `ReaderChrome` 算好后写进去，
  因为子视图都是 `match_parent`，`wrap_content` 的父容器会被撑开。加/减按钮（`buildCells`）
  之后不用改别的地方，`relayout` 会重算行高。
- **没有协程**：保持 `Executors` + `Handler` 的风格，别引新运行时（APK 才 1.9 MB）。
