package mindustrymoddevelopmentplugin.meta

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Verifies that [ModFileReader] understands the metadata shapes the engine accepts.
 *
 * Key regression: the old regex required the key at the start of a line and a quoted value,
 * so it **could not read an indented `mod.json`** (quoted keys + two-space indent) or a bare HJSON scalar.
 */
class ModFileReaderTest {

    @field:TempDir
    lateinit var tempDir: Path

    private fun create(name: String) = tempDir.resolve(name).toFile().also { it.writeText("name: '''x'''\n") }

    // =========================================================================
    //  Metadata file discovery (the engine's 4 file names)
    // =========================================================================

    @Test
    fun `recognizes all four engine metadata file names`() {
        create("mod.json")
        create("mod.hjson")
        create("plugin.json")
        create("plugin.hjson")
        val found = ModFileReader.existingFiles(tempDir.toFile()).map { it.name }
        assertTrue(found == listOf("mod.json", "mod.hjson", "plugin.json", "plugin.hjson"), "got $found")
    }

    @Test
    fun `existingFiles follows engine priority and skips missing names`() {
        // The engine's metaFiles order is mod.json → mod.hjson → plugin.json → plugin.hjson
        create("plugin.hjson")
        create("mod.hjson")
        val found = ModFileReader.existingFiles(tempDir.toFile()).map { it.name }
        assertTrue(found == listOf("mod.hjson", "plugin.hjson"), "got $found")
    }

    @Test
    fun `existingFiles ignores unrelated files`() {
        create("mod.json5")
        create("plugin.yaml")
        create("README.md")
        assertTrue(ModFileReader.existingFiles(tempDir.toFile()).isEmpty())
    }

    @Test
    fun `existingFiles is empty for an empty directory`() {
        assertTrue(ModFileReader.existingFiles(tempDir.toFile()).isEmpty())
    }

    @Test
    fun `existingFiles ignores directories with a metadata file name`() {
        tempDir.resolve("mod.hjson").toFile().mkdirs()
        assertTrue(ModFileReader.existingFiles(tempDir.toFile()).isEmpty())
    }

    // =========================================================================
    //  Strings
    // =========================================================================

    @Test
    fun `reads triple quoted hjson value`() {
        val value = ModFileReader.readString("name: '''test-mod'''\n", "name")
        assertTrue(value == "test-mod", "got '$value'")
    }

    @Test
    fun `reads single and double quoted values`() {
        assertTrue(ModFileReader.readString("name: 'a'\n", "name") == "a")
        assertTrue(ModFileReader.readString("name: \"b\"\n", "name") == "b")
    }

    @Test
    fun `reads indented json with quoted keys`() {
        // The old implementation could not read this shape (leading indent + quoted key)
        val json = """
            {
              "name": "json-mod",
              "version": "1.0.0",
              "java": true
            }
        """.trimIndent()
        assertTrue(ModFileReader.readString(json, "name") == "json-mod", "got '${ModFileReader.readString(json, "name")}'")
        assertTrue(ModFileReader.readString(json, "version") == "1.0.0")
        assertTrue(ModFileReader.readBoolean(json, "java") == true)
    }

    @Test
    fun `reads compact one-line json`() {
        val json = """{ "name": "json-mod", "author": "someone", "hidden": true }"""
        assertTrue(ModFileReader.readString(json, "name") == "json-mod", "got '${ModFileReader.readString(json, "name")}'")
        assertTrue(ModFileReader.readString(json, "author") == "someone", "got '${ModFileReader.readString(json, "author")}'")
        assertTrue(ModFileReader.readBoolean(json, "hidden") == true)
    }

    @Test
    fun `does not confuse a key with the tail of a neighbouring compact key`() {
        val json = """{"displayName":"Display","name":"real"}"""
        assertTrue(ModFileReader.readString(json, "name") == "real", "got '${ModFileReader.readString(json, "name")}'")
        assertTrue(ModFileReader.readString(json, "displayName") == "Display")
    }

    @Test
    fun `reads arrays in compact one-line json`() {
        val json = """{"name":"m","dependencies":["a","b"],"hidden":true}"""
        assertTrue(ModFileReader.readStringList(json, "dependencies") == listOf("a", "b"), "got ${ModFileReader.readStringList(json, "dependencies")}")
        assertTrue(ModFileReader.readBoolean(json, "hidden") == true)
    }

    @Test
    fun `reads compact one-line hjson with bare scalars`() {
        val hjson = "{name: x, author: y, hidden: true}"
        assertTrue(ModFileReader.readString(hjson, "name") == "x", "got '${ModFileReader.readString(hjson, "name")}'")
        assertTrue(ModFileReader.readString(hjson, "author") == "y", "got '${ModFileReader.readString(hjson, "author")}'")
        assertTrue(ModFileReader.readBoolean(hjson, "hidden") == true)
    }

    @Test
    fun `reads bare hjson scalar`() {
        assertTrue(ModFileReader.readString("name: test-mod\n", "name") == "test-mod")
        assertTrue(ModFileReader.readString("minGameVersion: 146\n", "minGameVersion") == "146")
        assertTrue(ModFileReader.readString("minGameVersion: 158.1\n", "minGameVersion") == "158.1")
    }

    @Test
    fun `strips trailing comments from bare scalars`() {
        assertTrue(ModFileReader.readString("name: mod-a # 注释\n", "name") == "mod-a")
        assertTrue(ModFileReader.readString("name: mod-b // comment\n", "name") == "mod-b")
    }

    @Test
    fun `does not strip comment characters inside quotes`() {
        assertTrue(ModFileReader.readString("name: '''a#b'''\n", "name") == "a#b")
        assertTrue(ModFileReader.readString("name: 'a//b'\n", "name") == "a//b")
    }

    @Test
    fun `unescapes quoted values`() {
        assertTrue(ModFileReader.readString("""name: 'a\'b'""" + "\n", "name") == "a'b")
        assertTrue(ModFileReader.readString("""name: "a\\b"""" + "\n", "name") == "a\\b")
    }

    @Test
    fun `value may contain colons`() {
        assertTrue(ModFileReader.readString("main: '''a.b.C'''\n", "main") == "a.b.C")
        assertTrue(ModFileReader.readString("repo: 'owner/repo'\n", "repo") == "owner/repo")
    }

    @Test
    fun `does not match a key that is a suffix of another key`() {
        val text = "displayName: '''Display'''\nname: '''real'''\n"
        assertTrue(ModFileReader.readString(text, "name") == "real", "got '${ModFileReader.readString(text, "name")}'")
        assertTrue(ModFileReader.readString(text, "displayName") == "Display")
    }

    @Test
    fun `returns null for missing key`() {
        assertTrue(ModFileReader.readString("name: '''a'''\n", "author") == null)
        assertTrue(ModFileReader.readString("", "name") == null)
    }

    // =========================================================================
    //  Booleans / floats
    // =========================================================================

    @Test
    fun `reads booleans`() {
        assertTrue(ModFileReader.readBoolean("hidden: true\n", "hidden") == true)
        assertTrue(ModFileReader.readBoolean("hidden: false\n", "hidden") == false)
        assertTrue(ModFileReader.readBoolean("""hidden: "true"""" + "\n", "hidden") == true)
    }

    @Test
    fun `returns null for non-boolean values`() {
        assertTrue(ModFileReader.readBoolean("hidden: yes\n", "hidden") == null)
        assertTrue(ModFileReader.readBoolean("hidden: 1\n", "hidden") == null)
        assertTrue(ModFileReader.readBoolean("name: '''a'''\n", "hidden") == null)
    }

    @Test
    fun `reads floats`() {
        assertTrue(ModFileReader.readFloat("texturescale: 2.5\n", "texturescale") == 2.5f)
        assertTrue(ModFileReader.readFloat("texturescale: '''2.5'''\n", "texturescale") == 2.5f)
    }

    @Test
    fun `returns null for unparsable float`() {
        assertTrue(ModFileReader.readFloat("texturescale: big\n", "texturescale") == null)
    }

    // =========================================================================
    //  Arrays
    // =========================================================================

    @Test
    fun `reads inline string arrays`() {
        assertTrue(ModFileReader.readStringList("dependencies: ['a', 'b']\n", "dependencies") == listOf("a", "b"))
        assertTrue(ModFileReader.readStringList("""dependencies: ["a", "b"]""" + "\n", "dependencies") == listOf("a", "b"))
    }

    @Test
    fun `reads multiline arrays with trailing comma`() {
        val text = """
            dependencies: [
              'a',
              'b',
            ]
        """.trimIndent()
        assertTrue(ModFileReader.readStringList(text, "dependencies") == listOf("a", "b"), "got ${ModFileReader.readStringList(text, "dependencies")}")
    }

    @Test
    fun `reads empty array as empty list`() {
        assertTrue(ModFileReader.readStringList("dependencies: []\n", "dependencies") == emptyList<String>())
    }

    @Test
    fun `array elements may contain commas and brackets inside quotes`() {
        val text = "dependencies: ['a,b', '''c[d]''']\n"
        assertTrue(ModFileReader.readStringList(text, "dependencies") == listOf("a,b", "c[d]"), "got ${ModFileReader.readStringList(text, "dependencies")}")
    }

    @Test
    fun `returns null for non-array value`() {
        assertTrue(ModFileReader.readStringList("dependencies: '''a'''\n", "dependencies") == null)
        assertTrue(ModFileReader.readStringList("", "dependencies") == null)
    }

    @Test
    fun `returns null for unclosed array`() {
        assertTrue(ModFileReader.readStringList("dependencies: ['a', 'b'\n", "dependencies") == null)
    }

    @Test
    fun `last array wins when key is duplicated`() {
        // Matches arc's parser: a later key overrides an earlier one
        val text = "dependencies: ['a']\ndependencies: ['b']\n"
        assertTrue(ModFileReader.readStringList(text, "dependencies") == listOf("b"), "got ${ModFileReader.readStringList(text, "dependencies")}")
    }

    // =========================================================================
    //  JSON escapes are decoded, not stripped (the engine decodes them)
    // =========================================================================

    @Test
    fun `json escapes in a value are decoded`() {
        // A raw string keeps the backslashes literal, which is exactly what a file on disk contains.
        val json = """{"displayName": "\u6d4b\u8bd5", "subtitle": "a\nb\tc\"d\\e"}"""

        assertTrue(ModFileReader.readString(json, "displayName") == "\u6d4b\u8bd5")
        assertTrue(ModFileReader.readString(json, "subtitle") == "a\nb\tc\"d\\e")
    }

    @Test
    fun `hjson single quote escapes in a value are decoded`() {
        assertTrue(ModFileReader.readString("""name: 'it\'s'""", "name") == "it's")
    }
}
