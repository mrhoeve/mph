import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "nl.hicts.mph"

val pomVersion = Regex(
    """<artifactId>\s*mph\s*</artifactId>\s*<version>\s*([^<]+)\s*</version>""",
).find(file("../pom.xml").readText())?.groupValues?.get(1)?.trim()
    ?: error("Could not determine the MPH version from ../pom.xml")
val requestedVersion = providers.gradleProperty("mphVersion").orNull?.trim()?.takeIf(String::isNotEmpty)
val releaseBuild = providers.gradleProperty("releaseBuild").map(String::toBoolean).getOrElse(false)
version = requestedVersion ?: if (releaseBuild) pomVersion.removeSuffix("-SNAPSHOT") else pomVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.4")
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
            untilBuild = "261.*"
        }

        description = """
            A native IntelliJ IDEA prototype for exploring Maven projects across a multi-repository workspace.
        """.trimIndent()

        vendor {
            name = "MPH"
        }
    }

    pluginVerification {
        ides {
            current()
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "nl.hicts.mph.intellij.actions.UpdateDependentProjectsAction",
                    "nl.hicts.mph.intellij.services.DependentProjectVersionUpdateService",
                    "nl.hicts.mph.intellij.services.IdeaProjectDiscoveryService",
                    "nl.hicts.mph.intellij.ui.MphToolWindowFactory",
                )
            }
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}
