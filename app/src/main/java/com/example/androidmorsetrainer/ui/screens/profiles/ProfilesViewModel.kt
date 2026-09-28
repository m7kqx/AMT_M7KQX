package com.example.androidmorsetrainer.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing user profiles, observing Room database changes, and exposing a single StateFlow.
 */
class ProfilesViewModel(
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfilesUiState(isLoading = true))
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    init {
        observeProfiles()
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            profileRepository.allProfiles
                .catch { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message) }
                }
                .collect { profiles ->
                    _uiState.update { current ->
                        val currentActiveId = current.activeProfileId
                        val nextActiveId = when {
                            profiles.isEmpty() -> null
                            currentActiveId == null -> profiles.first().id
                            profiles.none { it.id == currentActiveId } -> profiles.first().id
                            else -> currentActiveId
                        }
                        current.copy(
                            profiles = profiles,
                            activeProfileId = nextActiveId,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun onOpenAddDialog() {
        _uiState.update {
            it.copy(
                isAddDialogOpen = true,
                newProfileNameInput = "",
                errorMessage = null
            )
        }
    }

    fun onDismissAddDialog() {
        _uiState.update {
            it.copy(
                isAddDialogOpen = false,
                newProfileNameInput = "",
                errorMessage = null
            )
        }
    }

    fun onProfileNameChange(name: String) {
        _uiState.update { it.copy(newProfileNameInput = name) }
    }

    fun onAddProfile() {
        val trimmedName = _uiState.value.newProfileNameInput.trim()
        if (trimmedName.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Profile name cannot be empty.") }
            return
        }

        viewModelScope.launch {
            try {
                val newId = profileRepository.createProfile(trimmedName)
                _uiState.update {
                    it.copy(
                        isAddDialogOpen = false,
                        newProfileNameInput = "",
                        activeProfileId = newId,
                        userMessage = "Profile \"$trimmedName\" created",
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to create profile: ${e.localizedMessage ?: "Unknown error"}")
                }
            }
        }
    }

    fun onRequestDeleteProfile(profile: UserProfile) {
        _uiState.update { it.copy(profileToDelete = profile) }
    }

    fun onDismissDeleteDialog() {
        _uiState.update { it.copy(profileToDelete = null) }
    }

    fun onConfirmDeleteProfile() {
        val profile = _uiState.value.profileToDelete ?: return
        viewModelScope.launch {
            try {
                profileRepository.deleteProfile(profile)
                _uiState.update {
                    it.copy(
                        profileToDelete = null,
                        userMessage = "Profile \"${profile.name}\" deleted",
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        profileToDelete = null,
                        errorMessage = "Failed to delete profile: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun onSelectProfile(profileId: Long) {
        _uiState.update { it.copy(activeProfileId = profileId) }
    }

    fun onClearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun onClearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MorseTrainerApplication)
                ProfilesViewModel(application.container.profileRepository)
            }
        }
    }
}
