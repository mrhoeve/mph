package nl.hicts.mph.services

import org.apache.maven.settings.Settings
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest
import java.io.File
import java.util.Properties

internal class MavenResolverConfiguration(
    val localRepository: File,
    val settings: Settings
)

class MavenSettingsLoader(
    private val systemProperties: Properties = System.getProperties(),
    private val environment: Map<String, String> = System.getenv(),
    private val userSettingsFile: File? = defaultUserSettingsFile(systemProperties),
    private val globalSettingsFile: File? = defaultGlobalSettingsFile(systemProperties, environment)
) {

    internal fun load(): MavenResolverConfiguration {
        val interpolationProperties = Properties()
        val normalizeEnvironmentNames = System.getProperty("os.name")
            ?.contains("windows", ignoreCase = true) == true
        environment.forEach { (name, value) ->
            val normalizedName = if (normalizeEnvironmentNames) name.uppercase() else name
            interpolationProperties.setProperty("env.$normalizedName", value)
        }
        interpolationProperties.putAll(systemProperties)

        val request = DefaultSettingsBuildingRequest()
            .setSystemProperties(interpolationProperties)
            .setUserProperties(systemProperties)
        userSettingsFile?.takeIf(File::isFile)?.let(request::setUserSettingsFile)
        globalSettingsFile?.takeIf(File::isFile)?.let(request::setGlobalSettingsFile)

        val settings = DefaultSettingsBuilderFactory().newInstance().build(request).effectiveSettings
        val configuredLocalRepository = systemProperties.getProperty(MAVEN_REPO_LOCAL)
            ?.takeIf(String::isNotBlank)
            ?: settings.localRepository?.takeIf(String::isNotBlank)
            ?: File(defaultUserConfigurationDirectory(systemProperties), "repository").path

        return MavenResolverConfiguration(File(configuredLocalRepository).absoluteFile.normalize(), settings)
    }

    companion object {
        private const val MAVEN_REPO_LOCAL = "maven.repo.local"

        private fun defaultUserSettingsFile(properties: Properties): File {
            properties.getProperty("maven.user.settings")?.takeIf(String::isNotBlank)?.let(::File)?.let { return it }
            return File(defaultUserConfigurationDirectory(properties), "settings.xml")
        }

        private fun defaultUserConfigurationDirectory(properties: Properties): File {
            return properties.getProperty("maven.user.conf")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: File(properties.getProperty("user.home"), ".m2")
        }

        private fun defaultGlobalSettingsFile(
            properties: Properties,
            environment: Map<String, String>
        ): File? {
            properties.getProperty("maven.installation.settings")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { return it }
            properties.getProperty("maven.conf")
                ?.takeIf(String::isNotBlank)
                ?.let { return File(it, "settings.xml") }
            properties.getProperty("maven.home")
                ?.takeIf(String::isNotBlank)
                ?.let { return File(it, "conf/settings.xml") }
            return (environment["MAVEN_HOME"] ?: environment["M2_HOME"])
                ?.takeIf(String::isNotBlank)
                ?.let { File(it, "conf/settings.xml") }
        }
    }
}
