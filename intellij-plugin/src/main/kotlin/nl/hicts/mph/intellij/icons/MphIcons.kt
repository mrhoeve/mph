package nl.hicts.mph.intellij.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon

object MphIcons {
    @JvmField
    val Mph = IconLoader.getIcon("/icons/mph.svg", MphIcons::class.java)

    @JvmField
    val VersionTag = IconLoader.getIcon("/icons/versionTag.svg", MphIcons::class.java)

    @JvmField
    val VersionsAlign = IconLoader.getIcon("/icons/versionsAlign.svg", MphIcons::class.java)

    @JvmField
    val VersionsRealign = IconLoader.getIcon("/icons/versionsRealign.svg", MphIcons::class.java)

    @JvmField
    val BuildOrder = IconLoader.getIcon("/icons/buildOrder.svg", MphIcons::class.java)

    @JvmField
    val GitTag = IconLoader.getIcon("/icons/gitTag.svg", MphIcons::class.java)

    @JvmField
    val SyncDevelop = IconLoader.getIcon("/icons/syncDevelop.svg", MphIcons::class.java)

    @JvmField
    val Waiting = IconLoader.getIcon("/icons/waiting.svg", MphIcons::class.java)

    @JvmField
    val Build = AllIcons.Actions.Compile

    @JvmField
    val Dependencies = AllIcons.Actions.DependencyAnalyzer

    @JvmField
    val Sbom = AllIcons.Nodes.PpLibFolder

    @JvmField
    val SecurityScan = AllIcons.General.InspectionsEye

    // Despite its SDK name, this is IntelliJ's red Stop square, not a pause glyph.
    @JvmField
    val Stop = AllIcons.Actions.Suspend

    @JvmField
    val Running = AnimatedIcon.Default.INSTANCE
}
