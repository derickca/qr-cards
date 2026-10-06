package ca.derickcampbell.qrcards.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ca.derickcampbell.qrcards.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Large touch-friendly HSV color picker: glide a finger over the
 * saturation/value plane (for the current hue) or the hue bar, watch the
 * hex readout update live, then Cancel or OK. Replaces the old hex-entry
 * dialog for the QR code's custom color.
 */
object HsvColorPickerDialog {

    fun show(context: Context, initialColor: Int, onColorPicked: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        var hue = hsv[0]
        var sat = hsv[1]
        var value = hsv[2]
        val density = context.resources.displayMetrics.density

        fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

        val pad = (20 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }

        // Hex readout + swatch row.
        val readoutRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        }
        val swatch = ImageView(context).apply {
            setImageResource(R.drawable.dot)
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(), (40 * density).toInt()
            ).apply { marginEnd = (12 * density).toInt() }
        }
        val hexText = TextView(context).apply {
            textSize = 20f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        readoutRow.addView(swatch)
        readoutRow.addView(hexText)
        root.addView(readoutRow)

        lateinit var plane: SvPlaneView
        lateinit var hueBar: HueBarView

        fun refresh() {
            val c = currentColor()
            hexText.text = "#%06X".format(0xFFFFFF and c)
            swatch.imageTintList = ColorStateList.valueOf(c)
            plane.setHsv(hue, sat, value)
            hueBar.hue = hue
        }

        // Picker row: SV plane (square, fills width) + hue bar.
        val pickerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        plane = SvPlaneView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            onPick = { s, v ->
                sat = s
                value = v
                refresh()
            }
        }
        hueBar = HueBarView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (36 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { marginStart = (12 * density).toInt() }
            onPick = { h ->
                hue = h
                refresh()
            }
        }
        pickerRow.addView(plane)
        pickerRow.addView(hueBar)
        root.addView(pickerRow)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.qr_color_custom_title)
            .setMessage(R.string.qr_color_custom_message)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> onColorPicked(currentColor()) }
            .show()
        refresh()
    }

    /**
     * Saturation (x) × value (y) plane for one hue. White→hue left to
     * right, transparent→black top to bottom, with a cursor on the pick.
     */
    private class SvPlaneView(context: Context) : View(context) {
        private var hue = 0f
        private var sat = 0f
        private var value = 0f
        var onPick: ((Float, Float) -> Unit)? = null

        private val planePaint = Paint()
        private val shadePaint = Paint()
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.WHITE
        }
        private val cursorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            color = Color.argb(140, 0, 0, 0)
        }

        init {
            isClickable = true
        }

        fun setHsv(h: Float, s: Float, v: Float) {
            hue = h
            sat = s
            value = v
            rebuildShaders()
            invalidate()
        }

        private fun rebuildShaders() {
            if (width == 0 || height == 0) return
            val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            planePaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                Color.WHITE, hueColor, Shader.TileMode.CLAMP
            )
            shadePaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildShaders()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Square: as wide as the dialog gives us.
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, w)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), planePaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
            val cx = sat * width
            val cy = (1f - value) * height
            val r = 22f
            canvas.drawCircle(cx, cy, r, cursorShadowPaint)
            canvas.drawCircle(cx, cy, r, cursorPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val s = (event.x / width).coerceIn(0f, 1f)
                    val v = (1f - event.y / height).coerceIn(0f, 1f)
                    onPick?.invoke(s, v)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /** Vertical rainbow bar: glide to pick the hue for the SV plane. */
    private class HueBarView(context: Context) : View(context) {
        var hue = 0f
            set(value) {
                field = value
                invalidate()
            }
        var onPick: ((Float) -> Unit)? = null

        private val barPaint = Paint()
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.WHITE
        }

        init {
            isClickable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (h == 0) return
            val stops = floatArrayOf(0f, 1f / 6, 2f / 6, 3f / 6, 4f / 6, 5f / 6, 1f)
            val colors = IntArray(7) { i ->
                Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f))
            }
            barPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(), colors, stops, Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), barPaint)
            val cy = hue / 360f * height
            canvas.drawLine(0f, cy, width.toFloat(), cy, cursorPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    onPick?.invoke((event.y / height).coerceIn(0f, 1f) * 360f)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
