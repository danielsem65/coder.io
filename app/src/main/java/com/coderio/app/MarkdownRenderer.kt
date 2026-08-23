package com.coderio.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.*
import android.text.style.*
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.regex.Pattern

/**
 * Renders lightweight Markdown into [Spanned] text for display in TextViews.
 *
 * Supports: headings (#–###), bold, italic, bold-italic, inline code,
 * fenced code blocks (```), blockquotes, unordered & ordered lists,
 * horizontal rules (---), and [links](url).
 */
object MarkdownRenderer {

    // ── Colours (resolved lazily) ────────────────────────────────
    private var codeBg = 0
    private var codeText = 0
    private var quoteBar = 0
    private var headingColor = 0
    private var linkColor = 0
    private var hrColor = 0

    private fun initColors(tv: TextView) {
        if (codeBg != 0) return
        val ctx = tv.context
        codeBg = 0x22FFFFFF.toInt()                    // semi-transparent white
        codeText = ContextCompat.getColor(ctx, R.color.accent_teal)
        quoteBar = ContextCompat.getColor(ctx, R.color.accent)
        headingColor = ContextCompat.getColor(ctx, R.color.text_primary)
        linkColor = ContextCompat.getColor(ctx, R.color.accent_bright)
        hrColor = ContextCompat.getColor(ctx, R.color.stroke)
    }

    // ── Public API ───────────────────────────────────────────────

    /** Render markdown source into a [Spanned] ready for [TextView.setText]. */
    fun render(source: String, tv: TextView): Spanned {
        initColors(tv)
        // Normalise line endings
        val text = source.replace("\r\n", "\n").replace("\r", "\n")
        val sb = SpannableStringBuilder()

        // Split into blocks separated by blank lines or fenced code blocks
        val blocks = splitBlocks(text)
        for ((i, block) in blocks.withIndex()) {
            when {
                block.isCodeFence -> renderCodeBlock(sb, block.lines)
                block.isHr       -> renderHr(sb)
                else             -> renderInlineBlock(sb, block.lines)
            }
            if (i < blocks.lastIndex) sb.append("\n")
        }

        // Make links clickable
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        return sb
    }

    /** Convenience: render and set directly on the TextView. */
    fun renderInto(source: String, tv: TextView) {
        tv.text = render(source, tv)
    }

    // ── Block splitting ──────────────────────────────────────────

    private class Block(
        val lines: List<String>,
        val isCodeFence: Boolean = false,
        val isHr: Boolean = false,
    )

    private fun splitBlocks(text: String): List<Block> {
        val result = mutableListOf<Block>()
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                val fence = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    fence.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing fence
                result.add(Block(fence, isCodeFence = true))
            }
            // Horizontal rule
            else if (line.trim().let { it == "---" || it == "***" || it == "___" }) {
                result.add(Block(emptyList(), isHr = true))
                i++
            }
            // Blank line → skip
            else if (line.isBlank()) {
                i++
            }
            // Regular text block
            else {
                val buf = mutableListOf<String>()
                while (i < lines.size
                    && lines[i].isNotBlank()
                    && !lines[i].trimStart().startsWith("```")
                    && lines[i].trim().let { it != "---" && it != "***" && it != "___" }
                ) {
                    buf.add(lines[i])
                    i++
                }
                result.add(Block(buf))
            }
        }
        return result
    }

    // ── Code block ───────────────────────────────────────────────

    private fun renderCodeBlock(sb: SpannableStringBuilder, lines: List<String>) {
        val start = sb.length
        val code = if (lines.isEmpty()) "" else lines.joinToString("\n")
        sb.append("  $code  ")
        val end = sb.length

        // Background
        sb.setSpan(object : ReplacementSpan() {
            override fun getSize(
                paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?
            ): Int {
                return paint.measureText(text, start, end).toInt()
            }
            override fun draw(
                canvas: Canvas, text: CharSequence?, start: Int, end: Int,
                x: Float, top: Int, y: Int, bottom: Int, paint: Paint
            ) {
                val bgPaint = Paint(paint).apply { color = codeBg; style = Paint.Style.FILL }
                val rect = android.graphics.RectF(
                    x - 4f, top.toFloat(), x + paint.measureText(text, start, end) + 4f, bottom.toFloat()
                )
                canvas.drawRoundRect(rect, 8f, 8f, bgPaint)
                paint.color = codeText
                canvas.drawText(text!!, start, end, x, y.toFloat(), paint)
            }
        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Monospace
        sb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Size
        sb.setSpan(AbsoluteSizeSpan(13, true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        sb.append("\n")
    }

    // ── Horizontal rule ──────────────────────────────────────────

    private fun renderHr(sb: SpannableStringBuilder) {
        val start = sb.length
        sb.append("──────────────────────────")
        val end = sb.length
        sb.setSpan(ForegroundColorSpan(hrColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(AbsoluteSizeSpan(8, true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
    }

    // ── Inline block (paragraphs, lists, blockquotes, headings) ─

    private fun renderInlineBlock(sb: SpannableStringBuilder, lines: List<String>) {
        for (line in lines) {
            val trimmed = line.trimStart()

            when {
                // Headings
                trimmed.startsWith("### ") -> {
                    appendStyledLine(sb, trimmed.removePrefix("### "), bold = false, sizeDp = 15, color = headingColor)
                }
                trimmed.startsWith("## ") -> {
                    appendStyledLine(sb, trimmed.removePrefix("## "), bold = true, sizeDp = 17, color = headingColor)
                }
                trimmed.startsWith("# ") -> {
                    appendStyledLine(sb, trimmed.removePrefix("# "), bold = true, sizeDp = 20, color = headingColor)
                }
                // Blockquote
                trimmed.startsWith("> ") -> {
                    val content = trimmed.removePrefix("> ")
                    val start = sb.length
                    sb.append("  $content")
                    val end = sb.length
                    sb.setSpan(ForegroundColorSpan(quoteBar), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // Unordered list
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val content = trimmed.removePrefix("- ").removePrefix("* ")
                    val start = sb.length
                    sb.append("  •  ")
                    renderInlineSpans(sb, content)
                    sb.append("\n")
                }
                // Ordered list  (1. …)
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val dot = trimmed.indexOf('.')
                    val num = trimmed.substring(0, dot)
                    val content = trimmed.substring(dot + 1).trimStart()
                    val start = sb.length
                    sb.append("  $num.  ")
                    renderInlineSpans(sb, content)
                    sb.append("\n")
                }
                // Regular line with inline markdown
                else -> {
                    renderInlineSpans(sb, trimmed)
                    sb.append("\n")
                }
            }
        }
    }

    /** Append a fully styled heading line. */
    private fun appendStyledLine(
        sb: SpannableStringBuilder, text: String,
        bold: Boolean, sizeDp: Int, color: Int
    ) {
        val start = sb.length
        renderInlineSpans(sb, text)
        val end = sb.length
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(AbsoluteSizeSpan(sizeDp, true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
    }

    // ── Inline span rendering ────────────────────────────────────

    private fun renderInlineSpans(sb: SpannableStringBuilder, text: String) {
        // Process the line character-by-character with regex passes for inline tokens.
        // We handle: **bold**, *italic*, `code`, [text](url)
        val tokens = tokenizeInline(text)
        for (token in tokens) {
            val start = sb.length
            when (token) {
                is Token.Plain -> sb.append(token.text)
                is Token.Bold -> {
                    sb.append(token.text)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Token.Italic -> {
                    sb.append(token.text)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Token.BoldItalic -> {
                    sb.append(token.text)
                    sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Token.Code -> {
                    sb.append(token.text)
                    val end = sb.length
                    sb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(codeText), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(object : ReplacementSpan() {
                        override fun getSize(p: Paint, t: CharSequence?, s: Int, e: Int, fm: Paint.FontMetricsInt?): Int {
                            return p.measureText(t, s, e).toInt()
                        }
                        override fun draw(c: Canvas, t: CharSequence?, s: Int, e: Int, x: Float, top: Int, y: Int, bot: Int, p: Paint) {
                            val bg = Paint(p).apply { color = codeBg; style = Paint.Style.FILL }
                            val rect = android.graphics.RectF(x - 2f, top.toFloat(), x + p.measureText(t, s, e) + 2f, bot.toFloat())
                            c.drawRoundRect(rect, 4f, 4f, bg)
                            p.color = codeText
                            c.drawText(t!!, s, e, x, y.toFloat(), p)
                        }
                    }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Token.Link -> {
                    sb.append(token.label)
                    sb.setSpan(URLSpan(token.url), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(linkColor), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    // ── Inline tokeniser ─────────────────────────────────────────

    private sealed class Token {
        data class Plain(val text: String) : Token()
        data class Bold(val text: String) : Token()
        data class Italic(val text: String) : Token()
        data class BoldItalic(val text: String) : Token()
        data class Code(val text: String) : Token()
        data class Link(val label: String, val url: String) : Token()
    }

    // Patterns (compiled once)
    private val P_LINK        = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)")
    private val P_CODE        = Pattern.compile("`([^`]+)`")
    private val P_BOLD_ITALIC = Pattern.compile("\\*\\*\\*(.+?)\\*\\*\\*")
    private val P_BOLD        = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__")
    private val P_ITALIC      = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)|(?<!_)_(?!_)(.+?)(?<!_)_(?!_)")

    private fun tokenizeInline(text: String): List<Token> {
        // Collect span ranges that are claimed by special tokens
        data class Span(val start: Int, val end: Int, val token: Token)

        val spans = mutableListOf<Span>()

        fun addSpans(re: Pattern, factory: (MatchResult) -> Token?) {
            val m = re.matcher(text)
            while (m.find()) {
                val t = factory(m) ?: continue
                // Avoid overlapping
                val s = m.start()
                val e = m.end()
                if (spans.any { it.start < e && it.end > s }) continue
                spans.add(Span(s, e, t))
            }
        }

        // Order matters: bold-italic first, then bold, italic, code, link
        addSpans(P_BOLD_ITALIC) { Token.BoldItalic(it.group(1)!!) }
        addSpans(P_BOLD) { m ->
            val g1 = m.group(1)
            val g2 = m.group(2)
            if (g1 != null) Token.Bold(g1) else if (g2 != null) Token.Bold(g2) else null
        }
        addSpans(P_LINK) { Token.Link(it.group(1)!!, it.group(2)!!) }
        addSpans(P_CODE) { Token.Code(it.group(1)!!) }
        // Italic last so * inside ** doesn't grab
        addSpans(P_ITALIC) { m ->
            val g1 = m.group(1)
            val g2 = m.group(2)
            if (g1 != null) Token.Italic(g1) else if (g2 != null) Token.Italic(g2) else null
        }

        // Build token list
        spans.sortBy { it.start }
        val result = mutableListOf<Token>()
        var cursor = 0
        for (sp in spans) {
            if (sp.start > cursor) {
                result.add(Token.Plain(text.substring(cursor, sp.start)))
            }
            result.add(sp.token)
            cursor = sp.end
        }
        if (cursor < text.length) {
            result.add(Token.Plain(text.substring(cursor)))
        }
        return result
    }
}
