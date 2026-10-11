package com.kaagaz.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

class EvidenceOverlayView(
    context: Context,
    private val bitmap: Bitmap,
    private val highlights: List<Highlight>
) : View(context) {
    data class Highlight(
        val box: android.graphics.Rect,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val color: Int,
        val number: Int
    )

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
        isFakeBoldText = true
    }

    init {
        contentDescription = if (highlights.size > 1) {
            "Original document with ${highlights.size} evidence regions highlighted"
        } else "Original document with supporting evidence highlighted"
        minimumHeight = dp(240f).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val naturalHeight = (width.toFloat() * bitmap.height / bitmap.width).roundToInt()
        val preferred = naturalHeight.coerceIn(dp(300f).roundToInt(), dp(720f).roundToInt())
        setMeasuredDimension(width, resolveSize(preferred, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val destination = RectF(
            (width - drawnWidth) / 2f,
            (height - drawnHeight) / 2f,
            (width + drawnWidth) / 2f,
            (height + drawnHeight) / 2f
        )
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, null, destination, imagePaint)

        highlights.forEach { highlight ->
            val rect = RectF(
                destination.left + highlight.box.left.toFloat() / highlight.sourceWidth * destination.width(),
                destination.top + highlight.box.top.toFloat() / highlight.sourceHeight * destination.height(),
                destination.left + highlight.box.right.toFloat() / highlight.sourceWidth * destination.width(),
                destination.top + highlight.box.bottom.toFloat() / highlight.sourceHeight * destination.height()
            )
            shadePaint.color = withAlpha(highlight.color, 42)
            boxPaint.color = highlight.color
            canvas.drawRoundRect(rect, dp(5f), dp(5f), shadePaint)
            canvas.drawRoundRect(rect, dp(5f), dp(5f), boxPaint)
            if (highlights.size > 1) drawBadge(canvas, rect, highlight)
        }
    }

    private fun drawBadge(canvas: Canvas, rect: RectF, highlight: Highlight) {
        val radius = dp(13f)
        val x = (rect.left + radius).coerceAtMost(width - radius)
        val y = (rect.top - radius).coerceAtLeast(radius)
        shadePaint.color = highlight.color
        canvas.drawCircle(x, y, radius, shadePaint)
        canvas.drawText(highlight.number.toString(), x, y - (badgePaint.ascent() + badgePaint.descent()) / 2f, badgePaint)
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
