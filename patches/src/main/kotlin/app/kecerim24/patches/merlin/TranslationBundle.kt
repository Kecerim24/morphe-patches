package app.kecerim24.patches.merlin

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The quantity keywords Czech uses, in the order CLDR defines them.
 *
 * Android picks a `<plurals>` item by the language's plural rules, so a Czech translation that
 * declares only `one` and `other` (the two forms English needs) renders the wrong form for
 * 2 to 4 and for decimals.
 */
internal val CZECH_PLURAL_QUANTITIES = listOf("one", "few", "many", "other")

/**
 * The contents of a translation file.
 *
 * @param strings Resource name to translated text, for `<string>` resources.
 * @param plurals Resource name to quantity keyword to translated text, for `<plurals>` resources.
 */
internal class Translations(
    val strings: Map<String, String>,
    val plurals: Map<String, Map<String, String>>
)

/**
 * Reads the translation files bundled into the patch bundle.
 *
 * The file format is a deliberately small subset of YAML, because no YAML library is available
 * when a patch runs: the bundle carries nothing but its own classes, and dependencies of the
 * patches module are never shaded into it. The subset is a flat map of resource name to
 * translated text, plus one nested `plurals` block:
 *
 * ```yaml
 * # About Merlin
 * about_merlin: "O aplikaci Merlin"
 *
 * plurals:
 *   # %d bird / %d birds
 *   bird_count:
 *     one: "%d pták"
 *     few: "%d ptáci"
 *     many: "%d ptáka"
 *     other: "%d ptáků"
 * ```
 *
 * The comment above each entry carries the app's English text, so the file can be reviewed
 * without the APK at hand. An entry with a blank value counts as untranslated and is left
 * alone, which makes a partially translated file valid.
 */
internal object TranslationBundle {

    private val QUOTED_ESCAPE = Regex("""\\(u[0-9a-fA-F]{4}|.)""")

    /**
     * Matches a single `java.util.Formatter` conversion, including a positional argument index
     * (`%1${'$'}s`) and an escaped percent sign (`%%`).
     */
    private val FORMAT_SPECIFIER = Regex("""%(?:%|(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z])""")

    /**
     * Reads a translation file from the patch bundle.
     *
     * @param resourcePath The path of the file inside the bundle, such as
     *   `translations/merlin/cs.yaml`.
     */
    fun load(resourcePath: String): Translations {
        val stream = TranslationBundle::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: throw PatchException("Translation file \"$resourcePath\" is missing from the patch bundle")

        return stream.use(::parse)
    }

    /**
     * Parses the YAML subset described in [TranslationBundle].
     */
    fun parse(inputStream: InputStream): Translations {
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, MutableMap<String, String>>()

        var inPluralsBlock = false
        var currentPlural: MutableMap<String, String>? = null

        inputStream.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val lineNumber = index + 1
                val line = rawLine.trimEnd()

                if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed

                val indent = line.length - line.trimStart().length
                if (line.take(indent).contains('\t')) {
                    throw PatchException("Line $lineNumber is indented with a tab; use spaces")
                }

                val content = line.trim()

                when (indent) {
                    0 -> {
                        val (key, rest) = splitKey(content, lineNumber)
                        if (key == "plurals") {
                            if (rest.isNotBlank()) {
                                throw PatchException("Line $lineNumber: \"plurals\" introduces a block, not a value")
                            }
                            inPluralsBlock = true
                            currentPlural = null
                        } else {
                            inPluralsBlock = false
                            currentPlural = null
                            scalar(rest, lineNumber)?.let { strings[key] = it }
                        }
                    }

                    2 -> {
                        if (!inPluralsBlock) {
                            throw PatchException("Line $lineNumber is indented but is not inside the \"plurals\" block")
                        }
                        val (key, rest) = splitKey(content, lineNumber)
                        if (rest.isNotBlank()) {
                            throw PatchException("Line $lineNumber: plural \"$key\" takes indented quantities")
                        }
                        currentPlural = plurals.getOrPut(key) { linkedMapOf() }
                    }

                    4 -> {
                        val quantities = currentPlural
                            ?: throw PatchException("Line $lineNumber declares a quantity outside of a plural")
                        val (key, rest) = splitKey(content, lineNumber)
                        if (key !in CZECH_PLURAL_QUANTITIES) {
                            throw PatchException(
                                "Line $lineNumber: \"$key\" is not one of the Czech quantities " +
                                    CZECH_PLURAL_QUANTITIES.joinToString(", ")
                            )
                        }
                        scalar(rest, lineNumber)?.let { quantities[key] = it }
                    }

                    else -> throw PatchException("Line $lineNumber is indented by $indent spaces; expected 0, 2 or 4")
                }
            }
        }

        // A plural whose quantities were all left blank is untranslated, not an empty plural.
        return Translations(strings, plurals.filterValues { it.isNotEmpty() })
    }

    private fun splitKey(content: String, lineNumber: Int): Pair<String, String> {
        val separator = content.indexOf(':')
        if (separator <= 0) throw PatchException("Line $lineNumber is not a \"key: value\" pair")

        val key = content.substring(0, separator).trim()
        if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' }) {
            throw PatchException("Line $lineNumber: \"$key\" is not a valid resource name")
        }

        return key to content.substring(separator + 1)
    }

    /**
     * Reads the value of a `key: value` pair. Returns null when the value is absent or blank,
     * which marks the entry as untranslated.
     */
    private fun scalar(rest: String, lineNumber: Int): String? {
        val trimmed = rest.trim()
        if (trimmed.isEmpty()) return null

        val value = when {
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
                unescapeQuoted(trimmed.substring(1, trimmed.length - 1), lineNumber)

            trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'') ->
                trimmed.substring(1, trimmed.length - 1).replace("''", "'")

            trimmed.startsWith('"') || trimmed.startsWith('\'') ->
                throw PatchException("Line $lineNumber has an unterminated quoted value")

            // An unquoted value ends at a trailing comment, the way YAML defines it.
            else -> trimmed.substringBefore(" #").trim()
        }

        return value.ifBlank { null }
    }

    private fun unescapeQuoted(text: String, lineNumber: Int): String =
        QUOTED_ESCAPE.replace(text) { match ->
            val escape = match.groupValues[1]
            when {
                escape == "\\" -> "\\"
                escape == "\"" -> "\""
                escape == "n" -> "\n"
                escape == "t" -> "\t"
                escape == "r" -> "\r"
                escape.startsWith("u") -> escape.substring(1).toInt(16).toChar().toString()
                else -> throw PatchException("Line $lineNumber has an unsupported escape \"\\$escape\"")
            }
        }

    /**
     * Escapes text the way the patcher expects to find it in a `strings.xml` of the decoded app.
     *
     * The patcher escapes every `strings.xml` right after decoding the app and unescapes them
     * again right before encoding it, so text a patch writes has to already be in the escaped
     * form or the unescape pass mangles it. This mirrors the patcher's
     * `StringsXmlEscapeProcessor`.
     *
     * Only files named `strings.xml` are treated this way, so `plurals.xml` is written as plain
     * text.
     */
    fun escapeForStringsXml(text: String): String = buildString(text.length) {
        for (character in text) {
            when (character) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else ->
                    if (character.code in 0x20..0x7E) {
                        append(character)
                    } else {
                        append("\\u")
                        append(character.code.toString(16).uppercase().padStart(4, '0'))
                    }
            }
        }
    }

    /**
     * The format conversions of [text], sorted, so a translation can be checked for having kept
     * the arguments of the source text. A translation that drops or mistypes one crashes the app
     * with an `IllegalFormatException` the moment the string is formatted.
     */
    fun formatSpecifiersOf(text: String): List<String> =
        FORMAT_SPECIFIER.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()
}

/**
 * Parses a resource file of the decoded app without marking it as modified.
 *
 * [ResourcePatchContext.document] writes the document back out when it is closed, which is not
 * wanted for a file that is only being read.
 */
private fun ResourcePatchContext.readOnlyDocument(file: File): org.w3c.dom.Document =
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).also { it.normalize() }

/**
 * The `<string>` resources of a resource file, as a map of resource name to text.
 *
 * The text is in the patcher's escaped form, see [TranslationBundle.escapeForStringsXml].
 */
internal fun ResourcePatchContext.readStrings(path: String): Map<String, String> {
    val file = get(path, copy = false)
    if (!file.exists()) return emptyMap()

    val document = readOnlyDocument(file)
    val nodes = document.getElementsByTagName("string")

    return (0 until nodes.length).mapNotNull { index ->
        val element = nodes.item(index) as? Element ?: return@mapNotNull null
        val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        name to element.textContent
    }.toMap()
}

/**
 * Creates a resource file with an empty `<resources>` root if the app does not have it yet.
 *
 * A configuration the app does not translate at all has no file in the decoded resources, but
 * one it translates in part does. Merlin, for instance, already carries 198 Czech strings from
 * AndroidX and Material, so `res/values-cs/strings.xml` exists and has to be merged into rather
 * than replaced.
 */
private fun ResourcePatchContext.ensureResourceFile(path: String) {
    val file = get(path, copy = false)
    if (file.exists()) return

    file.parentFile?.mkdirs()
    file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources />\n")
}

private fun org.w3c.dom.Document.elementsByName(tag: String): Map<String, Element> {
    val nodes = getElementsByTagName(tag)

    return (0 until nodes.length).mapNotNull { index ->
        val element = nodes.item(index) as? Element ?: return@mapNotNull null
        element.getAttribute("name").takeIf { it.isNotEmpty() }?.let { it to element }
    }.toMap()
}

/**
 * Writes [values] into the `<string>` resources of [path], replacing the text of the entries
 * that are already there and appending the ones that are not.
 *
 * @return The number of entries written.
 */
internal fun ResourcePatchContext.mergeStrings(path: String, values: Map<String, String>): Int {
    if (values.isEmpty()) return 0
    ensureResourceFile(path)

    document(path).use { document ->
        val resources = document.getElementsByTagName("resources").item(0) as? Element
            ?: throw PatchException("$path has no <resources> root element")
        val existing = document.elementsByName("string")

        values.forEach { (name, text) ->
            val element = existing[name] ?: document.createElement("string").also {
                it.setAttribute("name", name)
                resources.appendChild(it)
            }
            element.textContent = text
        }
    }

    return values.size
}

/**
 * Writes [values] into the `<plurals>` resources of [path]. An existing plural keeps only the
 * quantities of the translation, because the quantities the source language needs are not the
 * quantities the target language needs.
 *
 * @return The number of plurals written.
 */
internal fun ResourcePatchContext.mergePlurals(path: String, values: Map<String, Map<String, String>>): Int {
    if (values.isEmpty()) return 0
    ensureResourceFile(path)

    document(path).use { document ->
        val resources = document.getElementsByTagName("resources").item(0) as? Element
            ?: throw PatchException("$path has no <resources> root element")
        val existing = document.elementsByName("plurals")

        values.forEach { (name, quantities) ->
            val plural = existing[name] ?: document.createElement("plurals").also {
                it.setAttribute("name", name)
                resources.appendChild(it)
            }

            while (plural.hasChildNodes()) plural.removeChild(plural.firstChild)

            CZECH_PLURAL_QUANTITIES.forEach { quantity ->
                val text = quantities[quantity] ?: return@forEach
                plural.appendChild(
                    document.createElement("item").apply {
                        setAttribute("quantity", quantity)
                        textContent = text
                    }
                )
            }
        }
    }

    return values.size
}

/**
 * Adds [locale] to the app's `locales_config.xml`, which is the list Android 13 and above offers
 * in Settings as the app's language.
 *
 * The documents the patcher hands out are parsed without namespace awareness, so the attribute
 * is addressed by its literal `android:name` rather than by namespace. The prefix is already
 * declared on the root element of every app that has this file.
 *
 * @return Whether the locale was added, false if it was already declared.
 */
internal fun ResourcePatchContext.declareLocale(path: String, locale: String): Boolean {
    var added = false

    document(path).use { document ->
        val root = document.documentElement ?: throw PatchException("$path has no root element")
        val locales = document.getElementsByTagName("locale")

        val declared = (0 until locales.length).any { index ->
            (locales.item(index) as? Element)?.getAttribute("android:name") == locale
        }

        if (!declared) {
            root.appendChild(
                document.createElement("locale").apply { setAttribute("android:name", locale) }
            )
            added = true
        }
    }

    return added
}
