package com.example.dance.util

import android.content.Context
import com.example.dance.data.VideoRepository
import com.example.dance.data.db.AppDatabase

/**
 * Lightweight dependency holder. Initialized once from [com.example.dance.DanceApplication]
 * and read from ViewModel factories. Not a full DI framework — just enough to
 * keep a single AppDatabase and VideoRepository per process.
 */
object ServiceLocator {

    private var repository: VideoRepository? = null

    /** Must be called once, from [com.example.dance.DanceApplication.onCreate]. */
    fun init(context: Context) {
        if (repository == null) {
            val db = AppDatabase.build(context.applicationContext)
            repository = VideoRepository(db)
        }
    }

    fun videoRepository(): VideoRepository =
        requireNotNull(repository) { "ServiceLocator.init(context) must be called before use" }
}
