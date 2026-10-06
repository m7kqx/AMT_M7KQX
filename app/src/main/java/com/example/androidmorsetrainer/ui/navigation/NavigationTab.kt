package com.example.androidmorsetrainer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavigationTab(
    val title: String,
    val icon: ImageVector,
    val contentDescription: String
) {
    TRAIN("Train", Icons.Default.School, "Navigate to Training screen"),
    SEND("Send", Icons.Default.Radio, "Navigate to Hardware Keying Send screen"),
    DECODE("Decode", Icons.Default.Hearing, "Navigate to Morse Decoder screen"),
    SETUP("Setup", Icons.Default.Settings, "Navigate to Decoder Setup screen"),
    PROFILES("Profiles", Icons.Default.Person, "Navigate to User Profiles screen"),
    DEBUG("Debug", Icons.Default.Build, "Navigate to Debug screen")
}
