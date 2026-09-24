package app.inkfold.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** The drawing tools. ERASE_INK is the "rub out to white" eraser, stored as an opaque background-coloured stroke. */
enum class ToolType { PEN, HIGHLIGHTER, ERASER, ERASE_INK }

/** A page can be laid out tall or wide; this also drives PDF/JPG export size. */
enum class PageOrientation { PORTRAIT, LANDSCAPE }

/**
 * The underlying file kind a page renders from. SVG is the graphical
 * (handwriting) page; MARKDOWN is reserved for future text pages so the
 * document format can grow without a breaking change.
 */
enum class PageType { SVG, MARKDOWN }

/**
 * A single sampled input point in *page* coordinate space (not screen space),
 * so strokes render and export identically regardless of zoom or device size.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
)

/**
 * One continuous stroke. Colour is stored as a packed ARGB Int. [width] is the
 * base stroke width in page units; pen strokes additionally scale by pressure.
 */
@Serializable
data class Stroke(
    val tool: ToolType,
    val color: Int,
    val width: Float,
    val points: MutableList<StrokePoint> = mutableListOf(),
)

/**
 * A page holds an ordered list of strokes. Logical dimensions default to A4 at
 * ~150dpi; orientation swaps width/height.
 */
@Serializable
data class Page(
    val id: String = UUID.randomUUID().toString(),
    var orientation: PageOrientation = PageOrientation.PORTRAIT,
    val strokes: MutableList<Stroke> = mutableListOf(),
    var type: PageType = PageType.SVG,
    /** Source text for [PageType.MARKDOWN] pages; unused for SVG pages. */
    var markdown: String = "",
) {
    val width: Float get() = if (orientation == PageOrientation.PORTRAIT) A4_SHORT else A4_LONG
    val height: Float get() = if (orientation == PageOrientation.PORTRAIT) A4_LONG else A4_SHORT

    companion object {
        const val A4_SHORT = 1240f
        const val A4_LONG = 1754f
    }
}

/**
 * In-memory representation of an open document: a titled, ordered set of pages.
 * On disk this is a folder (one file per page) described by [DocumentManifest].
 */
data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Untitled",
    val pages: MutableList<Page> = mutableListOf(Page()),
    var updatedAt: Long = System.currentTimeMillis(),
)

/**
 * A deep copy safe to hand to a background thread for saving while the live
 * notebook keeps being edited on the UI thread. [StrokePoint] is immutable so
 * point references can be shared; the mutable lists are copied.
 */
fun Notebook.snapshot(): Notebook = copy(
    pages = pages.map { page ->
        page.copy(
            strokes = page.strokes.map { it.copy(points = it.points.toMutableList()) }.toMutableList(),
        )
    }.toMutableList(),
)

/** One entry in the document manifest, pointing at a page's underlying file. */
@Serializable
data class PageRef(
    val id: String = UUID.randomUUID().toString(),
    val type: PageType = PageType.SVG,
    val file: String,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
)

/**
 * The `.inkfold` manifest: a small JSON wrapper that lists a document's pages
 * in render order, each pointing at an underlying file (SVG today, Markdown in
 * future). The manifest plus those files live together in one folder.
 */
@Serializable
data class DocumentManifest(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Untitled",
    var updatedAt: Long = System.currentTimeMillis(),
    val pages: MutableList<PageRef> = mutableListOf(),
    val formatVersion: Int = 2,
)
