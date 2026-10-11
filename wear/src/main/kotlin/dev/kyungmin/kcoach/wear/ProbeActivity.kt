package dev.kyungmin.kcoach.wear

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Watch side of the measurement probe: start/stop streaming and toggle screen-on. */
class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var startButton: TextView
    private lateinit var screenButton: TextView
    private var keepScreenOn = true

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 40)
        }
        column.addView(text("KCOACH 실측", 12f, Color.rgb(0, 229, 255), bold = true))
        statusText = text("", 11f, Color.WHITE).apply { setPadding(0, 10, 0, 10) }
        column.addView(statusText)
        startButton = button("시작") { toggleProbe() }
        column.addView(startButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
        screenButton = button("") {
            keepScreenOn = !keepScreenOn
            applyScreenPolicy()
            render()
        }
        column.addView(screenButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        })
        applyScreenPolicy()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun toggleProbe() {
        val intent = Intent(this, ProbeService::class.java)
        if (ProbeStatus.running) stopService(intent) else startForegroundService(intent)
        handler.postDelayed({ render() }, 300L)
    }

    private fun applyScreenPolicy() {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun render() {
        val s = ProbeStatus
        startButton.text = if (s.running) "정지" else "시작"
        screenButton.text = if (keepScreenOn) "화면 켜둠: 켬" else "화면 켜둠: 끔"
        statusText.text = if (!s.running) {
            "폰에서 실측 화면을 먼저 열고 시작을 눌러"
        } else buildString {
            append(if (s.connected) "● 폰 연결됨\n" else "○ ${s.message}\n")
            append("가속도 %.0fHz · 자이로 %.0fHz\n".format(s.accelHz, s.gyroHz))
            append("보냄 ${s.sentSamples} · 대기 ${s.bufferedSamples}")
            if (s.droppedSamples > 0) append("\n버림 ${s.droppedSamples}")
            append("\n최소 주기 ${s.accelMinDelayUs}/${s.gyroMinDelayUs}µs")
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(value: String, onClick: () -> Unit) = text(value, 13f, Color.WHITE, bold = true).apply {
        setPadding(16, 14, 16, 14)
        background = GradientDrawable().apply {
            setColor(Color.rgb(35, 58, 72))
            cornerRadius = 40f
        }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
