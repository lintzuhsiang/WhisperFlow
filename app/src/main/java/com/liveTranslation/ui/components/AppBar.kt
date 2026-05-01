package com.liveTranslation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.liveTranslation.ui.theme.WhisperFlowColors

/**
 * Top app bar — wordmark on the left, status pill on the right, hairline
 * underneath. Used by every screen so the brand mark is anchored.
 */
@Composable
fun AppBar(
    statusLabel: String,
    statusTone: StatusTone,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Wordmark()
            StatusPill(label = statusLabel, tone = statusTone)
        }
        // Hairline divider across the full width, hugging the bottom edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .padding(horizontal = 24.dp)
                .background(WhisperFlowColors.Divider)
                .align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tiny amber dot — the "mark" stripped to its essence
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(WhisperFlowColors.Amber)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "WhisperFlow",
            style = MaterialTheme.typography.titleLarge,
            color = WhisperFlowColors.TextHi
        )
    }
}
