package com.example.androidmorsetrainer.ui.screens.train

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Extended Koch sequence of 41 characters:
 * 40 standard LCWO characters plus the '=' (<BT>) prosign symbol appended.
 */
val EXTENDED_KOCH_SEQUENCE: List<String> = listOf(
    "K", "M", "R", "S", "U", "A", "P", "T", "L", "O",
    "W", "I", ".", "N", "J", "E", "F", "0", "Y", ",",
    "V", "G", "5", "/", "Q", "9", "Z", "H", "3", "8",
    "B", "?", "4", "2", "7", "C", "1", "D", "6", "X",
    "="
)

/**
 * Fast O(1) index lookup mapping each character to its 0-indexed position in the extended Koch sequence.
 */
val EXTENDED_KOCH_INDEX_MAP: Map<String, Int> = EXTENDED_KOCH_SEQUENCE
    .mapIndexed { index, char -> char to index }
    .toMap()

/**
 * Evaluates whether a character is unlocked at the given Koch level.
 * Level 1 starts with 2 characters (indices 0 and 1: 'K' and 'M').
 */
fun isKochCharacterUnlocked(character: String, currentKochLevel: Int): Boolean {
    val index = EXTENDED_KOCH_INDEX_MAP[character] ?: return false
    return index <= currentKochLevel
}

private val KEYBOARD_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val KEYBOARD_ROW_2 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
private val KEYBOARD_ROW_3 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
private val KEYBOARD_ROW_4 = listOf("Z", "X", "C", "V", "B", "N", "M")

/**
 * Bespoke 5-row responsive Jetpack Compose in-app keyboard for the Koch CW Bootcamp.
 *
 * Dynamically gates character input based on the user's [currentKochLevel] to cultivate
 * spatial muscle memory while preventing inputs for characters not yet introduced.
 *
 * Layout Structure:
 * - Row 1: '1' through '0' (10 keys)
 * - Row 2: 'Q' through 'P' (10 keys)
 * - Row 3: 'A' through 'L' (9 keys with 0.5x horizontal padding on left & right)
 * - Row 4: 'Z' through 'M' (7 keys with 1.5x horizontal padding on left & right)
 * - Row 5: Action row: '?', ',', REPEAT (2.5x width), '.', '/', '=' (labeled "BT")
 */
@Composable
fun KochKeyboard(
    currentKochLevel: Int,
    onCharacterClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBackspaceClick: () -> Unit = {},
    onRepeatClick: () -> Unit = {},
    enabled: Boolean = true,
    lastGuessedCharacter: String? = null,
    lastGuessWasCorrect: Boolean? = null,
    keyHeight: Dp = 46.dp
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: 1 through 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                KEYBOARD_ROW_1.forEach { char ->
                    KeyboardCharKey(
                        character = char,
                        currentKochLevel = currentKochLevel,
                        onCharacterClick = onCharacterClick,
                        enabled = enabled,
                        lastGuessedCharacter = lastGuessedCharacter,
                        lastGuessWasCorrect = lastGuessWasCorrect,
                        height = keyHeight
                    )
                }
            }

            // Row 2: Q through P
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                KEYBOARD_ROW_2.forEach { char ->
                    KeyboardCharKey(
                        character = char,
                        currentKochLevel = currentKochLevel,
                        onCharacterClick = onCharacterClick,
                        enabled = enabled,
                        lastGuessedCharacter = lastGuessedCharacter,
                        lastGuessWasCorrect = lastGuessWasCorrect,
                        height = keyHeight
                    )
                }
            }

            // Row 3: A through L with horizontal padding (0.5x spacers on both ends)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Spacer(modifier = Modifier.weight(0.5f))
                KEYBOARD_ROW_3.forEach { char ->
                    KeyboardCharKey(
                        character = char,
                        currentKochLevel = currentKochLevel,
                        onCharacterClick = onCharacterClick,
                        enabled = enabled,
                        lastGuessedCharacter = lastGuessedCharacter,
                        lastGuessWasCorrect = lastGuessWasCorrect,
                        height = keyHeight
                    )
                }
                Spacer(modifier = Modifier.weight(0.5f))
            }

            // Row 4: Z through M with larger horizontal padding (1.5x spacers on both ends)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Spacer(modifier = Modifier.weight(1.5f))
                KEYBOARD_ROW_4.forEach { char ->
                    KeyboardCharKey(
                        character = char,
                        currentKochLevel = currentKochLevel,
                        onCharacterClick = onCharacterClick,
                        enabled = enabled,
                        lastGuessedCharacter = lastGuessedCharacter,
                        lastGuessWasCorrect = lastGuessWasCorrect,
                        height = keyHeight
                    )
                }
                Spacer(modifier = Modifier.weight(1.5f))
            }

            // Row 5: Action row left-to-right: '?', ',', REPEAT, '.', '/', '=' (labeled "BT")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 1. '?' Key
                KeyboardCharKey(
                    character = "?",
                    currentKochLevel = currentKochLevel,
                    onCharacterClick = onCharacterClick,
                    enabled = enabled,
                    lastGuessedCharacter = lastGuessedCharacter,
                    lastGuessWasCorrect = lastGuessWasCorrect,
                    weight = 1.5f,
                    height = keyHeight
                )

                // 2. ',' Key
                KeyboardCharKey(
                    character = ",",
                    currentKochLevel = currentKochLevel,
                    onCharacterClick = onCharacterClick,
                    enabled = enabled,
                    lastGuessedCharacter = lastGuessedCharacter,
                    lastGuessWasCorrect = lastGuessWasCorrect,
                    weight = 1.5f,
                    height = keyHeight
                )

                // 3. REPEAT Button (2.5x standard width, distinct primary container color, replay icon)
                KeyboardRepeatKey(
                    onRepeatClick = onRepeatClick,
                    enabled = enabled,
                    weight = 2.5f,
                    height = keyHeight
                )

                // 4. '.' Key
                KeyboardCharKey(
                    character = ".",
                    currentKochLevel = currentKochLevel,
                    onCharacterClick = onCharacterClick,
                    enabled = enabled,
                    lastGuessedCharacter = lastGuessedCharacter,
                    lastGuessWasCorrect = lastGuessWasCorrect,
                    weight = 1.5f,
                    height = keyHeight
                )

                // 5. '/' Key
                KeyboardCharKey(
                    character = "/",
                    currentKochLevel = currentKochLevel,
                    onCharacterClick = onCharacterClick,
                    enabled = enabled,
                    lastGuessedCharacter = lastGuessedCharacter,
                    lastGuessWasCorrect = lastGuessWasCorrect,
                    weight = 1.5f,
                    height = keyHeight
                )

                // 6. '=' Key (Labeled "BT", emits '=')
                KeyboardCharKey(
                    character = "=",
                    label = "BT",
                    currentKochLevel = currentKochLevel,
                    onCharacterClick = onCharacterClick,
                    enabled = enabled,
                    lastGuessedCharacter = lastGuessedCharacter,
                    lastGuessWasCorrect = lastGuessWasCorrect,
                    weight = 1.5f,
                    height = keyHeight
                )
            }
        }
    }
}

/**
 * Standard character key composable with dynamic level gating.
 */
@Composable
private fun RowScope.KeyboardCharKey(
    character: String,
    currentKochLevel: Int,
    onCharacterClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = character,
    weight: Float = 1.0f,
    enabled: Boolean = true,
    lastGuessedCharacter: String? = null,
    lastGuessWasCorrect: Boolean? = null,
    height: Dp = 46.dp,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    val kochIndex = EXTENDED_KOCH_INDEX_MAP[character] ?: Int.MAX_VALUE
    val isUnlocked = kochIndex <= currentKochLevel
    val isClickable = isUnlocked && enabled

    val isLastGuessed = lastGuessedCharacter?.equals(character, ignoreCase = true) == true

    val containerColor = when {
        !isUnlocked -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        isLastGuessed && lastGuessWasCorrect == true -> Color(0xFF2E7D32)
        isLastGuessed && lastGuessWasCorrect == false -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surface
    }

    val contentColor = when {
        !isUnlocked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
        isLastGuessed && lastGuessWasCorrect != null -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    val borderColor = if (isUnlocked) {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
    }

    Surface(
        onClick = { if (isClickable) onCharacterClick(character) },
        enabled = isClickable,
        modifier = modifier
            .weight(weight)
            .height(height)
            .semantics { contentDescription = "Key $label" },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (isUnlocked) 2.dp else 0.dp,
        shadowElevation = if (isUnlocked) 1.dp else 0.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = if (label.length > 1) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = contentColor
            )
        }
    }
}

/**
 * Dedicated REPEAT button on Row 5 (2.5x standard width, distinct primary container color, replay icon).
 */
@Composable
private fun RowScope.KeyboardRepeatKey(
    onRepeatClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 2.5f,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    Surface(
        onClick = onRepeatClick,
        enabled = enabled,
        modifier = modifier
            .weight(weight)
            .height(height)
            .semantics { contentDescription = "Repeat Morse Tone" },
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Replay,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "REPEAT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Dedicated Backspace button on Row 5. Always fully opaque and clickable regardless of Koch level.
 */
@Composable
private fun RowScope.KeyboardBackspaceKey(
    onBackspaceClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 2.5f,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    Surface(
        onClick = onBackspaceClick,
        enabled = enabled,
        modifier = modifier
            .weight(weight)
            .height(height)
            .semantics { contentDescription = "Backspace" },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
