package com.htmlstudy.library.web

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.htmlstudy.library.util.FileUtil
import java.io.File
import java.io.FileInputStream

/**
 * 把库目录映射成 https://study.local/lib/... 这样一个"虚拟站点"。
 *
 * 相比直接用 file:// 打开有三个好处：
 *  1. 相对路径引用的 css/js/图片能正常加载，且没有 file:// 的跨域限制（fetch/XHR 可用）；
 *  2. localStorage / indexDB 有稳定的同源身份，很多自带测验、笔记的课件靠这个存进度；
 *  3. 响应头可控，能给出正确的 MIME 类型（比如 .woff2 字体、.wasm）。
 */
class LibraryPathHandler(private val root: File) : WebViewAssetLoader.PathHandler {

    private val rootPath: String = root.canonicalPath

    override fun handle(path: String): WebResourceResponse? {
        val raw = path.trimStart('/')
        if (raw.isEmpty()) return null

        val decoded = runCatching { Uri.decode(raw) }.getOrDefault(raw)
        val target = File(root, decoded)

        val canonical = runCatching { target.canonicalPath }.getOrNull() ?: return null
        // 目录穿越防护：解析后的真实路径必须仍在库目录内
        if (canonical != rootPath && !canonical.startsWith(rootPath + File.separator)) return null
        if (!target.isFile) return null

        return runCatching {
            val headers = HashMap<String, String>()
            headers["Cache-Control"] = "no-cache"
            headers["Access-Control-Allow-Origin"] = "*"
            // encoding 传 null：让 WebView 依据 <meta charset> 自行判断，
            // 这样 GBK 编码的老课件也能正常显示。
            WebResourceResponse(FileUtil.mimeOf(target.name), null, 200, "OK", headers, FileInputStream(target))
        }.getOrNull()
    }

    companion object {
        /** 虚拟站点域名与路径前缀 */
        const val DOMAIN = "study.local"
        const val PREFIX = "/lib/"

        fun urlFor(relPath: String): String {
            val encoded = relPath.split('/').joinToString("/") { Uri.encode(it) }
            return "https://$DOMAIN$PREFIX$encoded"
        }
    }
}
