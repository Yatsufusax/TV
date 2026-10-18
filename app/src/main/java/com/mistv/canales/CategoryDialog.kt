package com.mistv.canales

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Ventana para elegir una categoría (grupo de la lista) y ver solo sus canales. */
object CategoryDialog {

    /**
     * @param groups pares (nombre del grupo, cantidad de canales), en el orden de la lista
     * @param current categoría activa (null = todas)
     * @param onPick recibe la categoría elegida (null = todas)
     */
    fun show(
        a: Activity,
        total: Int,
        groups: List<Pair<String, Int>>,
        current: String?,
        onPick: (String?) -> Unit
    ) {
        val dm = a.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val holder = arrayOfNulls<Dialog>(1)
        var first: View? = null
        var focusTarget: View? = null

        val panel = LinearLayout(a)
        panel.orientation = LinearLayout.VERTICAL
        val pad = UiKit.dp(a, 20)
        panel.setPadding(pad, pad, pad, UiKit.dp(a, 14))
        panel.background = UiKit.shape(a, Color.parseColor(UiKit.PANEL), 22, Color.parseColor(UiKit.PANEL_LINE), 1)

        val title = TextView(a)
        title.text = "Categorías"
        title.setTextColor(Color.parseColor(UiKit.TEXT))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        panel.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sub = TextView(a)
        sub.text = "Elegí una categoría para ver solo esos canales en la lista."
        sub.setTextColor(Color.parseColor(UiKit.TEXT_DIM))
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val sublp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sublp.topMargin = UiKit.dp(a, 4)
        sublp.bottomMargin = UiKit.dp(a, 12)
        panel.addView(sub, sublp)

        fun addRow(label: String, count: Int, active: Boolean, value: String?) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(UiKit.dp(a, 16), UiKit.dp(a, 13), UiKit.dp(a, 16), UiKit.dp(a, 13))
            row.background = UiKit.focusBg(a, UiKit.TILE, UiKit.TILE_FOCUS, 14)
            row.isFocusable = true
            row.isClickable = true
            row.setOnClickListener {
                holder[0]?.dismiss()
                onPick(value)
            }
            val l = TextView(a)
            l.text = label
            l.setTextColor(Color.parseColor(UiKit.TEXT))
            l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            if (active) l.setTypeface(l.typeface, Typeface.BOLD)
            row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val s = TextView(a)
            s.text = if (active) "Activa  ·  $count" else "$count"
            s.gravity = Gravity.END
            s.setTextColor(Color.parseColor(if (active) "#4ADE80" else UiKit.ACCENT))
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            row.addView(s, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = UiKit.dp(a, 8)
            panel.addView(row, lp)
            if (first == null) first = row
            if (active) focusTarget = row
        }

        addRow("Todas", total, current == null, null)
        for ((g, n) in groups) addRow(g, n, g == current, g)

        val scroll = ScrollView(a)
        scroll.addView(panel, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = UiKit.newDialog(a, scroll, if (widthDp >= 700f) 0.55f else 0.92f)
        holder[0] = d
        // El foco arranca en la categoría activa (o en "Todas")
        d.setOnShowListener { (focusTarget ?: first)?.requestFocus() }
        d.show()
    }
}
