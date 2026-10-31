package dev.peekaboo.squirrel

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Draws a frame with plain Canvas calls. Zero allocations per frame. */
class Renderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.HUD }
    private val sky = Paint()
    private val path = Path()
    private val r = RectF()
    private var w = 0
    private var h = 0
    private var clock = 0f

    /** Owner-facing info for the resting screen; set by the activity. */
    @Volatile var restMessage: String = ""

    fun resize(width: Int, height: Int) {
        w = width; h = height
        sky.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Palette.SKY_TOP, Palette.SKY_BOTTOM, Shader.TileMode.CLAMP)
    }

    fun draw(c: Canvas, g: GameLogic, dt: Float) {
        clock += dt
        if (w == 0) return
        if (g.phase == Phase.DONE) { drawRest(c, g); return }

        val groundY = h * 0.78f
        val s = h * 0.2f // critter size unit
        val bushW = h * 0.42f
        val bushH = h * 0.26f
        val bushTop = groundY - bushH

        // Sky (+ flash on celebrate).
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sky)
        if (g.phase == Phase.CELEBRATE) {
            val a = (1f - g.phaseTime / 0.6f).coerceIn(0f, 1f)
            if (a > 0f) {
                fill.color = Palette.SKY_FLASH
                fill.alpha = (a * 200).toInt()
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
                fill.alpha = 255
            }
        }
        fill.color = Palette.GROUND
        c.drawRect(0f, groundY, w.toFloat(), h.toFloat(), fill)

        // Critter pose.
        val visibleCritter = g.phase != Phase.HIDDEN && g.phase != Phase.RUSTLE
        val cx = g.critterX * w
        val peekY = bushTop + s * (0.95f - 0.5f * g.peek)
        val baseY = if (g.critterBehindBush) peekY
        else lerp(peekY, groundY, g.leap) - sin(g.leap * PI.toFloat()) * s * 0.5f
        val hop = if (g.phase == Phase.VISIBLE && g.moving) -kotlin.math.abs(sin(clock * 40f)) * s * 0.12f else 0f
        val spin = if (g.phase == Phase.CELEBRATE) (g.phaseTime / 0.8f).coerceAtMost(1f) * 720f else 0f

        // Critter behind bush layer.
        if (visibleCritter && g.critterBehindBush && g.peek > 0f) drawCritter(c, cx, baseY, s, g.facing, 0f, g)

        // Bushes.
        for (i in 0 until g.spotCount) {
            var bx = g.spotX[i] * w
            if (g.phase == Phase.RUSTLE && i == g.rustleSpot) bx += sin(clock * 45f) * h * 0.015f
            drawBush(c, bx, groundY, bushW, bushH)
        }

        // Critter in front layer.
        if (visibleCritter && !g.critterBehindBush) {
            if (g.phase == Phase.CELEBRATE) drawSparks(c, cx, baseY - s * 0.5f, s, g.phaseTime)
            drawCritter(c, cx, baseY + hop, s, g.facing, spin, g)
        }

        // Owner treat cue + HUD (low-contrast, for humans).
        if (g.phase == Phase.CELEBRATE) drawTreatCue(c, g.phaseTime)
        text.textSize = h * 0.032f
        text.color = Palette.HUD
        text.textAlign = Paint.Align.LEFT
        c.drawText(hud(g), h * 0.14f, h * 0.075f, text)
        drawGear(c)
    }

    /** Trainer gear, top-left. Hit area is generous for human fingers. */
    fun inGear(x: Float, y: Float) = w > 0 && x < h * 0.13f && y < h * 0.13f

    private fun drawGear(c: Canvas) {
        text.color = Palette.HUD
        text.textAlign = Paint.Align.CENTER
        text.textSize = h * 0.075f
        c.drawText("\u2699", h * 0.065f, h * 0.09f, text)
        text.textAlign = Paint.Align.LEFT
    }

    // HUD text is rebuilt only when a shown value changes (String.format every frame is
    // a steady stream of garbage on a weak tablet).
    private var hudKey = Long.MIN_VALUE
    private var hudText = ""
    private fun hud(g: GameLogic): String {
        val unlimited = g.remainingSeconds.isInfinite()
        val rem = if (unlimited) 0 else g.remainingSeconds.toInt()
        val lvl = (g.difficulty * 100).toInt()
        val key = (g.hits.toLong() shl 40) or (lvl.toLong() shl 24) or (if (unlimited) 0xFFFFFFL else rem.toLong())
        if (key != hudKey) {
            hudKey = key
            val ss = rem % 60
            val time = if (unlimited) "∞" else "${rem / 60}:${if (ss < 10) "0" else ""}$ss"
            hudText = "★ ${g.hits}   lvl $lvl%   $time   (Back = trainer menu)"
        }
        return hudText
    }

    private var restHits = -1
    private var restText = ""
    private fun restLine(g: GameLogic): String {
        if (g.hits != restHits) { restHits = g.hits; restText = "All done — good dog!  ★ ${g.hits}" }
        return restText
    }

    private val corners = intArrayOf(-1, 1)

    private fun drawBush(c: Canvas, x: Float, groundY: Float, bw: Float, bh: Float) {
        fill.color = Palette.BUSH_SHADE
        r.set(x - bw * 0.5f, groundY - bh * 0.85f, x + bw * 0.5f, groundY + bh * 0.08f)
        c.drawOval(r, fill)
        fill.color = Palette.BUSH
        c.drawCircle(x - bw * 0.22f, groundY - bh * 0.55f, bh * 0.42f, fill)
        c.drawCircle(x + bw * 0.22f, groundY - bh * 0.55f, bh * 0.42f, fill)
        c.drawCircle(x, groundY - bh * 0.62f, bh * 0.38f, fill)
        r.set(x - bw * 0.48f, groundY - bh * 0.6f, x + bw * 0.48f, groundY + bh * 0.05f)
        c.drawRoundRect(r, bh * 0.3f, bh * 0.3f, fill)
    }

    private fun drawCritter(c: Canvas, x: Float, y: Float, s: Float, facing: Int, spin: Float, g: GameLogic) {
        c.save()
        c.translate(x, y)
        if (spin != 0f) { c.translate(0f, -s * 0.45f); c.rotate(spin); c.translate(0f, s * 0.45f) }
        c.scale(facing.toFloat(), 1f)

        // Big fluffy tail — the main motion cue, flicks when frozen.
        val flick = if (g.twitch && !g.moving) sin(clock * 30f) * 12f else sin(clock * 3f) * 4f
        c.save()
        c.translate(-s * 0.4f, -s * 0.35f)
        c.rotate(-20f + flick)
        fill.color = Palette.CRITTER_TAIL
        r.set(-s * 0.3f, -s * 0.75f, s * 0.22f, s * 0.15f)
        c.drawOval(r, fill)
        c.restore()

        fill.color = Palette.CRITTER
        r.set(-s * 0.5f, -s * 0.62f, s * 0.35f, 0f) // body
        c.drawOval(r, fill)
        c.drawCircle(s * 0.38f, -s * 0.62f, s * 0.25f, fill) // head
        path.reset() // ear
        path.moveTo(s * 0.28f, -s * 0.8f)
        path.lineTo(s * 0.36f, -s * 1.02f)
        path.lineTo(s * 0.45f, -s * 0.8f)
        path.close()
        c.drawPath(path, fill)
        r.set(-s * 0.3f, -s * 0.08f, -s * 0.05f, s * 0.04f); c.drawOval(r, fill) // feet
        r.set(s * 0.1f, -s * 0.08f, s * 0.35f, s * 0.04f); c.drawOval(r, fill)

        fill.color = Palette.CRITTER_DETAIL
        c.drawCircle(s * 0.47f, -s * 0.68f, s * 0.055f, fill) // eye
        c.drawCircle(s * 0.62f, -s * 0.6f, s * 0.035f, fill) // nose
        c.restore()
    }

    private fun drawSparks(c: Canvas, x: Float, y: Float, s: Float, t: Float) {
        val reach = s * (0.6f + 1.4f * (1f - 1f / (1f + t * 1.5f)))
        fill.color = Palette.SPARK
        fill.alpha = ((1f - t / 3.5f).coerceIn(0.25f, 1f) * 255).toInt()
        for (i in 0 until 10) {
            val a = i * (2 * PI / 10).toFloat() + t * 1.5f
            c.drawCircle(x + cos(a) * reach, y + sin(a) * reach * 0.8f, s * 0.09f, fill)
        }
        fill.alpha = 255
    }

    private fun drawTreatCue(c: Canvas, t: Float) {
        val cx = w * 0.5f
        val cy = h * 0.14f
        val k = 1f + 0.08f * sin(t * 10f)
        val bw = h * 0.14f * k
        val bh = h * 0.045f * k
        fill.color = Palette.CRITTER
        r.set(cx - bw, cy - bh * 0.5f, cx + bw, cy + bh * 0.5f)
        c.drawRect(r, fill)
        for (sx in corners) for (sy in corners)
            c.drawCircle(cx + sx * bw, cy + sy * bh * 0.55f, bh * 0.6f, fill)
        text.color = Palette.CRITTER
        text.textAlign = Paint.Align.CENTER
        text.textSize = h * 0.055f
        c.drawText("TREAT TIME!", cx, cy + h * 0.1f, text)
    }

    private fun drawRest(c: Canvas, g: GameLogic) {
        fill.color = Palette.REST_BG
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        // Crescent moon.
        val mx = w * 0.5f; val my = h * 0.38f; val mr = h * 0.12f
        fill.color = Palette.MOON
        c.drawCircle(mx, my, mr, fill)
        fill.color = Palette.REST_BG
        c.drawCircle(mx + mr * 0.45f, my - mr * 0.2f, mr * 0.9f, fill)
        text.color = Palette.HUD
        text.textAlign = Paint.Align.CENTER
        text.textSize = h * 0.05f
        c.drawText(restLine(g), mx, h * 0.66f, text)
        text.textSize = h * 0.03f
        c.drawText(restMessage, mx, h * 0.74f, text)
        drawGear(c)
    }
}
