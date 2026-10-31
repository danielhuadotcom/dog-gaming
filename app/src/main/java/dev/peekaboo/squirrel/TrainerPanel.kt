package dev.peekaboo.squirrel

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen trainer overlay (opened with Back or the corner gear). Plain views, no XML.
 * Left: settings (tap a button to cycle its value). Right: instant actions.
 */
class TrainerPanel(
    context: Context,
    private val settings: TrainerSettings,
    private val status: () -> String,
    private val onSettingsChanged: () -> Unit,
    private val actions: List<Pair<String, () -> Unit>>,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val statusView = TextView(context)
    private val dp = context.resources.displayMetrics.density

    init {
        setBackgroundColor(0xE6060B22.toInt())
        isClickable = true // swallow touches so the dog/trainer can't boop the game underneath
        visibility = GONE

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(16), px(24), px(16)) }
        root.addView(TextView(context).apply {
            text = "Trainer"
            textSize = 26f; setTextColor(Color.WHITE)
        })
        statusView.apply { textSize = 14f; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, px(4), 0, px(12)) }
        root.addView(statusView)

        val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(column("Settings", settingButtons()), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        cols.addView(View(context), LinearLayout.LayoutParams(px(24), 1))
        cols.addView(column("Do it now", actions.map { (label, fn) -> button(label) { fn() } }),
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        root.addView(cols)
        root.addView(TextView(context).apply {
            text = "Back button or ▶ Resume returns to the game. Footage: Wikimedia Commons, CC BY / BY-SA / CC0 — see WILDLIFE_CREDITS.md."
            textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, px(12), 0, 0)
        })
        addView(ScrollView(context).apply { addView(root) })
    }

    fun open() { refresh(); visibility = VISIBLE }
    fun close() { visibility = GONE; onClose() }
    val isOpen get() = visibility == VISIBLE

    private fun refresh() { statusView.text = status() }

    private class Cycler(val label: () -> String, val next: () -> Unit)

    private fun settingButtons(): List<View> {
        val s = settings
        val items = listOf(
            Cycler({ "Animals: " + arrayOf("Cartoon only", "Mixed cartoon + real footage", "Real footage only")[s.footage] }) {
                s.footage = (s.footage + 1) % 3
            },
            Cycler({ "Level: " + levelName(s.level) }) {
                val steps = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                val i = steps.indexOfFirst { it > s.level + 0.01f }
                s.level = if (i < 0) 0f else steps[i]
            },
            Cycler({ "Auto-adjust level: " + onOff(s.autoLevel) }) { s.autoLevel = !s.autoLevel },
            Cycler({ "Session length: " + if (s.sessionMin <= 0) "Unlimited" else "${s.sessionMin} min" }) {
                s.sessionMin = when (s.sessionMin) { 2 -> 5; 5 -> 10; 10 -> 0; else -> 2 }
            },
            Cycler({ "Rest after session: " + if (s.cooldownMin <= 0) "Off" else "${s.cooldownMin} min" }) {
                s.cooldownMin = when (s.cooldownMin) { 0 -> 5; 5 -> 10; else -> 0 }
            },
            Cycler({ "Treat screen: ${fmt(s.treatSec)} s" }) {
                s.treatSec = when (s.treatSec) { 1.5f -> 3.5f; 3.5f -> 6f; else -> 1.5f }
            },
            Cycler({ "Tap dismisses treat screen: " + onOff(s.tapSkipsTreat) }) { s.tapSkipsTreat = !s.tapSkipsTreat },
            Cycler({ "Training re-pop every: ${s.repopSec} s" }) {
                s.repopSec = when (s.repopSec) { 5 -> 8; 8 -> 12; else -> 5 }
            },
            Cycler({ "Animal sounds with footage: " + onOff(s.clipSounds) }) { s.clipSounds = !s.clipSounds },
        )
        return items.map { c ->
            lateinit var b: Button
            b = button(c.label()) { c.next(); b.text = c.label(); onSettingsChanged(); refresh() }
            b
        }
    }

    private fun column(title: String, views: List<View>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(0xFFFFE800.toInt()); setPadding(0, 0, 0, px(6)) })
        views.forEach { addView(it, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) }) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setColor(0xFF1A2A5A.toInt()) }
        setPadding(px(16), px(12), px(16), px(12))
        setOnClickListener { onClick() }
    }

    private fun px(v: Int) = (v * dp).toInt()

    companion object {
        fun levelName(d: Float) = when {
            d < Tuning.TRAINING_BELOW -> "Training (easy, re-pops on its own)"
            else -> "${(d * 100).toInt()}%"
        }
        private fun onOff(b: Boolean) = if (b) "On" else "Off"
        private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()
    }
}
