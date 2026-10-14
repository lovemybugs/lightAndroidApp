package com.htmlstudy.library

import android.app.Application
import android.content.Context
import com.htmlstudy.library.data.LibraryRepository
import com.htmlstudy.library.data.Prefs

/**
 * 全局单例持有者。库的根目录、元数据索引、用户偏好都在这里初始化一次。
 */
class App : Application() {

    lateinit var repo: LibraryRepository
        private set

    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = LibraryRepository(this)
        repo.init()
    }
}

val Context.app: App
    get() = applicationContext as App
