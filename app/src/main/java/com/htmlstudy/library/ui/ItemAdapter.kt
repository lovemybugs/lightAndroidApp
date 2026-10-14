package com.htmlstudy.library.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.htmlstudy.library.R
import com.htmlstudy.library.data.LibraryItem
import com.htmlstudy.library.data.LibraryRepository
import com.htmlstudy.library.util.FileUtil
import com.google.android.material.color.MaterialColors

/**
 * 库列表。三种行样式：目录 / HTML 文档 / 其它素材。
 */
class ItemAdapter(
    private val repo: LibraryRepository,
    private val onOpen: (LibraryItem) -> Unit,
    private val onMenu: (LibraryItem, View) -> Unit
) : ListAdapter<LibraryItem, ItemAdapter.Row>(Diff) {

    /** 搜索结果 / 收藏 / 最近里显示完整路径，普通浏览只显示大小等信息 */
    var showPath: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_TEXT)
            }
        }

    /** 阅读器里高亮当前文档 */
    var highlightPath: String? = null

    companion object {
        private const val PAYLOAD_TEXT = 1

        private val Diff = object : DiffUtil.ItemCallback<LibraryItem>() {
            override fun areItemsTheSame(a: LibraryItem, b: LibraryItem) = a.relPath == b.relPath
            override fun areContentsTheSame(a: LibraryItem, b: LibraryItem): Boolean =
                false // 元数据（收藏/阅读时间）随时会变，全量刷新内容更省心
        }
    }

    class Row(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.row_icon)
        val title: TextView = view.findViewById(R.id.row_title)
        val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        val star: ImageView = view.findViewById(R.id.row_star)
        val more: ImageButton = view.findViewById(R.id.row_more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return Row(view)
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        val titleText = if (item.isFolder) item.name else repo.meta.titleOf(item.relPath, item.name)
        holder.title.text = titleText

        when (item.type) {
            LibraryItem.Type.FOLDER -> {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.icon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(holder.icon, com.google.android.material.R.attr.colorPrimary)
                )
                holder.subtitle.text = context.getString(
                    R.string.folder_summary,
                    repo.countFiles(item.file),
                    FileUtil.formatSize(repo.sizeOf(item.file))
                )
            }

            LibraryItem.Type.DOC -> {
                holder.icon.setImageResource(R.drawable.ic_html)
                holder.icon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.doc_tint)
                )
                val meta = repo.meta.peek(item.relPath)
                holder.subtitle.text = if (showPath) {
                    item.relPath
                } else {
                    buildString {
                        append(FileUtil.formatSize(item.file.length()))
                        if (meta != null && meta.scroll > 0.02f && meta.scroll < 0.98f) {
                            append(" · 已读到 ").append((meta.scroll * 100).toInt()).append("%")
                        }
                        if (meta != null && meta.lastOpenedAt > 0) {
                            append(" · ").append(FileUtil.formatDate(meta.lastOpenedAt))
                        }
                    }
                }
            }

            LibraryItem.Type.ASSET -> {
                holder.icon.setImageResource(R.drawable.ic_file)
                holder.icon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(holder.icon, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                holder.subtitle.text = if (showPath) item.relPath else FileUtil.formatSize(item.file.length())
            }
        }

        holder.star.visibility = if (repo.meta.isFavorite(item.relPath)) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onOpen(item) }
        holder.itemView.setOnLongClickListener {
            onMenu(item, holder.itemView)
            true
        }
        holder.more.setOnClickListener { onMenu(item, it) }
    }

    override fun onBindViewHolder(holder: Row, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            onBindViewHolder(holder, position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}
