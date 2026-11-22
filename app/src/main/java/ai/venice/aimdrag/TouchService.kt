package ai.venice.aimdrag

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class TouchService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var guide: GuideView? = null
    private var pad: PadView? = null
    private var padParams: WindowManager.LayoutParams? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("aimdrag", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        guide?.let { runCatching { wm.removeView(it) } }
        pad?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    private fun apply() {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels

        if (prefs.getBoolean("guides", true)) {
            if (guide == null) {
                val v = GuideView(this)
                val lp = WindowManager.LayoutParams(
                    sw, sh,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                runCatching { wm.addView(v, lp) }
                guide = v
            }
            guide?.invalidate()
        } else {
            guide?.let { runCatching { wm.removeView(it) } }
            guide = null
        }

        if (prefs.getBoolean("assist", false)) {
            val size = (sw * prefs.getFloat("fire_pct", 24f) / 100f).toInt()
            if (pad == null) {
                val v = PadView(this)
                val lp = WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.x = (sw * 0.015f).toInt()
                lp.y = (sh * 0.04f).toInt()
                runCatching { wm.addView(v, lp) }
                pad = v
                padParams = lp
            } else {
                padParams?.let {
                    it.width = size
                    it.height = size
                    pad?.let { p -> runCatching { wm.updateViewLayout(p, it) } }
                }
            }
        } else {
            pad?.let { runCatching { wm.removeView(it) } }
            pad = null
        }
    }

    private inner class GuideView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            val r = sw * prefs.getFloat("fire_pct", 24f) / 100f / 2f
            val cx = sw - sw * 0.015f - r
            val cy = sh - sh * 0.04f - r

            p.style = Paint.Style.FILL
            p.color = Color.argb(28, 59, 130, 246)
            canvas.drawRect(cx - r * 1.9f, cy - r * 4.2f, sw, sh, p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.argb(140, 239, 68, 68)
            canvas.drawCircle(cx, cy, r, p)

            p.color = Color.argb(120, 59, 130, 246)
            p.strokeWidth = 2f
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 12f), 0f)
            canvas.drawLine(cx, cy - r, cx, cy - r * 4f, p)
            p.pathEffect = null

            p.style = Paint.Style.FILL
            p.color = Color.argb(190, 255, 255, 255)
            p.textSize = sw * 0.028f
            canvas.drawText("drag", cx - sw * 0.02f, cy - r * 4.4f, p)
        }
    }

    private inner class PadView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val loc = IntArray(2)
        private var down = 0L
        private var sx = 0f
        private var sy = 0f
        private val pts = ArrayList<FloatArray>()
        private var dragging = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    getLocationOnScreen(loc)
                    down = SystemClock.uptimeMillis()
                    sx = e.x
                    sy = e.y
                    pts.clear()
                    dragging = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        pts.add(floatArrayOf(e.x, e.y))
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        invalidate()
                        replay()
                    }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        private fun replay() {
            if (pts.isEmpty()) return
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            val mult = prefs.getFloat("mult", 2f)
            val gx = loc[0] + sx
            val gy = loc[1] + sy
            val path = Path()
            path.moveTo(gx, gy)
            for (pt in pts) {
                val x = (gx + (pt[0] - sx) * mult).coerceIn(1f, sw - 1f)
                val y = (gy + (pt[1] - sy) * mult).coerceIn(1f, sh - 1f)
                path.lineTo(x, y)
            }
            val dur = (SystemClock.uptimeMillis() - down).coerceIn(50L, 500L)
            val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            runCatching { dispatchGesture(gesture, null, null) }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.style = Paint.Style.FILL
            p.color = Color.argb(if (dragging) 80 else 30, 239, 68, 68)
            canvas.drawCircle(w / 2f, h / 2f, w / 2f - 4f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = Color.argb(if (dragging) 230 else 150, 239, 68, 68)
            canvas.drawCircle(w / 2f, h / 2f, w / 2f - 4f, p)
        }
    }
}
