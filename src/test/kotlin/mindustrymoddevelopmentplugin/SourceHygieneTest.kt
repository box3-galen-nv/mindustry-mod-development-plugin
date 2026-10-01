package mindustrymoddevelopmentplugin

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards a mistake that the Kotlin compiler accepts silently.
 *
 * Two KDoc blocks in a row — the first closed and the second opened immediately after it, blank
 * lines allowed in between — mean the first documents nothing and the declaration that follows loses
 * its documentation. It is easy to produce with a scripted edit and impossible to notice in a diff
 * that only adds lines, which is how it happened three times here.
 *
 * Neither terminator can be spelled out in this comment: writing the closing sequence inside a
 * comment ends the comment (see the comment-style notes in AGENTS.md). The shapes are therefore
 * described in prose, and the naive check below looks for the closing line followed by an opening
 * line.
 */
class SourceHygieneTest {

    private val sourceRoots = listOf(File("src/main/kotlin"), File("src/test/kotlin"))

    @Test
    fun `the README examples use the current plugin version`() {
        // The release procedure bumps gradle.properties only; nothing else notices when the README keeps
        // advertising the old version, which is exactly what happened between 1.0.0 and 1.0.1.
        val version = File("gradle.properties").readLines()
            .first { it.startsWith("version=") }
            .substringAfter('=')
            .trim()
        val applyLine = Regex(
            """id\("io\.github\.box3-galen-nv\.mindustry-mod-development-plugin"\) version "([^"]+)""""
        )

        for (readme in listOf("README.md", "README_zh.md")) {
            val text = File(readme).readText()
            val shown = applyLine.findAll(text).map { it.groupValues[1] }.toList()
            assertTrue(shown.isNotEmpty(), "$readme must show how to apply the plugin")
            assertTrue(
                shown.all { it == version },
                "$readme advertises $shown while gradle.properties says $version",
            )
        }
    }

    @Test
    fun `no declaration has two doc comments`() {
        sourceRoots.forEach { root ->
            assertTrue(
                root.isDirectory,
                "expected to run from the project directory, but ${root.absolutePath} does not exist",
            )
        }

        val offenders = sourceRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val found = mutableListOf<String>()
                    var previous = ""
                    file.readLines().forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEachIndexed
                        if (previous.endsWith("*/") && trimmed.startsWith("/**")) {
                            found.add("${file.path}:${index + 1}")
                        }
                        previous = trimmed
                    }
                    found
                }
        }

        assertTrue(
            offenders.isEmpty(),
            "a KDoc must directly document the declaration below it; orphaned block(s) at $offenders",
        )
    }
}
