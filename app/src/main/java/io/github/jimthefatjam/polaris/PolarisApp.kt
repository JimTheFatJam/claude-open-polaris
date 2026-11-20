package io.github.jimthefatjam.polaris

import android.app.Application
import io.github.jimthefatjam.polaris.di.AppContainer

class PolarisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
