package app.kecerim24.patches.merlin

import app.kecerim24.patches.shared.Constants.COMPATIBILITY_MERLIN
import app.morphe.patcher.patch.resourcePatch
import java.util.logging.Logger

private val logger = Logger.getLogger("CzechTranslationPatch")

/**
 * The locale this patch adds. Everything below is keyed off it, so a second language is a new
 * translation file plus a copy of this patch with a different constant.
 */
private const val LOCALE = "cs"

private const val TRANSLATIONS = "translations/merlin/$LOCALE.yaml"

private const val SOURCE_STRINGS = "res/values/strings.xml"
private const val TRANSLATED_STRINGS = "res/values-$LOCALE/strings.xml"
private const val TRANSLATED_PLURALS = "res/values-$LOCALE/plurals.xml"
private const val LOCALES_CONFIG = "res/xml/locales_config.xml"

/**
 * Adds a Czech translation of Merlin's interface.
 *
 * Merlin ships translations for about thirty locales and Czech is not one of them, so the whole
 * interface falls back to English. The translated text lives in `$TRANSLATIONS` inside the patch
 * bundle rather than in this file, so it can be corrected and released without touching Kotlin;
 * see [TranslationBundle] for the format.
 *
 * The patch does three things:
 *
 * 1. merges the translated strings into the app's `values-cs` configuration. That configuration
 *    already exists and holds 198 Czech strings that AndroidX, Material and ExoPlayer ship, so
 *    the entries are merged in rather than written over;
 * 2. merges the translated plurals, spelled out in all four quantities Czech uses;
 * 3. declares `cs` in the app's `locales_config.xml`.
 *
 * Step 3 is what makes the translation reachable. That file gates the per-app language picker
 * Android 13 and above shows in Settings, and Merlin also parses the very same file itself to
 * build its in-app Settings, App language list. Without the entry the translation would only
 * apply to a device whose own language is Czech.
 *
 * Bird names are deliberately untouched. They are not app resources but rows of a database that
 * Merlin fills from the eBird taxonomy API, and Czech is already one of the 110 languages that
 * API serves, offered under Settings, Common name language.
 */
@Suppress("unused")
val czechTranslationPatch = resourcePatch(
    name = "Czech translation",
    description = "Translates the app's interface into Czech.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MERLIN)

    execute {
        val translations = TranslationBundle.load(TRANSLATIONS)

        // region Drop anything that would not survive contact with the app.

        val sourceStrings = readStrings(SOURCE_STRINGS)
        if (sourceStrings.isEmpty()) {
            logger.warning("$SOURCE_STRINGS has no strings; translating blind")
        }

        val strings = translations.strings.filter { (name, translated) ->
            val source = sourceStrings[name]

            when {
                // The app no longer has this resource. Either the translation file has a typo
                // or the app was updated and dropped the string.
                sourceStrings.isNotEmpty() && source == null -> {
                    logger.warning("Skipping \"$name\": the app has no such string")
                    false
                }

                // A translation that loses a format argument crashes the app the moment the
                // string is formatted, so leaving it in English is the safer failure.
                source != null && TranslationBundle.formatSpecifiersOf(source) !=
                    TranslationBundle.formatSpecifiersOf(translated) -> {
                    logger.warning(
                        "Skipping \"$name\": format arguments " +
                            "${TranslationBundle.formatSpecifiersOf(translated)} do not match " +
                            "the app's ${TranslationBundle.formatSpecifiersOf(source)}"
                    )
                    false
                }

                else -> true
            }
        }

        // endregion

        // region Write the translation.

        // Text of a strings.xml has to be escaped, text of a plurals.xml must not be. See
        // TranslationBundle.escapeForStringsXml.
        val written = mergeStrings(
            TRANSLATED_STRINGS,
            strings.mapValues { (_, text) -> TranslationBundle.escapeForStringsXml(text) }
        )

        val writtenPlurals = mergePlurals(TRANSLATED_PLURALS, translations.plurals)

        logger.info("Translated $written strings and $writtenPlurals plurals into $LOCALE")

        // endregion

        // region Offer the language in the app and in Android's Settings.

        if (get(LOCALES_CONFIG, copy = false).exists()) {
            if (declareLocale(LOCALES_CONFIG, LOCALE)) {
                logger.info("Declared $LOCALE in $LOCALES_CONFIG")
            }
        } else {
            // Not fatal: the translation still applies to a device set to Czech, the language
            // just cannot be picked for this app alone.
            logger.warning("$LOCALES_CONFIG is missing, so $LOCALE cannot be offered as the app's language")
        }

        // endregion
    }
}
