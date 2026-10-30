import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.sonarqube") version "7.5.0.8588"
}

group = "nl.hicts.mph"

val pluginVersion = providers.gradleProperty("pluginVersion").get().trim()
require(pluginVersion.isNotEmpty()) { "Set pluginVersion in the plugin's gradle.properties." }
val requestedVersion = providers.gradleProperty("mphVersion").orNull?.trim()?.takeIf(String::isNotEmpty)
val releaseBuild = providers.gradleProperty("releaseBuild").map(String::toBoolean).getOrElse(false)
version = requestedVersion ?: if (releaseBuild) pluginVersion.removeSuffix("-SNAPSHOT") else pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.5")
        bundledPlugins("com.intellij.java", "org.jetbrains.idea.maven", "Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "nl.hicts.mph.plugin"
        name = "Maven Project Helper"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }

        description = """
            Manage versions, builds, dependencies, and Git synchronization across a Maven workspace from IntelliJ IDEA.
        """.trimIndent()

        vendor {
            name = "MPH"
        }
    }

    pluginVerification {
        ides {
            current()
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "nl.hicts.mph.intellij.actions.UpdateDependentProjectsAction",
                    "nl.hicts.mph.intellij.services.BulkVersionUpdateService",
                    "nl.hicts.mph.intellij.services.DependentProjectVersionUpdateService",
                    "nl.hicts.mph.intellij.services.GitRebaseService",
                    "nl.hicts.mph.intellij.services.IdeaProjectDiscoveryService",
                    "nl.hicts.mph.intellij.services.MavenBuildService",
                    "nl.hicts.mph.intellij.services.IntellijSbomService",
                    "nl.hicts.mph.intellij.services.IntellijNexusIqService",
                    "nl.hicts.mph.intellij.services.ManagedVersionService",
                    "nl.hicts.mph.intellij.services.NexusIqSettings",
                    "nl.hicts.mph.intellij.services.SpringBootVersionCatalog",
                    "nl.hicts.mph.intellij.ui.*",
                )
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectBaseDir", rootDir.parentFile.absolutePath)
        property("sonar.projectName", "Maven Project Helper")
        property("sonar.sources", "intellij-plugin/src/main/kotlin,intellij-plugin/src/main/resources")
        property("sonar.tests", "intellij-plugin/src/test/kotlin,intellij-plugin/src/test/java,intellij-plugin/src/test/resources")
        property("sonar.test.inclusions", "intellij-plugin/src/test/kotlin/**/*.kt,intellij-plugin/src/test/java/**/*.java")
        property("sonar.coverage.jacoco.xmlReportPaths", layout.buildDirectory.file("reports/kover/report.xml").get().asFile.absolutePath)
        property("sonar.coverage.exclusions", listOf(
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/ui/**/*.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/actions/UpdateDependentProjectsAction.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/services/BulkVersionUpdateService.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/services/DependentProjectVersionUpdateService.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/services/GitRebaseService.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/services/IdeaProjectDiscoveryService.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/services/IntellijNexusIqService.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/services/MavenBuildService.kt",
            "intellij-plugin/src/main/kotlin/nl/hicts/mph/intellij/services/NexusIqSettings.kt",
        ).joinToString(","))
    }
}

tasks {
    jar {
        manifest.attributes["Implementation-Version"] = project.version.toString()
    }

    test {
        useJUnit()
        systemProperty("mph.test.classpath", sourceSets.test.get().output.classesDirs.asPath)
        environment.keys.filter { it.startsWith("GIT_", ignoreCase = true) }.forEach { environment.remove(it) }
        environment("GIT_CONFIG_NOSYSTEM", "1")
        environment("GIT_CONFIG_GLOBAL", if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
        environment("GIT_TERMINAL_PROMPT", "0")
        environment("GCM_INTERACTIVE", "Never")
        // Load MPH and its dependencies, rather than unrelated bundled language plugins.
        systemProperty("idea.load.plugins.id", "nl.hicts.mph.plugin")
    }

    named("sonar") {
        dependsOn("koverXmlReport")
    }
}
