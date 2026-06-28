package nl.hicts.mph.models

import org.apache.maven.model.Model
import java.io.File

data class MavenProject(
    val moduleWithinProject: MavenProject?,
    val pomLocation: File,
    val model: Model,
    var modules: List<MavenProject>
) {
    fun artifact(): String {
        return "${this.getAppropiateGroupId().value}:${this.getAppropiateArtifactId().value}"
    }

    override fun toString(): String {
        return "MavenProject(artifact='${artifact()}', moduleWithinProject=${moduleWithinProject?.artifact()}, pomLocation=$pomLocation, model=$model, modules=$modules)"
    }
}
