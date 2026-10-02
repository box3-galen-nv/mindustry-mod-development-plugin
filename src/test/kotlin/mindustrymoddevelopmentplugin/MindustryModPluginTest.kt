package mindustrymoddevelopmentplugin

import mindustrymoddevelopmentplugin.wiring.ModWiring
import mindustrymoddevelopmentplugin.game.MindustryApi
import mindustrymoddevelopmentplugin.dsl.MindustryModRootExtension
import mindustrymoddevelopmentplugin.meta.ModMeta
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Repository and dependency policy of [MindustryModPlugin].
 *
 * The plugin used to inject six Maven repositories (mavenCentral, JitPack, XPDustry, two Sonatype
 * URLs, an Aliyun mirror) and resolve the game API from Maven coordinates. That route was not even
 * self-sufficient: JitPack's POM asks for an unserved Arc commit hash, and the MindustryRepo mirror
 * lacks `org.lz4:lz4-java`. The release assets need no repository from the project.
 */
class MindustryModPluginTest {

    private fun modProject(apiVersion: String?): Project {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(MindustryModPlugin::class.java)
        if (apiVersion != null) {
            project.extensions.getByType(MindustryModRootExtension::class.java).mindustryApiVersion.set(apiVersion)
        }
        // Triggers configureModule(), which is where repositories and dependencies are added.
        project.extensions.getByType(ModMeta::class.java).name = "repo-probe"
        // evaluate() lives on the internal Project interface; ProjectBuilder only exposes Project.
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun Project.compileOnlyDependencies(): List<String> =
        configurations.getByName("compileOnly").dependencies.map { "${it.group}:${it.name}:${it.version}" }

    /** Gradle wraps configuration failures, so the useful text is somewhere in the cause chain. */
    private fun Throwable.chainMessages(): String =
        generateSequence(this) { it.cause }.joinToString("\n") { it.message.orEmpty() }

    @Test
    fun `adds the mirror plus the release asset repository and one dependency`() {
        val project = modProject("159")

        assertEquals(
            listOf(MindustryApi.MIRROR_REPO_NAME, MindustryApi.RELEASES_REPO_NAME),
            project.repositories.map { it.name },
            "a ProjectBuilder project starts with no repositories, so these came from the plugin",
        )
        // One compileOnly dependency: the asset bundles Arc, so arc-core is not needed, and because
        // the repository declares artifact-only metadata there are no transitive dependencies either.
        assertEquals(listOf("Anuken:Mindustry:v159"), project.compileOnlyDependencies())
    }

    @Test
    fun `without an api version no asset repository or dependency is added`() {
        val project = modProject(null)

        assertEquals(listOf(MindustryApi.MIRROR_REPO_NAME), project.repositories.map { it.name })
        assertTrue(project.compileOnlyDependencies().isEmpty(), "nothing to add without a version")
    }

    @Test
    fun `mod sources never include build caches or a project-local gradle home`() {
        val projectDir = File("/work/mod")
        // modSourceExcludes is a member of the plugin class, so ask an applied plugin for it.
        val pluginProject = ProjectBuilder.builder().build()
        pluginProject.plugins.apply(MindustryModPlugin::class.java)
        val plugin = pluginProject.plugins.findPlugin(MindustryModPlugin::class.java)!!

        assertEquals(
            listOf("build/**", ".gradle/**", "**/*.kts", "**/caches/**", "data/**"),
            ModWiring.modSourceExcludes(projectDir, File("/home/user/.gradle")),
            "a Gradle user home outside the project still leaves the cache globs; the default data " +
                "directory is excluded even when the feature is off",
        )
        assertEquals(
            listOf("build/**", ".gradle/**", "**/*.kts", "**/caches/**", "data/**", "gradle-home/**"),
            ModWiring.modSourceExcludes(projectDir, File("/work/mod/gradle-home")),
            "a project-local Gradle user home holds the DSL accessor sources, not mod sources",
        )
        // Exactly the project dir is not a subdirectory of itself.
        assertEquals(
            listOf("build/**", ".gradle/**", "**/*.kts", "**/caches/**", "data/**"),
            ModWiring.modSourceExcludes(projectDir, projectDir),
        )
        assertEquals(
            listOf("build/**", ".gradle/**", "**/*.kts", "**/caches/**", "data/**", "custom-data/**"),
            ModWiring.modSourceExcludes(projectDir, File("/home/user/.gradle"), File("/work/mod/custom-data")),
            "a custom data directory inside the project must not be compiled as mod source",
        )
        assertEquals(
            listOf("build/**", ".gradle/**", "**/*.kts", "**/caches/**", "data/**"),
            ModWiring.modSourceExcludes(projectDir, File("/home/user/.gradle"), File("/tmp/somewhere/data")),
            "a data directory outside the project adds no relative entry",
        )
    }

    @Test
    fun `a version without a mod system is rejected`() {
        // v92 shipped only server plugins; the mod system starts at v97.
        val error = assertThrows(GradleException::class.java) { modProject("92") }
        assertTrue(
            error.chainMessages().contains("v97"),
            "the message should name the floor; chain was:\n${error.chainMessages()}",
        )
        assertTrue(error.chainMessages().contains("no mod system"), error.chainMessages())
    }
}
