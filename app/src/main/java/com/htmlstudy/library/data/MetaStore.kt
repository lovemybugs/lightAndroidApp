package com.htmlstudy.library.data

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 单个文档的附加信息。文件系统只存内容，这些“管理用”的字段存在 .library.json 里。
 */
class MetaEntry {
    var title: String? = null
    var addedAt: Long = 0L
    var lastOpenedAt: Long = 0L
    var favorite: Boolean = false
    var scroll: Float = 0f
    var openCount: Int = 0

    fun toJson(): JSONObject {
        val o = JSONObject()
        title?.let { o.put("title", it) }
        o.put("added", addedAt)
        o.put("opened", lastOpenedAt)
        o.put("fav", favorite)
        o.put("scroll", scroll.toDouble())
        o.put("count", openCount)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): MetaEntry = MetaEntry().apply {
            if (o.has("title")) title = o.optString("title").ifEmpty { null }
            addedAt = o.optLong("added", 0L)
            lastOpenedAt = o.optLong("opened", 0L)
            favorite = o.optBoolean("fav", false)
            scroll = o.optDouble("scroll", 0.0).toFloat()
            openCount = o.optInt("count", 0)
        }
    }
}

/**
 * 元数据索引。整个库的“册子”信息都在这里，读写都做缓存 + 原子落盘。
 */
class MetaStore(private val root: File) {

    private val metaFile = File(root, Library.META_FILE)
    private val entries = HashMap<String, MetaEntry>()
    private var dirty = false

    @Synchronized
    fun load() {
        entries.clear()
        dirty = false
        if (!metaFile.isFile) return
        runCatching {
            val text = metaFile.readText()
            if (text.isBlank()) return
            val rootJson = JSONObject(text)
            val files = rootJson.optJSONObject("files") ?: return
            val keys = files.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                entries[key] = MetaEntry.fromJson(files.optJSONObject(key) ?: JSONObject())
            }
        }.onFailure { Log.w(TAG, "读取索引失败: ${it.message}") }
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        runCatching {
            root.mkdirs()
            val files = JSONObject()
            for ((k, v) in entries) {
                if (!v.favorite && v.title == null && v.lastOpenedAt == 0L &&
                    v.addedAt == 0L && v.scroll == 0f && v.openCount == 0
                ) continue
                files.put(k, v.toJson())
            }
            val rootJson = JSONObject()
            rootJson.put("version", 1)
            rootJson.put("files", files)

            val tmp = File(root, "${Library.META_FILE}.tmp")
            tmp.writeText(rootJson.toString())
            if (!tmp.renameTo(metaFile)) {
                metaFile.writeText(rootJson.toString())
                tmp.delete()
            }
            dirty = false
        }.onFailure { Log.w(TAG, "写入索引失败: ${it.message}") }
    }

    @Synchronized
    fun get(rel: String): MetaEntry = entries.getOrPut(rel) { MetaEntry() }

    @Synchronized
    fun peek(rel: String): MetaEntry? = entries[rel]

    fun titleOf(rel: String, fallback: String): String =
        peek(rel)?.title?.takeIf { it.isNotBlank() } ?: fallback

    fun isFavorite(rel: String): Boolean = peek(rel)?.favorite == true

    @Synchronized
    fun markAdded(rel: String) {
        val e = get(rel)
        if (e.addedAt == 0L) e.addedAt = System.currentTimeMillis()
        dirty = true
    }

    @Synchronized
    fun markOpened(rel: String) {
        val e = get(rel)
        e.lastOpenedAt = System.currentTimeMillis()
        e.openCount += 1
        if (e.addedAt == 0L) e.addedAt = e.lastOpenedAt
        dirty = true
    }

    @Synchronized
    fun setTitle(rel: String, title: String?) {
        get(rel).title = title?.takeIf { it.isNotBlank() }
        dirty = true
    }

    @Synchronized
    fun setFavorite(rel: String, fav: Boolean) {
        get(rel).favorite = fav
        dirty = true
    }

    @Synchronized
    fun toggleFavorite(rel: String): Boolean {
        val e = get(rel)
        e.favorite = !e.favorite
        dirty = true
        return e.favorite
    }

    @Synchronized
    fun setScroll(rel: String, value: Float) {
        get(rel).scroll = value.coerceIn(0f, 1f)
        dirty = true
    }

    /** 重命名/移动之后，把路径相关的记录整体搬过去 */
    @Synchronized
    fun renamePath(oldRel: String, newRel: String) {
        if (oldRel == newRel) return
        val moved = HashMap<String, MetaEntry>()
        val prefix = "$oldRel/"
        val it = entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (k == oldRel) {
                moved[newRel] = v
                it.remove()
            } else if (k.startsWith(prefix)) {
                moved[newRel + k.substring(oldRel.length)] = v
                it.remove()
            }
        }
        entries.putAll(moved)
        dirty = true
    }

    /** 删除后清理记录（目录会连带清理其下所有子项） */
    @Synchronized
    fun removePath(rel: String) {
        val prefix = "$rel/"
        val it = entries.iterator()
        while (it.hasNext()) {
            val k = it.next().key
            if (k == rel || k.startsWith(prefix)) it.remove()
        }
        dirty = true
    }

    @Synchronized
    fun allOpened(): List<Pair<String, MetaEntry>> =
        entries.entries
            .filter { it.value.lastOpenedAt > 0L }
            .sortedByDescending { it.value.lastOpenedAt }
            .map { it.key to it.value }

    @Synchronized
    fun allFavorites(): List<String> =
        entries.entries.filter { it.value.favorite }.map { it.key }

    private companion object {
        const val TAG = "MetaStore"
    }
}
