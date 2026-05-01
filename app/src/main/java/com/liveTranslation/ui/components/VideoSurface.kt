package com.liveTranslation.ui.components

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.liveTranslation.ui.theme.WhisperFlowColors

/**
 * Compose wrapper around Media3's [PlayerView]. We don't need to write a
 * native Compose video surface — PlayerView handles aspect, scaling, and
 * subtitle rendering for free. We just clip it into a rounded box and let
 * Compose layout decide the size.
 *
 * The PlayerView is created once via [AndroidView]'s factory; subsequent
 * recompositions only update the bound [Player] reference, never recreate
 * the surface (cheap re-render).
 */
@Composable
fun VideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    showControls: Boolean = false  // We render our own custom controls
) {
    AndroidView(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(WhisperFlowColors.PhoneBg),
        factory = { context ->
            PlayerView(context).apply {
                useController = showControls
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                // Let our Compose container drive aspect; PlayerView fits.
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.player = player
            view.useController = showControls
        }
    )
}

/**
 * Convenience: a 16:9 framed [VideoSurface] for the portrait player screen.
 */
@Composable
fun VideoSurface16x9(
    player: Player?,
    modifier: Modifier = Modifier
) {
    VideoSurface(
        player = player,
        modifier = modifier.aspectRatio(16f / 9f),
        showControls = false
    )
}
