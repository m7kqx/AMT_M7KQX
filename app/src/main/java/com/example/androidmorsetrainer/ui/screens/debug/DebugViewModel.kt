package com.example.androidmorsetrainer.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DebugViewModel(
    private val audioGenerator: MorseAudioGenerator
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MorseTrainerApplication)
                DebugViewModel(
                    audioGenerator = application.container.morseAudioGenerator
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

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        audioGenerator.stop()
    }
}
