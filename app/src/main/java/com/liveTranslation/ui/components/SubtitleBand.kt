package com.liveTranslation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liveTranslation.ui.theme.WhisperFlowColors

/**
 * Subtitle band — the YouTube-CC-style strip below the video. NO card
 * background; it's just text on the screen background. The translated
 * line is the visual anchor; the original line is small and dim and only
 * shown when the user has flipped the "show original" toggle.
 *
 * A thin amber capillary below the band represents the ASR progress for
 * the active window — purely cosmetic, but communicates "the system is
 * still chewing" without a spinner.
 */
@Composable
fun SubtitleBand(
    translatedText: String?,
    originalText: String?,
    showOriginal: Boolean,
    asrProgress: Float = 0f,         // 0f..1f — capillary fill
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Reserve a min height so the band doesn't collapse and shift the
        // controls upward when there's no current subtitle yet.
        val placeholder = "—"
        Text(
            translatedText ?: placeholder,
            style = MaterialTheme.typography.displaySmall,
            color = if (translatedText.isNullOrBlank()) WhisperFlowColors.TextLl
                    else WhisperFlowColors.TextHi,
            textAlign = TextAlign.Center
        )
        if (showOriginal && !originalText.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                originalText,
                style = MaterialTheme.typography.bodySmall,
                color = WhisperFlowColors.TextLo,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(10.dp))
        AsrCapillary(progress = asrProgress)
    }
}

@Composable
private fun AsrCapillary(progress: Float) {
    // 72dp wide hairline — a centred filled segment to the left, dim to
    // the right. We don't animate here; the caller can recompose with a
    // changing [progress] value to imply motion if needed.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val total = 72.dp
        val clamped = progress.coerceIn(0f, 1f)
        Box(modifier = Modifier.width(total).height(1.dp)) {
            // Dim background full width
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WhisperFlowColors.AmberDim)
            )
            // Fill from the left
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(1.dp)
                    .background(WhisperFlowColors.Amber)
            )
        }
    }
}
