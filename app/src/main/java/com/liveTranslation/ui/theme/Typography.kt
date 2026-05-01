package com.liveTranslation.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.liveTranslation.R

/**
 * WhisperFlow typography — three voices (display / body / mono) used like
 * a small ensemble. Sizes follow a strict scale, no orphan sizes.
 *
 * Display  — Bricolage Grotesque (geometric, slightly humanist) — for
 *            wordmark, hero text, the active subtitle line.
 * Body     — Instrument Sans — readable workhorse, used for everything
 *            non-display.
 * Mono     — Geist Mono — for status, timestamps, machine-state metadata.
 *            Anything that suggests "this is data" gets mono.
 */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.instrument_sans_bold, FontWeight.Bold)
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_bold, FontWeight.Bold)
)

val BricolageGrotesque = FontFamily(
    Font(R.font.bricolage_grotesque_regular, FontWeight.Normal),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold)
)

/**
 * Material3 Typography mapped to our three families. Material slot names
 * (titleLarge, bodyMedium, etc.) describe role — the family choice
 * encodes the visual register we want at that role.
 */
val WhisperFlowTypography = Typography(
    // Display — wordmark, "Real-time AI translation" hero
    displayLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.4).sp,
        lineHeight = 36.sp
    ),
    displayMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = 0.sp,
        lineHeight = 28.sp
    ),
    displaySmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 0.sp,
        lineHeight = 22.sp
    ),

    // Headline — section anchors
    headlineMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp
    ),

    // Title — wordmark + active subtitle line
    titleLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        letterSpacing = 0.4.sp,
        lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 0.15.sp,
        lineHeight = 20.sp
    ),

    // Body — most non-display text
    bodyLarge = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
        lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        lineHeight = 16.sp
    ),

    // Label — small caps role (status pills, button text, dropdown triggers).
    // Mono on purpose — gives a "machine-state" register to anything labelled.
    labelLarge = TextStyle(
        fontFamily = GeistMono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        lineHeight = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = GeistMono,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 1.2.sp,
        lineHeight = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = GeistMono,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 1.0.sp,
        lineHeight = 11.sp
    )
)
