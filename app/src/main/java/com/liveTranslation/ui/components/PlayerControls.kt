package com.liveTranslation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.liveTranslation.ui.theme.WhisperFlowColors

/**
 * Bottom-of-screen control row in PlayerScreen — scrub bar above, then
 * a single horizontal row: [▶] · [JA → EN] · [⛶].
 *
 * Stays lean so it works in both portrait and landscape (same widget,
 * different parent constraints).
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    sourceLangCode: String,
    targetLangCode: String,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onLangChipClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        ScrubBar(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeek
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause)
            LangChip(
                source = sourceLangCode,
                target = targetLangCode,
                onClick = onLangChipClick
            )
            FullscreenButton(onClick = onFullscreenClick)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScrubBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    val total = durationMs.coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / total).coerceIn(0f, 1f)
    Column {
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(WhisperFlowColors.Divider)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(WhisperFlowColors.Amber)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatTime(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = WhisperFlowColors.TextLo
            )
            Text(
                formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = WhisperFlowColors.TextLo
            )
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(WhisperFlowColors.Amber)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = WhisperFlowColors.PhoneBg,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LangChip(source: String, target: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(WhisperFlowColors.Surface)
            .border(0.5.dp, WhisperFlowColors.Divider, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
    ) {
        Text(
            source.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = WhisperFlowColors.TextMd
        )
        Spacer(Modifier.width(8.dp))
        Text("→", style = MaterialTheme.typography.labelMedium, color = WhisperFlowColors.Amber)
        Spacer(Modifier.width(8.dp))
        Text(
            target.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = WhisperFlowColors.TextHi
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.ArrowDropDown,
            contentDescription = null,
            tint = WhisperFlowColors.TextLo,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun FullscreenButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Fullscreen,
            contentDescription = "Fullscreen",
            tint = WhisperFlowColors.TextMd,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * mm:ss for short videos, hh:mm:ss for long ones. Negative or unknown
 * durations show as "—:—".
 */
private fun formatTime(ms: Long): String {
    if (ms < 0L) return "—:—"
    val totalSec = ms / 1000
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
