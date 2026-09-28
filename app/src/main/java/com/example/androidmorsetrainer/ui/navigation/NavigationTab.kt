package com.example.androidmorsetrainer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavigationTab(
    val title: String,
    val icon: ImageVector,
    val contentDescription: String
) {
    TRAIN("Train", Icons.Default.School, "Navigate to Training screen"),
    DECODE("Decode", Icons.Default.Hearing, "Navigate to Morse Decoder screen"),
    PROFILES("Profiles", Icons.Default.Person, "Navigate to User Profiles screen")
}
