package com.htmlstudy.library.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.htmlstudy.library.R

/** 导入 / 解压时用的进度框（不可取消，避免拷贝到一半被打断） */
class ProgressHandle(private val dialog: AlertDialog, private val text: TextView) {

    private var lastUpdate = 0L

    fun update(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 80) return
        lastUpdate = now
        text.text = message
    }

    fun dismiss() {
        runCatching { dialog.dismiss() }
    }

    companion object {
        fun show(context: Context, message: String): ProgressHandle {
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null)
            val text = view.findViewById<TextView>(R.id.progress_text)
            text.text = message
            val dialog = MaterialAlertDialogBuilder(context)
                .setView(view)
                .setCancelable(false)
                .create()
            dialog.show()
            return ProgressHandle(dialog, text)
        }
    }
}
