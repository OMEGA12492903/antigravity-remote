package com.omegax.antigravityremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lightweight zero-dependency crash screen (runs in isolated :crashui process)
 * so any unexpected exception is visible and copyable instead of silently exiting.
 */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "Error desconocido"
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            setPadding(pad, (48 * dp).toInt(), pad, pad)
        }

        val title = TextView(this).apply {
            text = "Antigravity Remote se cerró inesperadamente"
            setTextColor(Color.parseColor("#F28B82"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "Copia este diagnóstico y pásamelo para arreglarlo al instante:"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 13f
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (12 * dp).toInt())
        }

        val copyBtn = Button(this).apply {
            text = "Copiar error"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Antigravity Crash", trace))
                Toast.makeText(this@CrashActivity, "Error copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }

        val retryBtn = Button(this).apply {
            text = "Reintentar"
            setOnClickListener {
                val restart = Intent(this@CrashActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(restart)
                finish()
            }
        }

        btnRow.addView(copyBtn)
        btnRow.addView(retryBtn)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#141518"))
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }

        val traceView = TextView(this).apply {
            text = trace
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        scroll.addView(traceView)
        root.addView(title)
        root.addView(subtitle)
        root.addView(btnRow)
        root.addView(scroll)

        setContentView(root)
    }

    companion object {
        const val EXTRA_TRACE = "extra_crash_trace"
    }
}
