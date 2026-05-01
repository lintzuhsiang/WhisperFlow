package com.liveTranslation.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * WhisperFlow palette — calibrated to the "Quiet Instrument" design philosophy.
 * Colour names map 1:1 to design/render_mockup.py so the app and the mockup
 * stay in sync.
 *
 * Convention:
 *   - Surface tones (Bg / PhoneBg / Surface / SurfaceHi) climb in luminance.
 *   - Text tones (TextHi / TextMd / TextLo / TextLl) descend in luminance.
 *   - Amber is the ONLY accent — used for focus, action, "now" indicators.
 *     Reserve it; if everything is amber, nothing is.
 */
object WhisperFlowColors {
    // Surfaces (dark → less dark)
    val Bg          = Color(0xFF0E0E10)
    val PhoneBg     = Color(0xFF10101A)
    val Surface     = Color(0xFF18181B)
    val SurfaceHi   = Color(0xFF1E1E22)

    // Hairlines / dividers
    val Divider     = Color(0xFF2A2A2F)

    // Text
    val TextHi      = Color(0xFFF2F2F4)   // headline white
    val TextMd      = Color(0xFFAAAAB4)   // body
    val TextLo      = Color(0xFF646470)   // caption
    val TextLl      = Color(0xFF41414A)   // barely visible

    // Single accent
    val Amber       = Color(0xFFD4A24C)
    val AmberDim    = Color(0xFF785B26)

    // Status / state colours — used sparingly
    val PulseGreen  = Color(0xFF60D481)   // "READY"
    val PulseGreenDim = Color(0xFF38783B)
}
