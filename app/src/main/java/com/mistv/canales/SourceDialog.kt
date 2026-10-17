package com.mistv.canales

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Ventana para elegir a mano con cuál enlace (fuente) se ve un canal. */
object SourceDialog {

    fun show(
        a: Activity,
        channel: String,
        count: Int,
        playing: Int,
        pinned: Int,
        dead: List<Boolean>,
        onAuto: () -> Unit,
        onPick: (Int) -> Unit
    ) {
        val dm = a.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val holder = arrayOfNulls<Dialog>(1)
        var first: View? = null

        val panel = LinearLayout(a)
        panel.orientation = LinearLayout.VERTICAL
        val pad = UiKit.dp(a, 20)
        panel.setPadding(pad, pad, pad, UiKit.dp(a, 14))
        panel.background = UiKit.shape(a, Color.parseColor(UiKit.PANEL), 22, Color.parseColor(UiKit.PANEL_LINE), 1)

        val title = TextView(a)
        title.text = "Fuentes de $channel"
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(Color.parseColor(UiKit.TEXT))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        panel.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sub = TextView(a)
        sub.text = "Elegí con qué enlace ver este canal. Se recuerda hasta que vuelvas a Automático."
        sub.setTextColor(Color.parseColor(UiKit.TEXT_DIM))
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val sublp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sublp.topMargin = UiKit.dp(a, 4)
        sublp.bottomMargin = UiKit.dp(a, 12)
        panel.addView(sub, sublp)

        fun addRow(label: String, status: String, statusColor: String, bold: Boolean, click: () -> Unit) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(UiKit.dp(a, 16), UiKit.dp(a, 13), UiKit.dp(a, 16), UiKit.dp(a, 13))
            row.background = UiKit.focusBg(a, UiKit.TILE, UiKit.TILE_FOCUS, 14)
            row.isFocusable = true
            row.isClickable = true
            row.setOnClickListener {
                holder[0]?.dismiss()
                click()
            }
            val l = TextView(a)
            l.text = label
            l.setTextColor(Color.parseColor(UiKit.TEXT))
            l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            if (bold) l.setTypeface(l.typeface, Typeface.BOLD)
            row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (status.isNotEmpty()) {
                val s = TextView(a)
                s.text = status
                s.gravity = Gravity.END
                s.setTextColor(Color.parseColor(statusColor))
                s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                row.addView(s, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = UiKit.dp(a, 8)
            panel.addView(row, lp)
            if (first == null) first = row
        }

        addRow(
            "Automático (la primera que ande)",
            if (pinned < 0) "Activo" else "",
            "#4ADE80",
            pinned < 0
        ) { onAuto() }

        for (i in 0 until count) {
            val parts = ArrayList<String>()
            if (i == playing) parts.add("Reproduciendo")
            if (i == pinned) parts.add("Fijada")
            val isDead = i < dead.size && dead[i]
            if (isDead) parts.add("Marcada caída")
            val color = if (isDead) "#F87171" else if (i == playing) "#4ADE80" else UiKit.ACCENT
            val label = if (i == 0) "Fuente 1 (principal)" else "Fuente ${i + 1} (respaldo)"
            addRow(label, parts.joinToString("  ·  "), color, i == pinned) { onPick(i) }
        }

        val scroll = ScrollView(a)
        scroll.addView(panel, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = UiKit.newDialog(a, scroll, if (widthDp >= 700f) 0.55f else 0.92f)
        holder[0] = d
        d.setOnShowListener { first?.requestFocus() }
        d.show()
    }
}
