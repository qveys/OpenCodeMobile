package org.opencodemobile.design.system.i18n

import androidx.compose.ui.text.intl.Locale

/**
 * The languages shipped in V1 — cahier des charges §9.6: "FR + EN dès la V1", device locale.
 *
 * The Compose Multiplatform resource API already resolves `Res.string.*` against the
 * device locale. [AppLanguage] exposes that resolution explicitly so screens, tests, and
 * any future in-app language override share a single mapping instead of re-reading the
 * platform locale in every component.
 */
public enum class AppLanguage(public val tag: String) {
    English("en"),
    French("fr");

    public companion object {
        /** Fallback when the device locale is not one of the shipped languages. */
        public val Default: AppLanguage = English

        /**
         * Maps a BCP-47 language tag to a shipped language, ignoring region, script and
         * case: `"fr"`, `"fr-CA"` and `"FR"` all resolve to [French]; anything else
         * falls back to [Default].
         */
        public fun fromTag(tag: String): AppLanguage {
            val language = tag.substringBefore('-').substringBefore('_').lowercase()
            return entries.firstOrNull { it.tag == language } ?: Default
        }

        /** The shipped language that matches the current device locale. */
        public fun current(): AppLanguage = fromTag(Locale.current.language)
    }
}
