package mindustrymoddevelopmentplugin.dsl

import java.util.Locale
import mindustrymoddevelopmentplugin.meta.ModFileReader

/**
 * Mod metadata, mirroring the engine's `Mods.ModMeta` (`Mods.java:1424`) field by field.
 *
 * 19 fields in total: 18 are configurable and [internalName] is read-only, derived from
 * [name] (it has no key in `mod.hjson`).
 *
 * Serialized to `mod.hjson` / `mod.json` by [toHJson] / [toJson]. Fields still at their
 * default value can be inherited from an existing metadata file via [fillMissingFrom].
 *
 * Configured in `build.gradle.kts`:
 * ```kotlin
 * mindustryMod {
 *     modMeta {
 *         name = "my-mod"
 *         version = "1.0.0"
 *         java = true
 *     }
 * }
 * ```
 *
 * Fields are marked **Required** (the engine rejects the mod without it), **Optional**
 * (the engine supplies a default) or **Recommended** (optional to the engine, but advised
 * by the official sources).
 */
open class ModMeta {

    // ---- Identity ----

    /**
     * Mod identifier — the only **Required** field.
     *
     * Used to identify the mod, to derive [internalName], to auto-derive the main class
     * and as the file name in the mod's directory. Lowercase with hyphens is recommended.
     *
     * The engine silently discards a mod whose `name` is null (`Mods.java:529`); this
     * plugin fails loudly instead — [toHJson] / [toJson] throw, and `jar` reports the
     * missing field.
     */
    var name: String = ""

    /**
     * Display name shown in the mod browser.
     *
     * **Optional**. Where blank, the engine substitutes [name] in `cleanup()`.
     */
    var displayName: String = ""

    /**
     * Short description shown in the mod list.
     *
     * **Optional**. Preferred over [description] by `shortDescription()`; the engine
     * strips colors and removes newlines from it.
     */
    var subtitle: String = ""

    /**
     * Mod author, color-stripped.
     *
     * **Optional**. Shown in the mod browser, and used for the `{author}` placeholder of
     * `build.format` in the jar file name.
     */
    var author: String = ""

    /**
     * Long description shown in the mod browser details dialog.
     *
     * **Optional**. Used by `shortDescription()` as a fallback when [subtitle] is unset
     * and the text is short enough.
     */
    var description: String = ""

    // ---- Entry point and version ----

    /**
     * Fully qualified main class.
     *
     * **Optional**. Auto-derived from [name] when blank:
     * ```
     * camelized = name.replace(" ", "")
     * mainClass = camelized.toLowerCase(Locale.ROOT) + "." + camelized + "Mod"
     * ```
     * (`Mods.java:1140`), e.g. `"Example"` → `"example.ExampleMod"` and
     * `"My Mod"` → `"mymod.MyModMod"`.
     *
     * Set it explicitly when the entry point does not match this pattern.
     */
    var main: String = ""

    /**
     * GitHub repository as `"owner/repo"`, e.g. `"Anuken/Mindustry"`.
     *
     * **Optional**. The browser opens `"https://github.com/" + repo` and uses it to
     * reinstall / import from GitHub Releases. Overridable at runtime — `getRepo()` reads
     * the `mod-<name>-repo` setting first.
     */
    var repo: String = ""

    /**
     * Mod version string.
     *
     * **Optional**. Where blank, the engine substitutes `"0"` in `cleanup()`.
     *
     * Used for multiplayer sync (`name:version`), update checks and the manual blacklist.
     * The engine truncates it at the first newline (`Mods.java:1233`).
     */
    var version: String = ""

    // ---- Compatibility ----

    /**
     * Minimum compatible game build, e.g. `"146"` or `"158.1"`.
     *
     * **Optional**. Where blank, no minimum version is required.
     *
     * A plain `String` upstream (`public String minGameVersion = "0";`). `"146"` and
     * `"146.0"` behave identically because both gates parse only the part before the first
     * dot:
     * - load gate: `Version.isAtLeast(meta.minGameVersion)` (`Mods.java:1198`)
     * - outdated check: `getMinMajor()` (`Mods.java:1346`)
     *
     * Emitted quoted (`'''146'''` / `"146"`); the engine also accepts a bare number.
     */
    var minGameVersion: String = ""

    /**
     * Whether the mod also works on an older major version.
     *
     * **Optional**. Exempts the mod from both minimum-version gates (`Mods.java:1199`
     * and `:1343`).
     */
    var legacyCompatible: Boolean = false

    // ---- Flags ----

    /**
     * Whether to hide the mod from the mod browser.
     *
     * **Optional**.
     *
     * Hidden mods are excluded from multiplayer compatibility lists (`getModStrings()`)
     * and cannot register new content (`loadContent()` is skipped), which suits server-only,
     * library or client-side mods. The engine forces `hidden = true` for `Plugin` mods.
     */
    var hidden: Boolean = false

    /**
     * Whether this is a Java/Kotlin class mod.
     *
     * **Recommended**. Optional to the engine, which also infers it in `isJava()`
     * (`meta.java || main != null || meta.main != null`, `Mods.java:1297`), but setting it
     * explicitly is the most reliable option.
     */
    var java: Boolean = false

    /**
     * Whether this script mod claims iOS compatibility.
     *
     * **Optional**. Only set it to `true` when the mod does not use `extend()` or
     * `JavaAdapter`.
     *
     * The engine declares this field but never reads it from `ModMeta`
     * (`Mods.java:1439`); the iOS warning in the browser uses the separately fetched
     * `ModListing.iosCompatible` instead.
     */
    var iosCompatible: Boolean = false

    /**
     * Texture scale — the pixel size of a 1x1 block sprite.
     *
     * **Optional**. Applied while packing sprites (`packSprites`, `Mods.java:378`);
     * useful for high-resolution texture packs.
     *
     * Serialized under the upstream key `texturescale`, the only non-camelCase field in the
     * engine's `ModMeta`. The Kotlin property is camelCase here; the file key is not.
     */
    var textureScale: Float = 1.0f

    /**
     * Whether content is pregenerated.
     *
     * **Optional**. When `true` the engine skips texture bleeding (edge extension) and
     * automatic content icon generation.
     */
    var pregenerated: Boolean = false

    /**
     * Content load order.
     *
     * **Optional**. Content loads alphabetically by file name unless an order is given.
     *
     * Array of content file basename without extension, e.g.
     * `["stone-wall", "stone-floor"]`. Listed files load first, in this order; the rest
     * follow alphabetically. Use it to control initialization order when some content
     * depends on other content.
     */
    var contentOrder: Array<String>? = null

    /**
     * Mod internal name — read-only, derived from [name]. Not a `mod.hjson` key.
     *
     * Mirrors the engine's `cleanup()` rule: `name.toLowerCase(Locale.ROOT).replace(" ", "-")`
     * — lowercase, spaces replaced by hyphens (not removed), e.g. `"My Mod"` → `"my-mod"`.
     *
     * The engine maps mods by this value when resolving the dependency graph
     * (`orderedMods()`), so [dependencies] and [softDependencies] must use it.
     */
    val internalName: String
        get() = name.lowercase(Locale.ROOT).replace(" ", "-")

    // ---- Dependencies ----

    /**
     * Hard dependency names.
     *
     * **Optional**. Each entry is the [internalName] of another mod, e.g. `"my-mod"` for
     * a mod whose `name` is `"My Mod"`.
     *
     * Hard dependencies load before this mod; a missing one blocks loading and the game
     * offers to import it from GitHub.
     *
     * ```kotlin
     * dependencies += "my-library"
     * dependencies = listOf("my-library", "other-mod")
     * ```
     */
    var dependencies: List<String> = emptyList()

    /**
     * Soft (optional) dependency names.
     *
     * **Optional**, same format as [dependencies].
     *
     * A missing soft dependency only shows a dismissible prompt instead of blocking
     * loading. Mod code can test for it at runtime through `Vars.mods`.
     *
     * ```kotlin
     * softDependencies += "optional-lib"
     * ```
     */
    var softDependencies: List<String> = emptyList()

    // ---- Back-fill from an existing metadata file ----

    /**
     * Back-fills every field that is still at its default value from the text of an
     * existing `mod.hjson` / `mod.json`.
     *
     * DSL values always win: only fields equal to their default are inherited, so enabling
     * `generateModMeta` does not drop metadata that exists only in the file.
     *
     * Because "unset" means "equal to the default", a field explicitly set *to* its default
     * cannot override the file value (e.g. `hidden = false` does not beat `hidden: true`).
     *
     * Parsing capabilities and limits: see [ModFileReader]. Idempotent for a given text.
     */
    fun fillMissingFrom(text: String) {
        fun fillString(key: String, current: String, assign: (String) -> Unit) {
            if (current.isNotBlank()) return
            ModFileReader.readString(text, key)?.takeIf { it.isNotBlank() }?.let(assign)
        }

        fun fillBoolean(key: String, current: Boolean, assign: (Boolean) -> Unit) {
            if (current) return
            ModFileReader.readBoolean(text, key)?.let(assign)
        }

        fillString("name", name) { name = it }
        fillString("displayName", displayName) { displayName = it }
        fillString("subtitle", subtitle) { subtitle = it }
        fillString("author", author) { author = it }
        fillString("description", description) { description = it }
        fillString("main", main) { main = it }
        fillString("repo", repo) { repo = it }
        fillString("version", version) { version = it }
        fillString("minGameVersion", minGameVersion) { minGameVersion = it }

        fillBoolean("legacyCompatible", legacyCompatible) { legacyCompatible = it }
        fillBoolean("hidden", hidden) { hidden = it }
        fillBoolean("java", java) { java = it }
        fillBoolean("iosCompatible", iosCompatible) { iosCompatible = it }
        fillBoolean("pregenerated", pregenerated) { pregenerated = it }

        if (textureScale == 1.0f) {
            ModFileReader.readFloat(text, "texturescale")?.let { textureScale = it }
        }
        if (contentOrder == null) {
            ModFileReader.readStringList(text, "contentOrder")?.let { contentOrder = it.toTypedArray() }
        }
        if (dependencies.isEmpty()) {
            ModFileReader.readStringList(text, "dependencies")?.let { dependencies = it }
        }
        if (softDependencies.isEmpty()) {
            ModFileReader.readStringList(text, "softDependencies")?.let { softDependencies = it }
        }
    }

    /**
     * Stable snapshot of every configurable field, used as a Gradle task input fingerprint
     * (the up-to-date check of `buildModHJson`).
     *
     * Unlike [toHJson] / [toJson] it never throws when [name] is blank, because the
     * fingerprint is taken before the back-fill runs. Any field change alters the result;
     * a reflection-based test guards against forgetting a newly added field.
     */
    internal fun inputSnapshot(): String = listOf(
        name, displayName, subtitle, author, description, main, repo, version,
        minGameVersion, legacyCompatible, hidden, java, iosCompatible, textureScale,
        pregenerated, contentOrder?.toList(), dependencies, softDependencies,
        /*
        Length-prefixed rather than NUL-separated: a field value could otherwise contain the
        separator and make two different metadata sets produce the same fingerprint, which would
        leave buildModHJson UP-TO-DATE with a stale file.
        */
    ).joinToString("") { value -> value.toString().let { "${it.length}:$it" } }

    // ---- Serialization ----

    /**
     * Serializes this metadata to HJSON, the format of `mod.hjson`.
     *
     * Fields that are blank or still at their default are omitted to keep the file small.
     * Strings and array elements are wrapped in HJSON triple quotes, so arrays look like
     * `['''a''', '''b''']` (verified to parse with arc v159).
     *
     * @throws IllegalStateException if the required field ([name]) is blank.
     */
    fun toHJson(): String {
        val missing = mutableListOf<String>()
        if (name.isBlank()) missing.add("name")

        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "mod.hjson generation failed: required fields are missing or empty: ${missing.joinToString(", ")}. " +
                "Please set them in the mindustryMod { modMeta { ... } } block."
            )
        }

        /**
         * HJSON string literal: triple quotes `'''...'''` preferred. A value containing
         * `'''` cannot be escaped inside a triple-quoted string, so it falls back to a
         * single-quoted literal with `\` and `'` escaped.
         */
        fun quote(s: String): String =
            /*
            A triple-quoted literal ends at the first `'''`, so *any* single quote forces the escaped
            form: a value ending in one (say `abc'`) would otherwise become `'''abc''''`, which the
            engine reads as `abc` plus junk. Mods.load() swallows that failure, so the mod would just
            never show up in the game.
            */
            if (s.contains('\'')) {
                "'${s.replace("\\", "\\\\").replace("'", "\\'")}'"
            } else {
                "'''$s'''"
            }

        return buildString {
            // Blank entries are dropped: an empty triple-quoted literal makes the engine's parser read
            // past the end of the string, and an empty dependency name means nothing.
            fun List<String>.format() = filter { it.isNotBlank() }
                .joinToString(prefix = "[", separator = ", ") { quote(it) } + "]"
            fun Array<String>.format() = filter { it.isNotBlank() }
                .joinToString(prefix = "[", separator = ", ") { quote(it) } + "]"

            appendLine("name: ${quote(name)}")
            if (displayName.isNotBlank()) appendLine("displayName: ${quote(displayName)}")
            if (subtitle.isNotBlank()) appendLine("subtitle: ${quote(subtitle)}")
            if (author.isNotBlank()) appendLine("author: ${quote(author)}")
            if (description.isNotBlank()) appendLine("description: ${quote(description)}")
            if (main.isNotBlank()) appendLine("main: ${quote(main)}")
            if (repo.isNotBlank()) appendLine("repo: ${quote(repo)}")
            if (version.isNotBlank()) appendLine("version: ${quote(version)}")
            if (minGameVersion.isNotBlank()) appendLine("minGameVersion: ${quote(minGameVersion)}")
            appendLine("legacyCompatible: $legacyCompatible")
            appendLine("hidden: $hidden")
            appendLine("java: $java")
            if (iosCompatible) appendLine("iosCompatible: true")
            /*
            Float.toString() is locale-independent (always a '.'); never rewrite this with
            String.format without Locale.ROOT, or a German locale would emit "2,5" and the game
            would silently fail to read the metadata.
            */
            if (textureScale != 1.0f) appendLine("texturescale: $textureScale")
            if (pregenerated) appendLine("pregenerated: true")
            val co = contentOrder; if (!co.isNullOrEmpty()) appendLine("contentOrder: ${co.format()}")
            if (dependencies.isNotEmpty()) appendLine("dependencies: ${dependencies.format()}")
            if (softDependencies.isNotEmpty()) appendLine("softDependencies: ${softDependencies.format()}")
        }
    }

    /**
     * Serializes this metadata to strict JSON, the format of `mod.json`.
     *
     * Fields that are blank or still at their default are omitted. Strings are double-quoted
     * and escaped, arrays look like `["a", "b"]`, and every comma is written at the
     * end of its line so strict parsers accept the output.
     *
     * @throws IllegalStateException if the required field ([name]) is blank.
     */
    fun toJson(): String {
        val missing = mutableListOf<String>()
        if (name.isBlank()) missing.add("name")

        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "mod.json generation failed: required fields are missing or empty: ${missing.joinToString(", ")}. " +
                "Please set them in the mindustryMod { modMeta { ... } } block."
            )
        }

        /**
         * A strict JSON string literal.
         *
         * Control characters have to be escaped: a raw newline made the generated `mod.json` invalid,
         * which the engine tolerated but `jq`, IDEs and this plugin's own reader did not.
         */
        fun q(s: String) = buildString {
            append('"')
            for (c in s) {
                when {
                    c == '\\' -> append("\\\\")
                    c == '"' -> append("\\\"")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c == '\b' -> append("\\b")
                    c == '\u000C' -> append("\\f")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

        // Collect fields in a fixed order to emit valid strict JSON: commas belong at the
        // end of each line, not at the start, so strict parsers accept the result.
        val fields = mutableListOf<String>()
        fields += "\"name\": ${q(name)}"
        if (displayName.isNotBlank()) fields += "\"displayName\": ${q(displayName)}"
        if (subtitle.isNotBlank()) fields += "\"subtitle\": ${q(subtitle)}"
        if (author.isNotBlank()) fields += "\"author\": ${q(author)}"
        if (description.isNotBlank()) fields += "\"description\": ${q(description)}"
        if (main.isNotBlank()) fields += "\"main\": ${q(main)}"
        if (repo.isNotBlank()) fields += "\"repo\": ${q(repo)}"
        if (version.isNotBlank()) fields += "\"version\": ${q(version)}"
        if (minGameVersion.isNotBlank()) fields += "\"minGameVersion\": ${q(minGameVersion)}"
        fields += "\"legacyCompatible\": $legacyCompatible"
        fields += "\"hidden\": $hidden"
        fields += "\"java\": $java"
        if (iosCompatible) fields += "\"iosCompatible\": true"
        // Locale-independent for the same reason as the HJSON path above.
        if (textureScale != 1.0f) fields += "\"texturescale\": $textureScale"
        if (pregenerated) fields += "\"pregenerated\": true"
        val co = contentOrder
        if (!co.isNullOrEmpty()) {
            fields += "\"contentOrder\": " + co.filter { it.isNotBlank() }
                .joinToString(prefix = "[", separator = ", ") { q(it) } + "]"
        }
        if (dependencies.isNotEmpty()) {
            fields += "\"dependencies\": " + dependencies.filter { it.isNotBlank() }
                .joinToString(prefix = "[", separator = ", ") { q(it) } + "]"
        }
        if (softDependencies.isNotEmpty()) {
            fields += "\"softDependencies\": " + softDependencies.filter { it.isNotBlank() }
                .joinToString(prefix = "[", separator = ", ") { q(it) } + "]"
        }

        return buildString {
            appendLine("{")
            fields.forEachIndexed { index, field ->
                appendLine(if (index == fields.lastIndex) "  $field" else "  $field,")
            }
            appendLine("}")
        }
    }
}
