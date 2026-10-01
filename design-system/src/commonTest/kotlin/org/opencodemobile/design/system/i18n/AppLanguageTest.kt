package org.opencodemobile.design.system.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {

    @Test
    fun resolvesFrenchLanguageTags() {
        assertEquals(AppLanguage.French, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.French, AppLanguage.fromTag("FR"))
        assertEquals(AppLanguage.French, AppLanguage.fromTag("fr-CA"))
        assertEquals(AppLanguage.French, AppLanguage.fromTag("fr_CA"))
    }

    @Test
    fun resolvesEnglishLanguageTags() {
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en-US"))
    }

    @Test
    fun fallsBackToDefaultForUnsupportedOrEmptyLocales() {
        assertEquals(AppLanguage.Default, AppLanguage.fromTag("de-DE"))
        assertEquals(AppLanguage.Default, AppLanguage.fromTag(""))
    }
}
