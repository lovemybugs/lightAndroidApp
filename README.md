# 学习库 · HTMLStudy

一个**轻量的个人离线学习资料库**：把手机上零散的 HTML 自学课件（各科目、各来源）集中收进一个 APP，
按科目分目录管理，随时点开就能读——不需要网络，不需要电脑。

- 纯本地应用，只有一个 `INTERNET` 权限（用于打开课件里的外部链接），不收集任何数据
- 内置 HTML 阅读器（基于系统 WebView，没有塞任何第三方浏览器内核）
- release APK 约 1.9 MB

> 想改代码 / 想知道那些效果是怎么实现的，看 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**：
> 分层与线程模型、虚拟站点、外壳几何、AGSL 透镜逐段说明、活的背景的算法与符号约定、
> 测试边界、已知问题与未验证项，都在那里。

---

## 一、功能

### 管理

| 能力 | 说明 |
| --- | --- |
| 新建目录 | 任意层级，重名自动加序号（`高等数学 (2)`），不会覆盖 |
| 导入 HTML 文件 | 一次可选多个，选择器只列 HTML（`.html/.htm/.xhtml/.mht/.mhtml`），重名自动改名保留两份 |
| 导入整个文件夹 | 从手机里选一个文件夹，连同里面的图片/CSS/JS 一起搬进来 |
| 导入 ZIP 压缩包 | 支持 GBK 中文文件名；自动去掉多余的顶层目录 |
| 重命名 / 移动 / 删除 | 目录级操作会连带处理子项和它们的阅读记录 |
| 收藏 / 最近打开 / 搜索 | 搜索同时匹配文件名和自定义标题 |
| 显示非 HTML 文件 | 默认只列文档；打开开关可以看到图片、PDF 等素材 |
| 排序 | 按名称 / 修改时间 / 加入时间 |
| 主题 | 跟随系统 / 浅色 / 深色 |

导入文件夹时选「**作为一个子目录导入**」最省心：`我的课件/` 进来后还是 `我的课件/`；
选「直接展开到当前目录」则把它里面的内容平铺到当前位置。

### 阅读

- 首页点任意文档进入阅读器；屏幕边上那条**小把手**点一下就能拉出工具栏面板
- **把手能拖到任意一条边**：从左边拖到右边、从右边拖到下边……松手就吸附到最近的那条边，
  贴在哪儿下次打开还在哪儿（记的是"哪条边 + 这条边上的比例"，所以转屏后也在同一段位置）；
  换了边之后**把手的方向、圆角朝向和箭头**都会跟着转过来 —— 左右两条边都是竖把手，
  但圆角朝的是屏幕里侧，所以"左 → 右"也得翻过来
- **点一下正文可以让把手滑出去**（阅读时彻底消失），再点一下又滑回来。
  只有点到**空白处**才算：戳"＋"、"显示答案"、选项这类能点的东西不会把把手收走
- **正文是满屏的**：阅读器没有顶栏，文档一直画到屏幕最顶端（含刘海/挖孔区），
  系统设置里给本应用开启「显示顶部区域」也不会在正文上方留白
- 拉出来的面板是条**竖向细长**的玻璃片，宽度只够并排两个按钮，共十格：
  目录 / 收藏 / 字号 / 深色 / 桌面版 / 全屏 / 刷新 / 分享 / 其它应用 / 收起
- 面板和把手是**液态玻璃**：贴边的斜面上能看到被折弯的正文，边沿一圈彩色细线，
  玻璃上的高光会**跟着手机倾斜走**（迎光侧有高光、背光侧有一道对称的内壁反射）
- 玻璃背后是**活的**：面板开着的时候滑动正文，玻璃里的背景跟着一起滚，
  折射的内容实时变化，而不是停在拉开那一刻
- 相对路径引用的 CSS、JS、图片、字体、`fetch` 都能正常工作
- 自动记住每篇文档的**阅读进度**，下次从原处继续
- 正文字号调节、桌面版排版、强制深色、全屏沉浸模式
- 文档里的外链自动交给系统浏览器；`mailto:` / `tel:` 也一样
- 从别的应用（文件管理器、聊天软件）用「**打开方式**」选中本应用也能直接读，
  文件会先被收进库里的 `导入/` 目录

---

## 二、资料在手机的什么位置

```
Android/data/com.htmlstudy.library/files/library/
```

内部存储根目录下的这个路径（不是 SD 卡，所以**不需要任何存储权限**）。
用数据线连电脑后，直接用文件管理器打开这个文件夹整理即可——新建/移动/删除文件，
下次打开 APP 会看到结果，无需导入。

> 卸载 APP 会连同这个文件夹一起删除，请先用电脑备份。

库里的隐藏文件 `.library.json` 保存了收藏、阅读进度、自定义标题和加入时间。
它跟着文件夹走，所以整体拷到新手机上，记录也一起带过去。

---

## 三、自己编译

需要 JDK 17 + Android SDK（platform 34、build-tools 34）。

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/android-sdk          # 换成你的 SDK 路径
./gradlew assembleDebug                        # 调试包
./gradlew assembleRelease                      # 正式包
./gradlew testDebugUnitTest                    # 跑单元测试
```

产物在 `app/build/outputs/apk/`。

国内网络下项目已经配好了镜像，不用另外改：

- Maven → `maven.aliyun.com/repository/{google,public,gradle-plugin}`（见 `settings.gradle`）
- Gradle 发行包 → `mirrors.cloud.tencent.com/gradle/`（见 `gradle/wrapper/gradle-wrapper.properties`）
- Robolectric 的 android-all 运行时 → 阿里云 public 仓库（见 `app/build.gradle` 的 `testOptions`）

如果 SDK 缺失，`sdkmanager` 走不通 `dl.google.com` 时可以直接从
`mirrors.cloud.tencent.com/AndroidSDK/` 下 zip 解压进 `$ANDROID_HOME`，再补一个
`licenses/android-sdk-license` 文件即可。

### 签名

在项目根目录放一个 `keystore.properties`，`assembleRelease` 就会用它签名：

```properties
storeFile=my.jks
storePassword=……
keyAlias=……
keyPassword=……
```

**本仓库不含任何签名文件** —— `keystore.properties`、`*.jks`、`*.keystore`、
`local.properties` 都在 `.gitignore` 里。自己生成一个：

```bash
keytool -genkeypair -v -keystore my.jks -alias mysign -keyalg RSA -keysize 2048 -validity 10000
```

删掉 `keystore.properties` 也不会导致构建失败，release 只会产出一个未签名的 APK。

> 已发布的那些 APK 用的是本地一份演示签名（同一套密钥从头用到尾），所以升级安装可以
> 直接覆盖、书架和阅读进度都在。换成自己的密钥重签之后，旧版本就无法覆盖安装了，
> 得先卸载。

---

## 四、测试

`app/src/test/` 下是 62 个 JVM 单元测试（Robolectric，不需要真机或模拟器）：

```
FileUtilTest            8   文件名清理、去重编号、MIME、整目录复制、大小统计
LibraryRepositoryTest  13   增删改查、重命名时迁移收藏与进度、搜索、排序、元数据落盘
ImportManagerTest       9   多文件导入、重名不覆盖、ZIP 解压、GBK 中文名、zip-slip 防护
ChromeGeometryTest      8   把手吸附到最近的边、比例↔偏移、面板对齐与夹取
EdgeToEdgeTest          4   全面屏内边距只加一次（反复显示/隐藏工具栏不会越堆越厚）
LibraryPathHandlerTest  4   虚拟站点的 MIME 与目录穿越防护、中文/空格路径
UiSmokeTest             8   主页/阅读器能真正启动；把手默认位置、拖到下边后下次还在下边
BackdropMathTest        8   活的背景：位移量得准不准、什么时候该认输、补行会不会越界
```

```bash
./gradlew testDebugUnitTest
# 报告：app/build/reports/tests/testDebugUnitTest/index.html
```

---

## 五、实现要点

**为什么不用 `file://` 打开？** 直接 `file://` 加载课件有三个坑：跨域导致 `fetch`/XHR 失败、
`localStorage` 同源身份不稳定（很多自带测验/笔记的课件靠它存进度）、MIME 类型没法保证。
所以这里用 `WebViewAssetLoader` + 自定义 `PathHandler`，把库目录映射成一个虚拟站点
`https://study.local/lib/...`（见 `web/LibraryPathHandler.kt`），相对路径、跨域、存储、MIME 一次全部解决。

**老课件的 GBK 编码。** 中文课件大量是 GBK 编码，而 WebView 默认按 UTF-8 显示会乱码。
`WebResourceResponse` 的 `encoding` 传 `null`，让 WebView 自己去读页面里的 `<meta charset>`
（见 `LibraryPathHandler.handle`）。ZIP 里的中文文件名同理，用 GBK 打开压缩包，
带 UTF-8 标志位的条目 `ZipFile` 会自动按 UTF-8 解析。

**ZIP 安全。** 解压前用 `canonicalPath` 校验每个条目必须落在目标目录内（zip-slip 防护），
跳过 `__MACOSX` 和隐藏文件；解完只保留一层目录，避免 `物理课件/course/index.html` 这种套娃。

**为什么阅读器没有顶栏。** 顶部工具栏那条既要垫状态栏高度、又要跟着"显示 / 隐藏"
来回改内边距，很容易变成每次显示都再叠一层系统栏高度 —— 于是唤出工具栏后回不到全屏、
白边一条比一条厚。现在正文满屏，工具栏改成浮在屏幕边缘的小把手 + 它拉出来的面板：
两者都浮在 WebView 上层、只做平移，不占布局也不碰任何内边距，
白边从结构上就不可能再出现（`EdgeToEdgeTest` 盯着这一点）。
把手的位置换算全是纯算术，单独放在 `ui/ChromeGeometry.kt` 里，
所以吸附、比例换算这些都能老老实实写单元测试（`ChromeGeometryTest`）。

**点一下空白才收把手。** 网页里的"＋"、"显示答案"这些能点的东西大多是 `div`/`span` 挂监听，
`WebView.hitTestResult` 只认 `ANCHOR`/`EDIT_TEXT`/`IMAGE` 这几种，它们全都报 `UNKNOWN_TYPE`，
跟点空白一模一样 —— 光靠它区分，戳按钮就会把把手收走。所以判断放到**网页里**做：
`onPageFinished` 时注入一段钩子（`ViewerActivity.TAP_HOOK_JS`），
`touchstart` 记下起点、往上遍历找可点元素（`A/BUTTON/INPUT/…`、`contenteditable`、
`role=button|link|…`、`onclick`/`data-toggle`、常见的 `btn|quiz|answer|toggle` 类名、
`cursor:pointer`），`touchend` 时没移动过、没超时、又不在可点元素上，才算"点了空白"。

结果不写标志位让 Kotlin 回来问 —— 网页在另一个进程里，抬手那一瞬间它还没处理完这次触摸，
问到的是**上一次**的结果（点按钮照样误触发）。改成钩子通过 `addJavascriptInterface`
直接回调 Kotlin（`TapBridge`，暴露给课件页的只有"切一下把手"这一个方法）。
只有钩子没装上时，才退回到"延迟 160ms 再问 + `hitTestResult`"的老路。

**液态玻璃是怎么做的。** Android 没有"模糊身后内容"的公开 API
（`Window.setBackgroundBlurRadius` 模糊的是窗口**背后**的桌面/上一个应用，
`View.setRenderEffect` 模糊的是**自己**的内容），所以这里走截图这条路：
面板拉开前先 `PixelCopy` 把面板**将要占住**的那块窗口像素拷出来（那一刻面板还在屏幕外，
拍到的是干净的正文），贴到面板背后，再做 GPU 效果。`ui/LiquidGlass.kt` 里按系统版本分三档：

| 系统 | 做法 |
| --- | --- |
| Android 13+ | 截图 → 高斯模糊 → AGSL（`RuntimeShader`）单 pass 透镜 |
| Android 12 | 截图 → 高斯模糊 |
| Android 7~11 | 截图缩到 1/6 再放大（双线性插值就是模糊），没有 GPU 效果 |

透镜那一档的光学模型参考了 GitHub 上的 `QWEA0/Liquid-Glass-Android`
（同样是 View 体系里做液态玻璃的项目），按本项目的规模砍成单输入、无触摸凸起、无形状合并：

1. 圆角矩形的 SDF → 中心差分求梯度 → 屏幕空间法线；
2. 厚度剖面用逆幂衰减（越贴边折得越狠）算"斜面斜率"，采样点沿法线往面板内侧移，
   于是边缘出现内侧背景的压缩镜像 —— 这就是透明厚玻璃的观感来源；
3. 红绿蓝三个通道的位移量略有差别 → 玻璃边上一圈彩边（色散）；
4. 沿法线再三下抹匀（`rimSoft`）：压缩带从一条硬边变成一段渐变，就是那点"晕染"；
5. 同一套法线驱动光照：迎光侧、背光侧各一道对称的窄光瓣（背光那道是透明介质的内壁反射），
   加上贴边 1px 内的细高光，**没有**跟方向无关的常亮描边 —— 否则看起来就像"描了个边"；
6. 逐像素自适应染色：背景亮就压暗、背景暗就提亮，面板上的图标文字在任何背景上都读得清，
   又不用整体加一层厚底把背景糊死。透镜这一档因此把固定底色压到 28%，
   比过去通透得多（退化的两档没有逐像素染色，底色仍是满的）。

光源方向由**重力传感器**驱动（屏幕方向重映射 + 低通滤波，`TYPE_GRAVITY` 取不到就用加速度计），
只在面板拉开时注册、收起就注销。模糊只留 6dp：折射位移（15dp）远大于模糊半径，
糊太狠位移就被抹平，看起来只是块磨砂板。

把手和面板一样是"一边直角、一边圆角"：面板向屏幕外多探出一个圆角的距离，
探出去的那半个圆角被窗口裁掉，玻璃片就像从屏幕边上长出来的；收起来时则停在屏幕外沿。

三档都是"哪一步失败就退回半透明底色"（`RuntimeShader` 编不过、`PixelCopy` 失败、
`setRenderEffect` 不被支持），任何一环出问题都不会糊掉正文。

**玻璃背后的背景怎么"活"起来。** 难点在于：面板压住的那片内容在屏幕上永远看不到 ——
截图里那块地方拍到的是面板自己。所以这里存的不是"面板那片"，而是一条比面板**大一圈**的
"条子"（左右各多 26dp、上下各多 150dp，`GlassBackdrop`）：

1. 面板拉开前先拍第一张：那一刻面板整个还在屏幕外，条子是干净的；
2. 之后网页每报一次滚动，**只改一个 Matrix** 把条子整体平移 —— 一帧的代价就是一次矩阵
   赋值，正文滚多快玻璃就跟多快（`LiquidGlass.slide`，不重建 `RenderEffect`）；
3. 条子会被平移出界，所以趁还没挪出去就提前拍下一张：新条子里"面板压住的那几行"从上一张
   条子按内容位移平移过来补齐（`GlassBackdrop.splice`）—— 现在被压住的内容，
   上一张里正好露在条子的别处；
4. 相邻两张条子之间"内容到底走了多少像素"用**一维相关**量出来：取面板压不到的那些行
   （两张里都是干净正文），每列一条竖直亮度曲线做 SAD 匹配（`ui/BackdropMath.kt`，
   纯算术所以能在 JVM 上测）。量出来的位移还能顺手**校准网页报来的单位**
   —— 网页那边有延迟，报的也未必就是像素。

网页那边由一段注入的钩子（`ViewerActivity.SCROLL_HOOK_JS`）报位置：`scroll`（捕获阶段，
认出内层滚动容器）/ `visualViewport` 的滚动与缩放 / `window` 的 resize 都挂上，
`requestAnimationFrame` 节流后算"参照元素"的位置变化，累加成**总位移**而不是绝对坐标
（页面里换了参照物、迷你缩放时都不会跳），经 `addJavascriptInterface` 的
`hsChrome.moved(x, y)` 回调 Kotlin。`__hsTrack(true)` 把两边基准一起归零。

拿不定主意的地方一律**认输**：纯色背景、周期性花纹（怎么挪都"对得上"）、采样行跑出条子，
`bestShift` 都返回 `null`，调用方退回网页报来的位置，并把基准硬性对齐到当前这一张条子
（宁可丢掉这一段没量到的位移，也不能把条子摆错地方）。网页钩子根本没装上时，
退化成"按时间重拍 + 每次硬对齐"：玻璃不会错位，只是跟着滚动走的时候是一格一格的。
另外侧栏一开始滑动就把面板收起来 —— 正文区整块被侧栏推着走，窗口坐标当场失效。

只有**透镜那一档**开这个实时背景（折射的向内采样正好把面板自己那一条遮住，
退化两档的底色是满的，实时刷新也看不出来）。

**没有协程。** 全项目只用 `Executors.newSingleThreadExecutor()` + `Handler(Looper.getMainLooper())`，
不引入协程运行时，保持体积轻。

### 目录结构

```
app/src/main/java/com/htmlstudy/library/
├── App.kt                    应用级单例（库、偏好设置）
├── data/
│   ├── LibraryRepository.kt  库的增删改查、排序、搜索
│   ├── ImportManager.kt      多文件 / 文件夹 / ZIP 导入
│   ├── MetaStore.kt          .library.json 的读写与重命名迁移
│   ├── LibraryItem.kt        列表项模型
│   └── Prefs.kt              偏好设置与库路径常量
├── web/LibraryPathHandler.kt 虚拟站点（https://study.local/lib/...）
├── util/FileUtil.kt          纯文件工具，无 Android 依赖
└── ui/
    ├── MainActivity.kt       浏览 / 搜索 / 收藏 / 最近
    ├── ViewerActivity.kt     阅读器 + 科目抽屉，把外壳的动作接上业务
    ├── ReaderChrome.kt       边缘小把手 + 液态玻璃面板（吸附、开合）
    ├── GlassBackdrop.kt      活的背景：条子的截图 / 平移 / 补行、按需重拍
    ├── BackdropMath.kt       活的背景里的纯计算（位移匹配、补行、贴合位置）
    ├── LiquidGlass.kt        液态玻璃的 GPU 部分（AGSL 透镜 / 模糊 / 缩放三档）
    ├── ChromeGeometry.kt     把手 / 面板的位置换算（纯算术，好测）
    ├── EdgeToEdge.kt         全面屏 / 刘海适配（系统栏高度只加一次）
    ├── ItemAdapter.kt        列表
    ├── DrawerTreeAdapter.kt  可展开的科目树
    ├── Dialogs.kt            输入框、确认框、动作面板
    └── ProgressDialog.kt     导入进度
```

---

## 六、已知限制

- **没有在真机/模拟器上跑过**：开发机没有 `/dev/kvm`，起不了模拟器。
  验证方式是 release（R8 混淆 + 资源压缩）构建通过 + 62 个 JVM/Robolectric 测试全绿
  （含两个 Activity 的启动冒烟测试）。真机上的界面细节请以实际运行为准。
- 液态玻璃里"截图 + GPU 模糊/折射"这一段**只能在真机上才看得到效果**，
  Robolectric 跑不到 `PixelCopy` / `RenderEffect`，所以这一段是按公开 API 写的、
  没有被自动化测试覆盖；三档效果都做了失败回退，最差也只是退回半透明底色。
- **把手背后没有真的玻璃**：把手是一直显示着的，按它的位置去截图会把把手自己拍进去
  （然后越叠越深），所以把手只能是一枚调过透明度的玻璃色药丸，
  折射只做在临时拉开的面板上。
- 折射效果、色散、跟着重力走的高光都只在 Android 13+ 且 `RuntimeShader` 编得过时才有。
- 折射那一层（Android 13+ 才有）的 AGSL 着色器同样没法在开发机上编译验证，
  已用 `runCatching` 兜住、编不过就只用模糊。
- 玻璃**跟着正文走**这一套（截图 + 补行 + 相关匹配）同样只有真机跑得到，
  开发机上只把最容易错的几步纯计算抽出来钉死了（`BackdropMathTest`）。
- 重拍下一张条子时面板要**躲开两帧**（不躲就会把面板自己拍进去），
  所以偶尔能看到玻璃闪一下；页面跳转、缩放、转屏、面板被拖到另一条边之后第一次重拍都会这样。
- 面板压住的那几行是从**上一张**条子平移补过来的，所以正文里如果有正在播的动画/视频，
  玻璃里那一块会慢半拍；纯文字页面看不出来。位移只精确到 1px，边缘偶尔有极细的接缝。
- 面板大到几乎占满正文区时，条子里没有"面板压不到的行"可用于匹配，
  只能退回网页报来的位置：玻璃仍然不会错位（每次重拍都把基准硬对齐），
  但滚动过程中预测的位移可能略有滞后。
- 部分课件用了 WebView 不支持的在线 CDN 资源或需要联网的接口，离线打开会缺样式。
- 强制深色依赖系统 WebView 版本（`WebSettingsCompat.setForceDark` 在旧版上无效）。
- 阅读进度按滚动比例记录，动态加载内容很长的页面恢复位置可能不够准。

更完整的清单（含每条的原因、风险排序和可以怎么改）在
[docs/ARCHITECTURE.md 第九节](docs/ARCHITECTURE.md#9-已知问题与未验证项)。
