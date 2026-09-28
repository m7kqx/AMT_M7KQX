package com.example.androidmorsetrainer

import android.app.Application
import com.example.androidmorsetrainer.di.AppContainer
import com.example.androidmorsetrainer.di.DefaultAppContainer

class MorseTrainerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
