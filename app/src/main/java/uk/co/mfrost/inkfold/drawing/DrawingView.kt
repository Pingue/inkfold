package uk.co.mfrost.inkfold.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import uk.co.mfrost.inkfold.model.Notebook
import uk.co.mfrost.inkfold.model.Page
import uk.co.mfrost.inkfold.model.PageOrientation
import uk.co.mfrost.inkfold.model.PageType
import uk.co.mfrost.inkfold.model.Stroke
import uk.co.mfrost.inkfold.model.StrokePoint
import uk.co.mfrost.inkfold.model.ToolType
import kotlin.math.abs
import kotlin.math.hypot

/** Object eraser deletes whole strokes; ink eraser paints opaque white over them. */
enum class EraserMode { OBJECT, INK }

/** Mutable holder for the currently selected tool and its per-tool attributes. */
data class ToolSettings(
    var tool: ToolType = ToolType.PEN,
    var penColor: Int = Color.BLACK,
    var penSize: Float = 4f,
    var highlighterColor: Int = Color.YELLOW,
    var highlighterSize: Float = 28f,
    var eraserSize: Float = 40f,
    var eraserMode: EraserMode = EraserMode.OBJECT,
)

/**
 * A continuously scrolling, zoomable canvas that stacks every page of a
 * [Notebook] vertically. One pointer draws or erases on whichever page is under
 * it; two pointers pan (scroll) and zoom. Input is captured from stylus or
 * finger alike, with stylus pressure modulating pen width.
 */
class DrawingView(context: Context) : View(context) {

    private var notebook: Notebook = Notebook()
    var settings: ToolSettings = ToolSettings()

    /** When true, only a stylus draws; a single finger scrolls instead. */
    var penMode: Boolean = false

    /** Invoked when stroke content changes, so the host can autosave. */
    var onChanged: (() -> Unit)? = null

    /** Invoked when pages are added/removed/reoriented, so the host can refresh. */
    var onStructureChanged: (() -> Unit)? = null

    /** Invoked when a Markdown page is tapped, so the host can open the editor. */
    var onMarkdownTap: ((Page) -> Unit)? = null

    // Content transform: screen = content * scale + pan.
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private val minScale = 0.2f
    private val maxScale = 8f
    private val gap = 40f
    private val margin = 24f

    private val density = resources.displayMetrics.density
    // Extra scroll room so pages can clear the floating overlay toolbars,
    // plus a little deadspace above the first and below the last page.
    private val topReserve get() = 64f * density
    private val bottomReserve get() = 200f * density
    private val overscroll get() = 48f * density

    // Pan/scroll state. A pan gesture tracks the focal point (the single finger,
    // or the centroid of two) and translates the canvas by its movement, with
    // pinch-zoom layered on top and fling momentum on release.
    private var panActive = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    // Once a finger gesture turns multi-touch it stays a pan until all fingers
    // lift, so lifting one finger can't suddenly start drawing.
    private var multiTouchGesture = false

    // Tap-to-edit state for Markdown pages.
    private var mdTapping = false
    private var mdDownX = 0f
    private var mdDownY = 0f
    private val tapSlop = 12f * resources.displayMetrics.density

    // Palm rejection state (pen mode): finger input is dropped while the stylus
    // is hovering over the screen or was used moments ago, and palm-sized
    // contacts are dropped outright. A gesture rejected at its DOWN stays
    // rejected until all pointers lift, so the guard can't kick in mid-pan.
    private var stylusHovering = false
    private var lastStylusMs = 0L
    private var fingerGestureRejected = false
    private val palmContactPx = PALM_CONTACT_MM / 25.4f * resources.displayMetrics.xdpi

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#D0D0D0")
        strokeWidth = 1f
    }
    private val tmpPath = Path()

    // Vertical layout of pages in content space.
    private class Placed(val page: Page, val xOffset: Float, val topY: Float)
    private var placed: List<Placed> = emptyList()
    private var contentWidth = Page.A4_SHORT
    private var contentHeight = Page.A4_LONG

    private var currentStroke: Stroke? = null
    private var activePage: Placed? = null
    private val erasedThisGesture = mutableListOf<Stroke>()

    // ---- Undo / redo (ops remember which page they touched) ---------------
    private sealed interface EditOp
    private data class AddOp(val page: Page, val stroke: Stroke) : EditOp
    private data class EraseOp(val page: Page, val strokes: List<Stroke>) : EditOp

    private val undoStack = ArrayDeque<EditOp>()
    private val redoStack = ArrayDeque<EditOp>()

    init {
        setBackgroundColor(Color.parseColor("#ECECEC"))
    }

    fun setNotebook(nb: Notebook) {
        notebook = nb
        undoStack.clear()
        redoStack.clear()
        MarkdownRenderer.clearCache()
        recomputeLayout()
        post {
            fitToWidth()
            clampPan()
            invalidate()
        }
        invalidate()
    }

    fun pageCount(): Int = notebook.pages.size

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is AddOp -> op.page.strokes.remove(op.stroke)
            is EraseOp -> op.page.strokes.addAll(op.strokes)
        }
        redoStack.addLast(op)
        onChanged?.invoke()
        invalidate()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        when (op) {
            is AddOp -> op.page.strokes.add(op.stroke)
            is EraseOp -> op.page.strokes.removeAll(op.strokes.toSet())
        }
        undoStack.addLast(op)
        onChanged?.invoke()
        invalidate()
    }

    fun resetView() {
        fitToWidth()
        clampPan()
        invalidate()
    }

    // ---- Page structure operations (act on the page nearest the viewport) --

    fun focusedPageIndex(): Int {
        if (placed.isEmpty()) return 0
        val centerContentY = (height / 2f - panY) / scale
        val contained = placed.indexOfFirst {
            centerContentY >= it.topY && centerContentY <= it.topY + it.page.height
        }
        if (contained >= 0) return contained
        return placed.indices.minByOrNull { i ->
            abs(placed[i].topY + placed[i].page.height / 2f - centerContentY)
        } ?: 0
    }

    fun addPage(orientation: PageOrientation, type: PageType = PageType.SVG): Page {
        val page = Page(orientation = orientation, type = type)
        notebook.pages.add(page)
        recomputeLayout()
        onChanged?.invoke()
        onStructureChanged?.invoke()
        post { scrollToPage(notebook.pages.lastIndex) }
        invalidate()
        return page
    }

    /** Re-render after a Markdown page's text was edited elsewhere. */
    fun refreshMarkdown() {
        MarkdownRenderer.clearCache()
        onChanged?.invoke()
        invalidate()
    }

    fun setFocusedPageOrientation(orientation: uk.co.mfrost.inkfold.model.PageOrientation) {
        val i = focusedPageIndex()
        notebook.pages.getOrNull(i)?.orientation = orientation
        recomputeLayout()
        clampPan()
        onChanged?.invoke()
        onStructureChanged?.invoke()
        invalidate()
    }

    fun deleteFocusedPage() {
        if (notebook.pages.size <= 1) return
        val i = focusedPageIndex()
        notebook.pages.removeAt(i)
        recomputeLayout()
        clampPan()
        onChanged?.invoke()
        onStructureChanged?.invoke()
        invalidate()
    }

    private fun scrollToPage(index: Int) {
        val p = placed.getOrNull(index) ?: return
        panY = margin - p.topY * scale
        clampPan()
        invalidate()
    }

    // ---- Layout & transform -----------------------------------------------

    private fun recomputeLayout() {
        val pages = notebook.pages
        contentWidth = pages.maxOfOrNull { it.width } ?: Page.A4_SHORT
        var y = 0f
        val list = ArrayList<Placed>(pages.size)
        for (p in pages) {
            list.add(Placed(p, (contentWidth - p.width) / 2f, y))
            y += p.height + gap
        }
        contentHeight = if (pages.isEmpty()) Page.A4_LONG else y - gap
        placed = list
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0) fitToWidth()
        clampPan()
    }

    private fun fitToWidth() {
        if (width == 0) return
        scale = ((width - 2 * margin) / contentWidth).coerceIn(minScale, maxScale)
        panX = (width - contentWidth * scale) / 2f
        panY = margin
    }

    /** Integer pan bounds for [OverScroller] fling/clamp; mirrors [clampPan]. */
    private class PanBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    private fun panBounds(): PanBounds {
        val cw = contentWidth * scale
        val ch = contentHeight * scale
        val (minX, maxX) = if (cw <= width) {
            val c = ((width - cw) / 2f).toInt(); c to c
        } else (width - cw).toInt() to 0

        // Vertical bounds keep a band of deadspace at both ends and reserve room
        // so the last/first page can scroll clear of the overlay toolbars.
        val maxPanY = margin + topReserve + overscroll
        val minPanY = height - ch - margin - bottomReserve - overscroll
        val (minY, maxY) = if (minPanY > maxPanY) {
            val c = ((height - ch) / 2f).toInt(); c to c
        } else minPanY.toInt() to maxPanY.toInt()
        return PanBounds(minX, maxX, minY, maxY)
    }

    private fun clampPan() {
        if (width == 0) return
        val b = panBounds()
        panX = panX.coerceIn(b.minX.toFloat(), b.maxX.toFloat())
        panY = panY.coerceIn(b.minY.toFloat(), b.maxY.toFloat())
    }

    private fun toContentX(sx: Float) = (sx - panX) / scale
    private fun toContentY(sy: Float) = (sy - panY) / scale

    private fun placedAt(cy: Float): Placed? {
        if (placed.isEmpty()) return null
        return placed.firstOrNull { cy >= it.topY && cy <= it.topY + it.page.height }
            ?: placed.minByOrNull { abs(it.topY + it.page.height / 2f - cy) }
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val newScale = (scale * d.scaleFactor).coerceIn(minScale, maxScale)
                // Zoom about the focal point, keeping the content under it fixed.
                // Focal-point translation (two-finger scroll) is applied separately.
                val fx = d.focusX
                val fy = d.focusY
                panX = fx - (fx - panX) * (newScale / scale)
                panY = fy - (fy - panY) * (newScale / scale)
                scale = newScale
                clampPan()
                invalidate()
                return true
            }
        })

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isStylusTool(event.getToolType(0))) {
            stylusHovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            lastStylusMs = SystemClock.uptimeMillis()
        }
        return super.onHoverEvent(event)
    }

    private fun isStylusTool(toolType: Int) =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun palmGuardActive(event: MotionEvent): Boolean {
        if (stylusHovering) return true
        if (SystemClock.uptimeMillis() - lastStylusMs < PALM_RECENT_MS) return true
        // Devices that don't report hover: reject palm-sized contacts by area.
        for (i in 0 until event.pointerCount) {
            if (event.getTouchMajor(i) > palmContactPx) return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val stylusIndex = (0 until event.pointerCount)
            .firstOrNull { isStylusTool(event.getToolType(it)) } ?: -1
        if (stylusIndex >= 0) lastStylusMs = SystemClock.uptimeMillis()

        if (penMode) {
            // Stylus present: it draws; any other pointers in the same event
            // (a resting palm) are simply ignored rather than cancelling the
            // stroke or panning the canvas.
            if (stylusIndex >= 0) {
                if (panActive) endPan()
                handleDraw(event, stylusIndex)
                return true
            }
            // Finger-only input scrolls/zooms (one finger or two), unless the
            // palm guard says this gesture is a resting hand. The decision is
            // made at DOWN and held for the whole gesture.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                fingerGestureRejected = palmGuardActive(event)
            }
            if (fingerGestureRejected) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) fingerGestureRejected = false
                return true
            }
            scaleDetector.onTouchEvent(event)
            handlePan(event)
            return true
        }

        // Finger mode: a single finger draws; two fingers scroll/zoom. Once a
        // gesture goes multi-touch it stays a pan until every finger lifts.
        if (event.pointerCount >= 2 || multiTouchGesture) {
            if (currentStroke != null) {
                currentStroke = null
                activePage = null
                invalidate()
            }
            multiTouchGesture = true
            scaleDetector.onTouchEvent(event)
            handlePan(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) multiTouchGesture = false
            return true
        }
        handleDraw(event, 0)
        return true
    }

    /** Whether this event begins/ends the gesture for the given pointer. */
    private fun isDownFor(event: MotionEvent, pi: Int) =
        event.actionMasked == MotionEvent.ACTION_DOWN ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.actionIndex == pi)

    private fun isUpFor(event: MotionEvent, pi: Int) =
        event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == pi)

    private fun handleDraw(event: MotionEvent, pi: Int) {
        val sx = event.getX(pi)
        val sy = event.getY(pi)
        val cx = toContentX(sx)
        val cy = toContentY(sy)
        val objectErase = settings.tool == ToolType.ERASER && settings.eraserMode == EraserMode.OBJECT

        when {
            isDownFor(event, pi) -> {
                scroller.forceFinished(true)
                redoStack.clear()
                val pl = placedAt(cy) ?: return
                activePage = pl
                if (pl.page.type == PageType.MARKDOWN) {
                    // Markdown pages aren't drawn on; a tap opens the text editor.
                    mdTapping = true
                    mdDownX = sx
                    mdDownY = sy
                } else if (objectErase) {
                    erasedThisGesture.clear()
                    eraseAt(pl, cx, cy)
                } else {
                    currentStroke = newStroke().also {
                        it.points.add(localPoint(pl, cx, cy, pressureAt(event, pi)))
                    }
                }
                invalidate()
            }

            event.actionMasked == MotionEvent.ACTION_MOVE -> {
                val pl = activePage ?: return
                if (pl.page.type == PageType.MARKDOWN) {
                    if (mdTapping && (abs(sx - mdDownX) > tapSlop || abs(sy - mdDownY) > tapSlop)) {
                        mdTapping = false
                    }
                } else if (objectErase) {
                    // Walk the batched samples too so fast swipes don't skip strokes.
                    for (h in 0 until event.historySize) {
                        eraseAt(
                            pl,
                            toContentX(event.getHistoricalX(pi, h)),
                            toContentY(event.getHistoricalY(pi, h)),
                        )
                    }
                    eraseAt(pl, cx, cy)
                } else {
                    currentStroke?.let { s ->
                        // The stylus samples faster than events arrive; the extra
                        // samples ride along in the history and are what make
                        // curves smooth instead of polygonal.
                        for (h in 0 until event.historySize) {
                            addStrokePoint(
                                s, pl,
                                toContentX(event.getHistoricalX(pi, h)),
                                toContentY(event.getHistoricalY(pi, h)),
                                event.getHistoricalPressure(pi, h),
                            )
                        }
                        addStrokePoint(s, pl, cx, cy, pressureAt(event, pi))
                    }
                    invalidate()
                }
            }

            isUpFor(event, pi) -> {
                val pl = activePage
                if (pl != null && pl.page.type == PageType.MARKDOWN) {
                    if (mdTapping && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        onMarkdownTap?.invoke(pl.page)
                    }
                    mdTapping = false
                } else if (objectErase) {
                    if (pl != null && erasedThisGesture.isNotEmpty()) {
                        undoStack.addLast(EraseOp(pl.page, erasedThisGesture.toList()))
                        erasedThisGesture.clear()
                        onChanged?.invoke()
                    }
                } else {
                    currentStroke?.let { s ->
                        if (s.points.isNotEmpty() && pl != null) {
                            pl.page.strokes.add(s)
                            undoStack.addLast(AddOp(pl.page, s))
                            onChanged?.invoke()
                        }
                    }
                    currentStroke = null
                }
                activePage = null
                invalidate()
            }
        }
    }

    private fun pressureAt(event: MotionEvent, pi: Int) =
        event.getPressure(pi).takeIf { it > 0f } ?: 1f

    /** Appends a point, skipping samples too close to the last one to matter. */
    private fun addStrokePoint(s: Stroke, pl: Placed, cx: Float, cy: Float, pressure: Float) {
        val p = localPoint(pl, cx, cy, pressure)
        val last = s.points.lastOrNull()
        if (last != null && hypot(p.x - last.x, p.y - last.y) < MIN_POINT_DISTANCE) return
        s.points.add(p)
    }

    private fun handlePan(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPan(event)

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!panActive) startPan(event)
                else {
                    velocityTracker?.addMovement(event)
                    // Re-centre on the new pointer set so the canvas doesn't jump.
                    lastFocusX = focusX(event)
                    lastFocusY = focusY(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panActive) startPan(event)
                velocityTracker?.addMovement(event)
                val fx = focusX(event)
                val fy = focusY(event)
                panX += fx - lastFocusX
                panY += fy - lastFocusY
                lastFocusX = fx
                lastFocusY = fy
                clampPan()
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                velocityTracker?.addMovement(event)
                // focusX/Y exclude the lifting pointer, so this re-centres cleanly.
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                val vt = velocityTracker
                var vx = 0f
                var vy = 0f
                if (vt != null) {
                    vt.computeCurrentVelocity(1000, maxFlingVelocity)
                    vx = vt.xVelocity
                    vy = vt.yVelocity
                }
                endPan()
                if (hypot(vx, vy) > minFlingVelocity) fling(vx, vy)
            }

            MotionEvent.ACTION_CANCEL -> endPan()
        }
    }

    private fun startPan(event: MotionEvent) {
        scroller.forceFinished(true)
        panActive = true
        lastFocusX = focusX(event)
        lastFocusY = focusY(event)
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun endPan() {
        panActive = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Average pointer X, excluding a pointer that is lifting on this event. */
    private fun focusX(event: MotionEvent): Float {
        val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            sum += event.getX(i); n++
        }
        return if (n > 0) sum / n else event.getX(0)
    }

    private fun focusY(event: MotionEvent): Float {
        val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var sum = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            sum += event.getY(i); n++
        }
        return if (n > 0) sum / n else event.getY(0)
    }

    private fun fling(vx: Float, vy: Float) {
        val b = panBounds()
        scroller.forceFinished(true)
        scroller.fling(
            panX.toInt(), panY.toInt(),
            vx.toInt(), vy.toInt(),
            b.minX, b.maxX, b.minY, b.maxY,
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = scroller.currY.toFloat()
            clampPan()
            postInvalidateOnAnimation()
        }
    }

    private fun localPoint(pl: Placed, cx: Float, cy: Float, pressure: Float) =
        StrokePoint(cx - pl.xOffset, cy - pl.topY, pressure)

    private fun newStroke(): Stroke = when (settings.tool) {
        ToolType.PEN -> Stroke(ToolType.PEN, settings.penColor, settings.penSize)
        ToolType.HIGHLIGHTER ->
            Stroke(ToolType.HIGHLIGHTER, settings.highlighterColor, settings.highlighterSize)
        // The ink eraser is a white opaque stroke; object erase never reaches here.
        ToolType.ERASER, ToolType.ERASE_INK ->
            Stroke(ToolType.ERASE_INK, Color.WHITE, settings.eraserSize)
    }

    private fun eraseAt(pl: Placed, cx: Float, cy: Float) {
        val lx = cx - pl.xOffset
        val ly = cy - pl.topY
        val radius = settings.eraserSize / 2f
        val hit = pl.page.strokes.filter { strokeNear(it, lx, ly, radius) }
        if (hit.isNotEmpty()) {
            pl.page.strokes.removeAll(hit.toSet())
            erasedThisGesture.addAll(hit)
            invalidate()
        }
    }

    private fun strokeNear(stroke: Stroke, px: Float, py: Float, radius: Float): Boolean {
        val pts = stroke.points
        val tol = radius + stroke.width / 2f
        if (pts.size == 1) return hypot(pts[0].x - px, pts[0].y - py) <= tol
        for (i in 0 until pts.size - 1) {
            if (distToSegment(px, py, pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y) <= tol) return true
        }
        return false
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(px - ax, py - ay).toFloat()
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scale, scale)

        val active = activePage
        for (pl in placed) {
            // Cull pages scrolled fully off-screen so we don't paint their strokes.
            val screenTop = panY + pl.topY * scale
            val screenBottom = screenTop + pl.page.height * scale
            if (screenBottom < 0f || screenTop > height) continue
            canvas.save()
            canvas.translate(pl.xOffset, pl.topY)
            canvas.clipRect(0f, 0f, pl.page.width, pl.page.height)
            canvas.drawRect(0f, 0f, pl.page.width, pl.page.height, pagePaint)
            if (pl.page.type == PageType.MARKDOWN) {
                MarkdownRenderer.draw(canvas, pl.page)
            } else {
                for (stroke in pl.page.strokes) StrokeRenderer.draw(canvas, stroke, paint, tmpPath)
                if (active != null && pl.page === active.page) {
                    currentStroke?.let { StrokeRenderer.draw(canvas, it, paint, tmpPath) }
                }
            }
            canvas.restore()
            // Border drawn outside the clip so it isn't clipped to half-width.
            canvas.drawRect(
                pl.xOffset, pl.topY,
                pl.xOffset + pl.page.width, pl.topY + pl.page.height,
                borderPaint,
            )
        }
        canvas.restore()
    }

    companion object {
        /** How long after the stylus last touched/hovered finger input stays rejected. */
        private const val PALM_RECENT_MS = 700L

        /** Contacts wider than this are treated as a palm even without hover support. */
        private const val PALM_CONTACT_MM = 22f

        /**
         * Page-unit distance below which consecutive samples are merged (~0.4mm).
         * Higher = fewer points = faster drawing/redraw, at a small cost in the
         * fidelity of very tight curves.
         */
        private const val MIN_POINT_DISTANCE = 2.5f
    }
}
