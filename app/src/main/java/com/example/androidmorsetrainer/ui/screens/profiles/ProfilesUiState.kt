package com.example.androidmorsetrainer.ui.screens.profiles

import androidx.compose.runtime.Immutable
import com.example.androidmorsetrainer.data.local.entity.UserProfile

/**
 * State representing the Profiles screen.
 * Immutability annotation helps Compose optimize recomposition passes.
 */
@Immutable
data class ProfilesUiState(
    val profiles: List<UserProfile> = emptyList(),
    val activeProfileId: Long? = null,
    val isLoading: Boolean = false,
    val isAddDialogOpen: Boolean = false,
    val newProfileNameInput: String = "",
    val profileToDelete: UserProfile? = null,
    val userMessage: String? = null,
    val errorMessage: String? = null
) {
    val activeProfile: UserProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }
}
