package mindustrymoddevelopmentplugin.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Version → release-asset mapping of [MindustryApi].
 *
 * Every asset, URL and boundary here was checked against the real release assets, and the mod-system
 * floor against the upstream source tree (`core/src/mindustry/mod/Mods.java` first exists in v97).
 */
class MindustryApiTest {

    @Test
    fun `be and latest use their own builds`() {
        val be = MindustryApi.routeFor("be")!!
        assertEquals("Anuken:MindustryBuilds:latest", be.coordinate)
        assertEquals("latest.jar", be.asset)
        assertEquals("/[organisation]/[module]/releases/download/master/latest.jar", be.urlPattern)

        val latest = MindustryApi.routeFor("latest")!!
        assertEquals("Anuken:Mindustry:latest", latest.coordinate)
        assertEquals("dependencies.jar", latest.asset)
        assertEquals("/[organisation]/[module]/releases/latest/download/dependencies.jar", latest.urlPattern)
    }

    @Test
    fun `versions from 155_4 use the compile-only dependencies jar`() {
        for (version in listOf("155.4", "v155.4", "156", "159", "160.5", "159.2.1")) {
            val route = MindustryApi.routeFor(version)!!
            assertEquals("dependencies.jar", route.asset, version)
            assertEquals("Mindustry", route.module, version)
            assertEquals("/[organisation]/[module]/releases/download/${route.revision}/dependencies.jar", route.urlPattern)
        }
        assertEquals("Anuken:Mindustry:v160.5", MindustryApi.routeFor("160.5")!!.coordinate)
        // A leading v is optional and normalized away.
        assertEquals(MindustryApi.routeFor("159"), MindustryApi.routeFor("v159"))
    }

    @Test
    fun `older versions use the runnable jar, which also bundles Arc`() {
        for (version in listOf("155.3", "150", "146", "104", "97")) {
            val route = MindustryApi.routeFor(version)!!
            assertEquals("Mindustry.jar", route.asset, version)
            assertEquals("Anuken:Mindustry:v${version.removePrefix("v")}", route.coordinate)
        }
    }

    @Test
    fun `versions without a mod system have no route`() {
        // v92 only had server plugins; v97 is the first release with mod.json / plugin.json.
        assertNull(MindustryApi.routeFor("96"))
        assertNull(MindustryApi.routeFor("92"))
        assertNull(MindustryApi.routeFor("40"))
        // Not a version at all: the plugin cannot guess an asset for it.
        assertNull(MindustryApi.routeFor("banana"))
        assertNull(MindustryApi.routeFor(""))
        assertNull(MindustryApi.routeFor("1.2.3.4"))
    }

    @Test
    fun `only mindustry modules are ever requested from github`() {
        // The content filter is what keeps unrelated dependencies away from github.com.
        val route = MindustryApi.routeFor("159")!!
        assertTrue(route.module == "Mindustry" && route.revision == "v159")
        assertEquals("Anuken", route.coordinate.substringBefore(':'))
    }

    @Test
    fun `data dir property needs v147`() {
        // The JVM property exists from v147; the v126 environment variable is deliberately not used.
        // `latest`/`be` track newer releases, and an unparseable value is left to the download URL to
        // reject, so it must not fail here as well.
        for (version in listOf("147", "v147", "155.3", "160.5", "latest", "be", "banana")) {
            assertTrue(MindustryApi.supportsDataDir(version), "'$version' must be accepted")
        }
        for (version in listOf("146", "126", "97")) {
            assertFalse(MindustryApi.supportsDataDir(version), "'$version' predates the property")
        }
    }
}
