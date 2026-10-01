package mindustrymoddevelopmentplugin.meta

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModMetaTest {

    // =========================================================================
    //  toHJson
    // =========================================================================

    @Test
    fun `toHJson outputs name`() {
        val info = ModMeta().apply { name = "test-mod"; java = true }
        val hjson = info.toHJson()
        assertTrue(hjson.contains("name: '''test-mod'''"))
    }
    @Test
    fun `toHJson outputs all populated fields`() {
        val info = ModMeta().apply {
            name = "test-mod"
            displayName = "Test Mod Display"
            subtitle = "A test"
            author = "tester"
            description = "Description here"
            main = "mymod.TestMod"
            repo = "tester/test-mod"
            version = "1.0.0"
            minGameVersion = "146"
            hidden = true
            java = true
            iosCompatible = true
            textureScale = 2.0f
            pregenerated = true
            dependencies += "dep-a"
            dependencies += "dep-b"
            softDependencies += "opt-dep"
        }
        val hjson = info.toHJson()
        assertTrue(hjson.contains("name: '''test-mod'''"))
        assertTrue(hjson.contains("displayName: '''Test Mod Display'''"))
        assertTrue(hjson.contains("subtitle: '''A test'''"))
        assertTrue(hjson.contains("author: '''tester'''"))
        assertTrue(hjson.contains("description: '''Description here'''"))
        assertTrue(hjson.contains("main: '''mymod.TestMod'''"))
        assertTrue(hjson.contains("repo: '''tester/test-mod'''"))
        assertTrue(hjson.contains("version: '''1.0.0'''"))
        assertTrue(hjson.contains("minGameVersion: '''146'''"))
        assertTrue(hjson.contains("hidden: true"))
        assertTrue(hjson.contains("java: true"))
        assertTrue(hjson.contains("iosCompatible: true"))
        assertTrue(hjson.contains("pregenerated: true"))
        assertTrue(hjson.contains("dependencies:"))
        assertTrue(hjson.contains("softDependencies:"))
    }
    @Test
    fun `toHJson omits empty optional fields`() {
        val info = ModMeta().apply { name = "minimal"; java = true }
        val hjson = info.toHJson()
        assertTrue(!hjson.contains("displayName"))
        assertTrue(!hjson.contains("subtitle"))
        assertTrue(!hjson.contains("author"))
        assertTrue(!hjson.contains("description"))
        assertTrue(!hjson.contains("main"))
        assertTrue(!hjson.contains("repo"))
        assertTrue(!hjson.contains("version"))
        assertTrue(!hjson.contains("contentOrder"))
        assertTrue(!hjson.contains("dependencies:"))
        assertTrue(!hjson.contains("softDependencies:"))
        assertTrue(!hjson.contains("iosCompatible"))
        assertTrue(!hjson.contains("pregenerated"))
    }
    @Test
    fun `toHJson includes boolean defaults`() {
        val info = ModMeta().apply { name = "minimal"; java = true }
        val hjson = info.toHJson()
        assertTrue(hjson.contains("legacyCompatible: false"))
        assertTrue(hjson.contains("hidden: false"))
    }
    @Test
    fun `toHJson throws when name is blank`() {
        val info = ModMeta().apply { name = "  " }
        assertThrows<IllegalStateException> { info.toHJson() }
    }

    @Test
    fun `toHJson does not require java flag`() {
        val info = ModMeta().apply { name = "script-mod" }
        val hjson = info.toHJson()
        assertTrue(hjson.contains("java: false"))
    }
    // =========================================================================
    //  toJson
    // =========================================================================

    @Test
    fun `toJson outputs name`() {
        val info = ModMeta().apply { name = "test-mod"; java = true }
        val json = info.toJson()
        assertTrue(json.contains("\"name\": \"test-mod\""))
    }
    @Test
    fun `toJson outputs all populated fields`() {
        val info = ModMeta().apply {
            name = "test-mod"
            displayName = "Test Mod"
            author = "tester"
            version = "1.0.0"
            java = true
        }
        val json = info.toJson()
        assertTrue(json.contains("\"name\": \"test-mod\""))
        assertTrue(json.contains("\"displayName\": \"Test Mod\""))
        assertTrue(json.contains("\"author\": \"tester\""))
        assertTrue(json.contains("\"version\": \"1.0.0\""))
        assertTrue(json.contains("\"java\": true"))
    }
    @Test
    fun `toJson omits empty optional fields`() {
        val info = ModMeta().apply { name = "minimal"; java = true }
        val json = info.toJson()
        assertTrue(!json.contains("\"displayName\""))
        assertTrue(!json.contains("\"author\""))
        assertTrue(!json.contains("\"version\""))
        assertTrue(!json.contains("\"contentOrder\""))
        assertTrue(!json.contains("\"dependencies\":"))
        assertTrue(!json.contains("\"softDependencies\":"))
    }
    @Test
    fun `toJson throws when name is blank`() {
        val info = ModMeta().apply { name = "  " }
        assertThrows<IllegalStateException> { info.toJson() }
    }

    @Test
    fun `toJson escapes special characters`() {
        val info = ModMeta().apply { name = "test\"mod\\"; java = true }
        val json = info.toJson()
        assertTrue(json.contains("\"name\": \"test\\\"mod\\\\\""))
    }

    // =========================================================================
    //  Bug3 regression: standard JSON (no leading commas)
    // =========================================================================

    @Test
    fun `toJson has no leading commas`() {
        val info = ModMeta().apply {
            name = "test-mod"
            displayName = "Display"
            author = "tester"
            version = "1.0"
            java = true
        }
        val json = info.toJson()
        val lines = json.lines().filter { it.isNotBlank() }
        // Standard JSON forbids leading commas (the old implementation emitted "," at the start of every line)
        assertTrue(lines.none { it.trimStart().startsWith(",") }, "JSON must not contain leading commas:\n$json")
        assertTrue(lines.last() == "}")
        // Middle field lines: every one but the last ends with a comma
        val fieldLines = lines.drop(1).dropLast(1)
        if (fieldLines.size > 1) {
            fieldLines.dropLast(1).forEach { line ->
                assertTrue(line.trimEnd().endsWith(","), "Field line must end with comma: $line")
            }
            assertTrue(!fieldLines.last().trimEnd().endsWith(","), "Last field must not end with comma: ${fieldLines.last()}")
        }
    }

    // =========================================================================
    //  Bug4 regression: toHJson escapes values containing triple quotes
    // =========================================================================

    @Test
    fun `toHJson escapes triple quotes in values`() {
        val info = ModMeta().apply { name = "a'''b"; java = true }
        val hjson = info.toHJson()
        // ''' cannot be escaped inside a triple-quoted string, so it degrades to a single-quoted string with escapes
        assertTrue(hjson.contains("name: 'a\\'\\'\\'b'"), "Triple quotes must be escaped: $hjson")
    }

    // =========================================================================
    //  internalName — matches the derivation rule of the engine's cleanup()
    // =========================================================================

    @Test
    fun `internalName lowercases name and replaces spaces with hyphens`() {
        val meta = ModMeta().apply { name = "My Mod" }
        assertTrue(meta.internalName == "my-mod", "My Mod -> my-mod, got '${meta.internalName}'")
    }

    @Test
    fun `internalName keeps existing hyphens and removes nothing`() {
        // Upstream is replace(" ", "-"), not space removal: consecutive spaces become consecutive hyphens
        val meta = ModMeta().apply { name = "My  Mod-2" }
        assertTrue(meta.internalName == "my--mod-2", "got '${meta.internalName}'")
    }

    @Test
    fun `internalName is empty for empty name`() {
        assertTrue(ModMeta().internalName == "")
    }

    @Test
    fun `internalName is not serialized`() {
        val hjson = ModMeta().apply { name = "my-mod"; java = true }.toHJson()
        assertTrue(!hjson.contains("internalName"), "internalName is engine-derived, not a mod.hjson key: $hjson")
    }

    // =========================================================================
    //  minGameVersion — upstream is a String, so the output must be quoted
    // =========================================================================

    @Test
    fun `minGameVersion is emitted as a quoted string`() {
        val meta = ModMeta().apply { name = "m"; minGameVersion = "158.1" }
        assertTrue(meta.toHJson().contains("minGameVersion: '''158.1'''"), meta.toHJson())
        assertTrue(meta.toJson().contains("\"minGameVersion\": \"158.1\""), meta.toJson())
    }

    @Test
    fun `minGameVersion preserves integer form without decimal point`() {
        // Regression: a Float implementation would emit 146 as 146.0
        val hjson = ModMeta().apply { name = "m"; minGameVersion = "146" }.toHJson()
        assertTrue(hjson.contains("minGameVersion: '''146'''"), hjson)
    }

    @Test
    fun `minGameVersion is omitted when blank`() {
        val meta = ModMeta().apply { name = "m"; java = true }
        assertTrue(!meta.toHJson().contains("minGameVersion"))
        assertTrue(!meta.toJson().contains("minGameVersion"))
    }

    // =========================================================================
    //  textureScale — the Kotlin property is camelCase, but the file key keeps upstream's texturescale
    // =========================================================================

    @Test
    fun `textureScale keeps the upstream texturescale file key`() {
        val meta = ModMeta().apply { name = "m"; textureScale = 2.5f }
        val hjson = meta.toHJson()
        val json = meta.toJson()
        assertTrue(hjson.contains("texturescale: 2.5"), hjson)
        assertTrue(json.contains("\"texturescale\": 2.5"), json)
        assertTrue(!hjson.contains("textureScale"), "must not emit the camelCase property name: $hjson")
        assertTrue(!json.contains("textureScale"), "must not emit the camelCase property name: $json")
    }

    @Test
    fun `textureScale default is omitted from output`() {
        val meta = ModMeta().apply { name = "m"; java = true }
        assertTrue(!meta.toHJson().contains("texturescale"))
        assertTrue(!meta.toJson().contains("texturescale"))
    }

    // =========================================================================
    //  dependencies / softDependencies — support whole-list assignment and +=
    // =========================================================================

    @Test
    fun `dependencies accepts whole-list assignment`() {
        val meta = ModMeta().apply {
            name = "m"
            dependencies = listOf("a", "b")
            softDependencies = listOf("c")
        }
        val hjson = meta.toHJson()
        // Array elements use triple single quotes like scalars (verified to parse correctly with arc v159)
        assertTrue(hjson.contains("dependencies: ['''a''', '''b''']"), hjson)
        assertTrue(hjson.contains("softDependencies: ['''c''']"), hjson)
    }

    @Test
    fun `dependencies also supports plus-equals`() {
        val meta = ModMeta().apply {
            name = "m"
            dependencies += "a"
            dependencies += "b"
        }
        assertTrue(meta.dependencies == listOf("a", "b"), "got ${meta.dependencies}")
    }

    // =========================================================================
    //  inputSnapshot (task fingerprint)
    // =========================================================================

    @Test
    fun `the fingerprint cannot be forged with a separator inside a value`() {
        // With the previous "\u0000"-joined string these two produced the same fingerprint, so a
        // changed field could leave buildModHJson UP-TO-DATE.
        val first = ModMeta().apply { name = "a"; version = "b\u0000c" }
        val second = ModMeta().apply { name = "a\u0000b"; version = "c" }

        assertTrue(first.inputSnapshot() != second.inputSnapshot())
    }

    // =========================================================================
    //  locale independence
    // =========================================================================

    @Test
    fun `texturescale is written with a dot regardless of the default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val meta = ModMeta().apply { name = "mod"; textureScale = 2.5f }

            val hjson = meta.toHJson()
            val json = meta.toJson()
            assertTrue(hjson.contains("texturescale: 2.5"), hjson)
            assertTrue(json.contains("\"texturescale\": 2.5"), json)
            assertTrue(!hjson.contains("2,5") && !json.contains("2,5"), "a comma would break the engine")
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // =========================================================================
    //  fillMissingFrom — back-fill from an existing mod file
    // =========================================================================

    private val existingMetaFile = """
        name: '''file-mod'''
        displayName: '''From File'''
        author: '''file-author'''
        version: '''2.0'''
        minGameVersion: 146
        hidden: true
        texturescale: 2.5
        pregenerated: true
        contentOrder: ['b', 'a']
        dependencies: ['dep-a', 'dep-b']
        softDependencies: ['opt']
    """.trimIndent()

    @Test
    fun `fillMissingFrom inherits unset fields and keeps DSL values`() {
        val meta = ModMeta().apply {
            name = "dsl-mod"
            displayName = "DSL Display"
            java = true
        }
        meta.fillMissingFrom(existingMetaFile)

        assertTrue(meta.name == "dsl-mod", "DSL name must win")
        assertTrue(meta.displayName == "DSL Display", "DSL displayName must win")
        assertTrue(meta.java, "DSL java must win")
        assertTrue(meta.author == "file-author")
        assertTrue(meta.version == "2.0")
        assertTrue(meta.minGameVersion == "146")
        assertTrue(meta.hidden)
        assertTrue(meta.textureScale == 2.5f, "got ${meta.textureScale}")
        assertTrue(meta.pregenerated)
        assertTrue(meta.contentOrder?.toList() == listOf("b", "a"), "got ${meta.contentOrder?.toList()}")
        assertTrue(meta.dependencies == listOf("dep-a", "dep-b"), "got ${meta.dependencies}")
        assertTrue(meta.softDependencies == listOf("opt"), "got ${meta.softDependencies}")
    }

    @Test
    fun `fillMissingFrom keeps values already present in DSL`() {
        val meta = ModMeta().apply {
            name = "m"
            author = "dsl-author"
            hidden = true
            dependencies = listOf("dsl-dep")
        }
        meta.fillMissingFrom(existingMetaFile)
        assertTrue(meta.author == "dsl-author")
        assertTrue(meta.dependencies == listOf("dsl-dep"))
    }

    @Test
    fun `fillMissingFrom is idempotent`() {
        val meta = ModMeta()
        meta.fillMissingFrom(existingMetaFile)
        val once = meta.toHJson()
        meta.fillMissingFrom(existingMetaFile)
        assertTrue(meta.toHJson() == once, "back-fill must be idempotent")
    }

    @Test
    fun `fillMissingFrom ignores missing keys`() {
        val meta = ModMeta().apply { name = "m" }
        meta.fillMissingFrom("name: '''other'''\n")
        assertTrue(meta.name == "m")
        assertTrue(meta.author == "")
        assertTrue(meta.dependencies.isEmpty())
    }

    // =========================================================================
    //  inputSnapshot — the Gradle task input fingerprint must cover every mutable field
    // =========================================================================

    /** Produce a value different from the default for one field; fail the test outright on an uncovered type. */
    private fun distinctValueFor(type: KType): Any {
        // Match on the type name string: under kotlin-reflect, the KType.classifier of Array<String>?
        // is awkward to compare directly with Array::class.
        val name = type.toString()
        return when {
            name.startsWith("kotlin.Array") -> arrayOf("different")
            name.startsWith("kotlin.collections.List") -> listOf("different")
            name.startsWith("kotlin.String") -> "different"
            name.startsWith("kotlin.Boolean") -> true
            name.startsWith("kotlin.Float") -> 2.5f
            else -> error("inputSnapshot 测试未覆盖类型 $type —— 请同时更新 inputSnapshot()")
        }
    }

    @Test
    fun `inputSnapshot changes for every mutable field`() {
        // Reflect over every var: a new field forgotten in inputSnapshot() fails this test,
        // preventing buildModHJson from wrongly reporting UP-TO-DATE on an incomplete fingerprint.
        val baseline = ModMeta().inputSnapshot()
        val missed = mutableListOf<String>()

        ModMeta::class.memberProperties
            .filterIsInstance<KMutableProperty1<ModMeta, *>>()
            .forEach { prop ->
                @Suppress("UNCHECKED_CAST")
                val mutable = prop as KMutableProperty1<ModMeta, Any?>
                val changed = ModMeta()
                mutable.set(changed, distinctValueFor(prop.returnType))
                if (changed.inputSnapshot() == baseline) missed.add(prop.name)
            }

        assertTrue(missed.isEmpty(), "这些字段没有进入 inputSnapshot(): $missed")
    }

    @Test
    fun `inputSnapshot does not throw when name is blank`() {
        // The fingerprint is computed before back-fill, when name may still be blank
        assertTrue(ModMeta().inputSnapshot().isNotEmpty())
        assertThrows<IllegalStateException> { ModMeta().toHJson() }
    }

    // =========================================================================
    //  escaping (the engine silently drops a mod it cannot parse)
    // =========================================================================

    @Test
    fun `hjson escapes a value that contains a single quote`() {
        val info = ModMeta().apply { name = "abc'"; java = true }

        val hjson = info.toHJson()

        assertTrue(!hjson.contains("'''abc''''"), "the trailing quote must not break the literal:\n$hjson")
        assertTrue(hjson.contains("'abc\\''"), "the escaped single-quoted form is expected:\n$hjson")
        // Round-trip through the plugin's own reader: the engine parses the same form.
        assertTrue(ModFileReader.readString(hjson, "name") == "abc'", "got '${ModFileReader.readString(hjson, "name")}'")
    }

    @Test
    fun `json escapes control characters so the file stays valid`() {
        val info = ModMeta().apply { name = "mod"; displayName = "line1\nline2"; java = true }

        val json = info.toJson()

        val line = json.lineSequence().first { it.contains("\"displayName\"") }
        assertTrue(line.trimEnd().endsWith("\","), "the value must stay on one line: $line")
        assertTrue(line.contains("\\n"), "the newline must be escaped: $line")
        assertTrue(ModFileReader.readString(json, "displayName") == "line1\nline2")
    }

    @Test
    fun `blank array entries are dropped from both formats`() {
        val info = ModMeta().apply {
            name = "mod"
            java = true
            dependencies = listOf("a", "  ", "b")
        }

        val hjson = info.toHJson()
        val json = info.toJson()

        assertTrue(hjson.contains("dependencies: ['''a''', '''b''']"), "got:\n$hjson")
        assertTrue(!hjson.contains("''''''"), "an empty literal breaks the engine parser:\n$hjson")
        assertTrue(json.contains("\"dependencies\": [\"a\", \"b\"]"), "got:\n$json")
    }
}
