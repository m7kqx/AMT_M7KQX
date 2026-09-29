# Android Morse Trainer (AMT)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android_8.0+_(API_24+)-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Architecture](https://img.shields.io/badge/Architecture-Clean_UDF_MVVM-blue.svg?style=flat)](https://developer.android.com/topic/architecture)
[![Room](https://img.shields.io/badge/Database-Room_KSP-orange.svg?style=flat)](https://developer.android.com/training/data-storage/room)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg?style=flat)](LICENSE)

**Android Morse Trainer (AMT)** is an open-source, high-performance Android application engineered for amateur radio (ham radio) operators, commercial telegraphers, and Morse code enthusiasts. Built with **Jetpack Compose**, **Material 3**, and an ultra-low-latency **Digital Signal Processing (DSP)** engine, AMT supports both **receiving** (ear training) and **sending** (hardware keying practice) using standard international CW specifications.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
  - [1. Koch Method Ear Training (Receive Mode)](#1-koch-method-ear-training-receive-mode)
  - [2. Hardware Keying Practice (Send Mode)](#2-hardware-keying-practice-send-mode)
  - [3. Live SDR Morse Decoder (Decode Mode)](#3-live-sdr-morse-decoder-decode-mode)
  - [4. Multi-Operator Profiles & Statistics](#4-multi-operator-profiles--statistics)
- [DSP & Mathematical Foundations](#dsp--mathematical-foundations)
  - [Goertzel Tone Detection](#goertzel-tone-detection)
  - [FFT Automatic Pitch Detection](#fft-automatic-pitch-detection)
  - [Adaptive CW Timing & Decoding](#adaptive-cw-timing--decoding)
  - [Anti-Click Audio Synthesis](#anti-click-audio-synthesis)
- [Architecture & Tech Stack](#architecture--tech-stack)
- [Project Structure](#project-structure)
- [Building & Installation](#building--installation)
- [Permissions](#permissions)
- [Testing](#testing)
- [License](#license)

---

## Overview

Traditional Morse trainers focus almost exclusively on audio playback, leaving operators without automated feedback when practicing sending with physical equipment. **Android Morse Trainer** bridges this gap:

- **Ears:** Train your copy speed using the proven **Koch method**, starting with full-speed characters (e.g. 20 WPM) and expanding your character pool as your accuracy reaches 90%.
- **Hands:** Plug in or place your microphone next to your CW keyer, straight key, bug, or transceiver sidetone. The real-time Goertzel DSP analyzes your keying, verifies your timing against live challenges, and alerts you to common mistakes (such as improper element or character spacing).
- **Eyes:** Monitor signals with an SDR-grade phosphor oscilloscope, adjust squelch thresholds live over ambient room noise, and inspect decoded text in a retro-style teletype terminal.

---

## Key Features

### 1. Koch Method Ear Training (Receive Mode)

- **Standard 40-Level Koch Sequence:** Progressive order (`K`, `M`, `R`, `D`, `S`, `U`, `A`, `P`, `T`, `L`, `W`, `I`, `.`, `J`, `Z`, `=`, `F`, `O`, `Y`, `/`, etc.).
- **Adaptive Priority Weighting:** Implements roulette-wheel selection. Characters you frequently miss are weighted higher and served more often until mastered.
- **Automated Level Advancement:** Advances to the next Koch level only when achieving $\ge 90\%$ accuracy over a customizable attempt threshold (default: 10 attempts).
- **Anti-Fatigue State Management:** Includes a Material 3 "Start Lesson" gate dialog, Play/Replay state awareness, and smooth cross-fade animation transitions between challenges.

### 2. Hardware Keying Practice (Send Mode)

- **Acoustic / Line-In Sidetone Listening:** Captures radio or oscillator audio through the device microphone without requiring specialized hardware adapters.
- **Real-Time Challenge Verification:** Displays the target Koch character and its Morse pattern (e.g., `K` $\rightarrow$ `[ - . - ]`). When you key your transmitter, `CwDecoder` processes the audio stream and immediately validates the match.
- **Live Spacing & Timing Feedback:** Displays the live decoded string below the target. If you send `E T` instead of `A` due to elongated inter-element spacing, you see the exact error immediately.
- **Dynamic Squelch Slider:** Real-time variable threshold with a live signal strength bar allows you to drag the squelch line just above ambient room noise.
- **Zero-Jitter Status Dot:** Hardware Goertzel detection state uses an absolute-sized, non-relayout Red/Green indicator dot (Red = Standby / Listening, Green = Sidetone Detected).
- **Lifecycle Audio Isolation:** Microphone capture resources are engaged only while the Send screen is active and strictly released upon navigation.

### 3. Live SDR Morse Decoder (Decode Mode)

- **Dual-Pass Phosphor Waterfall / Oscilloscope:** Custom Compose `Canvas` visualizer with graticule grid lines, ambient glow pass for active tones, sharp inner trace, and semi-transparent squelch threshold lines.
- **One-Tap FFT Auto-Tune:** Runs a 4096-point Radix-2 FFT spectral analysis across 400 Hz – 1000 Hz to lock onto your CW sidetone pitch with SNR reporting.
- **Ambient Noise Floor Calibration:** 1-second background calibration routine that measures room ambient noise and configures recommended squelch thresholds.
- **Live Decoded Teletype:** Monospace terminal displaying the decoded message stream and the currently held pulse element (e.g. `[ .- ]`).

### 4. Multi-Operator Profiles & Statistics

- **Profile Switching:** Independent profiles with individual Koch levels, historical scores, and custom accuracy goals.
- **Persistent Room Database:** Backed by SQLite via Android Room with KSP code generation. Tracks correct/incorrect counts and adaptive weights per character.

---

## DSP & Mathematical Foundations

All digital signal processing and decoding routines are isolated strictly on `Dispatchers.Default` and `Dispatchers.IO` to ensure smooth 60/120 Hz UI rendering.

```
       [ Microphone / AudioRecord ]
                   │
                   ▼  (PCM 16-bit, 44.1 kHz, 512-sample blocks)
         [ Goertzel Tone Detector ]
                   │
         ┌─────────┴─────────┐
         ▼                   ▼
  [ Tone Onset/Offset ]  [ Magnitude vs Squelch ]
         │                   │
         ▼                   ▼
    [ CwDecoder ]     [ Oscilloscope HUD ]
         │                   │
         ▼                   ▼
[ Character Verification ] [ Compose UI ]
```

### Goertzel Tone Detection

Rather than computing costly full-spectrum FFTs on every 11.6 ms audio frame (512 samples at 44.1 kHz), tone detection uses a second-order IIR **Goertzel Filter**:

$$\omega = 2\pi \frac{f_{\text{target}}}{f_s}$$

$$\text{coeff} = 2 \cos(\omega)$$

$$s[n] = x[n] + \text{coeff} \cdot s[n-1] - s[n-2]$$

The target magnitude is calculated at the end of each block:

$$\text{Power} = s[N-1]^2 + s[N-2]^2 - s[N-1] \cdot s[N-2] \cdot \text{coeff}$$

$$\text{Magnitude} = \frac{2 \sqrt{\max(0, \text{Power})}}{N}$$

A tone is registered when $\text{Magnitude} \ge \text{squelchThreshold}$ and spectral purity exceeds $20\%$.

### FFT Automatic Pitch Detection

When the user taps **Auto-Detect Pitch**, `FFTAnalyzer` collects audio, windows it using a **Hann window**:

$$w[n] = 0.5 \left(1 - \cos\left(\frac{2\pi n}{N - 1}\right)\right)$$

and executes an in-place Radix-2 Cooley-Tukey Decimation-in-Time (DIT) Fast Fourier Transform. It searches for the dominant spectral peak in the 400 Hz – 1000 Hz band and tunes the Goertzel filter to the exact pitch.

### Adaptive CW Timing & Decoding

In accordance with standard ITU / PARIS Morse code definitions:
- **Dit ($1\times$):** 1 unit
- **Dah ($3\times$):** 3 units (boundary at $1.8\times$ rolling dit duration)
- **Element space:** 1 unit
- **Character space:** 3 units (watchdog commits character at $2.2\times$)
- **Word space:** 7 units (watchdog commits space at $5.5\times$)

Speed adaptation uses an exponential moving average:

$$\text{dit}_{\text{new}} = \text{dit}_{\text{prev}} \times 0.82 + \text{dit}_{\text{observed}} \times 0.18$$

$$\text{WPM} = \frac{1200}{\text{dit}_{\text{ms}}}$$

### Anti-Click Audio Synthesis

Audio tone playback in `MorseAudioGenerator` uses a 5 ms raised-cosine Hann ramp on tone attack and decay. This eliminates high-frequency transient key clicks:

$$A(t) = \begin{cases} 0.5 \left(1 - \cos\left(\frac{\pi t}{t_{\text{ramp}}}\right)\right) & \text{Attack} \\ 1.0 & \text{Sustain} \\ 0.5 \left(1 + \cos\left(\frac{\pi (t - t_{\text{release}})}{t_{\text{ramp}}}\right)\right) & \text{Decay} \end{cases}$$

---

## Architecture & Tech Stack

- **Language:** Kotlin 2.0+
- **Minimum SDK:** Android 8.0 (API level 24)
- **Target SDK:** Android 15 / Upside Down Cake (API level 35+)
- **UI Toolkit:** Jetpack Compose with Material 3 Design
- **Architecture:** Clean Architecture + Unidirectional Data Flow (UDF) / MVVM
- **State Management:** Kotlin Coroutines `StateFlow` and `SharedFlow` with `collectAsStateWithLifecycle`
- **Database:** Android Room with KSP
- **Audio API:** Android `AudioRecord` (capture) and `AudioTrack` (synthesis)
- **Testing:** JUnit 4, Kotlinx Coroutines Test, StandardTestDispatcher

---

## Project Structure

```text
com.example.androidmorsetrainer/
├── audio/
│   ├── FFTAnalyzer.kt           # Radix-2 FFT spectrum analysis (pitch lock)
│   ├── GoertzelDetector.kt      # Real-time IIR Goertzel single-frequency detector
│   ├── MorseAudioGenerator.kt   # Anti-click PCM tone synthesizer (AudioTrack)
│   └── MorseDSPManager.kt       # Thread-safe audio capture & squelch manager
├── data/
│   ├── local/
│   │   ├── dao/                 # UserProfileDao, CharacterStatsDao
│   │   ├── entity/              # UserProfile, CharacterStats entities
│   │   └── MorseDatabase.kt     # Room database definition
│   └── repository/              # ProfileRepository & implementation
├── di/
│   └── AppContainer.kt          # Clean manual dependency injection container
├── morse/
│   ├── CwDecoder.kt             # Adaptive CW decoder & character event stream
│   ├── KochMethodManager.kt     # 40-level Koch sequence & roulette weighting
│   └── MorseConstants.kt        # ITU dictionary, prosigns, timing constants
├── ui/
│   ├── navigation/
│   │   └── NavigationTab.kt     # Bottom navigation tabs (Train, Send, Decode, Profiles)
│   ├── screens/
│   │   ├── decode/              # SDR Waterfall, Oscilloscope, Teletype
│   │   ├── profiles/            # Profile creation, deletion, statistics
│   │   ├── send/                # Phase 7.5 Hardware Keying Practice
│   │   └── train/               # Interactive Koch receive training
│   └── theme/                   # SDR Phosphor colors, Dark Theme, Typography
├── MainActivity.kt              # Scaffold, TopAppBar & NavGraph routing
└── MorseTrainerApplication.kt   # Application entry point & DI initialization
```

---

## Building & Installation

### Prerequisites

- Android Studio Meerkat or newer
- JDK 17 or JDK 21
- Android SDK 35+

### Build from Command Line

1. **Clone the repository:**
   ```bash
   git clone https://github.com/m7kqx/AMT_M7KQX.git
   cd AMT_M7KQX
   ```

2. **Run all unit tests:**
   ```bash
   ./gradlew testDebugUnitTest
   ```

3. **Assemble the Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```

   The generated APK will be available at:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Install to a connected device or emulator:**
   ```bash
   ./gradlew installDebug
   ```

---

## Permissions

| Permission | Purpose |
| :--- | :--- |
| `android.permission.RECORD_AUDIO` | Required to capture microphone audio for the Goertzel tone detector in **Send** (hardware keying) and **Decode** modes. Audio is processed purely on-device in memory and is **never** recorded to disk or transmitted over the network. |

---

## Testing

The project includes unit test suites covering the core domain, DSP algorithms, and presentation state machines:

```bash
# Run all tests
./gradlew test

# Test specific subsystems
./gradlew testDebugUnitTest --tests "com.example.androidmorsetrainer.morse.*"
./gradlew testDebugUnitTest --tests "com.example.androidmorsetrainer.audio.*"
./gradlew testDebugUnitTest --tests "com.example.androidmorsetrainer.ui.screens.send.*"
```

- [`CwDecoderTest`](app/src/test/java/com/example/androidmorsetrainer/morse/CwDecoderTest.kt): Verifies dit/dah classification, rolling speed adaptation, and character boundaries.
- [`FFTAnalyzerTest`](app/src/test/java/com/example/androidmorsetrainer/audio/FFTAnalyzerTest.kt): Verifies frequency peak resolution and SNR computation.
- [`SendViewModelTest`](app/src/test/java/com/example/androidmorsetrainer/ui/screens/send/SendViewModelTest.kt): Tests Goertzel event forwarding, verification matching, squelch changes, and Koch promotion logic.
- [`TrainViewModelTest`](app/src/test/java/com/example/androidmorsetrainer/ui/screens/train/TrainViewModelTest.kt): Tests adaptive weighting updates, attempt tracking, and lesson dialog states.

---

## License

```text
Copyright 2026 Chris Webster (M7KQX)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
