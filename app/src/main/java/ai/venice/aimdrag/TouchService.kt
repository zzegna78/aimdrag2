package ai.venice.aimdrag

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class TouchService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var guide: GuideView? = null
    private var pad: PadView? = null
    private var padParams: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> apply() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("aimdrag", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        apply()
        Toast.makeText(this, "AimDrag активен", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        guide?.let { runCatching { wm.removeView(it) } }
        pad?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    private fun fireCenter(): PointF {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.toFloat()
        val sh = dm.heightPixels.toFloat()
        val r = sw * prefs.getFloat("fire_pct", 24f) / 100f / 2f
        return PointF(sw - sw * 0.015f - r, sh - sh * 0.04f - r)
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
            if (pad == null) {
                val pw = (sw * 0.30f).toInt()
                val ph = (sh * 0.40f).toInt()
                val v = PadView(this)
                val lp = WindowManager.LayoutParams(
                    pw, ph,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.BOTTOM or Gravity.END
                val fire = fireCenter()
                lp.x = prefs.getInt("pad_x", (sw * 0.02f).toInt())
                lp.y = prefs.getInt("pad_y", (sh - fire.y + sh * 0.03f).toInt())
                runCatching { wm.addView(v, lp) }
                pad = v
                padParams = lp
            }
            guide?.invalidate()
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
            val f = fireCenter()

            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.argb(150, 239, 68, 68)
            canvas.drawCircle(f.x, f.y, r, p)
            canvas.drawLine(f.x - r * 0.45f, f.y, f.x + r * 0.45f, f.y, p)
            canvas.drawLine(f.x, f.y - r * 0.45f, f.x, f.y + r * 0.45f, p)

            p.color = Color.argb(110, 59, 130, 246)
            p.strokeWidth = 2f
            p.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
            canvas.drawLine(f.x, f.y - r, f.x, f.y - r * 4.5f, p)
            if (padParams != null) {
                val pp = padParams!!
                val pw = pp.width.toFloat()
                val ph = pp.height.toFloat()
                canvas.drawRect(sw - pp.x - pw, sh - pp.y - ph, sw - pp.x, sh - pp.y, p)
            }
            p.pathEffect = null

            p.style = Paint.Style.FILL
            p.color = Color.argb(200, 255, 255, 255)
            p.textSize = sw * 0.026f
            canvas.drawText("drag", f.x - sw * 0.022f, f.y - r * 4.8f, p)
            if (padParams != null) {
                canvas.drawText("hold = move", sw * 0.03f, sh * 0.09f, p)
            }
        }
    }

    private inner class PadView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val loc = IntArray(2)
        private var downTime = 0L
        private var downRawX = 0f
        private var downRawY = 0f
        private var startLpX = 0
        private var startLpY = 0
        private var startGlobal = PointF()
        private var localStart = PointF()
        private var pts = ArrayList<FloatArray>()
        private var pressed = false
        private var moving = false
        private var lastDispatch = 0L

        private val holdRunnable = Runnable {
            if (pressed) {
                moving = true
                pts.clear()
                invalidate()
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    getLocationOnScreen(loc)
                    startGlobal = PointF(loc[0] + e.x, loc[1] + e.y)
                    localStart = PointF(e.x, e.y)
                    downTime = SystemClock.uptimeMillis()
                    downRawX = e.rawX
                    downRawY = e.rawY
                    padParams?.let { startLpX = it.x; startLpY = it.y }
                    pts = ArrayList()
                    pressed = true
                    moving = false
                    main.postDelayed(holdRunnable, 550L)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (moving) {
                        padParams?.let {
                            it.x = startLpX - (e.rawX - downRawX).toInt()
                            it.y = startLpY - (e.rawY - downRawY).toInt()
                            pad?.let { v -> runCatching { wm.updateViewLayout(v, it) } }
                        }
                        guide?.invalidate()
                    } else {
                        pts.add(floatArrayOf(e.x, e.y))
                        if (prefs.getBoolean("live", false)) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastDispatch > 40L) {
                                lastDispatch = now
                                fire((now - downTime).coerceAtLeast(60L))
                            }
                        }
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    if (moving) {
                        padParams?.let {
                            prefs.edit().putInt("pad_x", it.x).putInt("pad_y", it.y).apply()
                        }
                    } else if (pts.isEmpty()) {
                        fire(70L)
                    } else {
                        fire((SystemClock.uptimeMillis() - downTime).coerceAtLeast(60L))
                    }
                    pressed = false
                    moving = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        private fun fire(duration: Long) {
            val mult = prefs.getFloat("mult", 2f)
            val dm = resources.displayMetrics
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            val path = Path()
            path.moveTo(startGlobal.x, startGlobal.y)
            for (pt in pts) {
                val x = (startGlobal.x + (pt[0] - localStart.x) * mult).coerceIn(1f, sw - 1f)
                val y = (startGlobal.y + (pt[1] - localStart.y) * mult).coerceIn(1f, sh - 1f)
                path.lineTo(x, y)
            }
            if (pts.isEmpty()) {
                path.lineTo(startGlobal.x, startGlobal.y - 2f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration.coerceIn(40L, 500L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            runCatching { dispatchGesture(gesture, null, null) }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.style = Paint.Style.FILL
            p.color = Color.argb(if (pressed) 70 else 26, 59, 130, 246)
            canvas.drawRoundRect(4f, 4f, w - 4f, h - 4f, 24f, 24f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.argb(if (moving) 235 else if (pressed) 200 else 130, 59, 130, 246)
            canvas.drawRoundRect(4f, 4f, w - 4f, h - 4f, 24f, 24f, p)

            p.style = Paint.Style.FILL
            p.color = Color.argb(220, 255, 255, 255)
            p.textSize = w * 0.11f
            canvas.drawText(if (moving) "move" else "drag", w * 0.09f, h * 0.16f, p)
            canvas.drawLine(w / 2f, h * 0.30f, w / 2f, h * 0.55f, p)
            p.style = Paint.Style.STROKE
            canvas.drawLine(w / 2f - w * 0.06f, h * 0.40f, w / 2f, h * 0.30f, p)
            canvas.drawLine(w / 2f + w * 0.06f, h * 0.40f, w / 2f, h * 0.30f, p)
        }
    }
}
