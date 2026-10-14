# 学习库 ProGuard / R8 规则

# WebView 的 JS 接口（如果以后通过 @JavascriptInterface 暴露方法，靠这条保住）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ViewBinding 生成的类由 R8 自动处理，这里不需要额外规则。

# 保留资源里用到反射的类名（org.json 等不需要）。
-dontwarn org.jetbrains.annotations.**
