package io.github.maximgromov.commentcloak

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a hidden comment block as a compact rounded pill carrying the CommentCloak icon and the
 * number of lines it stands for.
 *
 * Height is deliberately a full [Editor.getLineHeight]: a custom fold region replaces whole lines,
 * and giving it a shorter height makes the surrounding code jump up by a few pixels, which reads as
 * a rendering glitch. The pill itself is drawn inset inside that line box, so it still looks
 * compact without disturbing the editor's line grid.
 *
 * Every colour comes from the active editor colour scheme, so the marker follows light and dark
 * themes without any hardcoded values.
 */
class CloakRenderer(
    private val editor: Editor,
    /** Number of document lines this marker stands for. */
    val lineCount: Int,
    /** Leading whitespace columns of the block's first line, so the pill lines up with the code. */
    private val indentColumns: Int,
    /** Full text of the hidden comment, used by the hover tooltip. */
    val commentText: String
) : CustomFoldRegionRenderer {

    override fun calcWidthInPixels(region: CustomFoldRegion): Int = indentWidth() + pillWidth()

    override fun calcHeightInPixels(region: CustomFoldRegion): Int = editor.lineHeight

    /**
     * The IDE draws no fold arrow for custom regions, so the gutter icon is what makes a cloaked
     * block openable without hunting for the pill itself.
     */
    override fun calcGutterIconRenderer(region: CustomFoldRegion): GutterIconRenderer =
        CloakGutterIconRenderer(region)

    override fun paint(
        region: CustomFoldRegion,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes
    ) {
        val graphics = g.create() as Graphics2D
        try {
            val config = GraphicsUtil.setupAAPainting(graphics)
            UISettings.setupAntialiasing(graphics)

            val font = markerFont()
            graphics.font = font
            val metrics = graphics.fontMetrics

            val icon = icon()
            val label = label()
            val padding = JBUI.scale(HORIZONTAL_PADDING)
            val gap = JBUI.scale(ICON_TEXT_GAP)
            val contentWidth = icon.iconWidth + gap + metrics.stringWidth(label)

            val boxHeight = targetRegion.height
            val pillHeight = max(JBUI.scale(MIN_PILL_HEIGHT).toDouble(), boxHeight - JBUI.scale(4))
                .coerceAtMost(boxHeight)
            val pillWidth = (padding * 2 + contentWidth).toDouble()
            val x = targetRegion.x + indentWidth()
            val y = targetRegion.y + (boxHeight - pillHeight) / 2.0

            val shape = RoundRectangle2D.Double(x, y, pillWidth, pillHeight, pillHeight, pillHeight)
            graphics.color = backgroundColor()
            graphics.fill(shape)
            borderColor()?.let {
                graphics.color = it
                graphics.draw(shape)
            }

            val iconY = (y + (pillHeight - icon.iconHeight) / 2.0).roundToInt()
            icon.paintIcon(editor.contentComponent, graphics, (x + padding).roundToInt(), iconY)

            graphics.color = foregroundColor()
            val textX = (x + padding + icon.iconWidth + gap).roundToInt()
            val textY = (y + (pillHeight - metrics.height) / 2.0).roundToInt() + metrics.ascent
            graphics.drawString(label, textX, textY)

            config.restore()
        } finally {
            graphics.dispose()
        }
    }

    /** `"1 line"` / `"N lines"`. */
    fun label(): String = if (lineCount == 1) "1 line" else "$lineCount lines"

    // --------------------------------------------------------------------- geometry

    private fun pillWidth(): Int {
        val metrics = fontMetrics() ?: return JBUI.scale(80)
        val padding = JBUI.scale(HORIZONTAL_PADDING)
        val gap = JBUI.scale(ICON_TEXT_GAP)
        return padding * 2 + CommentCloakIcons.Cloak.iconWidth + gap + metrics.stringWidth(label())
    }

    private fun indentWidth(): Int {
        if (indentColumns <= 0) return 0
        val metrics = fontMetrics() ?: return 0
        return metrics.charWidth(' ') * indentColumns
    }

    private fun fontMetrics(): FontMetrics? = try {
        editor.contentComponent.getFontMetrics(markerFont())
    } catch (_: Exception) {
        null
    }

    private fun markerFont(): Font {
        val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val size = max(MIN_FONT_SIZE, base.size - 2)
        return base.deriveFont(Font.PLAIN, size.toFloat())
    }

    // ----------------------------------------------------------------------- colours

    private fun foldedAttributes(): TextAttributes? =
        editor.colorsScheme.getAttributes(EditorColors.FOLDED_TEXT_ATTRIBUTES)

    private fun foregroundColor(): Color =
        foldedAttributes()?.foregroundColor
            ?: editor.colorsScheme.getColor(EditorColors.FOLDED_TEXT_BORDER_COLOR)
            ?: JBColor.GRAY

    private fun backgroundColor(): Color =
        foldedAttributes()?.backgroundColor ?: ColorUtil.withAlpha(foregroundColor(), 0.12)

    private fun borderColor(): Color? = editor.colorsScheme.getColor(EditorColors.FOLDED_TEXT_BORDER_COLOR)

    private fun icon(): Icon {
        val base = CommentCloakIcons.Cloak
        return try {
            IconUtil.colorize(base, foregroundColor())
        } catch (_: Throwable) {
            base
        }
    }

    private class CloakGutterIconRenderer(private val region: CustomFoldRegion) : GutterIconRenderer() {

        override fun getIcon(): Icon = CommentCloakIcons.Cloak

        override fun getTooltipText(): String = "Show the comment hidden by CommentCloak"

        override fun getAlignment(): Alignment = Alignment.RIGHT

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                CommentFolder.reveal(region.editor, region)
            }
        }

        override fun equals(other: Any?): Boolean =
            other is CloakGutterIconRenderer && other.region === region

        override fun hashCode(): Int = System.identityHashCode(region)
    }

    private companion object {
        const val HORIZONTAL_PADDING = 6
        const val ICON_TEXT_GAP = 3
        const val MIN_PILL_HEIGHT = 14
        const val MIN_FONT_SIZE = 9
    }
}
