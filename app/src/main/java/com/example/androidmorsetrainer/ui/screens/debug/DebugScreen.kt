package com.example.androidmorsetrainer.ui.screens.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.morse.MorseConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    activeProfile: UserProfile? = null,
    onKochLevelChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val characters = MorseConstants.MORSE_MAP.keys.toList().sorted()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Debug Menu",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Koch Progression Override Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Koch Progression Override",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (activeProfile != null) {
                    Text(
                        text = "Active Profile: ${activeProfile.name} (Current Level: ${activeProfile.currentKochLevel})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "No active profile selected. Switch profile in Profiles tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                var expanded by remember { mutableStateOf(false) }
                val kochLevels = remember { (1..39).toList() }
                val currentLevel = activeProfile?.currentKochLevel ?: 1

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (activeProfile != null) expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    OutlinedTextField(
                        value = "Koch Level $currentLevel",
                        onValueChange = {},
                        readOnly = true,
                        enabled = activeProfile != null,
                        label = { Text("Koch Progression Level") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        kochLevels.forEach { level ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Level $level",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (level == currentLevel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (level == currentLevel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    if (onKochLevelChange != null) {
                                        onKochLevelChange(level)
                                    } else if (activeProfile != null) {
                                        viewModel.updateProfileKochLevel(activeProfile.id, level)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Prosigns Progression Override Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Prosigns Progression Override",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (activeProfile != null) {
                    Text(
                        text = "Current Prosigns Level: ${activeProfile.currentProsignLevel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                var expandedProsigns by remember { mutableStateOf(false) }
                val prosignLevels = remember { (1..12).toList() }
                val currentProsignLevel = activeProfile?.currentProsignLevel ?: 1

                ExposedDropdownMenuBox(
                    expanded = expandedProsigns,
                    onExpandedChange = { if (activeProfile != null) expandedProsigns = !expandedProsigns },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    OutlinedTextField(
                        value = "Prosigns Level $currentProsignLevel",
                        onValueChange = {},
                        readOnly = true,
                        enabled = activeProfile != null,
                        label = { Text("Prosigns Progression Level") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProsigns) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        singleLine = true
                    )
                    ExposedDropdownMenu(
                        expanded = expandedProsigns,
                        onDismissRequest = { expandedProsigns = false }
                    ) {
                        prosignLevels.forEach { level ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Level $level",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (level == currentProsignLevel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (level == currentProsignLevel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    expandedProsigns = false
                                    if (activeProfile != null) {
                                        viewModel.updateProfileProsignLevel(activeProfile.id, level)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Tone Generator Debug",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Press a button to trigger tone generation directly.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(characters) { char ->
                Button(onClick = { viewModel.playCharacter(char) }) {
                    Text(text = char)
                }
            }
        }
    }
}
