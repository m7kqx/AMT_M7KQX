package com.example.androidmorsetrainer.di

import android.content.Context
import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import com.example.androidmorsetrainer.audio.MorseDSPManager
import com.example.androidmorsetrainer.data.local.MorseDatabase
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import com.example.androidmorsetrainer.data.repository.ProfileRepositoryImpl
import com.example.androidmorsetrainer.morse.KochMethodManager

/**
 * Dependency container providing singletons across the application.
 * Implements manual dependency injection as per Clean Architecture guidelines without heavy frameworks.
 */
interface AppContainer {
    val database: MorseDatabase
    val profileRepository: ProfileRepository
    val kochMethodManager: KochMethodManager
    val morseAudioGenerator: MorseAudioGenerator
    val morseDSPManager: MorseDSPManager
}

class DefaultAppContainer(private val context: Context) : AppContainer {

    override val database: MorseDatabase by lazy {
        MorseDatabase.getDatabase(context)
    }

    override val profileRepository: ProfileRepository by lazy {
        ProfileRepositoryImpl(
            userProfileDao = database.userProfileDao(),
            characterStatsDao = database.characterStatsDao()
        )
    }

    override val kochMethodManager: KochMethodManager by lazy {
        KochMethodManager()
    }

    override val morseAudioGenerator: MorseAudioGenerator by lazy {
        MorseAudioGenerator()
    }

    override val morseDSPManager: MorseDSPManager by lazy {
        MorseDSPManager(context.applicationContext)
    }
}
