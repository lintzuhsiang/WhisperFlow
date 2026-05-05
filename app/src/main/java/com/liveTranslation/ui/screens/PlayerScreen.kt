package com.liveTranslation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.liveTranslation.ui.components.AppBar
import com.liveTranslation.ui.components.BackButton
import com.liveTranslation.ui.components.LanguageOption
import com.liveTranslation.ui.components.PlayerControls
import com.liveTranslation.ui.components.StatusPill
import com.liveTranslation.ui.components.StatusTone
import com.liveTranslation.ui.components.SubtitleBand
import com.liveTranslation.ui.components.VideoSurface16x9
import com.liveTranslation.ui.theme.WhisperFlowColors
import com.liveTranslation.ui.theme.WhisperFlowTheme

/**
 * Player / "TRANSLATING" screen. Layout (top → bottom):
 *
 *   1. AppBar with TRANSLATING pill (pulsing)
 *   2. Video surface (16:9, ExoPlayer via [VideoSurface16x9])
 *   3. SubtitleBand — translated text + (optional) original + ASR capillary
 *   4. PlayerControls — scrub bar + play/pause + lang chip + fullscreen
 *   5. RecentTranscript (3 dim lines, gives context without dominating)
 *
 * Pure / state-hoisted: every visible bit of state comes from arguments.
 */
@Composable
fun PlayerScreen(
    player: Player?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    translatedText: String?,
    originalText: String?,
    showOriginal: Boolean,
    asrProgress: Float,
    sourceLangCode: String,
    targetLangCode: String,
    recentEntries: List<RecentSubtitle>,
    onBackClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onSourceLangChange: (LanguageOption) -> Unit,
    onTargetLangChange: (LanguageOption) -> Unit,
    onFullscreenClick: () -> Unit,
    statusLabel: String = "TRANSLATING",
    statusTone: StatusTone = StatusTone.Translating,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WhisperFlowColors.PhoneBg)
            .padding(top = 48.dp)
    ) {
        // Top row: BACK on the left, status pill on the right.
        // Replaces the original full-width AppBar so users can leave
        // the player without backing out to the OS.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BackButton(onClick = onBackClick)
            StatusPill(label = statusLabel, tone = statusTone)
        }
        Spacer(Modifier.height(16.dp))

        VideoSurface16x9(
            player = player,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        )

        Spacer(Modifier.height(20.dp))

        SubtitleBand(
            translatedText = translatedText,
            originalText = originalText,
            showOriginal = showOriginal,
            asrProgress = asrProgress
        )

        Spacer(Modifier.height(24.dp))

        PlayerControls(
            isPlaying          = isPlaying,
            positionMs         = positionMs,
            durationMs         = durationMs,
            sourceLangCode     = sourceLangCode,
            targetLangCode     = targetLangCode,
            onPlayPause        = onPlayPause,
            onSeek             = onSeek,
            onSkipBackward     = onSkipBackward,
            onSkipForward      = onSkipForward,
            onSourceLangChange = onSourceLangChange,
            onTargetLangChange = onTargetLangChange,
            onFullscreenClick  = onFullscreenClick
        )

        Spacer(Modifier.height(24.dp))

        if (recentEntries.isNotEmpty()) {
            RecentTranscript(entries = recentEntries)
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** A single line in the "recent" preview list. */
data class RecentSubtitle(val timestamp: String, val translated: String)

@Composable
private fun RecentTranscript(entries: List<RecentSubtitle>) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "RECENT",
                style = MaterialTheme.typography.labelSmall,
                color = WhisperFlowColors.TextLo
            )
            Spacer(Modifier.height(0.dp))
            Spacer(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .height(0.5.dp)
                    .background(WhisperFlowColors.Divider)
                    .fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        entries.takeLast(3).forEach { e ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    e.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = WhisperFlowColors.TextLl,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    e.translated.take(48),
                    style = MaterialTheme.typography.bodySmall,
                    color = WhisperFlowColors.TextLo,
                    maxLines = 1
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview — uses null player; AndroidView's PlayerView shows its own
// transparent shutter background so the layout still reads.
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Player — translating", widthDp = 412, heightDp = 880, showBackground = true)
@Composable
private fun PlayerScreenPreview() {
    WhisperFlowTheme {
        PlayerScreen(
            player = null,
            isPlaying = true,
            positionMs = 14_320,
            durationMs = 23_180,
            translatedText = "Today I want to interview a special person.",
            originalText = "今日は特別な人にインタビューしたいと思います",
            showOriginal = true,
            asrProgress = 0.65f,
            sourceLangCode = "ja",
            targetLangCode = "en",
            recentEntries = listOf(
                RecentSubtitle("14:08", "Hello everyone, how are you?"),
                RecentSubtitle("14:14", "Today I want to interview a special person."),
                RecentSubtitle("14:20", "He was my senior when I taught Japanese in Mexico.")
            ),
            onBackClick = {},
            onPlayPause = {},
            onSeek = {},
            onSkipBackward = {},
            onSkipForward = {},
            onSourceLangChange = {},
            onTargetLangChange = {},
            onFullscreenClick = {}
        )
    }
}
