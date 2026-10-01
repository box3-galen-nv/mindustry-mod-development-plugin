package mindustrymoddevelopmentplugin.meta

import java.io.File

/**
 * Locates and reads the engine's four metadata files — `mod.json`, `mod.hjson`,
 * `plugin.json`, `plugin.hjson`.
 *
 * [FILE_NAMES] follows the engine's own `metaFiles` order (`Mods.java:34`), which
 * `findMeta()` walks until a file exists (`Mods.java:1047`), so [existingFiles] mirrors the
 * order the game considers them.
 *
 * The engine parses with `Jval.read(...).toString(Jformat.plain)` plus `Json.fromJson`
 * (`Mods.java:1057`). Arc is unavailable at build time, so only the flat subset is supported:
 *
 * - keys at line start or after `{` / `,`;
 * - the triple-quoted, single-quoted, double-quoted and bare scalar forms;
 * - inline and multi-line arrays;
 * - `#` and `//` comments after a bare scalar.
 * Nested objects and conditionals are out — `ModMeta` has none.
 */
internal object ModFileReader {

    /** Metadata file names in the engine's priority order (`Mods.java:34`). */
    val FILE_NAMES = listOf("mod.json", "mod.hjson", "plugin.json", "plugin.hjson")

    /** Metadata files present in [projectDir], in [FILE_NAMES] order. */
    fun existingFiles(projectDir: File): List<File> =
        FILE_NAMES.map { File(projectDir, it) }.filter { it.isFile }

    /**
     * Value start index for [key], or null when the key is absent.
     *
     * Line-anchored keys are tried first — which is what keeps `name` from matching
     * `displayName` — then keys right after `{` or `,` for compact one-line objects.
     * A duplicated key resolves to its last occurrence, like arc/Jval.
     */
    private fun valueStart(text: String, key: String): Int? {
        val name = """(?:"$key"|'$key'|$key)[ \t]*[:=][ \t]*"""

        val lineStart = Regex("""^[ \t]*$name""", RegexOption.MULTILINE)
        lineStart.findAll(text).lastOrNull()?.let { return it.range.last + 1 }

        val inline = Regex("""[{,][ \t]*$name""")
        return inline.findAll(text).lastOrNull()?.range?.last?.plus(1)
    }

    /** Raw value for [key]: the whole bracket block for arrays, otherwise to end of line. */
    private fun valueText(text: String, key: String): String? {
        val start = valueStart(text, key) ?: return null
        var i = start
        while (i < text.length && text[i] != '\n' && text[i].isWhitespace()) i++
        if (i >= text.length) return ""

        // HJSON multi-line string: closing ''' on a later line
        if (text.startsWith("'''", i)) {
            val end = text.indexOf("'''", i + 3)
            return if (end == -1) text.substring(i) else text.substring(i, end + 3)
        }

        // Array: scan to the matching bracket, possibly across lines
        if (text[i] == '[') return balancedArray(text, i)

        val lineEnd = text.indexOf('\n', i)
        return text.substring(i, if (lineEnd == -1) text.length else lineEnd)
    }

    /** Scans an opening bracket to its match, skipping brackets inside quotes; null if unclosed. */
    private fun balancedArray(text: String, start: Int): String? {
        var depth = 0
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (c == '\'' || c == '"') {
                i = skipQuoted(text, i)
                continue
            }
            when (c) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    /** Index just past the quoted run starting at [start]. */
    private fun skipQuoted(text: String, start: Int): Int {
        val quote = text[start]
        if (quote == '\'' && text.startsWith("'''", start)) {
            val end = text.indexOf("'''", start + 3)
            return if (end == -1) text.length else end + 3
        }
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            // Drop an unclosed quote at the newline so later fields survive
            if (c == quote || c == '\n') break
            i++
        }
        return i + 1
    }

    /**
     * Parses one scalar token: unquotes, unescapes, drops comments.
     *
     * A value whose quote is never closed yields null, i.e. "no value here", rather than the truncated
     * text up to the end of the line. Back-fill then keeps the DSL value instead of writing a broken name
     * into the generated metadata.
     */
    private fun parseScalar(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return ""

        if (t.startsWith("'''")) {
            val end = t.indexOf("'''", 3)
            return if (end == -1) null else t.substring(3, end)
        }
        if (t[0] == '\'' || t[0] == '"') {
            val quote = t[0]
            val body = StringBuilder()
            var closed = false
            var i = 1
            while (i < t.length) {
                val c = t[i]
                if (c == '\\' && i + 1 < t.length) {
                    // Escapes have to be decoded, not stripped: the engine decodes them, so a file with
                    // "\u6d4b\u8bd5" for a display name used to be read back as "u6d4bu8bd5" during
                    // metadata back-fill.
                    val next = t[i + 1]
                    when {
                        next == 'n' -> body.append('\n')
                        next == 'r' -> body.append('\r')
                        next == 't' -> body.append('\t')
                        next == 'b' -> body.append('\b')
                        next == 'f' -> body.append('\u000C')
                        next == 'u' && i + 5 < t.length -> {
                            val code = t.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code == null) {
                                body.append(next)
                                i += 2
                            } else {
                                body.append(code.toChar())
                                i += 6
                            }
                            continue
                        }
                        else -> body.append(next)
                    }
                    i += 2
                    continue
                }
                if (c == quote) {
                    closed = true
                    break
                }
                body.append(c)
                i++
            }
            return if (closed) body.toString() else null
        }

        // Bare scalar: cut HJSON comments and anything following on the same line
        // (`{name: x, author: y}`). A bare scalar cannot contain ',' or '}', so this is safe.
        var cut = t.length
        val stops = listOf(t.indexOf('#'), t.indexOf("//"), t.indexOf(','), t.indexOf('}'))
        for (stop in stops) {
            if (stop in 0 until cut) cut = stop
        }
        return t.substring(0, cut).trim()
    }

    /** Splits an array body on top-level commas, ignoring commas inside quotes. */
    private fun splitElements(body: String): List<String> {
        val elements = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\'' || c == '"') {
                val next = skipQuoted(body, i)
                current.append(body, i, next.coerceAtMost(body.length))
                i = next
                continue
            }
            if (c == ',') {
                elements += current.toString()
                current.setLength(0)
                i++
                continue
            }
            current.append(c)
            i++
        }
        elements += current.toString()
        return elements.map(::parseScalar).filterNotNull().filter { it.isNotEmpty() }
    }

    /** String field; null when absent, `""` when empty. */
    fun readString(text: String, key: String): String? =
        valueText(text, key)?.let(::parseScalar)

    /** Boolean field; null when absent or not a boolean. */
    fun readBoolean(text: String, key: String): Boolean? =
        readString(text, key)?.trim()?.lowercase()?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    /** Float field; null when absent or unparsable. */
    fun readFloat(text: String, key: String): Float? =
        readString(text, key)?.trim()?.toFloatOrNull()

    /** String array; null when absent or not an array. */
    fun readStringList(text: String, key: String): List<String>? {
        val value = valueText(text, key)?.trim() ?: return null
        if (!value.startsWith("[") || !value.endsWith("]")) return null
        return splitElements(value.substring(1, value.length - 1))
    }

    /**
     * Reads a string field from the metadata files in [projectDir], in [FILE_NAMES] priority order.
     *
     * Returns null when no file has the key, and also when the value is empty (callers want content).
     */
    fun readMetaValue(projectDir: File, key: String): String? {
        for (file in existingFiles(projectDir)) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            readString(text, key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
