package nl.hicts.mph.intellij.icons

import com.intellij.openapi.util.IconLoader

object MphIcons {
    @JvmField
    val Plugin = IconLoader.getIcon("/META-INF/pluginIcon.svg", MphIcons::class.java)

    @JvmField
    val UpdateDependents = IconLoader.getIcon("/icons/updateDependents.svg", MphIcons::class.java)
}
