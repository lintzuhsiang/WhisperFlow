package com.liveTranslation.ui.components

/**
 * Language option used by the various language pickers across the app.
 * Mirrors `LanguageOption` on the iOS side so the two surfaces stay
 * shape-compatible when comparing engine inputs.
 */
data class LanguageOption(
    /** BCP-47 code passed to STT/MT engines. */
    val code: String,
    /** Human-readable label for menus. */
    val displayName: String
) {
    companion object {
        val sources = listOf(
            LanguageOption("auto",  "Auto-detect"),
            LanguageOption("en",    "English"),
            LanguageOption("ja",    "Japanese"),
            LanguageOption("zh",    "Chinese (Simplified)"),
            LanguageOption("zh-tw", "Chinese (Traditional)"),
            LanguageOption("ko",    "Korean"),
            LanguageOption("es",    "Spanish"),
            LanguageOption("fr",    "French"),
            LanguageOption("de",    "German"),
            LanguageOption("ar",    "Arabic"),
            LanguageOption("pt",    "Portuguese"),
            LanguageOption("ru",    "Russian"),
            LanguageOption("hi",    "Hindi")
        )

        /** Targets exclude "auto" — translation needs a concrete destination. */
        val targets: List<LanguageOption> = sources.filter { it.code != "auto" }

        fun displayNameOf(code: String, defaultLabel: String = code.uppercase()): String =
            sources.firstOrNull { it.code.equals(code, ignoreCase = true) }?.displayName
                ?: defaultLabel
    }
}
