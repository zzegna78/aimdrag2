package ai.venice.aimdrag

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        prefs = getSharedPreferences("aimdrag", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply { text = "AimDrag"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Площадка драга поверх игры. Призрачный тач уходит на реальную кнопку огня, чувствительность умножается."
            textSize = 13f
            setPadding(0, dp(10), 0, dp(18))
        })

        status = TextView(this).apply { textSize = 13f }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Открыть спец. возможности"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(toggle("Показывать направляющие", "guides", true))
        root.addView(toggle("Ассист драга", "assist", false))
        root.addView(toggle("Живой режим (без задержки)", "live", false))
        root.addView(slider("Множитель драга", "mult", 1.0f, 3.0f, 2.0f))
        root.addView(slider("Размер кнопки огня, % ширины", "fire_pct", 18f, 34f, 24f))

        root.addView(Button(this).apply {
            text = "Сбросить позицию площадки"
            setOnClickListener {
                prefs.edit().remove("pad_x").remove("pad_y").apply()
                Toast.makeText(this@MainActivity, "Позиция сброшена", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(TextView(this).apply {
            text = "Красный круг с крестом — куда бьёт призрачный тач, совмести его с кнопкой огня в HUD. Синий прямоугольник — площадка, держи палец на нём и веди вверх. Долгое нажатие на площадке — перетащить её."
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        status.text = if (enabled) "Служба включена" else "Служба выключена"
        status.setTextColor(if (enabled) 0xFF22C55E.toInt() else 0xFFEF4444.toInt())
    }

    private fun toggle(label: String, key: String, def: Boolean): ViewGroup {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        })
        return row
    }

    private fun slider(label: String, key: String, min: Float, max: Float, def: Float): ViewGroup {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val caption = TextView(this).apply { textSize = 13f }
        fun paint(v: Float) { caption.text = "$label: " + String.format("%.1f", v) }
        paint(prefs.getFloat(key, def))
        box.addView(caption)
        box.addView(SeekBar(this).apply {
            this.max = ((max - min) * 10).toInt()
            progress = ((prefs.getFloat(key, def) - min) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p / 10f
                    paint(v)
                    prefs.edit().putFloat(key, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        box.setPadding(0, dp(12), 0, 0)
        return box
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
