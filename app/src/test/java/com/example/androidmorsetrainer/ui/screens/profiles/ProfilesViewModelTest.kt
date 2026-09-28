package com.example.androidmorsetrainer.ui.screens.profiles

import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeProfileRepository : ProfileRepository {
    private val profilesFlow = MutableStateFlow<List<UserProfile>>(emptyList())
    private var nextId = 1L

    override val allProfiles: Flow<List<UserProfile>> = profilesFlow.asStateFlow()

    override suspend fun getAllProfiles(): List<UserProfile> = profilesFlow.value

    override suspend fun getProfileById(id: Long): UserProfile? = profilesFlow.value.find { it.id == id }

    override suspend fun createProfile(name: String): Long {
        val newProfile = UserProfile(id = nextId++, name = name, currentKochLevel = 1, createdAt = System.currentTimeMillis())
        profilesFlow.update { listOf(newProfile) + it }
        return newProfile.id
    }

    override suspend fun updateProfile(profile: UserProfile) {
        profilesFlow.update { list -> list.map { if (it.id == profile.id) profile else it } }
    }

    override suspend fun deleteProfile(profile: UserProfile) {
        profilesFlow.update { list -> list.filterNot { it.id == profile.id } }
    }

    override suspend fun deleteProfileById(id: Long) {
        profilesFlow.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun getStatsForProfile(profileId: Long): List<CharacterStats> = emptyList()

    override suspend fun getWeightedStatsForProfile(profileId: Long): List<CharacterStats> = emptyList()

    override suspend fun getStatForCharacter(profileId: Long, character: String): CharacterStats? = null

    override suspend fun saveCharacterStat(stat: CharacterStats) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeProfileRepository
    private lateinit var viewModel: ProfilesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeProfileRepository()
        viewModel = ProfilesViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_loadsEmptyProfilesList() = runTest(testDispatcher) {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.profiles.isEmpty())
        assertNull(state.activeProfileId)
    }

    @Test
    fun addProfile_successUpdatesStateAndActiveProfile() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.onOpenAddDialog()
        assertTrue(viewModel.uiState.value.isAddDialogOpen)

        viewModel.onProfileNameChange("M7KQX")
        assertEquals("M7KQX", viewModel.uiState.value.newProfileNameInput)

        viewModel.onAddProfile()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isAddDialogOpen)
        assertEquals(1, state.profiles.size)
        assertEquals("M7KQX", state.profiles.first().name)
        assertEquals(state.profiles.first().id, state.activeProfileId)
        assertNotNull(state.userMessage)
    }

    @Test
    fun addProfile_emptyNameShowsError() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.onOpenAddDialog()
        viewModel.onProfileNameChange("   ")
        viewModel.onAddProfile()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.errorMessage)
        assertTrue(state.profiles.isEmpty())
    }

    @Test
    fun deleteProfile_removesFromState() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.onProfileNameChange("User1")
        viewModel.onAddProfile()
        advanceUntilIdle()

        val profile = viewModel.uiState.value.profiles.first()
        viewModel.onRequestDeleteProfile(profile)
        assertEquals(profile, viewModel.uiState.value.profileToDelete)

        viewModel.onConfirmDeleteProfile()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.profileToDelete)
        assertTrue(state.profiles.isEmpty())
        assertNull(state.activeProfileId)
    }

    @Test
    fun selectProfile_updatesActiveProfileId() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.onProfileNameChange("User 1")
        viewModel.onAddProfile()
        viewModel.onProfileNameChange("User 2")
        viewModel.onAddProfile()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.profiles.size)

        val targetId = state.profiles.last().id
        viewModel.onSelectProfile(targetId)

        assertEquals(targetId, viewModel.uiState.value.activeProfileId)
    }
}
