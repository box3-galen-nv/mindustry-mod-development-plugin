import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm").version("2.4.20")
    id("com.gradle.plugin-publish") version "2.2.1"
}

repositories {
    mavenCentral()
}

dependencies {
    // kotlin-reflect is only used by the tests (ModMetaTest reads member properties), so a consumer
    // must not have to download it.
    testImplementation(kotlin("reflect"))
    // The Kotlin integration is a runtime dependency on purpose: it is what lets the plugin point the
    // Kotlin source set at the project dir (which is what gives IDEA a source root for breakpoints).
    // Pinned to 2.4.20, the first release that fixes CVE-2026-53614, so a consumer that declared an
    // older Kotlin Gradle Plugin is pulled up onto the patched version instead of carrying the CVE.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Java 17 bytecode, whichever JDK runs the build. Without this the jar carries the building JDK's class
// file version (25 here), and a user on JDK 17-24 cannot load the plugin at all. `release` also pins the
// API surface, so the CI matrix (JDK 17/21/25) produces the same artifact everywhere.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

gradlePlugin {
    // Both links are required by the Plugin Portal; it rejects a publication without them.
    website = "https://github.com/box3-galen-nv/mindustry-mod-development-plugin"
    vcsUrl = "https://github.com/box3-galen-nv/mindustry-mod-development-plugin.git"
    plugins {
        create("mindustryMod") {
            id = "io.github.box3-galen-nv.mindustry-mod-development-plugin"
            implementationClass = "mindustrymoddevelopmentplugin.MindustryModPlugin"
            displayName = "Mindustry mod development plugin"
            description = "Builds Mindustry mods: metadata generation, jar and Android DEX packaging, " +
                "deployment into the game's data directory, and launching the game."
            tags = listOf("mindustry", "mod", "game", "android", "dex")

            // The Portal requires plugins to declare which opt-in Gradle features they support, and saying
            // "not supported" is explicitly encouraged over staying silent. This is honest: the log-stream
            // listener registers a plain provider as a task-completion listener, which Gradle's
            // configuration cache rejects. Flip it to true once that listener is a BuildService.
            compatibility {
                features {
                    configurationCache = false
                }
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "Mindustry mod development build plugin"
            description = "Gradle plugin that builds Mindustry mods in Java or Kotlin."
            url = "https://github.com/box3-galen-nv/mindustry-mod-development-plugin"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
                }
            }
            developers {
                developer {
                    id = "box3-galen-nv"
                    name = "box3-galen-nv"
                    url = "https://github.com/box3-galen-nv"
                }
            }
            scm {
                connection = "scm:git:https://github.com/box3-galen-nv/mindustry-mod-development-plugin.git"
                developerConnection = "scm:git:ssh://git@github.com/box3-galen-nv/mindustry-mod-development-plugin.git"
                url = "https://github.com/box3-galen-nv/mindustry-mod-development-plugin"
            }
        }
    }
}
