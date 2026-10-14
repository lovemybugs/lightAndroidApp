package com.htmlstudy.library.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.htmlstudy.library.R
import com.htmlstudy.library.data.LibraryItem
import com.htmlstudy.library.data.LibraryRepository
import java.io.File

/**
 * 阅读器侧边栏的目录树。
 *
 * 这是"在一个 APP 里切换学科"的关键：任何一篇文档里，
 * 划一下就能展开别的科目、直接跳过去，不用退回列表。
 */
class DrawerTreeAdapter(
    private val repo: LibraryRepository,
    private val onDocClick: (LibraryItem) -> Unit
) : RecyclerView.Adapter<DrawerTreeAdapter.Row>() {

    class Node(val item: LibraryItem, val depth: Int, val expanded: Boolean, val hasChildren: Boolean)

    private val nodes = ArrayList<Node>()
    private val expandedPaths = HashSet<String>()

    var currentPath: String? = null
        set(value) {
            if (field != value) {
                field = value
                rebuild()
            }
        }

    fun rebuild() {
        nodes.clear()
        walk(repo.root, 0)
        notifyDataSetChanged()
    }

    private fun walk(dir: File, depth: Int) {
        val items = repo.list(dir, showAssets = false)
        for (item in items) {
            val expanded = expandedPaths.contains(item.relPath)
            val hasChildren = item.isFolder && hasAnyChild(item.file)
            nodes.add(Node(item, depth, expanded, hasChildren))
            if (item.isFolder && expanded) walk(item.file, depth + 1)
        }
    }

    private fun hasAnyChild(dir: File): Boolean =
        dir.listFiles()?.any { !it.name.startsWith(".") } == true

    /** 展开到指定文档所在的目录，并把它滚动到可见位置 */
    fun revealPath(relPath: String, recycler: RecyclerView) {
        val parts = relPath.split('/')
        var prefix = ""
        for (i in 0 until parts.size - 1) {
            prefix = if (prefix.isEmpty()) parts[i] else "$prefix/${parts[i]}"
            expandedPaths.add(prefix)
        }
        rebuild()
        val index = nodes.indexOfFirst { it.item.relPath == relPath }
        if (index >= 0) {
            val pos = index
            recycler.post { recycler.smoothScrollToPosition(pos) }
        }
    }

    private fun toggle(relPath: String) {
        if (expandedPaths.contains(relPath)) expandedPaths.remove(relPath) else expandedPaths.add(relPath)
        rebuild()
    }

    override fun getItemCount(): Int = nodes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.item_drawer, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) {
        val node = nodes[position]
        val item = node.item
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val indent = (12 + node.depth * 14).let { (it * density).toInt() }

        holder.itemView.setPaddingRelative(indent, holder.itemView.paddingTop,
            holder.itemView.paddingEnd, holder.itemView.paddingBottom)

        holder.title.text = if (item.isFolder) item.name else repo.meta.titleOf(item.relPath, item.name)

        if (item.isFolder) {
            holder.icon.setImageResource(R.drawable.ic_folder)
            holder.icon.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(holder.icon, com.google.android.material.R.attr.colorPrimary)
            )
            holder.chevron.visibility = if (node.hasChildren) View.VISIBLE else View.INVISIBLE
            holder.chevron.rotation = if (node.expanded) 90f else 0f
            holder.itemView.setOnClickListener { toggle(item.relPath) }
        } else {
            holder.icon.setImageResource(R.drawable.ic_html)
            holder.icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.doc_tint))
            holder.chevron.visibility = View.INVISIBLE
            holder.itemView.setOnClickListener { onDocClick(item) }
        }

        val active = item.relPath == currentPath
        holder.itemView.isActivated = active
        holder.title.setTextColor(
            MaterialColors.getColor(
                holder.title,
                if (active) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface
            )
        )
    }

    class Row(view: View) : RecyclerView.ViewHolder(view) {
        val chevron: ImageView = view.findViewById(R.id.drawer_chevron)
        val icon: ImageView = view.findViewById(R.id.drawer_icon)
        val title: TextView = view.findViewById(R.id.drawer_title)
    }
}
