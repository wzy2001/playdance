package com.example.dance

import android.app.Application
import com.example.dance.data.VideoRepository
import com.example.dance.util.ServiceLocator

/**
 * Application-scoped singletons. A simple ServiceLocator keeps startup trivial
 * without committing to a DI framework.
 */
class DanceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }

    val videoRepository: VideoRepository get() = ServiceLocator.videoRepository()
}
