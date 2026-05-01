package com.liveTranslation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * WhisperFlow theme — dark only. The light scheme is intentionally not
 * implemented; the design philosophy ("Quiet Instrument") only resolves
 * on a dark ground.
 *
 * Material3's color slots are mapped to our palette — but most of the
 * UI reads from [WhisperFlowColors] directly via [LocalWhisperFlowColors]
 * because the design uses several greys that Material doesn't have slots
 * for (TextLo, TextLl, Divider).
 */
private val DarkColors = darkColorScheme(
    primary           = WhisperFlowColors.Amber,
    onPrimary         = WhisperFlowColors.PhoneBg,
    primaryContainer  = WhisperFlowColors.AmberDim,
    onPrimaryContainer = WhisperFlowColors.TextHi,
    secondary         = WhisperFlowColors.TextMd,
    onSecondary       = WhisperFlowColors.PhoneBg,
    background        = WhisperFlowColors.Bg,
    onBackground      = WhisperFlowColors.TextHi,
    surface           = WhisperFlowColors.Surface,
    onSurface         = WhisperFlowColors.TextHi,
    surfaceVariant    = WhisperFlowColors.SurfaceHi,
    onSurfaceVariant  = WhisperFlowColors.TextMd,
    outline           = WhisperFlowColors.Divider,
    outlineVariant    = WhisperFlowColors.Divider,
    error             = WhisperFlowColors.Amber,
    onError           = WhisperFlowColors.PhoneBg
)

val LocalWhisperFlowColors = staticCompositionLocalOf { WhisperFlowColors }

@Composable
fun WhisperFlowTheme(
    // Parameter kept for parity with Compose convention; ignored — we are
    // dark-only and don't want the system theme to swap our scheme.
    @Suppress("UNUSED_PARAMETER")
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalWhisperFlowColors provides WhisperFlowColors
    ) {
        MaterialTheme(
            colorScheme = DarkColors,
            typography  = WhisperFlowTypography,
            content     = content
        )
    }
}
