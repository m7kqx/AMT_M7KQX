package com.example.androidmorsetrainer.audio

import android.util.Log
import com.example.androidmorsetrainer.morse.MorseAudioPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hardware integration implementation of [MorseAudioPlayer].
 *
 * Synthesizes pure 16-bit PCM sine wave tones using Android's [android.media.AudioTrack]
 * via [MorseAudioGenerator]. Enforces strict 1:3:7 ITU/ARRL Morse timing rules:
 * - Dit = 1 unit (1200 / WPM ms)
 * - Dah = 3 units (3x dit)
 * - Intra-character element space = 1 unit (1x dit)
 * - Inter-character space = 3 units (3x dit)
 * - Inter-word space = 7 units (7x dit)
 *
 * All audio computation and streaming execute on background coroutines ([Dispatchers.Default])
 * to guarantee zero UI thread blocking. Hardware AudioTrack resources are cleanly stopped
 * and released via [stop] and [release].
 */
open class SineWaveMorsePlayer(
    val audioGenerator: MorseAudioGenerator = MorseAudioGenerator(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : MorseAudioPlayer {

    companion object {
        private const val TAG = "SineWaveMorsePlayer"
    }

    override suspend fun playTransmission(text: String, wpm: Int) = withContext(defaultDispatcher) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext

        Log.d(TAG, "Playing Morse transmission at $wpm WPM: '$trimmed'")
        audioGenerator.setWpm(wpm)
        audioGenerator.playString(trimmed)
    }

    /**
     * Halts ongoing AudioTrack playback immediately and flushes the buffer.
     */
    open fun stop() {
        audioGenerator.stop()
        Log.d(TAG, "Audio playback stopped")
    }

    /**
     * Explicitly releases Android AudioTrack hardware resources.
     */
    open fun release() {
        audioGenerator.release()
        Log.d(TAG, "AudioTrack hardware resources released")
    }
}
