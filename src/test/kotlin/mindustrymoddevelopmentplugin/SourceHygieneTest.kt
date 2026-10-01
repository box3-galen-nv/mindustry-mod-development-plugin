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
