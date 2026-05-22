package com.yourgame.world

// =============================================================================
//  SolarSystemRenderer.kt
//
//  DROP INTO: src/main/java/com/yourgame/world/
//  DEPENDS ON: SolarSystem.kt, PlanetRenderer.kt
//
//  Draws the full solar system on a Canvas for the map/navigation view.
//  The player sees this when they zoom out to system level.
//
//  ANDROID STUDIO AI: wire this into your SystemMapView or NavigationActivity.
//  Call draw() every frame from your SurfaceView.onDraw() or a Choreographer tick.
// =============================================================================

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.yourgame.sprites.PlanetRenderer
import kotlin.math.*

object SolarSystemRenderer {

    private val orbitPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val starPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f; color = Color.argb(140, 0, 220, 80)
    }
    private val ovalBounds  = RectF()

    /**
     * Draw the entire system.
     *
     * [auToPixels]  — how many pixels = 1 AU at the current zoom level.
     *                 Typical values: 60f (zoomed out), 200f (zoomed in).
     * [planetRadiusPx] — display radius of a typical planet in pixels.
     *                 Moons are drawn at half this size.
     *                 Recommend ~18f for overview, ~40f for close-up.
     */
    fun draw(
        canvas:        Canvas,
        system:        SolarSystem,
        cx:            Float,
        cy:            Float,
        auToPixels:    Float = 80f,
        planetRadiusPx: Float = 18f
    ) {
        drawStar(canvas, system, cx, cy)
        system.planets.forEach { planet ->
            drawOrbitRing(canvas, planet, cx, cy, auToPixels)
            val (px, py) = planet.worldPosition(cx, cy, auToPixels)
            PlanetRenderer.draw(canvas, planet.planetSpec, px, py, planetRadiusPx)
            drawLabel(canvas, planet.name, px, py, planetRadiusPx)
            planet.moons.forEach { moon ->
                drawOrbitRing(canvas, moon, px, py, auToPixels * 40f, isMoon = true)
                val (mx, my) = moon.worldPosition(px, py, auToPixels * 40f)
                PlanetRenderer.draw(canvas, moon.planetSpec, mx, my, planetRadiusPx * 0.4f)
            }
        }
    }

    private fun drawStar(canvas: Canvas, system: SolarSystem, cx: Float, cy: Float) {
        val star     = system.stars.first()
        val color    = Color.parseColor(star.type.colorHex)
        val starRadius = when (star.type) {
            StarType.BLUE_GIANT  -> 42f
            StarType.BINARY      -> 38f
            StarType.YELLOW_STAR -> 28f
            StarType.ORANGE_STAR -> 24f
            StarType.RED_DWARF   -> 18f
            StarType.WHITE_DWARF -> 10f
        }
        // Corona glow rings
        for (i in 3 downTo 1) {
            starPaint.color = Color.argb(20 + i * 12, Color.red(color), Color.green(color), Color.blue(color))
            starPaint.strokeWidth = starRadius * 0.4f
            canvas.drawCircle(cx, cy, starRadius + i * starRadius * 0.35f, starPaint)
        }
        // Core
        fillPaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(cx, cy, starRadius, fillPaint)
        // Bright centre
        fillPaint.color = Color.argb(180, 255, 255, 220)
        canvas.drawCircle(cx, cy, starRadius * 0.45f, fillPaint)
    }

    private fun drawOrbitRing(
        canvas:     Canvas,
        body:       OrbitalBody,
        parentX:    Float,
        parentY:    Float,
        auToPixels: Float,
        isMoon:     Boolean = false
    ) {
        val a  = body.semiMajorAxis * auToPixels
        val b  = a * sqrt(1f - body.eccentricity * body.eccentricity)  // semi-minor axis
        val focusOffset = a * body.eccentricity                          // star is at one focus
        // Centre of ellipse is offset from the focus (star) position
        val ellipseCx = parentX + focusOffset
        val alpha = if (isMoon) 60 else 45
        orbitPaint.color = Color.argb(alpha, 40, 180, 80)
        orbitPaint.strokeWidth = if (isMoon) 0.5f else 0.8f
        orbitPaint.pathEffect = null
        ovalBounds.set(ellipseCx - a, parentY - b, ellipseCx + a, parentY + b)
        canvas.drawOval(ovalBounds, orbitPaint)
    }

    private fun drawLabel(canvas: Canvas, name: String, x: Float, y: Float, radius: Float) {
        val shortName = name.substringAfterLast(" ")  // "Keth II" → "II"
        canvas.drawText(shortName, x - labelPaint.measureText(shortName) / 2f,
            y + radius + 14f, labelPaint)
    }
}
