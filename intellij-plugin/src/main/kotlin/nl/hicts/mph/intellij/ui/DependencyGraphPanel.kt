package nl.hicts.mph.intellij.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.DependencyGraphEdge
import nl.hicts.mph.intellij.model.DependencyGraphEdgeKind
import nl.hicts.mph.intellij.model.DependencyGraphNode
import nl.hicts.mph.intellij.model.WorkspaceDependencyGraph
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.min
import kotlin.math.sqrt

internal data class DependencyGraphLayout(
    val nodeBounds: Map<String, Rectangle2D.Double>,
    val stageHeaders: Map<Int, Rectangle2D.Double>,
    val width: Double,
    val height: Double,
)

internal object DependencyGraphLayoutEngine {
    private const val NODE_WIDTH = 220.0
    private const val NODE_HEIGHT = 64.0
    private const val COLUMN_GAP = 145.0
    private const val ROW_GAP = 22.0
    private const val REPOSITORY_GAP = 18.0
    private const val MARGIN = 45.0
    private const val HEADER_HEIGHT = 36.0

    fun layout(graph: WorkspaceDependencyGraph): DependencyGraphLayout {
        if (graph.nodes.isEmpty()) return DependencyGraphLayout(emptyMap(), emptyMap(), 400.0, 260.0)
        val nodeBounds = linkedMapOf<String, Rectangle2D.Double>()
        val headers = linkedMapOf<Int, Rectangle2D.Double>()
        val stages = graph.nodes.groupBy(DependencyGraphNode::buildStep).toSortedMap()
        var maxHeight = 0.0
        stages.entries.forEachIndexed { column, (stage, nodes) ->
            val x = MARGIN + column * (NODE_WIDTH + COLUMN_GAP)
            headers[stage] = Rectangle2D.Double(x, MARGIN, NODE_WIDTH, HEADER_HEIGHT)
            var y = MARGIN + HEADER_HEIGHT + 18.0
            nodes.groupBy(DependencyGraphNode::repositoryName).toSortedMap().values.forEach { repositoryNodes ->
                repositoryNodes.sortedWith(compareByDescending<DependencyGraphNode> { it.isRepositoryRoot }
                    .thenBy { it.project.artifactId }).forEach { node ->
                    nodeBounds[node.id] = Rectangle2D.Double(x, y, NODE_WIDTH, NODE_HEIGHT)
                    y += NODE_HEIGHT + ROW_GAP
                }
                y += REPOSITORY_GAP
            }
            maxHeight = maxOf(maxHeight, y)
        }
        return DependencyGraphLayout(
            nodeBounds,
            headers,
            MARGIN * 2 + stages.size * NODE_WIDTH + (stages.size - 1).coerceAtLeast(0) * COLUMN_GAP,
            maxHeight + MARGIN,
        )
    }
}

private data class GraphPalette(
    val background: Color,
    val card: Color,
    val rootCard: Color,
    val border: Color,
    val text: Color,
    val secondaryText: Color,
    val dependency: Color,
    val module: Color,
    val focused: Color,
    val focusedBorder: Color,
    val cycle: Color,
    val stage: Color,
) {
    companion object {
        fun current() = GraphPalette(
            JBColor.background(),
            JBColor(Color(0xFA, 0xFB, 0xFC), Color(0x31, 0x33, 0x35)),
            JBColor(Color(0xF1, 0xF5, 0xF9), Color(0x3A, 0x3D, 0x41)),
            JBColor(Color(0xBD, 0xC5, 0xD0), Color(0x5A, 0x5D, 0x63)),
            JBColor.foreground(),
            JBColor(Color(0x5F, 0x6B, 0x7A), Color(0xA5, 0xA8, 0xAD)),
            JBColor(Color(0x4F, 0x6B, 0x8A), Color(0x91, 0xA7, 0xC0)),
            JBColor(Color(0x9A, 0xA5, 0xB1), Color(0x78, 0x7D, 0x84)),
            JBColor(Color(0xDB, 0xEA, 0xFE), Color(0x25, 0x42, 0x68)),
            JBColor(Color(0x25, 0x63, 0xEB), Color(0x58, 0xA6, 0xFF)),
            JBColor(Color(0xDC, 0x26, 0x26), Color(0xFF, 0x6B, 0x68)),
            JBColor(Color(0x33, 0x41, 0x55), Color(0xCF, 0xD2, 0xD6)),
        )

        fun export() = GraphPalette(
            Color.WHITE, Color(0xFA, 0xFB, 0xFC), Color(0xF1, 0xF5, 0xF9), Color(0xBD, 0xC5, 0xD0),
            Color(0x1F, 0x29, 0x37), Color(0x5F, 0x6B, 0x7A), Color(0x4F, 0x6B, 0x8A), Color(0x9A, 0xA5, 0xB1),
            Color(0xDB, 0xEA, 0xFE), Color(0x25, 0x63, 0xEB), Color(0xDC, 0x26, 0x26), Color(0x33, 0x41, 0x55),
        )
    }
}

private object DependencyGraphPainter {
    fun paint(
        graphics: Graphics2D,
        graph: WorkspaceDependencyGraph,
        layout: DependencyGraphLayout,
        palette: GraphPalette,
        selectedNodeId: String? = null,
    ) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.color = palette.background
        graphics.fill(Rectangle2D.Double(0.0, 0.0, layout.width, layout.height))
        paintStageHeaders(graphics, layout, palette)
        graph.edges.filter { it.kind == DependencyGraphEdgeKind.MODULE }.forEach {
            paintEdge(graphics, it, layout, palette.module, dashed = true)
        }
        graph.edges.filter { it.kind == DependencyGraphEdgeKind.DEPENDENCY }.forEach {
            paintEdge(graphics, it, layout, palette.dependency, dashed = false)
        }
        graph.nodes.forEach { node ->
            layout.nodeBounds[node.id]?.let { bounds ->
                paintNode(graphics, node, bounds, palette, node.id == graph.focusedNodeId || node.id == selectedNodeId)
            }
        }
    }

    private fun paintStageHeaders(graphics: Graphics2D, layout: DependencyGraphLayout, palette: GraphPalette) {
        graphics.font = graphics.font.deriveFont(Font.BOLD, 14f)
        graphics.color = palette.stage
        layout.stageHeaders.forEach { (stage, bounds) ->
            graphics.drawString("Build stage $stage", bounds.x.toFloat(), (bounds.y + 23).toFloat())
            graphics.color = palette.border
            graphics.drawLine(bounds.x.toInt(), bounds.maxY.toInt(), bounds.maxX.toInt(), bounds.maxY.toInt())
            graphics.color = palette.stage
        }
    }

    private fun paintNode(
        graphics: Graphics2D,
        node: DependencyGraphNode,
        bounds: Rectangle2D.Double,
        palette: GraphPalette,
        focused: Boolean,
    ) {
        graphics.color = if (focused) palette.focused else if (node.isRepositoryRoot) palette.rootCard else palette.card
        graphics.fillRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 14, 14)
        graphics.color = when {
            node.partOfCycle -> palette.cycle
            focused -> palette.focusedBorder
            else -> palette.border
        }
        graphics.stroke = BasicStroke(if (focused || node.partOfCycle) 2.2f else 1.2f)
        graphics.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 14, 14)
        graphics.font = graphics.font.deriveFont(if (node.isRepositoryRoot) Font.BOLD else Font.PLAIN, 13f)
        graphics.color = palette.text
        graphics.drawString(ellipsize(node.project.artifactId, 29), (bounds.x + 13).toFloat(), (bounds.y + 24).toFloat())
        graphics.font = graphics.font.deriveFont(Font.PLAIN, 11f)
        graphics.color = palette.secondaryText
        val version = node.project.version?.takeIf(String::isNotBlank) ?: "version unresolved"
        graphics.drawString(ellipsize(version, 31), (bounds.x + 13).toFloat(), (bounds.y + 43).toFloat())
        graphics.drawString(ellipsize(node.repositoryName, 22), (bounds.x + 13).toFloat(), (bounds.y + 58).toFloat())
    }

    private fun paintEdge(
        graphics: Graphics2D,
        edge: DependencyGraphEdge,
        layout: DependencyGraphLayout,
        color: Color,
        dashed: Boolean,
    ) {
        val source = layout.nodeBounds[edge.sourceId] ?: return
        val target = layout.nodeBounds[edge.targetId] ?: return
        val start = Point(source.maxX.toInt(), source.centerY.toInt())
        val end = Point(target.x.toInt(), target.centerY.toInt())
        val backwards = end.x <= start.x
        val path = Path2D.Double()
        path.moveTo(start.x.toDouble(), start.y.toDouble())
        if (backwards) {
            val bend = maxOf(source.maxX, target.maxX) + 42.0
            path.curveTo(bend, start.y.toDouble(), bend, end.y.toDouble(), end.x.toDouble(), end.y.toDouble())
        } else {
            val middle = (start.x + end.x) / 2.0
            path.curveTo(middle, start.y.toDouble(), middle, end.y.toDouble(), end.x.toDouble(), end.y.toDouble())
        }
        graphics.color = color
        graphics.stroke = if (dashed) BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(5f, 5f), 0f)
        else BasicStroke(1.45f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        graphics.draw(path)
        if (!dashed) {
            val arrow = Path2D.Double().apply {
                moveTo(end.x.toDouble(), end.y.toDouble())
                lineTo((end.x - 9).toDouble(), (end.y - 5).toDouble())
                lineTo((end.x - 9).toDouble(), (end.y + 5).toDouble())
                closePath()
            }
            graphics.fill(arrow)
        }
    }

    private fun ellipsize(value: String, maximum: Int): String =
        if (value.length <= maximum) value else value.take(maximum - 1) + "…"
}

internal object DependencyGraphPngExporter {
    fun write(graph: WorkspaceDependencyGraph, target: Path) {
        val layout = DependencyGraphLayoutEngine.layout(graph)
        val requestedScale = 2.0
        val areaScale = sqrt(MAX_PIXELS / (layout.width * layout.height))
        val scale = min(requestedScale, min(MAX_DIMENSION / layout.width, min(MAX_DIMENSION / layout.height, areaScale)))
            .coerceAtLeast(0.25)
        val image = BufferedImage(
            (layout.width * scale).toInt().coerceAtLeast(1),
            (layout.height * scale).toInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale, scale)
            DependencyGraphPainter.paint(graphics, graph, layout, GraphPalette.export())
        } finally {
            graphics.dispose()
        }
        ImageIO.write(image, "png", target.toFile())
    }

    private const val MAX_DIMENSION = 16_000.0
    private const val MAX_PIXELS = 80_000_000.0
}

internal class DependencyGraphPanel(
    graph: WorkspaceDependencyGraph,
    private val openPom: (String) -> Unit,
) : JComponent() {
    private var graph = graph
    private var layout = DependencyGraphLayoutEngine.layout(graph)
    private var zoom = 1.0
    private var offsetX = 20.0
    private var offsetY = 20.0
    private var dragStart: Point? = null
    private var selectedNodeId: String? = null

    init {
        isOpaque = true
        background = JBColor.background()
        preferredSize = Dimension(JBUI.scale(950), JBUI.scale(620))
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                dragStart = event.point
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }

            override fun mouseDragged(event: MouseEvent) {
                dragStart?.let { previous ->
                    offsetX += event.x - previous.x
                    offsetY += event.y - previous.y
                    dragStart = event.point
                    repaint()
                }
            }

            override fun mouseReleased(event: MouseEvent) {
                dragStart = null
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseClicked(event: MouseEvent) {
                val node = nodeAt(event.point)
                selectedNodeId = node?.id
                repaint()
                if (node != null && event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    openPom(node.project.pomPath)
                }
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                val factor = if (event.preciseWheelRotation < 0) 1.12 else 1 / 1.12
                zoomAt(factor, event.point)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        addComponentListener(object : ComponentAdapter() {
            private var fitted = false
            override fun componentResized(event: ComponentEvent) {
                if (!fitted && width > 0 && height > 0) {
                    fitted = true
                    fitToView()
                }
            }
        })
    }

    fun setGraph(graph: WorkspaceDependencyGraph) {
        this.graph = graph
        layout = DependencyGraphLayoutEngine.layout(graph)
        selectedNodeId = graph.focusedNodeId
        fitToView()
    }

    fun zoomIn() = zoomAt(1.2, Point(width / 2, height / 2))
    fun zoomOut() = zoomAt(1 / 1.2, Point(width / 2, height / 2))

    fun fitToView() {
        if (width <= 0 || height <= 0) return
        zoom = min((width - 30.0) / layout.width, (height - 30.0) / layout.height).coerceIn(0.12, 1.35)
        offsetX = (width - layout.width * zoom) / 2.0
        offsetY = (height - layout.height * zoom) / 2.0
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        graphics.color = background
        graphics.fillRect(0, 0, width, height)
        val copy = (graphics as Graphics2D).create() as Graphics2D
        try {
            copy.translate(offsetX, offsetY)
            copy.scale(zoom, zoom)
            DependencyGraphPainter.paint(copy, graph, layout, GraphPalette.current(), selectedNodeId)
        } finally {
            copy.dispose()
        }
    }

    override fun getToolTipText(event: MouseEvent): String? = nodeAt(event.point)?.let { node ->
        "<html><b>${node.project.coordinates}</b><br>Version: ${node.project.version ?: "unresolved"}" +
            "<br>Repository: ${node.repositoryName}<br>${node.project.pomPath}</html>"
    }

    private fun nodeAt(point: Point): DependencyGraphNode? {
        val logicalX = (point.x - offsetX) / zoom
        val logicalY = (point.y - offsetY) / zoom
        val id = layout.nodeBounds.entries.firstOrNull { it.value.contains(logicalX, logicalY) }?.key
        return graph.nodes.firstOrNull { it.id == id }
    }

    private fun zoomAt(factor: Double, anchor: Point) {
        val oldZoom = zoom
        zoom = (zoom * factor).coerceIn(0.12, 3.5)
        val logicalX = (anchor.x - offsetX) / oldZoom
        val logicalY = (anchor.y - offsetY) / oldZoom
        offsetX = anchor.x - logicalX * zoom
        offsetY = anchor.y - logicalY * zoom
        repaint()
    }
}
