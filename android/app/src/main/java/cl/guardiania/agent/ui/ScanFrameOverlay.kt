package cl.guardiania.agent.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Marco de escaneo: oscurece todo menos un recuadro central, dibuja las
 * esquinas y una lÃ­nea que recorre el Ã¡rea. Es solo guÃ­a visual; el lector
 * decodifica el cuadro completo de la cÃ¡mara.
 */
class ScanFrameOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val hole = RectF()

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C7081B17")
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A9C6B8")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val side = min(width, height) * 0.66f
        val centerX = width / 2f
        val centerY = height * 0.45f
        hole.set(centerX - side / 2f, centerY - side / 2f, centerX + side / 2f, centerY + side / 2f)
        linePaint.shader = LinearGradient(
            hole.left + 14f * density,
            0f,
            hole.right - 14f * density,
            0f,
            intArrayOf(0x00A9C6B8, 0xE6A9C6B8.toInt(), 0x00A9C6B8),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hole.isEmpty) return

        val scrim = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(hole, 24f * density, 24f * density, Path.Direction.CW)
        }
        canvas.drawPath(scrim, scrimPaint)

        drawCorners(canvas)

        val lineTop = hole.top + 16f * density + (hole.height() - 32f * density) * progress
        canvas.drawRoundRect(
            hole.left + 14f * density,
            lineTop,
            hole.right - 14f * density,
            lineTop + 2f * density,
            1f * density,
            1f * density,
            linePaint,
        )
    }

    private fun drawCorners(canvas: Canvas) {
        val length = 32f * density
        val inset = 2f * density
        val box = RectF(hole).apply { inset(-inset, -inset) }
        val path = Path().apply {
            moveTo(box.left, box.top + length)
            lineTo(box.left, box.top)
            lineTo(box.left + length, box.top)

            moveTo(box.right - length, box.top)
            lineTo(box.right, box.top)
            lineTo(box.right, box.top + length)

            moveTo(box.right, box.bottom - length)
            lineTo(box.right, box.bottom)
            lineTo(box.right - length, box.bottom)

            moveTo(box.left + length, box.bottom)
            lineTo(box.left, box.bottom)
            lineTo(box.left, box.bottom - length)
        }
        canvas.drawPath(path, cornerPaint)
    }
}

