package nl.hicts.mph.services

import nl.hicts.mph.logging.LoggerDelegate
import nl.hicts.mph.models.MavenProject
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ScanProjectUtil {
    companion object {
        private val logger by LoggerDelegate()

        fun searchAllMavenProjects(baseFolder: File, maxDepth: Int = 4): List<MavenProject> {
            logger.debug("Start scanning of ${if (baseFolder.isDirectory) "folder" else "file"} $baseFolder for Maven projects with max depth $maxDepth")
            return findMavenProjects(baseFolder, maxDepth, null)
        }

        private fun findMavenProjects(
            baseFolder: File,
            maxScanDepth: Int,
            parentProject: MavenProject?,
            logAsInfo: Boolean = true
        ): List<MavenProject> {
            val foundProjects = mutableListOf<MavenProject>()
            for (currentScanDepth in 1..maxScanDepth) {
                scanFolderForXMLFiles(baseFolder, currentScanDepth).forEach { file ->
                    readPOM(file, parentProject)?.let { foundProjects.add(it) }
                }
                if (foundProjects.isNotEmpty()) {
                    val message =
                        "Found ${foundProjects.size} projects after $currentScanDepth ${if (currentScanDepth == 1) "scan" else "scans"} ${if (baseFolder.isDirectory) "within folder" else "of file"} $baseFolder"
                    if (logAsInfo) {
                        logger.info(message)
                    } else {
                        logger.debug(message)
                    }
                    return foundProjects.toList()
                }
            }
            logger.info("No projects found after performing $maxScanDepth ${if (maxScanDepth == 1) "scan" else "scans"} ${if (baseFolder.isDirectory) "within folder" else "of file"} $baseFolder")
            return emptyList()
        }

        private fun scanFolderForXMLFiles(folder: File, scanDepth: Int): MutableList<File> {
            return folder
                .walk(FileWalkDirection.TOP_DOWN)
                .maxDepth(scanDepth)
                .filter { it.extension == "xml" }
                .filter { it.isFile }
                .sortedBy { it.absolutePath }
                .toMutableList()
        }

        private fun readPOM(pomFile: File, parentProject: MavenProject?): MavenProject? {
            return try {
                val reader = MavenXpp3Reader()
                Files.newInputStream(pomFile.toPath()).use { inputStream ->
                    InputStreamReader(inputStream, StandardCharsets.UTF_8).use { readerStream ->
                        val model = reader.read(readerStream)
                        val project = MavenProject(
                            parentProject,
                            pomFile,
                            model,
                            emptyList()
                        )
                        project.modules = readModules(model, pomFile, project)
                        return project
                    }
                }
            } catch (e: Exception) {
                logger.info("File is not a valid Maven Project Object Model file: $pomFile")
                null
            }
        }

        private fun readModules(model: Model, pomFileLocation: File, parentProject: MavenProject?): List<MavenProject> {
            return model.modules.flatMap { modulePath ->
                findMavenProjects(
                    getCorrectPathOfModuleFile(
                        pomFileLocation.parent.plus(File.separator).plus(modulePath)
                    ), 1, parentProject, false
                )
            }.toList()
        }

        private fun getCorrectPathOfModuleFile(modulePath: String): File {
            // Check if de given modulePath is a Directory
            // If it is, we'll append the filename pom.xml to it
            if (File(modulePath).isDirectory) {
                return File(modulePath.plus(File.separator).plus("pom.xml"))
            }
            return File(modulePath)
        }
    }
}
