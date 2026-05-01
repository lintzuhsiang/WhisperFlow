package com.liveTranslation.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.liveTranslation.ui.theme.WhisperFlowColors

/**
 * Pill that shows the app's high-level state — Ready, Translating, Playing,
 * Error. Tone controls the dot colour; an active tone (Translating) pulses
 * subtly to signal "something is happening".
 */
enum class StatusTone {
    /** Idle — green dot, static. */
    Ready,
    /** Active work — amber dot, pulsing. */
    Translating,
    /** Active playback — amber dot, static. */
    Playing,
    /** Error — amber dot, static (re-using amber so we don't add red noise). */
    Error
}

@Composable
fun StatusPill(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier
) {
    val dotColor = when (tone) {
        StatusTone.Ready       -> WhisperFlowColors.PulseGreen
        StatusTone.Translating -> WhisperFlowColors.Amber
        StatusTone.Playing     -> WhisperFlowColors.Amber
        StatusTone.Error       -> WhisperFlowColors.Amber
    }
    val pulse = tone == StatusTone.Translating

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(WhisperFlowColors.Surface)
            .padding(horizontal = 10.dp)
    ) {
        PulseDot(color = dotColor, pulsing = pulse)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = WhisperFlowColors.TextHi
        )
    }
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue  = 0.35f,
            animationSpec = infiniteRepeatable(
                animation  = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
        value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}
