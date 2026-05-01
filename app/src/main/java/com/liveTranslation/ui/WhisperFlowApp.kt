package com.liveTranslation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import com.liveTranslation.ui.components.StatusTone
import com.liveTranslation.ui.screens.LandingScreen
import com.liveTranslation.ui.screens.PlayerScreen
import com.liveTranslation.ui.screens.RecentSubtitle
import com.liveTranslation.ui.theme.WhisperFlowColors
import com.liveTranslation.ui.theme.WhisperFlowTheme

/**
 * Top-level state holder. Plain data class — the Activity owns one and
 * mutates it; Compose reads it. No coupling to Compose internals so the
 * same state can be unit-tested or driven from a ViewModel later.
 */
data class WhisperFlowAppState(
    // Landing
    val url: String = "",
    val sourceLangLabel: String = "Auto-detect",
    val targetLangLabel: String = "English",
    val sourceLangCode: String = "auto",
    val targetLangCode: String = "en",
    val showOriginal: Boolean = false,
    val statusLabel: String = "READY",
    val statusTone: StatusTone = StatusTone.Ready,

    // Player (only relevant when isVideoLoaded)
    val isVideoLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val translatedText: String? = null,
    val originalText: String? = null,
    val asrProgress: Float = 0f,
    val recentEntries: List<RecentSubtitle> = emptyList()
)

/**
 * Top-level composable. Switches between Landing and Player with a fade
 * transition. State is owned by the caller (Activity or ViewModel) and
 * passed in; callbacks bubble up the same way.
 *
 * The [player] parameter is the live Media3 [Player] from DualTrackPlayer.
 * Pass null while engines are still loading or after release().
 */
@Composable
fun WhisperFlowApp(
    state: WhisperFlowAppState,
    player: Player?,
    onUrlChange: (String) -> Unit,
    onGoClick: () -> Unit,
    onFileClick: () -> Unit,
    onSourceLangClick: () -> Unit,
    onTargetLangClick: () -> Unit,
    onShowOriginalChange: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onLangChipClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    WhisperFlowTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(WhisperFlowColors.PhoneBg)
        ) {
            AnimatedContent(
                targetState = state.isVideoLoaded,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen"
            ) { videoLoaded ->
                if (!videoLoaded) {
                    LandingScreen(
                        url = state.url,
                        onUrlChange = onUrlChange,
                        onGoClick = onGoClick,
                        onFileClick = onFileClick,
                        sourceLangLabel = state.sourceLangLabel,
                        targetLangLabel = state.targetLangLabel,
                        onSourceLangClick = onSourceLangClick,
                        onTargetLangClick = onTargetLangClick,
                        showOriginal = state.showOriginal,
                        onShowOriginalChange = onShowOriginalChange,
                        statusLabel = state.statusLabel,
                        statusTone = state.statusTone
                    )
                } else {
                    PlayerScreen(
                        player = player,
                        isPlaying = state.isPlaying,
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        translatedText = state.translatedText,
                        originalText = state.originalText,
                        showOriginal = state.showOriginal,
                        asrProgress = state.asrProgress,
                        sourceLangCode = state.sourceLangCode,
                        targetLangCode = state.targetLangCode,
                        recentEntries = state.recentEntries,
                        onPlayPause = onPlayPause,
                        onSeek = onSeek,
                        onLangChipClick = onLangChipClick,
                        onFullscreenClick = onFullscreenClick,
                        statusLabel = state.statusLabel,
                        statusTone = state.statusTone
                    )
                }
            }
        }
    }
}
