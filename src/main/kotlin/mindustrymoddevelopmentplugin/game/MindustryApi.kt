package mindustrymoddevelopmentplugin.game

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Provides the Mindustry/Arc compile classpath from the **GitHub release assets**.
 *
 * One content-filtered Ivy repository with `metadataSources { artifact() }` serves a single
 * `compileOnly` dependency: no POM, so no transitive dependencies and no mirror to trust. That is the
 * mechanism the official `MindustryJavaModTemplate` uses.
 *
 * Which asset [configure] picks for `mindustryApiVersion`:
 *
 * - `be` → `MindustryBuilds` `master/latest.jar` (~15 MB);
 * - `latest` or `>= 155.4` → that release's `dependencies.jar` (13–15 MB, compile-only);
 * - `97 … 155.3` → `Mindustry.jar` (73–89 MB, the runnable jar, which bundles Arc).
 *
 * **v97 is the floor**: the first release with a mod system. Below it [configure] fails with an
 * explanation rather than a confusing resolution error. The rejected Maven routes and the version
 * history are in AGENTS.md.
 */
internal object MindustryApi {

    /** Name of the content-filtered Ivy repository over the GitHub release assets. */
    const val RELEASES_REPO_NAME = "MindustryReleases"

    /**
     * Name and URL of `Zelaux/MindustryRepo`: the Maven layout of Arc and Mindustry that the plugin
     * contributes for projects resolving those through Maven coordinates themselves.
     *
     * The plugin's own dependency never uses it. `core` POMs there want `org.lz4:lz4-java`, which the
     * repository does not host — v146 was verified to resolve end to end once `mavenCentral()` is added
     * — and the metadata of its newest release, v156.1, contains leftover Git conflict markers, which
     * Gradle rejects as malformed. AGENTS.md has the details, including why the game author's own
     * repositories are not an alternative.
     */
    const val MIRROR_REPO_NAME = "MindustryRepo"
    const val MIRROR_REPO_URL = "https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository"

    private const val RELEASES_BASE_URL = "https://github.com/"
    private const val OWNER = "Anuken"

    /** First version whose release ships the compile-only `dependencies.jar`. */
    private val DEPENDENCIES_JAR_FROM = intArrayOf(155, 4)

    /**
     * First version with a mod system.
     *
     * `core/src/mindustry/mod/Mods.java` (then still `io.anuke.mindustry`) first appears in v97 and
     * reads `mod.json` + `plugin.json`. v92 only had the server-plugin mechanism, and v101 is where
     * `mod.hjson` shows up.
     */
    private val MOD_SUPPORT_FROM = intArrayOf(97)

    /**
     * First version whose client reads the `mindustry.data.dir` JVM property.
     *
     * `ClientLauncher.setup()` calls `Core.settings.setDataDirectory(...)` from that property (and
     * falls back to the `MINDUSTRY_DATA_DIR` environment variable, which exists since v126). The
     * dedicated server launcher ignores both: it hardcodes its `config/` directory.
     */
    private val DATA_DIR_PROPERTY_FROM = intArrayOf(147)

    /** First version whose release ships a `Mindustry.jar` that actually contains the game classes. */
    private val MINDUSTRY_JAR_FROM = intArrayOf(90)

    /** How one API version maps onto a release asset. */
    internal data class Route(
        /** Gradle coordinate, e.g. `Anuken:Mindustry:v159`. */
        val coordinate: String,
        /** Module name used by the repository's content filter. */
        val module: String,
        /** Value of the `[revision]` placeholder in [urlPattern]. */
        val revision: String,
        /** File name of the release asset. */
        val asset: String,
        /** `true` for `/releases/latest/download/<asset>`, `false` for `/releases/download/<revision>/<asset>`. */
        val latestRelease: Boolean = false,
    ) {
        val urlPattern: String
            get() = if (latestRelease) {
                "/[organisation]/[module]/releases/latest/download/$asset"
            } else {
                "/[organisation]/[module]/releases/download/$revision/$asset"
            }
    }

    /**
     * The release asset that serves [apiVersion], or null when the version has none.
     *
     * `be` and `latest` are accepted next to plain numbers (`"159"` and `"v159"` are the same).
     */
    internal fun routeFor(apiVersion: String): Route? {
        val version = apiVersion.trim()
        when (version.lowercase()) {
            "be" -> return Route(
                coordinate = "$OWNER:MindustryBuilds:latest",
                module = "MindustryBuilds",
                revision = "master",
                asset = "latest.jar",
            )
            "latest" -> return Route(
                coordinate = "$OWNER:Mindustry:latest",
                module = "Mindustry",
                revision = "latest",
                asset = "dependencies.jar",
                latestRelease = true,
            )
        }

        val parsed = parseVersion(version) ?: return null
        if (!atLeast(parsed, MOD_SUPPORT_FROM)) return null
        val revision = "v${version.removePrefix("v").removePrefix("V")}"
        val asset = when {
            atLeast(parsed, DEPENDENCIES_JAR_FROM) -> "dependencies.jar"
            atLeast(parsed, MINDUSTRY_JAR_FROM) -> "Mindustry.jar"
            else -> return null
        }
        return Route(coordinate = "$OWNER:Mindustry:$revision", module = "Mindustry", revision = revision, asset = asset)
    }

    /**
     * Adds the repositories and the single `compileOnly` dependency for [apiVersion].
     *
     * Does nothing but add the mirror when no API version is configured, so a project that supplies
     * its own dependency is left alone.
     */
    fun configure(project: Project, apiVersion: String?) {
        project.repositories.maven { repo ->
            repo.name = MIRROR_REPO_NAME
            repo.setUrl(MIRROR_REPO_URL)
        }

        if (apiVersion.isNullOrBlank()) return

        val parsed = parseVersion(apiVersion)
        if (parsed != null && !atLeast(parsed, MOD_SUPPORT_FROM)) {
            throw GradleException(
                "Mindustry v${apiVersion.trim().removePrefix("v")} is not supported: it has no mod " +
                "system. Mods were introduced in v97 (mod.json / plugin.json); v92 had only server " +
                "plugins, and mod.hjson only exists from v101.\n" +
                "Set mindustryApiVersion to v97 or later, or remove the mod configuration."
            )
        }

        // Reached only for values `parseVersion` rejects — a version below the mod floor already threw
        // above, and every supported shape is handled by [routeFor].
        val route = routeFor(apiVersion)
        if (route == null) {
            project.logger.warn(
                "Cannot map mindustryApiVersion '$apiVersion' to a Mindustry release asset. Expected a " +
                "version such as \"159\" or \"v159\" (>= v97), or \"latest\" / \"be\". Declare the " +
                "dependency yourself, for example:\n" +
                "  dependencies { compileOnly(\"com.github.Anuken.Mindustry:core:v<version>\") }\n" +
                "plus the repository that serves it."
            )
            return
        }

        // Content-filtered: this repository is only ever asked for that one module, so no other
        // dependency resolution ever touches github.com.
        project.repositories.ivy { repo ->
            repo.name = RELEASES_REPO_NAME
            repo.setUrl(RELEASES_BASE_URL)
            repo.patternLayout { layout -> layout.artifact(route.urlPattern) }
            repo.metadataSources { sources -> sources.artifact() }
            repo.content { content -> content.includeVersion(OWNER, route.module, route.revision) }
        }
        project.dependencies.add("compileOnly", route.coordinate)
    }

    /**
     * True when [version] is a client that reads `-Dmindustry.data.dir`.
     *
     * `latest` / `be` count as supported: they track releases newer than the floor.
     *
     * A value that cannot be parsed counts as supported too, so that a bad download version fails in
     * the download with its own message instead of failing here first.
     */
    internal fun supportsDataDir(version: String): Boolean {
        val trimmed = version.trim()
        if (trimmed.lowercase() == "latest" || trimmed.lowercase() == "be") return true
        val parsed = parseVersion(trimmed) ?: return true
        return atLeast(parsed, DATA_DIR_PROPERTY_FROM)
    }

    /** Parses `159`, `v159`, `155.4` … into its numeric parts, or null when it is not a version. */
    private fun parseVersion(version: String): IntArray? {
        val digits = version.removePrefix("v").removePrefix("V")
        if (digits.isEmpty()) return null
        val parts = digits.split('.')
        if (parts.size > 3) return null
        val parsed = parts.map { part -> part.toIntOrNull() ?: return null }
        return if (parsed.any { it < 0 }) null else parsed.toIntArray()
    }

    /** Lexicographic comparison with missing parts read as zero: `160` ≥ `155.4`, `155.3` < `155.4`. */
    private fun atLeast(parsed: IntArray, wanted: IntArray): Boolean {
        for (i in wanted.indices) {
            val got = parsed.getOrElse(i) { 0 }
            if (got != wanted[i]) return got > wanted[i]
        }
        return true
    }
}
