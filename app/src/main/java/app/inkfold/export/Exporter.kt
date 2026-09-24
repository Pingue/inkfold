package app.inkfold.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import app.inkfold.drawing.MarkdownRenderer
import app.inkfold.drawing.StrokeRenderer
import app.inkfold.model.Notebook
import app.inkfold.model.Page
import app.inkfold.model.PageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Renders notebooks to shareable PDF and JPG files in the app cache. */
object Exporter {

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "notebook" }

    private fun exportsDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    /** Paints one page onto a fresh white bitmap at the given pixel scale. */
    fun renderPage(page: Page, renderScale: Float = 1f): Bitmap {
        val w = (page.width * renderScale).toInt().coerceAtLeast(1)
        val h = (page.height * renderScale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.scale(renderScale, renderScale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        drawPageContent(canvas, page, paint, path)
        return bmp
    }

    /** Renders a page's content (strokes or Markdown) onto an already-white canvas. */
    private fun drawPageContent(canvas: Canvas, page: Page, paint: Paint, path: Path) {
        if (page.type == PageType.MARKDOWN) {
            MarkdownRenderer.draw(canvas, page)
        } else {
            for (stroke in page.strokes) StrokeRenderer.draw(canvas, stroke, paint, path)
        }
    }

    suspend fun exportPdf(context: Context, notebook: Notebook): File = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        try {
            notebook.pages.forEachIndexed { index, page ->
                val pageInfo = PdfDocument.PageInfo.Builder(
                    page.width.toInt(), page.height.toInt(), index + 1
                ).create()
                val pdfPage = doc.startPage(pageInfo)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val path = Path()
                pdfPage.canvas.drawColor(Color.WHITE)
                drawPageContent(pdfPage.canvas, page, paint, path)
                doc.finishPage(pdfPage)
            }
            val out = File(exportsDir(context), "${sanitize(notebook.title)}.pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            out
        } finally {
            doc.close()
        }
    }

    /** Exports every page as a separate JPG and returns the files in order. */
    suspend fun exportJpgs(context: Context, notebook: Notebook): List<File> =
        withContext(Dispatchers.IO) {
            val base = sanitize(notebook.title)
            notebook.pages.mapIndexed { index, page ->
                val bmp = renderPage(page, renderScale = 2f)
                val out = File(exportsDir(context), "${base}_p${index + 1}.jpg")
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                bmp.recycle()
                out
            }
        }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
