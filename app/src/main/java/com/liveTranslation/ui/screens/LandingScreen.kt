package com.liveTranslation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liveTranslation.ui.components.AppBar
import com.liveTranslation.ui.components.PrimaryButton
import com.liveTranslation.ui.components.SecondaryButton
import com.liveTranslation.ui.components.StatusPill
import com.liveTranslation.ui.components.StatusTone
import com.liveTranslation.ui.theme.WhisperFlowColors
import com.liveTranslation.ui.theme.WhisperFlowTheme

/**
 * Landing / "READY" screen — first thing the user sees.
 *
 * Layout (top → bottom):
 *   - AppBar (wordmark + READY pill)
 *   - URL input + GO + FILE inline row (single-row, never wraps)
 *   - FROM/TO language selectors
 *   - Show original toggle
 *   - Hero translation glyph + tagline (centered)
 *   - Hint copy at the bottom
 *
 * State is hoisted: this composable is pure, all callbacks lift up to the
 * Activity which owns engine state, the dual-track player, etc.
 */
@Composable
fun LandingScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    onGoClick: () -> Unit,
    onFileClick: () -> Unit,
    sourceLangLabel: String,
    targetLangLabel: String,
    onSourceLangClick: () -> Unit,
    onTargetLangClick: () -> Unit,
    showOriginal: Boolean,
    onShowOriginalChange: (Boolean) -> Unit,
    statusLabel: String = "READY",
    statusTone: StatusTone = StatusTone.Ready,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WhisperFlowColors.PhoneBg)
            .padding(top = 48.dp)
    ) {
        AppBar(
            statusLabel = statusLabel,
            statusTone = statusTone
        )

        Spacer(Modifier.height(24.dp))

        // URL input + GO + FILE in a single row — equal heights, no wrapping
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UrlField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .weight(1f)
                    .height(BUTTON_HEIGHT)
            )
            PrimaryButton(
                text = "GO",
                onClick = onGoClick,
                modifier = Modifier.height(BUTTON_HEIGHT)
            )
            SecondaryButton(
                text = "FILE",
                onClick = onFileClick,
                modifier = Modifier.height(BUTTON_HEIGHT)
            )
        }

        Spacer(Modifier.height(20.dp))

        // FROM → TO row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "FROM",
                style = MaterialTheme.typography.labelMedium,
                color = WhisperFlowColors.TextLo
            )
            LangSelector(
                label = sourceLangLabel,
                onClick = onSourceLangClick
            )
            Icon(
                imageVector = Icons.Outlined.ArrowForward,
                contentDescription = null,
                tint = WhisperFlowColors.Amber,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "TO",
                style = MaterialTheme.typography.labelMedium,
                color = WhisperFlowColors.TextLo
            )
            LangSelector(
                label = targetLangLabel,
                onClick = onTargetLangClick
            )
        }

        Spacer(Modifier.height(20.dp))

        // Show original toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Show original text",
                style = MaterialTheme.typography.bodyMedium,
                color = WhisperFlowColors.TextMd,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = showOriginal,
                onCheckedChange = onShowOriginalChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = WhisperFlowColors.PhoneBg,
                    checkedTrackColor = WhisperFlowColors.Amber,
                    uncheckedThumbColor = WhisperFlowColors.TextLo,
                    uncheckedTrackColor = WhisperFlowColors.SurfaceHi,
                    uncheckedBorderColor = WhisperFlowColors.Divider
                )
            )
        }

        Spacer(Modifier.weight(1f))

        // Hero — translation glyph + tagline, centered
        HeroBlock()

        Spacer(Modifier.weight(1f))

        // Bottom hint
        HintCopy()

        Spacer(Modifier.height(40.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-pieces (file-private; not promoted to /components yet to keep stage
// 1 small)
// ─────────────────────────────────────────────────────────────────────────────

private val BUTTON_HEIGHT = 44.dp

@Composable
private fun UrlField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(WhisperFlowColors.Surface)
            .border(0.5.dp, WhisperFlowColors.Divider, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                "Paste video URL or YouTube link",
                style = MaterialTheme.typography.bodyMedium,
                color = WhisperFlowColors.TextLo
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = WhisperFlowColors.TextHi
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(WhisperFlowColors.Amber),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LangSelector(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(WhisperFlowColors.Surface)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = WhisperFlowColors.TextHi
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = WhisperFlowColors.TextLo,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun HeroBlock() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Glyph: a thin amber-bordered square. Inner mark drawn in stage-2
        // when we add a custom Path for the "文/A" abstraction.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, WhisperFlowColors.Amber, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "文",
                style = MaterialTheme.typography.displayMedium.copy(
                    color = WhisperFlowColors.Amber,
                    fontSize = androidx.compose.ui.unit.TextUnit(
                        24f,
                        androidx.compose.ui.unit.TextUnitType.Sp
                    )
                )
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Real-time AI translation",
            style = MaterialTheme.typography.displaySmall,
            color = WhisperFlowColors.TextHi
        )
        Text(
            "for any video",
            style = MaterialTheme.typography.displaySmall,
            color = WhisperFlowColors.TextHi
        )
        Spacer(Modifier.height(12.dp))
        // Tiny accent line
        Box(
            modifier = Modifier
                .height(1.dp)
                .width(36.dp)
                .background(WhisperFlowColors.Amber)
        )
    }
}

@Composable
private fun HintCopy() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Paste a direct video URL  ·  .mp4  .m3u8  ·  YouTube",
            style = MaterialTheme.typography.labelSmall,
            color = WhisperFlowColors.TextLo
        )
        Text(
            "or tap FILE to load from your device",
            style = MaterialTheme.typography.labelSmall,
            color = WhisperFlowColors.TextLl
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Landing — empty", widthDp = 412, heightDp = 880, showBackground = true)
@Composable
private fun LandingScreenPreview() {
    var url by remember { mutableStateOf("") }
    var showOriginal by remember { mutableStateOf(false) }
    WhisperFlowTheme {
        LandingScreen(
            url = url,
            onUrlChange = { url = it },
            onGoClick = {},
            onFileClick = {},
            sourceLangLabel = "Auto-detect",
            targetLangLabel = "English",
            onSourceLangClick = {},
            onTargetLangClick = {},
            showOriginal = showOriginal,
            onShowOriginalChange = { showOriginal = it }
        )
    }
}
