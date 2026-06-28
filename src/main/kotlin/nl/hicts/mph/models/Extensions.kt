package nl.hicts.mph.models

data class InheritableProperty(
    val value: String,
    val inherited: Boolean
)

fun MavenProject.getAppropiateGroupId(inherited: Boolean = false): InheritableProperty {
    if(model.groupId.isNullOrBlank() && moduleWithinProject == null) {
        return InheritableProperty("UNDETERMINED", false)
    }
    return if (model.groupId != null)
        InheritableProperty(model.groupId, inherited)
    else
        moduleWithinProject!!.getAppropiateGroupId(true)
}

fun MavenProject.getAppropiateArtifactId(inherited: Boolean = false): InheritableProperty {
    if(model.artifactId.isNullOrBlank() && moduleWithinProject == null) {
        return InheritableProperty("UNDETERMINED", false)
    }
    return if (model.artifactId != null)
        InheritableProperty(model.artifactId, inherited)
    else
        moduleWithinProject!!.getAppropiateArtifactId(true)
}

fun MavenProject.getAppropiateVersion(inherited: Boolean = false): InheritableProperty {
    if(model.version.isNullOrBlank() && moduleWithinProject == null) {
        return InheritableProperty("UNDETERMINED", false)
    }
    return if (model.version != null)
        InheritableProperty(model.version, inherited)
    else
        moduleWithinProject!!.getAppropiateVersion(true)
}
