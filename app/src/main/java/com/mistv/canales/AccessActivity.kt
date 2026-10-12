package com.mistv.canales

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pantalla que pide el código de acceso la primera vez. Si ya está desbloqueada, pasa directo a la app. */
class AccessActivity : Activity() {

    private lateinit var edit: EditText
    private lateinit var msg: TextView
    private lateinit var button: Button
    private var busy = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AccessGate.isUnlocked(this)) {
            openMain()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = TextView(this)
        title.text = "Código de acceso"
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        title.gravity = Gravity.CENTER

        val sub = TextView(this)
        sub.text = if (AccessGate.hadMonthly(this)) "El código del mes venció. Escribí el código nuevo"
        else "Escribilo una sola vez en este equipo"
        sub.setTextColor(Color.parseColor("#9AA4B2"))
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        sub.gravity = Gravity.CENTER

        edit = EditText(this)
        edit.hint = "XXXXX-XXXXX"
        edit.setHintTextColor(Color.parseColor("#5B6573"))
        edit.setTextColor(Color.WHITE)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        edit.gravity = Gravity.CENTER
        edit.setSingleLine(true)
        edit.setBackgroundColor(Color.parseColor("#1B2230"))
        edit.setPadding(dp(16), dp(12), dp(16), dp(12))
        edit.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        edit.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submit()
                true
            } else {
                false
            }
        }

        button = Button(this)
        button.text = "Entrar"
        button.setOnClickListener { submit() }

        msg = TextView(this)
        msg.setTextColor(Color.parseColor("#FF6B6B"))
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        msg.gravity = Gravity.CENTER

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(32), dp(24), dp(32), dp(24))
        box.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(sub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(20)
        })
        box.addView(edit, LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(button, LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(msg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setBackgroundColor(Color.parseColor("#0E1116"))
        scroll.addView(box, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(scroll)
        edit.requestFocus()
    }

    private fun submit() {
        if (busy) return
        val locked = AccessGate.lockedSeconds(this)
        if (locked > 0) {
            msg.text = "Demasiados intentos. Esperá $locked segundos."
            return
        }
        val text = edit.text.toString()
        if (AccessGate.normalize(text).isEmpty()) {
            msg.text = "Escribí el código."
            return
        }
        busy = true
        button.isEnabled = false
        msg.setTextColor(Color.parseColor("#9AA4B2"))
        msg.text = "Verificando…"
        Thread {
            val ok = AccessGate.tryUnlock(this, text)
            runOnUiThread {
                busy = false
                button.isEnabled = true
                if (!isFinishing) {
                    if (ok) {
                        openMain()
                    } else {
                        val left = AccessGate.lockedSeconds(this)
                        msg.setTextColor(Color.parseColor("#FF6B6B"))
                        msg.text = if (left > 0) "Demasiados intentos. Esperá $left segundos." else "Código incorrecto."
                        edit.selectAll()
                    }
                }
            }
        }.start()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
