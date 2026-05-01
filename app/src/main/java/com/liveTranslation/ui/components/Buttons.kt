package com.liveTranslation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.liveTranslation.ui.theme.WhisperFlowColors

private val BTN_RADIUS = 12.dp
private val BTN_MIN_WIDTH = 56.dp
private val BTN_PADDING_H = 16.dp

/**
 * Solid amber button — the action. Used for the "GO" button in the URL
 * row, primary CTAs in dialogs. Reserve it: only one per screen.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = BTN_MIN_WIDTH)
            .clip(RoundedCornerShape(BTN_RADIUS))
            .background(if (enabled) WhisperFlowColors.Amber else WhisperFlowColors.AmberDim)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = BTN_PADDING_H),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = WhisperFlowColors.PhoneBg
        )
    }
}

/**
 * Outlined amber button — the alternative. Used for "FILE" beside GO.
 * Matches the primary's geometry exactly so both can sit in a Row at the
 * same height (no vertical wobble).
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = BTN_MIN_WIDTH)
            .clip(RoundedCornerShape(BTN_RADIUS))
            .background(WhisperFlowColors.PhoneBg)
            .border(
                BorderStroke(0.8.dp, if (enabled) WhisperFlowColors.Amber else WhisperFlowColors.AmberDim),
                RoundedCornerShape(BTN_RADIUS)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = BTN_PADDING_H),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) WhisperFlowColors.Amber else WhisperFlowColors.AmberDim
        )
    }
}
