package uk.co.mfrost.inkfold.drawing

import uk.co.mfrost.inkfold.model.Page
import uk.co.mfrost.inkfold.model.Stroke
import uk.co.mfrost.inkfold.model.StrokePoint
import uk.co.mfrost.inkfold.model.ToolType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Reads and writes a single page as a standalone SVG file. The SVG renders in
 * any viewer (browser, Inkscape, …); it also embeds the exact stroke data in a
 * `<metadata>` element so the app can reload it losslessly — including pen
 * pressure, tool type and colours that plain SVG can't fully express.
 *
 * Pen pressure is drawn at constant base width in the visible SVG; the true
 * pressure-tapered rendering is reconstructed in-app from the embedded data.
 */
object SvgPage {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val strokeListSerializer = ListSerializer(Stroke.serializer())

    private const val NS = "https://inkfold.app/ns"
    private const val OPEN = "<inkfold:strokes><![CDATA["
    private const val CLOSE = "]]></inkfold:strokes>"

    fun toSvg(page: Page): String {
        val w = page.width
        val h = page.height
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:inkfold=\"$NS\" " +
                "width=\"${num(w)}\" height=\"${num(h)}\" viewBox=\"0 0 ${num(w)} ${num(h)}\">\n"
        )
        sb.append("<rect x=\"0\" y=\"0\" width=\"${num(w)}\" height=\"${num(h)}\" fill=\"#ffffff\"/>\n")
        for (stroke in page.strokes) appendStroke(sb, stroke)
        val embedded = json.encodeToString(strokeListSerializer, page.strokes)
        sb.append("<metadata>$OPEN$embedded$CLOSE</metadata>\n")
        sb.append("</svg>\n")
        return sb.toString()
    }

    /** Recovers strokes from the embedded metadata; empty if absent/unreadable. */
    fun parseStrokes(svg: String): MutableList<Stroke> {
        val start = svg.indexOf(OPEN)
        if (start < 0) return mutableListOf()
        val from = start + OPEN.length
        val end = svg.indexOf(CLOSE, from)
        if (end < 0) return mutableListOf()
        val text = svg.substring(from, end)
        return runCatching {
            json.decodeFromString(strokeListSerializer, text).toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun appendStroke(sb: StringBuilder, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        when (stroke.tool) {
            ToolType.PEN -> appendPath(sb, stroke.points, hex(stroke.color), stroke.width, null)
            ToolType.HIGHLIGHTER -> appendPath(sb, stroke.points, hex(stroke.color), stroke.width, 0.35f)
            ToolType.ERASE_INK -> appendPath(sb, stroke.points, "#ffffff", stroke.width, null)
            ToolType.ERASER -> { /* object eraser leaves nothing to draw */ }
        }
    }

    private fun appendPath(
        sb: StringBuilder,
        pts: List<StrokePoint>,
        color: String,
        width: Float,
        opacity: Float?,
    ) {
        if (pts.size == 1) {
            sb.append("<circle cx=\"${num(pts[0].x)}\" cy=\"${num(pts[0].y)}\" r=\"${num(width / 2f)}\" fill=\"$color\"")
            if (opacity != null) sb.append(" fill-opacity=\"$opacity\"")
            sb.append("/>\n")
            return
        }
        // Quadratic curves through segment midpoints, mirroring StrokeRenderer,
        // so the standalone SVG is as smooth as the in-app rendering.
        val d = StringBuilder("M ${num(pts[0].x)} ${num(pts[0].y)}")
        if (pts.size == 2) {
            d.append(" L ${num(pts[1].x)} ${num(pts[1].y)}")
        } else {
            for (i in 1 until pts.size - 1) {
                val midX = (pts[i].x + pts[i + 1].x) / 2f
                val midY = (pts[i].y + pts[i + 1].y) / 2f
                d.append(" Q ${num(pts[i].x)} ${num(pts[i].y)} ${num(midX)} ${num(midY)}")
            }
            d.append(" L ${num(pts[pts.size - 1].x)} ${num(pts[pts.size - 1].y)}")
        }
        sb.append("<path d=\"$d\" fill=\"none\" stroke=\"$color\" stroke-width=\"${num(width)}\" ")
        sb.append("stroke-linecap=\"round\" stroke-linejoin=\"round\"")
        if (opacity != null) sb.append(" stroke-opacity=\"$opacity\"")
        sb.append("/>\n")
    }

    private fun hex(argb: Int): String = String.format("#%06X", 0xFFFFFF and argb)

    private fun num(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)
}
