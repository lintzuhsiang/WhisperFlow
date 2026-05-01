package com.liveTranslation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liveTranslation.ui.components.AppBar
import com.liveTranslation.ui.components.StatusTone
import com.liveTranslation.ui.theme.WhisperFlowColors
import com.liveTranslation.ui.theme.WhisperFlowTheme

/** Single record in the transcript list. */
data class TranscriptEntry(
    val startMs: Long,
    val endMs: Long,
    val timestamp: String,   // formatted "00:01:24"
    val sourceLang: String,  // BCP-47, used for the lang tag
    val original: String,
    val translated: String
)

/**
 * Transcript screen — scrollable list of all subtitles emitted so far.
 * The currently-playing entry is highlighted with a surface lift + amber
 * left border. A "Jump to now" pill floats above the bottom edge so the
 * user can return to the live position after scrolling around.
 */
@Composable
fun TranscriptScreen(
    entries: List<TranscriptEntry>,
    currentPositionMs: Long,
    onJumpToNow: () -> Unit,
    statusLabel: String = "PLAYING",
    statusTone: StatusTone = StatusTone.Playing,
    modifier: Modifier = Modifier
) {
    val activeIndex = entries.indexOfFirst {
        currentPositionMs in it.startMs..it.endMs
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WhisperFlowColors.PhoneBg)
            .padding(top = 48.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppBar(statusLabel = statusLabel, statusTone = statusTone)

            val listState = rememberLazyListState()
            // Auto-scroll the active entry into view as the player advances —
            // but only if the user isn't actively scrolling (don't fight them).
            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0 && !listState.isScrollInProgress) {
                    listState.animateScrollToItem(activeIndex)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 24.dp, end = 24.dp,
                    top = 16.dp, bottom = 96.dp  // leave room for the floating pill
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items = entries, key = { it.startMs }) { entry ->
                    val isActive = entries.indexOf(entry) == activeIndex
                    EntryCard(entry = entry, active = isActive)
                }
            }
        }

        // Floating "Jump to now" pill — only shows when the user has scrolled
        // away from the active entry. Cheap heuristic: show when there's an
        // active item and it's not visible.
        // (We don't strictly check visibility; the pill is harmless when it
        // appears redundantly. Tap action is idempotent.)
        if (activeIndex >= 0) {
            JumpToNowPill(
                onClick = onJumpToNow,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun EntryCard(entry: TranscriptEntry, active: Boolean) {
    val bg = if (active) WhisperFlowColors.Surface else WhisperFlowColors.PhoneBg
    val tsColor = if (active) WhisperFlowColors.Amber else WhisperFlowColors.TextLo
    val origColor = if (active) WhisperFlowColors.TextMd else WhisperFlowColors.TextLo
    val transColor = if (active) WhisperFlowColors.TextHi else WhisperFlowColors.TextMd
    val transStyle = if (active) MaterialTheme.typography.titleMedium
                     else MaterialTheme.typography.bodyMedium

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
    ) {
        // Active marker — 2dp amber column on the left edge
        if (active) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .padding(vertical = 12.dp)
                    .background(WhisperFlowColors.Amber)
            )
        } else {
            Spacer(Modifier.width(2.dp))
        }

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = tsColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "·  " + entry.sourceLang.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = WhisperFlowColors.TextLl
                )
            }
            if (entry.original.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(entry.original, style = MaterialTheme.typography.bodySmall, color = origColor)
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.translated, style = transStyle, color = transColor)
        }
    }
}

@Composable
private fun JumpToNowPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(WhisperFlowColors.SurfaceHi)
            .border(0.6.dp, WhisperFlowColors.Amber, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = WhisperFlowColors.Amber,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "JUMP TO NOW",
            style = MaterialTheme.typography.labelLarge,
            color = WhisperFlowColors.TextHi
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Transcript", widthDp = 412, heightDp = 880, showBackground = true)
@Composable
private fun TranscriptScreenPreview() {
    val sample = listOf(
        TranscriptEntry(72_000, 78_000, "00:01:12", "ja",
            "皆さんこんにちは、元気ですか",
            "Hello everyone, how are you?"),
        TranscriptEntry(78_000, 84_000, "00:01:18", "ja",
            "今日は特別な人にインタビューしたいと思います",
            "Today I want to interview a special person."),
        TranscriptEntry(84_000, 92_000, "00:01:24", "ja",
            "私の先輩で、メキシコで日本語を教えていた時の先輩です",
            "He was my senior when I taught Japanese in Mexico."),
        TranscriptEntry(92_000, 98_000, "00:01:32", "ja",
            "だから今日は私はタメ語を使いません",
            "So today I won't use casual speech."),
        TranscriptEntry(98_000, 104_000, "00:01:38", "ja",
            "敬語で話します",
            "I'll speak in formal Japanese.")
    )
    WhisperFlowTheme {
        TranscriptScreen(
            entries = sample,
            currentPositionMs = 88_000,  // active = 3rd entry
            onJumpToNow = {}
        )
    }
}
