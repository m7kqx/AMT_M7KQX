package com.example.androidmorsetrainer.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DebugViewModel(
    private val audioGenerator: MorseAudioGenerator,
    private val profileRepository: ProfileRepository? = null
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MorseTrainerApplication)
                DebugViewModel(
                    audioGenerator = application.container.morseAudioGenerator,
                    profileRepository = application.container.profileRepository
                )
            }
        }
    }

    private var playbackJob: Job? = null

    fun playCharacter(character: String) {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            try {
                audioGenerator.playCharacter(character)
            } catch (e: CancellationException) {
                // Ignore
            }
        }
    }

    fun updateProfileKochLevel(profileId: Long, level: Int) {
        viewModelScope.launch {
            profileRepository?.updateProfileKochLevel(profileId, level)
        }
    }

    fun updateProfileProsignLevel(profileId: Long, level: Int) {
        viewModelScope.launch {
            val profile = profileRepository?.getProfileById(profileId)
            if (profile != null) {
                profileRepository?.updateProfile(profile.copy(currentProsignLevel = level))
            }
        }
    }

    fun updateActiveProfileKochLevel(profile: UserProfile?, level: Int) {
        if (profile == null) return
        updateProfileKochLevel(profile.id, level)
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        audioGenerator.stop()
    }
}
