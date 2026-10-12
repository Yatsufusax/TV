package com.mistv.canales

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MenuEntry(
    val icon: Int,
    val label: String,
    val sub: String?,
    val color: String,
    val action: () -> Unit
)

/** Menú principal: panel centrado con una cuadrícula de opciones con icono. */
object MenuDialog {

    fun show(a: Activity, title: String, subtitle: String, entries: List<MenuEntry>) {
        val dm = a.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val cols = if (widthDp >= 820f) 5 else if (widthDp >= 560f) 4 else 3
        val holder = arrayOfNulls<Dialog>(1)

        val panel = LinearLayout(a)
        panel.orientation = LinearLayout.VERTICAL
        val pad = UiKit.dp(a, 20)
        panel.setPadding(pad, pad, pad, UiKit.dp(a, 14))
        panel.background = UiKit.shape(a, Color.parseColor(UiKit.PANEL), 22, Color.parseColor(UiKit.PANEL_LINE), 1)

        val t = TextView(a)
        t.text = title
        t.setTextColor(Color.parseColor(UiKit.TEXT))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        t.setTypeface(t.typeface, Typeface.BOLD)
        panel.addView(t, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val st = TextView(a)
        st.text = subtitle
        st.setTextColor(Color.parseColor(UiKit.TEXT_DIM))
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val stl = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        stl.topMargin = UiKit.dp(a, 2)
        stl.bottomMargin = UiKit.dp(a, 12)
        panel.addView(st, stl)

        var first: View? = null
        val m = UiKit.dp(a, 5)
        for (chunk in entries.chunked(cols)) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            for (e in chunk) {
                val tl = makeTile(a, e) {
                    holder[0]?.dismiss()
                    e.action()
                }
                if (first == null) first = tl
                val lp = LinearLayout.LayoutParams(0, UiKit.dp(a, 124), 1f)
                lp.setMargins(m, m, m, m)
                row.addView(tl, lp)
            }
            repeat(cols - chunk.size) {
                val sp = View(a)
                val lp = LinearLayout.LayoutParams(0, UiKit.dp(a, 124), 1f)
                lp.setMargins(m, m, m, m)
                row.addView(sp, lp)
            }
            panel.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val hint = TextView(a)
        hint.text = "Atrás para cerrar"
        hint.gravity = Gravity.CENTER
        hint.setTextColor(Color.parseColor(UiKit.TEXT_DIM))
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val hl = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hl.topMargin = UiKit.dp(a, 6)
        panel.addView(hint, hl)

        val scroll = ScrollView(a)
        scroll.addView(panel, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = UiKit.newDialog(a, scroll, if (widthDp >= 820f) 0.82f else 0.94f)
        holder[0] = d
        d.setOnShowListener { first?.requestFocus() }
        d.show()
    }

    private fun makeTile(a: Activity, e: MenuEntry, onClick: () -> Unit): View {
        val accent = Color.parseColor(e.color)
        val t = LinearLayout(a)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        t.setPadding(UiKit.dp(a, 6), UiKit.dp(a, 8), UiKit.dp(a, 6), UiKit.dp(a, 8))
        t.background = UiKit.focusBg(a, UiKit.TILE, UiKit.TILE_FOCUS, 16)
        t.isFocusable = true
        t.isClickable = true
        t.setOnClickListener { onClick() }

        val iv = ImageView(a)
        iv.setImageResource(e.icon)
        iv.setColorFilter(accent)
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor((accent and 0x00FFFFFF) or 0x33000000)
        iv.background = circle
        val ip = UiKit.dp(a, 11)
        iv.setPadding(ip, ip, ip, ip)
        t.addView(iv, LinearLayout.LayoutParams(UiKit.dp(a, 46), UiKit.dp(a, 46)))

        val label = TextView(a)
        label.text = e.label
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.ellipsize = TextUtils.TruncateAt.END
        label.setTextColor(Color.parseColor(UiKit.TEXT))
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val ll = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ll.topMargin = UiKit.dp(a, 8)
        t.addView(label, ll)

        if (e.sub != null) {
            val sub = TextView(a)
            sub.text = e.sub
            sub.gravity = Gravity.CENTER
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            sub.setTextColor(accent)
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            t.addView(sub, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return t
    }
}
