package org.opencodemobile.design.system.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FR/EN parity for the design-system `composeResources` catalogues.
 *
 * The copy is the project's single source of truth, so a key added to `values/`
 * without its `values-fr/` counterpart (or vice versa) is a user-visible defect:
 * Compose Multiplatform silently falls back to the default locale for the
 * missing translation. This test walks every `values/` catalogue and asserts:
 *
 *  1. a `values-fr/` file with the exact same key set exists;
 *  2. no value is blank;
 *  3. the keys of the local-access and connection surfaces are actually
 *     translated (FR value differs from EN) — those were the hardcoded/migrated
 *     surfaces this guard exists for.
 *
 * It lives in `androidUnitTest` because it reads the source `.xml` files, which
 * `commonTest` cannot do on Kotlin/Native. `scripts/test.sh android` runs it.
 */
class SettingsStringsCatalogParityTest {

    private val catalogRoot: File =
        File(System.getProperty("user.dir"), "src/commonMain/composeResources")

    private val defaultDir: File = File(catalogRoot, "values")
    private val frenchDir: File = File(catalogRoot, "values-fr")

    /** Prefixes whose keys must have a real FR translation, not an EN copy. */
    private val mustBeTranslated: List<String> = listOf(
        "connection_",
        "settings_open",
        "settings_back",
        "settings_local_access_",
        "settings_optional_",
        "settings_multitask_",
        "settings_screen_capture_",
    )

    @Test
    fun everyDefaultCatalogueHasAFrenchCounterpartWithTheSameKeys() {
        assertTrue(defaultDir.isDirectory, "composeResources/values not found at ${defaultDir.absolutePath}")

        val defaultFiles = defaultDir.listFiles { file -> file.isFile && file.name.endsWith(".xml") }.orEmpty()
        assertTrue(defaultFiles.isNotEmpty(), "no catalogue files under ${defaultDir.absolutePath}")

        for (file in defaultFiles) {
            val french = File(frenchDir, file.name)
            assertTrue(
                french.isFile,
                "missing French catalogue: values-fr/${file.name} (add it to keep FR/EN parity)",
            )

            val englishStrings = parseStrings(file)
            val frenchStrings = parseStrings(french)

            assertEquals(
                englishStrings.keys,
                frenchStrings.keys,
                "FR/EN key mismatch in ${file.name}: translate the missing keys or remove the extra ones",
            )
            assertTrue(englishStrings.values.none { it.isBlank() }, "blank EN value in ${file.name}")
            assertTrue(frenchStrings.values.none { it.isBlank() }, "blank FR value in ${file.name}")

            englishStrings.forEach { (key, english) ->
                if (mustBeTranslated.any { key.startsWith(it) }) {
                    assertNotEquals(
                        english,
                        frenchStrings.getValue(key),
                        "$key is not translated in values-fr/${file.name}",
                    )
                }
            }
        }
    }

    private fun parseStrings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        document.documentElement.normalize()
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index)
                val name = element.attributes.getNamedItem("name").nodeValue
                put(name, element.textContent)
            }
        }
    }
}
