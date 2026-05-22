package com.yourgame.sprites

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
//  DATA MODEL
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A point in normalised ship-space.
 * (0,0) = geometric centre.  (0,-1) = tip of the nose.  (0,+1) = bottom edge.
 * Multiply by `scale` to get real pixels at draw time.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2)  = Vec2(x + o.x, y + o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun rotate(rad: Float) = Vec2(
        x * cos(rad) - y * sin(rad),
        x * sin(rad) + y * cos(rad)
    )
    fun toPointF(cx: Float, cy: Float, scale: Float, rotation: Float): PointF {
        val r = rotate(rotation)
        return PointF(cx + r.x * scale, cy + r.y * scale)
    }
}

// ── Stroke primitives ─────────────────────────────────────────────────────────

sealed class Stroke {
    /** Straight-line polygon. closePath=true adds the final closing segment. */
    data class Poly(val pts: List<Vec2>, val close: Boolean = false) : Stroke()
    /** Circle centred at `centre` with radius `r` (both normalised). */
    data class Circle(val centre: Vec2, val r: Float) : Stroke()
    /** Axis-aligned oval. rx/ry normalised. */
    data class Oval(val centre: Vec2, val rx: Float, val ry: Float) : Stroke()
    /** Single straight line. */
    data class Line(val a: Vec2, val b: Vec2) : Stroke()
    /**
     * Cubic bezier path, can be open or closed.
     * `segments` is a flat list of (cp1, cp2, end) triples appended to the
     * starting point [start].
     */
    data class Bezier(
        val start: Vec2,
        val segments: List<Triple<Vec2, Vec2, Vec2>>,
        val close: Boolean = false
    ) : Stroke()
    /** Mixed path: lines then optionally bezier segments — used for Nemesis hull. */
    data class Mixed(val ops: List<PathOp>, val close: Boolean = false) : Stroke()
}

sealed class PathOp {
    data class MoveTo(val p: Vec2)                               : PathOp()
    data class LineTo(val p: Vec2)                               : PathOp()
    data class CubicTo(val c1: Vec2, val c2: Vec2, val end: Vec2) : PathOp()
}

// ── Named nodes ───────────────────────────────────────────────────────────────

/**
 * Key points on the ship hull, all in normalised ship-space.
 *
 * [centre]    — always (0,0).  World position anchor.
 * [bow]       — tip of the nose.  Bullet spawn, collision leading edge.
 * [thrusters] — one entry per engine.  Jet-particle spawn point.
 *               The exhaust vector for each thruster is always away from the bow,
 *               i.e. in the +Y direction before rotation is applied.
 *               Use [thrustVector] to get it in world space.
 */
data class ShipNodes(
    val centre:    Vec2          = Vec2(0f, 0f),
    val bow:       Vec2,
    val thrusters: List<Vec2>
) {
    /** Unit vector pointing from bow toward engines (= thrust direction before rotation). */
    val thrustDir: Vec2 = Vec2(0f, 1f)   // always "downward" in ship-space

    /**
     * World-space thruster positions and exhaust vectors, given the ship's
     * world centre, scale, and current rotation (radians, 0 = up).
     *
     * Returns list of Pair(spawnPoint, exhaustDirection) — feed directly into
     * your particle system.
     */
    fun worldThrusters(cx: Float, cy: Float, scale: Float, rotation: Float)
        : List<Pair<PointF, PointF>> =
        thrusters.map { t ->
            val pos = t.toPointF(cx, cy, scale, rotation)
            val dir = thrustDir.rotate(rotation)
            Pair(pos, PointF(dir.x, dir.y))
        }

    /** World-space bow position. */
    fun worldBow(cx: Float, cy: Float, scale: Float, rotation: Float): PointF =
        bow.toPointF(cx, cy, scale, rotation)
}

// ── Ship spec ─────────────────────────────────────────────────────────────────

data class ShipSpec(
    val id:          String,
    val displayName: String,
    val tier:        Int,
    /** Normalised line-width (multiplied by scale at draw time). */
    val lineWeight:  Float,
    val strokes:     List<Stroke>,
    val nodes:       ShipNodes,
    /** Game stats — handy to keep co-located with the visual spec. */
    val speed:       Int,
    val armor:       Int,
    val firepower:   Int
) {
    val defaultColor: Int get() = when (tier) {
        1    -> 0xFF00FFFF.toInt()   // cyan
        2    -> 0xFF00FF88.toInt()   // green
        3    -> 0xFFFFAA00.toInt()   // amber
        else -> 0xFFFFFFFF.toInt()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SHIP CATALOGUE  (pure data, zero Canvas imports)
// ─────────────────────────────────────────────────────────────────────────────

object ShipCatalogue {

    val ALL: List<ShipSpec> = listOf(

        // ── TIER 1 ────────────────────────────────────────────────────────────

        ShipSpec(
            id = "dart", displayName = "DART MK-I", tier = 1,
            lineWeight = 0.04f, speed = 85, armor = 20, firepower = 30,
            strokes = listOf(
                // Slim needle hull
                Stroke.Poly(listOf(
                    Vec2( 0f,    -1f   ),
                    Vec2( 0.28f,  0.6f ),
                    Vec2( 0f,     0.35f),
                    Vec2(-0.28f,  0.6f )
                ), close = true),
                // Cockpit slit
                Stroke.Line(Vec2(-0.1f, -0.4f), Vec2(0.1f, -0.4f)),
                // Single engine block
                Stroke.Poly(listOf(
                    Vec2(-0.1f, 0.35f),
                    Vec2(-0.1f, 0.7f ),
                    Vec2( 0.1f, 0.7f ),
                    Vec2( 0.1f, 0.35f)
                ))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -1f),
                thrusters = listOf(Vec2(0f, 0.7f))
            )
        ),

        ShipSpec(
            id = "wasp", displayName = "WASP SCOUT", tier = 1,
            lineWeight = 0.04f, speed = 90, armor = 15, firepower = 25,
            strokes = listOf(
                // Thin delta hull
                Stroke.Poly(listOf(
                    Vec2( 0f,    -0.9f),
                    Vec2( 0.5f,   0.7f),
                    Vec2( 0.15f,  0.45f),
                    Vec2( 0f,     0.55f),
                    Vec2(-0.15f,  0.45f),
                    Vec2(-0.5f,   0.7f )
                ), close = true),
                // Cockpit bubble
                Stroke.Circle(Vec2(0f, -0.35f), 0.13f),
                // Spine
                Stroke.Line(Vec2(0f, -0.22f), Vec2(0f, 0.55f))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -0.9f),
                thrusters = listOf(Vec2(-0.15f, 0.55f), Vec2(0.15f, 0.55f))
            )
        ),

        ShipSpec(
            id = "arrow", displayName = "ARROW-9", tier = 1,
            lineWeight = 0.04f, speed = 75, armor = 30, firepower = 40,
            strokes = listOf(
                Stroke.Poly(listOf(
                    Vec2( 0f,    -1f  ),
                    Vec2( 0.4f,  -0.1f),
                    Vec2( 0.4f,   0.3f),
                    Vec2( 0.2f,   0.3f),
                    Vec2( 0.2f,   0.7f),
                    Vec2(-0.2f,   0.7f),
                    Vec2(-0.2f,   0.3f),
                    Vec2(-0.4f,   0.3f),
                    Vec2(-0.4f,  -0.1f)
                ), close = true),
                Stroke.Line(Vec2(-0.12f, -0.5f), Vec2(0.12f, -0.5f))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -1f),
                thrusters = listOf(Vec2(-0.1f, 0.7f), Vec2(0.1f, 0.7f))
            )
        ),

        // ── TIER 2 ────────────────────────────────────────────────────────────

        ShipSpec(
            id = "falcon", displayName = "FALCON-X", tier = 2,
            lineWeight = 0.045f, speed = 70, armor = 55, firepower = 65,
            strokes = listOf(
                // Teardrop hull
                Stroke.Bezier(
                    start = Vec2(0f, -1f),
                    segments = listOf(
                        Triple(Vec2( 0.5f, -0.2f), Vec2( 0.42f,  0.6f), Vec2( 0f,  0.45f)),
                        Triple(Vec2(-0.42f,  0.6f), Vec2(-0.5f, -0.2f), Vec2( 0f, -1f   ))
                    )
                ),
                // Canopy
                Stroke.Bezier(
                    start = Vec2(-0.15f, -0.38f),
                    segments = listOf(
                        Triple(Vec2(-0.18f, -0.68f), Vec2(0.18f, -0.68f), Vec2(0.15f, -0.38f))
                    )
                ),
                // Left wing
                Stroke.Poly(listOf(Vec2(-0.32f, 0.05f), Vec2(-0.9f, 0.6f), Vec2(-0.32f, 0.5f))),
                // Right wing
                Stroke.Poly(listOf(Vec2(0.32f, 0.05f), Vec2(0.9f, 0.6f), Vec2(0.32f, 0.5f))),
                // Left nacelle
                Stroke.Poly(listOf(
                    Vec2(-0.22f, 0.38f), Vec2(-0.22f, 0.75f),
                    Vec2(-0.1f,  0.75f), Vec2(-0.1f,  0.38f)
                )),
                // Right nacelle
                Stroke.Poly(listOf(
                    Vec2(0.1f, 0.38f), Vec2(0.1f, 0.75f),
                    Vec2(0.22f, 0.75f), Vec2(0.22f, 0.38f)
                ))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -1f),
                thrusters = listOf(Vec2(-0.16f, 0.75f), Vec2(0.16f, 0.75f))
            )
        ),

        ShipSpec(
            id = "raptor", displayName = "RAPTOR-II", tier = 2,
            lineWeight = 0.045f, speed = 60, armor = 70, firepower = 60,
            strokes = listOf(
                Stroke.Poly(listOf(
                    Vec2( 0f,    -0.95f),
                    Vec2( 0.6f,   0.3f ),
                    Vec2( 0.35f,  0.55f),
                    Vec2( 0.35f,  0.8f ),
                    Vec2(-0.35f,  0.8f ),
                    Vec2(-0.35f,  0.55f),
                    Vec2(-0.6f,   0.3f )
                ), close = true),
                // Cockpit triangle
                Stroke.Poly(listOf(
                    Vec2( 0f,    -0.95f),
                    Vec2( 0.12f, -0.4f ),
                    Vec2(-0.12f, -0.4f )
                ), close = true),
                Stroke.Line(Vec2(-0.35f, 0.55f), Vec2(0.35f, 0.55f)),
                Stroke.Line(Vec2( 0.12f, -0.1f), Vec2( 0.5f,  0.28f)),
                Stroke.Line(Vec2(-0.12f, -0.1f), Vec2(-0.5f,  0.28f))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -0.95f),
                thrusters = listOf(Vec2(-0.175f, 0.8f), Vec2(0.175f, 0.8f))
            )
        ),

        ShipSpec(
            id = "viper", displayName = "VIPER-VII", tier = 2,
            lineWeight = 0.045f, speed = 80, armor = 50, firepower = 70,
            strokes = listOf(
                Stroke.Poly(listOf(
                    Vec2( 0f,    -1f  ),
                    Vec2( 0.18f, -0.3f),
                    Vec2( 0.55f,  0f  ),
                    Vec2( 0.55f,  0.35f),
                    Vec2( 0.2f,   0.55f),
                    Vec2( 0.2f,   0.75f),
                    Vec2(-0.2f,   0.75f),
                    Vec2(-0.2f,   0.55f),
                    Vec2(-0.55f,  0.35f),
                    Vec2(-0.55f,  0f  ),
                    Vec2(-0.18f, -0.3f)
                ), close = true),
                // Inner cockpit
                Stroke.Poly(listOf(
                    Vec2( 0f,    -1f  ),
                    Vec2( 0.1f,  -0.3f),
                    Vec2(-0.1f,  -0.3f)
                ), close = true),
                Stroke.Line(Vec2(-0.55f, 0.18f), Vec2(0.55f, 0.18f))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -1f),
                thrusters = listOf(Vec2(-0.1f, 0.75f), Vec2(0.1f, 0.75f))
            )
        ),

        // ── TIER 3 ────────────────────────────────────────────────────────────

        ShipSpec(
            id = "wraith", displayName = "WRAITH ZERO", tier = 3,
            lineWeight = 0.05f, speed = 95, armor = 75, firepower = 90,
            strokes = listOf(
                // Outer hull
                Stroke.Bezier(
                    start = Vec2(0f, -1f),
                    segments = listOf(
                        Triple(Vec2( 0.08f, -0.5f), Vec2( 0.6f, -0.1f), Vec2( 0.7f,  0.5f)),
                        Triple(Vec2( 0.5f,   0.45f), Vec2( 0.15f, 0.55f), Vec2(0f,   0.4f)),
                        Triple(Vec2(-0.15f,  0.55f), Vec2(-0.5f,  0.45f), Vec2(-0.7f, 0.5f)),
                        Triple(Vec2(-0.6f,  -0.1f), Vec2(-0.08f, -0.5f), Vec2(0f,   -1f  ))
                    )
                ),
                // Inner hull lines
                Stroke.Bezier(
                    start = Vec2(0f, -0.6f),
                    segments = listOf(
                        Triple(Vec2( 0.25f, -0.3f), Vec2( 0.35f, 0.2f), Vec2( 0.18f, 0.42f))
                    )
                ),
                Stroke.Bezier(
                    start = Vec2(0f, -0.6f),
                    segments = listOf(
                        Triple(Vec2(-0.25f, -0.3f), Vec2(-0.35f, 0.2f), Vec2(-0.18f, 0.42f))
                    )
                ),
                // Cockpit ellipse
                Stroke.Oval(Vec2(0f, -0.55f), 0.1f, 0.18f),
                // Tri-nacelles (left outer, centre, right outer)
                Stroke.Poly(listOf(Vec2(-0.45f, 0.42f), Vec2(-0.45f, 0.85f), Vec2(-0.28f, 0.85f), Vec2(-0.28f, 0.42f))),
                Stroke.Poly(listOf(Vec2(-0.1f,  0.42f), Vec2(-0.1f,  0.85f), Vec2( 0.1f,  0.85f), Vec2( 0.1f,  0.42f))),
                Stroke.Poly(listOf(Vec2( 0.28f, 0.42f), Vec2( 0.28f, 0.85f), Vec2( 0.45f, 0.85f), Vec2( 0.45f, 0.42f)))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -1f),
                thrusters = listOf(Vec2(-0.365f, 0.85f), Vec2(0f, 0.85f), Vec2(0.365f, 0.85f))
            )
        ),

        ShipSpec(
            id = "sovereign", displayName = "SOVEREIGN", tier = 3,
            lineWeight = 0.05f, speed = 75, armor = 95, firepower = 95,
            strokes = listOf(
                Stroke.Poly(listOf(
                    Vec2( 0f,    -1f  ),
                    Vec2( 0.25f, -0.5f),
                    Vec2( 0.65f, -0.1f),
                    Vec2( 0.8f,   0.35f),
                    Vec2( 0.5f,   0.65f),
                    Vec2( 0.5f,   0.85f),
                    Vec2(-0.5f,   0.85f),
                    Vec2(-0.5f,   0.65f),
                    Vec2(-0.8f,   0.35f),
                    Vec2(-0.65f, -0.1f),
                    Vec2(-0.25f, -0.5f)
                ), close = true),
                // Cockpit fortress
                Stroke.Poly(listOf(
                    Vec2( 0f,    -1f  ),
                    Vec2( 0.18f, -0.48f),
                    Vec2( 0.18f, -0.15f),
                    Vec2(-0.18f, -0.15f),
                    Vec2(-0.18f, -0.48f)
                ), close = true),
                Stroke.Line(Vec2(-0.65f, 0.1f), Vec2(0.65f, 0.1f)),
                Stroke.Line(Vec2(-0.5f,  0.65f), Vec2(0.5f,  0.65f)),
                // Cannon barrels
                Stroke.Line(Vec2(-0.62f, -0.1f), Vec2(-0.62f, -0.45f)),
                Stroke.Line(Vec2(-0.2f,  -0.1f), Vec2(-0.2f,  -0.45f)),
                Stroke.Line(Vec2( 0.2f,  -0.1f), Vec2( 0.2f,  -0.45f)),
                Stroke.Line(Vec2( 0.62f, -0.1f), Vec2( 0.62f, -0.45f))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -1f),
                thrusters = listOf(Vec2(-0.25f, 0.85f), Vec2(0.25f, 0.85f))
            )
        ),

        ShipSpec(
            id = "nemesis", displayName = "NEMESIS-∞", tier = 3,
            lineWeight = 0.05f, speed = 85, armor = 88, firepower = 100,
            strokes = listOf(
                // Organic predator hull (mixed bezier + lines)
                Stroke.Mixed(listOf(
                    PathOp.MoveTo(Vec2( 0f,     -1f  )),
                    PathOp.CubicTo(Vec2( 0.55f, -0.3f), Vec2( 0.5f,  0.4f), Vec2( 0.3f,  0.6f)),
                    PathOp.LineTo(Vec2( 0.55f,  0.9f)),
                    PathOp.LineTo(Vec2( 0f,     0.65f)),
                    PathOp.LineTo(Vec2(-0.55f,  0.9f)),
                    PathOp.LineTo(Vec2(-0.3f,   0.6f)),
                    PathOp.CubicTo(Vec2(-0.5f,  0.4f), Vec2(-0.55f,-0.3f), Vec2( 0f,    -1f  ))
                ), close = false),
                // Central spine
                Stroke.Line(Vec2(0f, -1f), Vec2(0f, 0.65f)),
                // Rib lines — 3 pairs
                Stroke.Line(Vec2(0f, -0.3f), Vec2( 0.42f, -0.1f)),
                Stroke.Line(Vec2(0f, -0.3f), Vec2(-0.42f, -0.1f)),
                Stroke.Line(Vec2(0f, -0.1f), Vec2( 0.42f,  0.1f)),
                Stroke.Line(Vec2(0f, -0.1f), Vec2(-0.42f,  0.1f)),
                Stroke.Line(Vec2(0f,  0.15f), Vec2( 0.42f, 0.3f)),
                Stroke.Line(Vec2(0f,  0.15f), Vec2(-0.42f, 0.3f)),
                // Cockpit visor
                Stroke.Poly(listOf(
                    Vec2(-0.12f, -0.55f),
                    Vec2( 0.12f, -0.55f),
                    Vec2( 0.08f, -0.75f),
                    Vec2(-0.08f, -0.75f)
                ), close = true),
                // Twin forward guns
                Stroke.Line(Vec2( 0.22f, -0.35f), Vec2( 0.22f, -0.85f)),
                Stroke.Line(Vec2(-0.22f, -0.35f), Vec2(-0.22f, -0.85f))
            ),
            nodes = ShipNodes(
                bow       = Vec2(0f, -1f),
                thrusters = listOf(Vec2(-0.275f, 0.9f), Vec2(0.275f, 0.9f))
            )
        )
    )

    /** Look up a spec by string id. */
    val byId: Map<String, ShipSpec> = ALL.associateBy { it.id }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SINGLE RENDERER  — reads any ShipSpec, knows nothing about individual ships
// ─────────────────────────────────────────────────────────────────────────────

object ShipRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /**
     * Draw [spec] centred at (cx, cy), scaled to [scale] pixels, rotated
     * [rotation] radians (0 = nose pointing up), tinted [color].
     *
     * Returns the fully resolved [ShipNodes] in world space so the caller can
     * immediately use thrust/bow positions without a second pass.
     */
    fun draw(
        canvas:   Canvas,
        spec:     ShipSpec,
        cx:       Float,
        cy:       Float,
        scale:    Float,
        rotation: Float = 0f,
        color:    Int   = spec.defaultColor
    ) {
        paint.color       = color
        paint.strokeWidth = spec.lineWeight * scale

        for (stroke in spec.strokes) {
            when (stroke) {
                is Stroke.Poly   -> drawPoly  (canvas, stroke, cx, cy, scale, rotation)
                is Stroke.Circle -> drawCircle(canvas, stroke, cx, cy, scale, rotation)
                is Stroke.Oval   -> drawOval  (canvas, stroke, cx, cy, scale, rotation)
                is Stroke.Line   -> drawLine  (canvas, stroke, cx, cy, scale, rotation)
                is Stroke.Bezier -> drawBezier(canvas, stroke, cx, cy, scale, rotation)
                is Stroke.Mixed  -> drawMixed (canvas, stroke, cx, cy, scale, rotation)
            }
        }
    }

    // ── Private stroke renderers ──────────────────────────────────────────────

    private fun p(v: Vec2, cx: Float, cy: Float, scale: Float, rot: Float): PointF =
        v.toPointF(cx, cy, scale, rot)

    private fun drawPoly(canvas: Canvas, s: Stroke.Poly, cx: Float, cy: Float, scale: Float, rot: Float) {
        path.reset()
        s.pts.forEachIndexed { i, v ->
            val pt = p(v, cx, cy, scale, rot)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        if (s.close) path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawCircle(canvas: Canvas, s: Stroke.Circle, cx: Float, cy: Float, scale: Float, rot: Float) {
        val c = p(s.centre, cx, cy, scale, rot)
        canvas.drawCircle(c.x, c.y, s.r * scale, paint)
    }

    private fun drawOval(canvas: Canvas, s: Stroke.Oval, cx: Float, cy: Float, scale: Float, rot: Float) {
        // For rotation support we draw the oval as a path approximation via bezier
        // (Android's drawOval doesn't rotate with the ship).
        val c = p(s.centre, cx, cy, scale, rot)
        val rx = s.rx * scale
        val ry = s.ry * scale
        // Rotate the four control-point axes
        val top    = p(Vec2(s.centre.x, s.centre.y - s.ry), cx, cy, scale, rot)
        val bottom = p(Vec2(s.centre.x, s.centre.y + s.ry), cx, cy, scale, rot)
        val left   = p(Vec2(s.centre.x - s.rx, s.centre.y), cx, cy, scale, rot)
        val right  = p(Vec2(s.centre.x + s.rx, s.centre.y), cx, cy, scale, rot)
        val kx = s.rx * 0.5523f * scale
        val ky = s.ry * 0.5523f * scale
        val cosR = cos(rot); val sinR = sin(rot)
        // Offset vectors for bezier handles (rotated)
        val hx = PointF( cosR * kx, sinR * kx)
        val hy = PointF(-sinR * ky, cosR * ky)
        path.reset()
        path.moveTo(top.x, top.y)
        path.cubicTo(top.x+hx.x, top.y+hx.y, right.x-hy.x, right.y-hy.y, right.x, right.y)
        path.cubicTo(right.x+hy.x, right.y+hy.y, bottom.x+hx.x, bottom.y+hx.y, bottom.x, bottom.y)
        path.cubicTo(bottom.x-hx.x, bottom.y-hx.y, left.x+hy.x, left.y+hy.y, left.x, left.y)
        path.cubicTo(left.x-hy.x, left.y-hy.y, top.x-hx.x, top.y-hx.y, top.x, top.y)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawLine(canvas: Canvas, s: Stroke.Line, cx: Float, cy: Float, scale: Float, rot: Float) {
        val a = p(s.a, cx, cy, scale, rot)
        val b = p(s.b, cx, cy, scale, rot)
        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
    }

    private fun drawBezier(canvas: Canvas, s: Stroke.Bezier, cx: Float, cy: Float, scale: Float, rot: Float) {
        path.reset()
        val start = p(s.start, cx, cy, scale, rot)
        path.moveTo(start.x, start.y)
        for ((c1, c2, end) in s.segments) {
            val pc1 = p(c1, cx, cy, scale, rot)
            val pc2 = p(c2, cx, cy, scale, rot)
            val pe  = p(end, cx, cy, scale, rot)
            path.cubicTo(pc1.x, pc1.y, pc2.x, pc2.y, pe.x, pe.y)
        }
        if (s.close) path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawMixed(canvas: Canvas, s: Stroke.Mixed, cx: Float, cy: Float, scale: Float, rot: Float) {
        path.reset()
        for (op in s.ops) {
            when (op) {
                is PathOp.MoveTo  -> { val pt = p(op.p,  cx, cy, scale, rot); path.moveTo(pt.x, pt.y) }
                is PathOp.LineTo  -> { val pt = p(op.p,  cx, cy, scale, rot); path.lineTo(pt.x, pt.y) }
                is PathOp.CubicTo -> {
                    val c1 = p(op.c1,  cx, cy, scale, rot)
                    val c2 = p(op.c2,  cx, cy, scale, rot)
                    val e  = p(op.end, cx, cy, scale, rot)
                    path.cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
                }
            }
        }
        if (s.close) path.close()
        canvas.drawPath(path, paint)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  EXAMPLE: how the game loop uses this
// ─────────────────────────────────────────────────────────────────────────────
//
//  class PlayerShip(id: String) {
//      val spec     = ShipCatalogue.byId[id]!!
//      var x        = 500f
//      var y        = 500f
//      var rotation = 0f          // radians, 0 = nose up
//      var scale    = 48f         // pixel "radius"
//      var velocity = Vec2(0f, 0f)
//
//      fun update(wasd: Input, dt: Float) {
//          if (wasd.left)  rotation -= 2f * dt
//          if (wasd.right) rotation += 2f * dt
//          if (wasd.up) {
//              // Thrust in the direction the bow is pointing
//              val thrustDir = Vec2(0f, -1f).rotate(rotation)
//              velocity = Vec2(
//                  velocity.x + thrustDir.x * spec.speed * dt,
//                  velocity.y + thrustDir.y * spec.speed * dt
//              )
//          }
//          x += velocity.x * dt
//          y += velocity.y * dt
//      }
//
//      fun draw(canvas: Canvas) {
//          ShipRenderer.draw(canvas, spec, x, y, scale, rotation)
//      }
//
//      // Feed this into your particle emitter each frame when thrusting:
//      fun thrusterSpawns(): List<Pair<PointF, PointF>> =
//          spec.nodes.worldThrusters(x, y, scale, rotation)
//
//      // Bullet spawn point:
//      fun bowPosition(): PointF =
//          spec.nodes.worldBow(x, y, scale, rotation)
//  }
