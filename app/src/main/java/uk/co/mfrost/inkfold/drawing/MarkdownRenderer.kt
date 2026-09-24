package uk.co.mfrost.inkfold.drawing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import uk.co.mfrost.inkfold.model.Page

/**
 * Renders a Markdown page onto a page-coordinate [Canvas], shared by the editor
 * and the PDF/JPG exporter. Supports a pragmatic subset: `#`/`##`/`###`
 * headings, `-`/`*` bullets, and inline `**bold**`, `*italic*` and `` `code` ``.
 *
 * Built layouts are cached by page id + text + width so panning/zooming doesn't
 * re-parse on every frame.
 */
object MarkdownRenderer {

    private const val PADDING = 56f
    private const val BODY_TEXT = 34f
    private const val PLACEHOLDER = "Tap to edit text…"

    private val cache = HashMap<String, StaticLayout>()

    fun draw(canvas: Canvas, page: Page) {
        val width = (page.width - 2 * PADDING).toInt().coerceAtLeast(1)
        val layout = layoutFor(page, width)
        canvas.save()
        canvas.translate(PADDING, PADDING)
        layout.draw(canvas)
        canvas.restore()
    }

    @Synchronized
    private fun layoutFor(page: Page, width: Int): StaticLayout {
        val key = "${page.id}|${page.markdown.hashCode()}|$width"
        cache[key]?.let { return it }
        if (cache.size > 48) cache.clear()

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BODY_TEXT
        }
        val content: CharSequence = if (page.markdown.isBlank()) {
            SpannableStringBuilder(PLACEHOLDER).apply {
                setSpan(ForegroundColorSpan(Color.GRAY), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            toSpannable(page.markdown)
        }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setLineSpacing(8f, 1f)
            .build()
        cache[key] = layout
        return layout
    }

    @Synchronized
    fun clearCache() = cache.clear()

    private fun toSpannable(md: String): Spanned {
        val sb = SpannableStringBuilder()
        val lines = md.split("\n")
        for ((index, raw) in lines.withIndex()) {
            val lineStart = sb.length
            var headingScale = 1f
            var body = raw
            when {
                body.startsWith("### ") -> { headingScale = 1.3f; body = body.removePrefix("### ") }
                body.startsWith("## ") -> { headingScale = 1.6f; body = body.removePrefix("## ") }
                body.startsWith("# ") -> { headingScale = 2.0f; body = body.removePrefix("# ") }
                body.startsWith("- ") -> body = "•  " + body.removePrefix("- ")
                body.startsWith("* ") -> body = "•  " + body.removePrefix("* ")
            }
            appendInline(sb, body)
            if (headingScale > 1f) {
                val end = sb.length
                sb.setSpan(RelativeSizeSpan(headingScale), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (index < lines.lastIndex) sb.append("\n")
        }
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, text: String) {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val close = text.indexOf("**", i + 2)
                    if (close >= 0) {
                        span(sb, text.substring(i + 2, close), StyleSpan(Typeface.BOLD))
                        i = close + 2
                    } else { sb.append("**"); i += 2 }
                }
                text[i] == '*' -> {
                    val close = text.indexOf('*', i + 1)
                    if (close >= 0) {
                        span(sb, text.substring(i + 1, close), StyleSpan(Typeface.ITALIC))
                        i = close + 1
                    } else { sb.append('*'); i += 1 }
                }
                text[i] == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close >= 0) {
                        span(sb, text.substring(i + 1, close), TypefaceSpan("monospace"))
                        i = close + 1
                    } else { sb.append('`'); i += 1 }
                }
                else -> { sb.append(text[i]); i += 1 }
            }
        }
    }

    private fun span(sb: SpannableStringBuilder, text: String, span: Any) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(span, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
