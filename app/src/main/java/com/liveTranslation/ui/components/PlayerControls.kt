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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onSourceLangChange: (LanguageOption) -> Unit,
    onTargetLangChange: (LanguageOption) -> Unit,
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Transport: -10  ▶  +10
            SkipButton(forward = false, onClick = onSkipBackward)
            PlayPauseButton(isPlaying = isPlaying, onClick = onPlayPause)
            SkipButton(forward = true, onClick = onSkipForward)

            Spacer(Modifier.weight(1f))

            // Inline lang pair: each chip opens its own DropdownMenu so
            // the user can switch source / target without leaving the
            // player. Same shape as iOS LangChipMenu.
            LangChipMenu(
                selectedCode = sourceLangCode,
                options = LanguageOption.sources,
                onSelect = onSourceLangChange
            )
            Text(
                "→",
                style = MaterialTheme.typography.labelMedium,
                color = WhisperFlowColors.Amber
            )
            LangChipMenu(
                selectedCode = targetLangCode,
                options = LanguageOption.targets,
                onSelect = onTargetLangChange
            )

            Spacer(Modifier.weight(1f))

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

/**
 * One BCP-47 code pill that drops a [DropdownMenu] of [options]. Used
 * twice in the controls row (source + target). Native Material3
 * dropdown so we get a11y / focus / dismissal handling for free.
 */
@Composable
private fun LangChipMenu(
    selectedCode: String,
    options: List<LanguageOption>,
    onSelect: (LanguageOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(WhisperFlowColors.Surface)
                .border(0.5.dp, WhisperFlowColors.Divider, RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp)
        ) {
            Text(
                selectedCode.uppercase(),
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
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(WhisperFlowColors.SurfaceHi)
        ) {
            options.forEach { opt ->
                val isSelected = opt.code.equals(selectedCode, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Text(
                            opt.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) WhisperFlowColors.Amber
                                    else WhisperFlowColors.TextHi
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = WhisperFlowColors.Amber,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(opt)
                    }
                )
            }
        }
    }
}

/** Outline ±10 s skip button. Material's gobackward/goforward equivalents. */
@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10,
            contentDescription = if (forward) "Skip forward 10 seconds" else "Skip backward 10 seconds",
            tint = WhisperFlowColors.TextMd,
            modifier = Modifier.size(26.dp)
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
