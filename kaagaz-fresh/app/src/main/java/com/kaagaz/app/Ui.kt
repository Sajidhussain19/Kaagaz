package com.kaagaz.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Targeting SDK 36 makes Android 15+ draw edge-to-edge; pad the root by the status and navigation bars. */
fun View.padForSystemBars() {
    setOnApplyWindowInsetsListener { v, insets ->
        val (top, bottom) = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.systemBars()).let { it.top to it.bottom }
        else @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
        v.setPadding(v.paddingLeft, top, v.paddingRight, bottom); insets
    }
    requestApplyInsets()
}

/** Mission-control palette: midnight/graphite ground, electric mint accent, restrained status colours. */
object Palette {
    val ground = Color.rgb(9, 13, 17)
    val surface = Color.rgb(18, 24, 30)
    val raised = Color.rgb(25, 33, 41)
    val line = Color.rgb(36, 46, 54)
    val ink = Color.rgb(234, 241, 237)
    val muted = Color.rgb(143, 160, 153)
    val faint = Color.rgb(96, 110, 116)
    val mint = Color.rgb(124, 242, 196)
    val lime = Color.rgb(200, 255, 92)
    val amber = Color.rgb(255, 190, 92)
    val red = Color.rgb(255, 120, 104)
    val blue = Color.rgb(130, 176, 255)
    val violet = Color.rgb(186, 156, 255)

    fun state(s: TrustState) = when (s) { TrustState.VERIFIED -> mint; TrustState.CONFLICTING -> red; TrustState.MISSING -> faint; TrustState.NEEDS_CONFIRMATION -> amber }
    fun severity(s: Severity) = when (s) { Severity.HIGH -> red; Severity.MEDIUM -> amber; Severity.LOW -> blue; Severity.REVIEW -> violet }
    fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}

class Ui(private val context: Context) {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * context.resources.displayMetrics.density

    fun rounded(fill: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(fill); stroke?.let { setStroke(dp(1), it) }
    }

    fun text(value: CharSequence, size: Float = 15f, color: Int = Palette.ink, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        setLineSpacing(dpf(3f), 1f)
        typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL); else -> Typeface.create("sans-serif", Typeface.NORMAL) }
    }

    fun eyebrow(value: String, color: Int = Palette.mint) = text(value.uppercase(), 11f, color, mono = true).apply { letterSpacing = .16f }

    fun column(padH: Int = 0, padV: Int = 0) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padH), dp(padV), dp(padH), dp(padV)) }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun card(accent: Int? = null, fill: Int = Palette.surface) = column(18, 16).apply { background = rounded(fill, 22, accent?.let { Palette.alpha(it, 90) } ?: Palette.line) }

    fun lp(top: Int = 0, width: Int = -1) = LinearLayout.LayoutParams(width, -2).apply { topMargin = dp(top) }
    fun weight(w: Float = 1f, start: Int = 0) = LinearLayout.LayoutParams(0, -2, w).apply { marginStart = dp(start) }

    fun primary(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(Palette.ground); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(Palette.mint, 18); minHeight = dp(56); stateListAnimator = null; setOnClickListener { action() }
    }

    fun ghost(label: String, color: Int = Palette.mint, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 15f; setTextColor(color); stateListAnimator = null
        background = rounded(Color.TRANSPARENT, 16, Palette.alpha(color, 120)); minHeight = dp(50); setOnClickListener { action() }
    }

    fun link(label: String, color: Int = Palette.mint, action: () -> Unit) = text(label, 14f, color, bold = true).apply {
        setPadding(0, dp(10), 0, dp(10)); isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    fun chip(label: String, color: Int, filled: Boolean = false) = text(label, 11.5f, if (filled) Palette.ground else color, mono = true).apply {
        setPadding(dp(10), dp(5), dp(10), dp(5)); background = rounded(if (filled) color else Palette.alpha(color, 28), 99, if (filled) null else Palette.alpha(color, 110))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    fun input(hint: String, lines: Int = 1) = EditText(context).apply {
        this.hint = hint; textSize = 16f; setTextColor(Palette.ink); setHintTextColor(Palette.faint)
        background = rounded(Palette.raised, 16, Palette.line); setPadding(dp(16), dp(14), dp(16), dp(14))
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP } else isSingleLine = true
    }

    fun divider() = View(context).apply { setBackgroundColor(Palette.line); layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) } }
}

/** Slow breathing orb used while work is running. One cheap animator, cancelled when detached. */
class PulseView(context: Context, private val color: Int = Palette.mint) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f
        for (i in 0..2) {
            val p = (phase + i / 3f) % 1f
            paint.color = Palette.alpha(color, ((1 - p) * 90).toInt()); canvas.drawCircle(cx, cy, r * (.35f + .65f * p), paint)
        }
        paint.color = color; canvas.drawCircle(cx, cy, r * .3f, paint)
    }
}

/** Four-segment ring of real ledger counts. Empty ledger draws a neutral ring, not fake data. */
class TrustRingView(context: Context) : View(context) {
    var counts = TrustLedger.Counts(0, 0, 0, 0); set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    override fun onDraw(canvas: Canvas) {
        val stroke = width * .09f; paint.strokeWidth = stroke
        val box = RectF(stroke, stroke, width - stroke, height - stroke)
        val parts = listOf(counts.verified to Palette.mint, counts.needsConfirmation to Palette.amber, counts.conflicting to Palette.red, counts.missing to Palette.faint)
        val total = parts.sumOf { it.first }
        if (total == 0) { paint.color = Palette.line; canvas.drawArc(box, 0f, 360f, false, paint) }
        else { var start = -90f; parts.filter { it.first > 0 }.forEach { (n, c) -> val sweep = 360f * n / total; paint.color = c; canvas.drawArc(box, start + 1.5f, sweep - 3f, false, paint); start += sweep } }
        textPaint.color = Palette.ink; textPaint.textSize = width * .24f
        canvas.drawText(if (total == 0) "—" else "${counts.verified}/$total", width / 2f, height / 2f + width * .06f, textPaint)
        textPaint.color = Palette.muted; textPaint.textSize = width * .09f
        canvas.drawText("verified", width / 2f, height / 2f + width * .2f, textPaint)
    }
}

/**
 * Evidence graph: documents on an outer ring, person facts inside, an edge for every source line. Edge colour is the
 * fact's ledger state, so a conflict literally shows as two red edges into one node. Drawn from real snapshot data.
 */
class EvidenceGraphView(context: Context) : View(context) {
    data class Node(val label: String, val color: Int)
    private var docs: List<Node> = emptyList(); private var facts: List<Node> = emptyList(); private var edges: List<Triple<Int, Int, Int>> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }

    fun bind(ledger: List<LedgerEntry>) {
        val entries = ledger.filter { it.groups.isNotEmpty() }
        val docNames = entries.flatMap { e -> e.groups.flatMap { g -> g.facts.map { it.documentName } } }.distinct().take(8)
        docs = docNames.map { Node(it.substringBeforeLast('.').take(14), Palette.blue) }
        facts = entries.map { Node(it.type.label.take(12), Palette.state(it.state)) }
        edges = entries.flatMapIndexed { fi, e -> e.groups.flatMap { g -> g.facts.mapNotNull { f -> docNames.indexOf(f.documentName).takeIf { it >= 0 }?.let { Triple(it, fi, Palette.state(e.state)) } } } }.distinct()
        contentDescription = "Evidence graph: ${docs.size} documents connected to ${facts.size} facts"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); setMeasuredDimension(w, (w * .78f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val outer = min(width, height) * .42f; val inner = outer * .48f
        fun pos(i: Int, n: Int, r: Float, offset: Double) = (2 * Math.PI * i / maxOf(n, 1) + offset).let { Pair(cx + r * cos(it).toFloat(), cy + r * sin(it).toFloat()) }
        if (docs.isEmpty()) {
            label.color = Palette.faint; label.textSize = width * .035f
            canvas.drawText("No person facts yet — scan a résumé or certificate", cx, cy, label); return
        }
        val dp = docs.indices.map { pos(it, docs.size, outer, -Math.PI / 2) }
        val fp = facts.indices.map { pos(it, facts.size, inner, -Math.PI / 2 + .3) }
        paint.strokeWidth = width * .006f; paint.style = Paint.Style.STROKE
        edges.forEach { (d, f, c) -> paint.color = Palette.alpha(c, 150); canvas.drawLine(dp[d].first, dp[d].second, fp[f].first, fp[f].second, paint) }
        paint.style = Paint.Style.FILL
        label.textSize = width * .028f
        // Labels sit radially outward from their node so neighbouring labels do not overprint each other.
        fun outward(p: Pair<Float, Float>, distance: Float): Pair<Float, Float> {
            val dx = p.first - cx; val dy = p.second - cy; val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            return Pair(p.first + dx / len * distance, p.second + dy / len * distance + label.textSize / 3)
        }
        docs.forEachIndexed { i, n ->
            paint.color = Palette.alpha(n.color, 50); canvas.drawCircle(dp[i].first, dp[i].second, width * .035f, paint)
            paint.color = n.color; canvas.drawCircle(dp[i].first, dp[i].second, width * .016f, paint)
            val at = outward(dp[i], width * .07f)
            label.color = Palette.muted; canvas.drawText(n.label, at.first, at.second, label)
        }
        facts.forEachIndexed { i, n ->
            paint.color = Palette.alpha(n.color, 60); canvas.drawCircle(fp[i].first, fp[i].second, width * .03f, paint)
            paint.color = n.color; canvas.drawCircle(fp[i].first, fp[i].second, width * .013f, paint)
            val at = outward(fp[i], width * .085f)
            label.color = Palette.ink; canvas.drawText(n.label, at.first, at.second, label)
        }
    }
}
