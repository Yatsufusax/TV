package com.mistv.canales

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Piezas de diseño compartidas: colores, fondos con foco (control remoto) y ventanas centradas. */
object UiKit {
    const val PANEL = "#161B22"
    const val PANEL_LINE = "#2A3342"
    const val TILE = "#1F2733"
    const val TILE_FOCUS = "#2B3E5C"
    const val ACCENT = "#8AB4F8"
    const val TEXT = "#FFFFFF"
    const val TEXT_DIM = "#9AA4B2"

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(ctx: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeDp > 0) g.setStroke(dp(ctx, strokeDp), strokeColor)
        return g
    }

    /** Fondo con estados: normal, enfocado con el control remoto, presionado. */
    fun focusBg(ctx: Context, normal: String, focused: String, radiusDp: Int): StateListDrawable {
        val s = StateListDrawable()
        val hi = shape(ctx, Color.parseColor(focused), radiusDp, Color.parseColor(ACCENT), 2)
        s.addState(intArrayOf(android.R.attr.state_focused), hi)
        s.addState(intArrayOf(android.R.attr.state_pressed), hi)
        s.addState(intArrayOf(), shape(ctx, Color.parseColor(normal), radiusDp))
        return s
    }

    /** Ventana flotante en el centro de la pantalla, con fondo transparente (el panel lo dibuja el contenido). */
    fun newDialog(a: Activity, content: View, widthFraction: Float): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(content)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.CENTER)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.65f)
            val width = (a.resources.displayMetrics.widthPixels * widthFraction).toInt()
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return d
    }
}

/** Cartel centrado con título, mensaje y botones (se usa para los avisos de actualización). */
class CenterDialog(private val a: Activity, titleText: String) {
    private val panel = LinearLayout(a)
    private val msg = TextView(a)
    private val row = LinearLayout(a)
    private var primaryButton: View? = null
    private val dialog: Dialog

    init {
        panel.orientation = LinearLayout.VERTICAL
        val p = UiKit.dp(a, 24)
        panel.setPadding(p, p, p, UiKit.dp(a, 16))
        panel.background = UiKit.shape(a, Color.parseColor(UiKit.PANEL), 20, Color.parseColor(UiKit.PANEL_LINE), 1)

        val title = TextView(a)
        title.text = titleText
        title.setTextColor(Color.parseColor(UiKit.TEXT))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        panel.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        msg.setTextColor(Color.parseColor("#C4C9D0"))
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        msg.setLineSpacing(0f, 1.2f)
        val mlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = UiKit.dp(a, 12)
        panel.addView(msg, mlp)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        val rlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = UiKit.dp(a, 20)
        panel.addView(row, rlp)

        val scroll = ScrollView(a)
        scroll.addView(panel, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog = UiKit.newDialog(a, scroll, 0.62f)
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun setMessage(t: String) {
        msg.text = t
    }

    fun setCancelable(c: Boolean) {
        dialog.setCancelable(c)
        dialog.setCanceledOnTouchOutside(c)
    }

    fun setOnDismiss(l: () -> Unit) {
        dialog.setOnDismissListener { l() }
    }

    /** Agrega un botón; al tocarlo se cierra el cartel y se ejecuta la acción. */
    fun addButton(label: String, primary: Boolean, onClick: () -> Unit) {
        val b = TextView(a)
        b.text = label
        b.gravity = Gravity.CENTER
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.setTextColor(Color.parseColor(if (primary) "#0B1220" else UiKit.TEXT))
        val ph = UiKit.dp(a, 20)
        val pv = UiKit.dp(a, 12)
        b.setPadding(ph, pv, ph, pv)
        b.background = if (primary) {
            UiKit.focusBg(a, UiKit.ACCENT, "#B9D2FB", 12)
        } else {
            UiKit.focusBg(a, "#2A3342", UiKit.TILE_FOCUS, 12)
        }
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener {
            dialog.dismiss()
            onClick()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = UiKit.dp(a, 10)
        row.addView(b, lp)
        if (primary) primaryButton = b
    }

    fun show() {
        dialog.setOnShowListener {
            val f = primaryButton ?: row.getChildAt(0)
            f?.requestFocus()
        }
        dialog.show()
    }

    fun dismiss() {
        dialog.dismiss()
    }
}
