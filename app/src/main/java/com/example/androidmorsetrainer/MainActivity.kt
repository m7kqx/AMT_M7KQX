package com.example.androidmorsetrainer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidmorsetrainer.ui.navigation.NavigationTab
import com.example.androidmorsetrainer.ui.screens.decode.DecodeScreen
import com.example.androidmorsetrainer.ui.screens.decode.DecodeViewModel
import com.example.androidmorsetrainer.ui.screens.profiles.ProfilesScreen
import com.example.androidmorsetrainer.ui.screens.profiles.ProfilesViewModel
import com.example.androidmorsetrainer.ui.screens.send.SendScreen
import com.example.androidmorsetrainer.ui.screens.send.SendViewModel
import com.example.androidmorsetrainer.ui.screens.train.TrainScreen
import com.example.androidmorsetrainer.ui.screens.train.TrainViewModel
import com.example.androidmorsetrainer.ui.theme.AndroidMorseTrainerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidMorseTrainerTheme {
                MorseTrainerApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure background audio capture is not forcefully terminated during transient configuration changes.
        // Audio capture and decoding resources are strictly deferred to ViewModel lifecycle or app termination.
        if (isFinishing) {
            (application as? MorseTrainerApplication)?.container?.morseDSPManager?.stopListening()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorseTrainerApp(
    profilesViewModel: ProfilesViewModel = viewModel(factory = ProfilesViewModel.Factory),
    trainViewModel: TrainViewModel = viewModel(factory = TrainViewModel.Factory),
    sendViewModel: SendViewModel = viewModel(factory = SendViewModel.Factory),
    decodeViewModel: DecodeViewModel = viewModel(factory = DecodeViewModel.Factory),
    debugViewModel: com.example.androidmorsetrainer.ui.screens.debug.DebugViewModel = viewModel(factory = com.example.androidmorsetrainer.ui.screens.debug.DebugViewModel.Factory),
) {
    val profilesUiState by profilesViewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(NavigationTab.PROFILES) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = when (currentTab) {
                                NavigationTab.TRAIN -> "Koch Morse Trainer"
                                NavigationTab.SEND -> "Hardware Keying Practice"
                                NavigationTab.DECODE -> "Morse Decoder"
                                NavigationTab.SETUP -> "Decoder Setup"
                                NavigationTab.PROFILES -> "User Profiles"
                                NavigationTab.DEBUG -> "Debug Mode"
                            },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        profilesUiState.activeProfile?.let { (_, name, currentKochLevel) ->
                            if (currentTab != NavigationTab.PROFILES) {
                                Text(
                                    text = "Active: $name (Level $currentKochLevel)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationTab.entries.forEach { tab ->
                    val isSelected = currentTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.contentDescription,
                            )
                        },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)

        when (currentTab) {
            NavigationTab.TRAIN -> {
                TrainScreen(
                    activeProfile = profilesUiState.activeProfile,
                    viewModel = trainViewModel,
                    modifier = contentModifier,
                )
            }
            NavigationTab.SEND -> {
                SendScreen(
                    activeProfile = profilesUiState.activeProfile,
                    viewModel = sendViewModel,
                    modifier = contentModifier,
                )
            }
            NavigationTab.DECODE -> {
                DecodeScreen(
                    viewModel = decodeViewModel,
                    modifier = contentModifier,
                )
            }
            NavigationTab.SETUP -> {
                com.example.androidmorsetrainer.ui.screens.decode.SetupScreen(
                    viewModel = decodeViewModel,
                    modifier = contentModifier,
                )
            }
            NavigationTab.PROFILES -> {
                ProfilesScreen(
                    uiState = profilesUiState,
                    onOpenAddDialog = profilesViewModel::onOpenAddDialog,
                    onDismissAddDialog = profilesViewModel::onDismissAddDialog,
                    onProfileNameChange = profilesViewModel::onProfileNameChange,
                    onAddProfile = profilesViewModel::onAddProfile,
                    onRequestDeleteProfile = profilesViewModel::onRequestDeleteProfile,
                    onDismissDeleteDialog = profilesViewModel::onDismissDeleteDialog,
                    onConfirmDeleteProfile = profilesViewModel::onConfirmDeleteProfile,
                    onSelectProfile = profilesViewModel::onSelectProfile,
                    onClearUserMessage = profilesViewModel::onClearUserMessage,
                    onClearError = profilesViewModel::onClearError,
                    modifier = contentModifier,
                )
            }
            NavigationTab.DEBUG -> {
                com.example.androidmorsetrainer.ui.screens.debug.DebugScreen(
                    viewModel = debugViewModel,
                    activeProfile = profilesUiState.activeProfile,
                    onKochLevelChange = { newLevel ->
                        profilesUiState.activeProfile?.let {
                            debugViewModel.updateProfileKochLevel(it.id, newLevel)
                        }
                    },
                    modifier = contentModifier,
                )
            }
        }
    }
}