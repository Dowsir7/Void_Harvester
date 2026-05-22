package com.yourgame.sprites

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.*

// =============================================================================
//  PlanetRenderer.kt
//
//  DROP THIS FILE INTO: src/main/java/com/yourgame/sprites/
//
//  HOW TO USE IN YOUR GAME:
//  ─────────────────────────────────────────────────────────────────────────────
//  Every planet/moon in the galaxy is generated from a Long seed.
//  Same seed → always the same planet. Different seed → different planet.
//
//      // Generate a planet spec from any Long (use the planet's world-ID as seed)
//      val spec = PlanetGenerator.generate(seed = 12345L)
//
//      // Draw it on a Canvas (e.g. inside your SurfaceView render loop)
//      PlanetRenderer.draw(canvas, spec, cx = 400f, cy = 300f, radius = 80f)
//
//      // The spec tells you everything the game needs to know:
//      spec.type          // PlanetType enum — drives gameplay (resources, danger, etc.)
//      spec.hasLife       // Boolean — green/blue biosphere worlds
//      spec.isHostile     // Boolean — toxic/radioactive/lava worlds
//      spec.hasRings      // Boolean — draw ring system around this body
//      spec.isMoon        // Boolean — true = draw smaller, orbiting another body
//      spec.atmosphereColor // the tint color of the atmosphere halo
//
//  GAMEPLAY INTEGRATION HINTS (for Android Studio AI to pick up):
//  ─────────────────────────────────────────────────────────────────────────────
//  - PlanetType.LIFE_BEARING   → safe landing, oxygen, trade posts possible
//  - PlanetType.OCEAN          → water resources, deep-scan for minerals
//  - PlanetType.JUNGLE         → bio-samples, organic fuel, dangerous fauna
//  - PlanetType.ICE            → water ice mining, cold-damage to ship hull
//  - PlanetType.DESERT         → rare minerals, sandstorm hazard events
//  - PlanetType.GAS_GIANT      → hydrogen fuel scoop, ring debris collisions
//  - PlanetType.LAVA           → extreme heat damage, rare heavy metals
//  - PlanetType.TOXIC          → shield drain, rare exotic chemicals
//  - PlanetType.RADIOACTIVE    → radiation damage over time, uranium deposits
//  - PlanetType.ALIEN          → unknown biosphere, unpredictable effects
//  - PlanetType.DEAD_ROCK      → safe but barren, basic minerals only
//  - PlanetType.STATION        → player can dock, buy/sell, repair
//
//  COLOR ↔ TYPE LEGEND (visible to the player as a scanner readout):
//  ─────────────────────────────────────────────────────────────────────────────
//  BLUE + GREEN dominant   → life-bearing (breathable, safe)
//  DEEP BLUE only          → ocean world (water rich, no land)
//  BRIGHT GREEN dominant   → jungle world (life, but dense / hostile fauna)
//  WHITE / PALE CYAN       → ice world   (frozen, cold hazard)
//  ORANGE / DARK AMBER     → desert      (hot, mineral-rich)
//  AMBER + ORANGE banded   → gas giant   (no landing, fuel scoop only)
//  AMBER + rings present   → gas giant with ring system
//  RED / DARK RED          → lava world  (volcanic, extreme heat)
//  NEON GREEN / YELLOW     → toxic       (corrosive atmosphere)
//  BRIGHT CYAN / MAGENTA   → radioactive (rad-burst hazard)
//  PURPLE / VIOLET         → alien       (unknown — scanner glitches)
//  GREY / BLUE-GREY        → dead rock / moon (barren, safe)
//  PCB GREEN + grid lines  → station / artificial body
// =============================================================================

// ─────────────────────────────────────────────────────────────────────────────
//  Planet type taxonomy
// ─────────────────────────────────────────────────────────────────────────────

enum class PlanetType {
    LIFE_BEARING,   // blue/green — oceans + green landmasses, breathable
    OCEAN,          // deep blue — water world, minimal land
    JUNGLE,         // vivid green — dense canopy, small water glints
    ICE,            // white/pale cyan — ice sheets, crack lines
    DESERT,         // orange/amber — craters, dust storms, latitudes
    GAS_GIANT,      // amber/orange banded — no surface, storm eye
    LAVA,           // deep red — lava vein network, hotspots
    TOXIC,          // neon yellow-green — corrosive clouds, green haze
    RADIOACTIVE,    // bright cyan + magenta — pulsing glow, hex grid
    ALIEN,          // purple/violet — exotic landmasses, bio-luminescence
    DEAD_ROCK,      // grey — craters, terminator shadow line
    STATION         // PCB green — artificial, right-angle traces, docking arms
}

// ─────────────────────────────────────────────────────────────────────────────
//  Spec — everything the renderer AND the game need
// ─────────────────────────────────────────────────────────────────────────────

data class PlanetSpec(
    val seed:             Long,
    val type:             PlanetType,
    val isMoon:           Boolean,      // smaller body; no ring system
    val hasRings:         Boolean,      // ring system visible around body
    val hasAtmosphere:    Boolean,      // draw a halo ring outside the body
    val atmosphereColor:  Int,          // Android color int for the halo
    val primaryColor:     Int,          // dominant stroke / outline color
    val accentColor:      Int,          // secondary detail color
    // Gameplay flags — use these in your planet interaction code
    val hasLife:          Boolean,
    val isHostile:        Boolean,      // lava / toxic / radioactive
    val isRadioactive:    Boolean,
    val isMineralRich:    Boolean,
    val hasFuelScoop:     Boolean,      // gas giants — hydrogen available
    val canLand:          Boolean,      // false for gas giants and stars
    val craterCount:      Int,          // 0–6, affects surface draw
    val bandCount:        Int,          // 0–8, gas/desert band lines
    val ringCount:        Int           // 0–4 ring planes
)

// ─────────────────────────────────────────────────────────────────────────────
//  Seeded RNG  (same as the HTML version — deterministic)
// ─────────────────────────────────────────────────────────────────────────────

class SeededRng(seed: Long) {
    private var s = seed.toInt().let { if (it == 0) 1 else abs(it) }
    fun next(): Float {
        s = ((s.toLong() * 16807L) % 2147483647L).toInt()
        return (s - 1).toFloat() / 2147483646f
    }
    fun nextInt(max: Int) = (next() * max).toInt()
    fun nextInRange(min: Float, max: Float) = min + next() * (max - min)
    fun nextBool(probability: Float = 0.5f) = next() < probability
}

// ─────────────────────────────────────────────────────────────────────────────
//  Generator — turns a Long seed into a fully-described PlanetSpec
// ─────────────────────────────────────────────────────────────────────────────

object PlanetGenerator {

    fun generate(seed: Long, forceMoon: Boolean = false): PlanetSpec {
        val rng = SeededRng(seed)

        // Pick planet type weighted by frequency
        val typeRoll = rng.next()
        val type = when {
            typeRoll < 0.10f -> PlanetType.LIFE_BEARING
            typeRoll < 0.17f -> PlanetType.OCEAN
            typeRoll < 0.23f -> PlanetType.JUNGLE
            typeRoll < 0.31f -> PlanetType.ICE
            typeRoll < 0.40f -> PlanetType.DESERT
            typeRoll < 0.50f -> PlanetType.GAS_GIANT
            typeRoll < 0.57f -> PlanetType.LAVA
            typeRoll < 0.63f -> PlanetType.TOXIC
            typeRoll < 0.68f -> PlanetType.RADIOACTIVE
            typeRoll < 0.73f -> PlanetType.ALIEN
            typeRoll < 0.90f -> PlanetType.DEAD_ROCK
            else             -> PlanetType.STATION
        }

        val isMoon = forceMoon || (!listOf(PlanetType.GAS_GIANT, PlanetType.STATION).contains(type) && rng.nextBool(0.2f))

        // Colors per type
        val (primary, accent, atmosphere) = colorsFor(type, rng)

        val hasRings = !isMoon && type == PlanetType.GAS_GIANT && rng.nextBool(0.6f) ||
                       !isMoon && type == PlanetType.DESERT && rng.nextBool(0.15f)

        return PlanetSpec(
            seed            = seed,
            type            = type,
            isMoon          = isMoon,
            hasRings        = hasRings,
            hasAtmosphere   = type !in listOf(PlanetType.DEAD_ROCK, PlanetType.STATION),
            atmosphereColor = atmosphere,
            primaryColor    = primary,
            accentColor     = accent,
            hasLife         = type in listOf(PlanetType.LIFE_BEARING, PlanetType.OCEAN, PlanetType.JUNGLE),
            isHostile       = type in listOf(PlanetType.LAVA, PlanetType.TOXIC, PlanetType.RADIOACTIVE),
            isRadioactive   = type == PlanetType.RADIOACTIVE,
            isMineralRich   = type in listOf(PlanetType.DEAD_ROCK, PlanetType.DESERT, PlanetType.LAVA),
            hasFuelScoop    = type == PlanetType.GAS_GIANT,
            canLand         = type !in listOf(PlanetType.GAS_GIANT),
            craterCount     = when (type) {
                PlanetType.DEAD_ROCK -> rng.nextInt(4) + 3
                PlanetType.DESERT    -> rng.nextInt(3) + 2
                PlanetType.ICE       -> rng.nextInt(2)
                else                 -> 0
            },
            bandCount = when (type) {
                PlanetType.GAS_GIANT -> rng.nextInt(4) + 4
                PlanetType.DESERT    -> rng.nextInt(3) + 2
                PlanetType.JUNGLE    -> rng.nextInt(2)
                else                 -> 0
            },
            ringCount = if (hasRings) rng.nextInt(3) + 2 else 0
        )
    }

    private fun colorsFor(type: PlanetType, rng: SeededRng): Triple<Int, Int, Int> =
        when (type) {
            // primary, accent, atmosphere-halo
            PlanetType.LIFE_BEARING -> Triple(
                Color.parseColor("#00aaff"),
                Color.parseColor("#00ff88"),
                Color.argb(40, 0, 150, 255)
            )
            PlanetType.OCEAN -> Triple(
                Color.parseColor("#0055cc"),
                Color.parseColor("#00ccff"),
                Color.argb(50, 0, 100, 200)
            )
            PlanetType.JUNGLE -> Triple(
                Color.parseColor("#00cc44"),
                Color.parseColor("#00ff44"),
                Color.argb(45, 0, 180, 60)
            )
            PlanetType.ICE -> Triple(
                Color.parseColor("#aaeeff"),
                Color.parseColor("#ffffff"),
                Color.argb(35, 180, 240, 255)
            )
            PlanetType.DESERT -> Triple(
                Color.parseColor("#cc6600"),
                Color.parseColor("#ffaa44"),
                Color.argb(30, 200, 100, 0)
            )
            PlanetType.GAS_GIANT -> Triple(
                Color.parseColor("#ff8800"),
                Color.parseColor("#ffcc44"),
                Color.argb(40, 255, 140, 0)
            )
            PlanetType.LAVA -> Triple(
                Color.parseColor("#ff2200"),
                Color.parseColor("#ff6600"),
                Color.argb(50, 200, 30, 0)
            )
            PlanetType.TOXIC -> Triple(
                Color.parseColor("#aaff00"),
                Color.parseColor("#ffff00"),
                Color.argb(55, 150, 255, 0)
            )
            PlanetType.RADIOACTIVE -> Triple(
                Color.parseColor("#00ffcc"),
                Color.parseColor("#ff00ff"),
                Color.argb(60, 0, 255, 180)
            )
            PlanetType.ALIEN -> Triple(
                Color.parseColor("#cc44ff"),
                Color.parseColor("#ff44cc"),
                Color.argb(45, 160, 0, 255)
            )
            PlanetType.DEAD_ROCK -> Triple(
                Color.parseColor("#6688aa"),
                Color.parseColor("#445566"),
                Color.argb(0, 0, 0, 0)   // no atmosphere
            )
            PlanetType.STATION -> Triple(
                Color.parseColor("#00ff41"),
                Color.parseColor("#00cc33"),
                Color.argb(0, 0, 0, 0)
            )
        }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Renderer — reads any PlanetSpec, knows nothing about individual types
//  internally it dispatches to private draw functions per type.
// ─────────────────────────────────────────────────────────────────────────────

object PlanetRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    /**
     * Draw [spec] centred at (cx, cy) with the given [radius].
     * Call from your render loop — no allocations inside.
     */
    fun draw(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, radius: Float) {
        val rng = SeededRng(spec.seed xor 0xDEAD_BEEF)
        val r = if (spec.isMoon) radius * 0.55f else radius

        // Rings behind planet
        if (spec.hasRings) drawRingsBack(canvas, spec, cx, cy, r, rng)

        // Planet body
        when (spec.type) {
            PlanetType.LIFE_BEARING -> drawLifeBearing(canvas, spec, cx, cy, r, rng)
            PlanetType.OCEAN        -> drawOcean      (canvas, spec, cx, cy, r, rng)
            PlanetType.JUNGLE       -> drawJungle     (canvas, spec, cx, cy, r, rng)
            PlanetType.ICE          -> drawIce        (canvas, spec, cx, cy, r, rng)
            PlanetType.DESERT       -> drawDesert     (canvas, spec, cx, cy, r, rng)
            PlanetType.GAS_GIANT    -> drawGasGiant   (canvas, spec, cx, cy, r, rng)
            PlanetType.LAVA         -> drawLava       (canvas, spec, cx, cy, r, rng)
            PlanetType.TOXIC        -> drawToxic      (canvas, spec, cx, cy, r, rng)
            PlanetType.RADIOACTIVE  -> drawRadioactive(canvas, spec, cx, cy, r, rng)
            PlanetType.ALIEN        -> drawAlien      (canvas, spec, cx, cy, r, rng)
            PlanetType.DEAD_ROCK    -> drawDeadRock   (canvas, spec, cx, cy, r, rng)
            PlanetType.STATION      -> drawStation    (canvas, spec, cx, cy, r, rng)
        }

        // Atmosphere halo (drawn on top of body, outside radius)
        if (spec.hasAtmosphere && Color.alpha(spec.atmosphereColor) > 0) {
            paint.color = spec.atmosphereColor
            paint.strokeWidth = r * 0.06f
            canvas.drawCircle(cx, cy, r + r * 0.04f, paint)
        }

        // Rings in front of planet
        if (spec.hasRings) drawRingsFront(canvas, spec, cx, cy, r, rng)
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    /** Clip canvas to circle, run [block], restore. */
    private inline fun clippedToCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, block: () -> Unit) {
        canvas.save()
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(path)
        block()
        canvas.restore()
    }

    private fun latitudeLines(canvas: Canvas, cx: Float, cy: Float, r: Float, count: Int, color: Int, alpha: Float = 0.3f) {
        val c = color
        paint.color = Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
        paint.strokeWidth = 0.7f
        for (i in -count..count) {
            val y = cy + i * (r / (count + 0.5f))
            val dx = sqrt(max(0f, r * r - (y - cy) * (y - cy)))
            if (dx < 2f) continue
            canvas.drawLine(cx - dx, y, cx + dx, y, paint)
        }
    }

    private fun craterAt(canvas: Canvas, ox: Float, oy: Float, cr: Float, cx: Float, cy: Float, r: Float, color: Int) {
        if (sqrt((ox - cx).pow(2) + (oy - cy).pow(2)) + cr > r - 2f) return
        paint.color = color
        paint.strokeWidth = 0.9f
        canvas.drawCircle(ox, oy, cr, paint)
        canvas.drawCircle(ox, oy, cr * 0.35f, paint)
        // Shadow arc on lower-right (sun from top-left convention)
        paint.strokeWidth = 1.2f
        paint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
        oval.set(ox - cr, oy - cr, ox + cr, oy + cr)
        canvas.drawArc(oval, 20f, 160f, false, paint)
    }

    private fun outline(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, width: Float = 1.5f) {
        paint.color = color; paint.strokeWidth = width
        canvas.drawCircle(cx, cy, r, paint)
    }

    // ── Ring system ───────────────────────────────────────────────────────────

    private fun drawRingsBack(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        val c = spec.primaryColor
        for (i in 0 until spec.ringCount) {
            val rr = r * (1.25f + i * 0.14f)
            val alpha = (0.25f + rng.next() * 0.15f)
            paint.color = Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            paint.strokeWidth = if (i == 1) 3f else 1.2f
            oval.set(cx - rr, cy - rr * 0.24f, cx + rr, cy + rr * 0.24f)
            canvas.drawArc(oval, 180f, 180f, false, paint)  // back half only
        }
    }

    private fun drawRingsFront(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        val c = spec.primaryColor
        for (i in 0 until spec.ringCount) {
            val rr = r * (1.25f + i * 0.14f)
            val alpha = (0.45f + rng.next() * 0.2f)
            paint.color = Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            paint.strokeWidth = if (i == 1) 3f else 1.2f
            oval.set(cx - rr, cy - rr * 0.24f, cx + rr, cy + rr * 0.24f)
            canvas.drawArc(oval, 0f, 180f, false, paint)    // front half only
        }
    }

    // ── Planet type draw functions ────────────────────────────────────────────

    private fun drawLifeBearing(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        // Ocean fill
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(80, 0, 80, 160)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
            // Landmasses — 2–4 irregular circles
            val landCount = 2 + rng.nextInt(3)
            repeat(landCount) {
                val a = rng.next() * PI.toFloat() * 2f
                val d = rng.next() * r * 0.55f
                val lx = cx + cos(a) * d
                val ly = cy + sin(a) * d
                val lr = r * (0.18f + rng.next() * 0.22f)
                fillPaint.color = Color.argb(70, 0, 160 + rng.nextInt(60), 40 + rng.nextInt(40))
                canvas.drawCircle(lx, ly, lr, fillPaint)
                paint.color = spec.accentColor
                paint.strokeWidth = 0.9f
                canvas.drawCircle(lx, ly, lr, paint)
            }
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        latitudeLines(canvas, cx, cy, r, 3, spec.primaryColor, 0.25f)
        // Polar ice caps
        paint.color = Color.parseColor("#aaffee"); paint.strokeWidth = 1f
        for (pole in listOf(-1f, 1f)) {
            val py = cy + pole * r * 0.82f
            val pdx = sqrt(max(0f, r * r - (py - cy) * (py - cy)))
            canvas.drawLine(cx - pdx, py, cx + pdx, py, paint)
        }
    }

    private fun drawOcean(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(90, 0, 60, 180)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
            // Small island specks
            repeat(3 + rng.nextInt(3)) {
                val a = rng.next() * PI.toFloat() * 2f
                val d = rng.next() * r * 0.6f
                val lx = cx + cos(a) * d; val ly = cy + sin(a) * d
                val lr = r * (0.05f + rng.next() * 0.1f)
                fillPaint.color = Color.argb(60, 0, 140, 80)
                canvas.drawCircle(lx, ly, lr, fillPaint)
            }
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        latitudeLines(canvas, cx, cy, r, 3, spec.primaryColor, 0.2f)
    }

    private fun drawJungle(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(100, 0, 70, 10)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
            repeat(6 + rng.nextInt(4)) {
                val a = rng.next() * PI.toFloat() * 2f
                val d = rng.next() * r * 0.7f
                val lx = cx + cos(a) * d; val ly = cy + sin(a) * d
                val lr = r * (0.1f + rng.next() * 0.2f)
                fillPaint.color = Color.argb(70, 0, 80 + rng.nextInt(80), 0)
                canvas.drawCircle(lx, ly, lr, fillPaint)
            }
            // Water glints
            repeat(2) {
                val a = rng.next() * PI.toFloat() * 2f
                val d = rng.next() * r * 0.5f
                val lx = cx + cos(a) * d; val ly = cy + sin(a) * d
                fillPaint.color = Color.argb(60, 0, 100, 180)
                canvas.drawCircle(lx, ly, r * 0.12f, fillPaint)
                paint.color = Color.parseColor("#00aaff"); paint.strokeWidth = 0.8f
                canvas.drawCircle(lx, ly, r * 0.12f, paint)
            }
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        // Bio-glow at poles
        paint.color = Color.argb(120, 0, 255, 100); paint.strokeWidth = 2f
        oval.set(cx - r - 2, cy - r - 2, cx + r + 2, cy + r + 2)
        canvas.drawArc(oval, 210f, 60f, false, paint)
        canvas.drawArc(oval, 30f, 60f, false, paint)
    }

    private fun drawIce(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(30, 0, 160, 200)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        // Ice crack network — branching lines
        paint.color = Color.argb(150, 0, 255, 238); paint.strokeWidth = 0.8f
        val nodes = mutableListOf<Pair<Float, Float>>()
        nodes.add(Pair(cx, cy - r * 0.4f))
        repeat(4 + rng.nextInt(4)) {
            val prev = nodes.random()   // pick a random existing node
            val a = rng.next() * PI.toFloat() * 2f
            val len = r * (0.2f + rng.next() * 0.35f)
            val nx = prev.first + cos(a) * len
            val ny = prev.second + sin(a) * len
            // Clip crack to sphere
            val dist = sqrt((nx - cx).pow(2) + (ny - cy).pow(2))
            val clampedNx = if (dist > r - 2f) cx + (nx - cx) * (r - 2f) / dist else nx
            val clampedNy = if (dist > r - 2f) cy + (ny - cy) * (r - 2f) / dist else ny
            canvas.drawLine(prev.first, prev.second, clampedNx, clampedNy, paint)
            nodes.add(Pair(clampedNx, clampedNy))
        }
        // Ice caps
        paint.color = spec.accentColor; paint.strokeWidth = 1f
        for (pole in listOf(-1f, 1f)) {
            val py = cy + pole * r * 0.75f
            val pdx = sqrt(max(0f, r * r - (py - cy) * (py - cy)))
            canvas.drawLine(cx - pdx, py, cx + pdx, py, paint)
        }
    }

    private fun drawDesert(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(50, 180, 80, 0)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
            // Dust band
            fillPaint.color = Color.argb(35, 220, 100, 0)
            val bandY = cy + rng.nextInRange(-r * 0.2f, r * 0.2f)
            canvas.drawRect(cx - r, bandY, cx + r, bandY + r * 0.35f, fillPaint)
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        // Craters
        repeat(spec.craterCount) {
            val a = rng.next() * PI.toFloat() * 2f
            val d = rng.next() * r * 0.65f
            val ox = cx + cos(a) * d; val oy = cy + sin(a) * d
            craterAt(canvas, ox, oy, r * (0.07f + rng.next() * 0.14f), cx, cy, r, spec.primaryColor)
        }
        latitudeLines(canvas, cx, cy, r, spec.bandCount, spec.primaryColor, 0.25f)
        // Polar ice line
        paint.color = Color.parseColor("#ffccaa"); paint.strokeWidth = 1f
        val py = cy - r * 0.82f
        val pdx = sqrt(max(0f, r * r - (py - cy) * (py - cy)))
        canvas.drawLine(cx - pdx, py, cx + pdx, py, paint)
    }

    private fun drawGasGiant(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            val bandAlphas = listOf(0.35f, 0.2f, 0.3f, 0.25f, 0.3f, 0.2f, 0.15f, 0.25f)
            val pc = spec.primaryColor
            for (i in 0 until spec.bandCount) {
                val offset = r * (i.toFloat() / spec.bandCount)
                val height = r * (0.22f / spec.bandCount * 2.5f)
                val alpha = bandAlphas.getOrElse(i) { 0.2f }
                fillPaint.color = Color.argb((alpha * 255).toInt(), Color.red(pc), Color.green(pc), Color.blue(pc))
                canvas.drawRect(cx - r, cy - r + offset, cx + r, cy - r + offset + height, fillPaint)
                canvas.drawRect(cx - r, cy - offset - height, cx + r, cy - offset, fillPaint)
            }
            // Storm eye — ellipse
            val ex = cx + rng.nextInRange(-r * 0.3f, r * 0.3f)
            val ey = cy + rng.nextInRange(-r * 0.1f, r * 0.3f)
            paint.color = spec.accentColor; paint.strokeWidth = 1f
            oval.set(ex - r * 0.18f, ey - r * 0.11f, ex + r * 0.18f, ey + r * 0.11f)
            canvas.drawOval(oval, paint)
            oval.set(ex - r * 0.08f, ey - r * 0.05f, ex + r * 0.08f, ey + r * 0.05f)
            canvas.drawOval(oval, paint)
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
    }

    private fun drawLava(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(75, 150, 20, 0)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        // Lava vein network — branching from centre
        paint.color = spec.accentColor; paint.strokeWidth = 1f
        val veins = mutableListOf(Pair(cx, cy))
        repeat(6 + rng.nextInt(4)) {
            val src = veins[rng.nextInt(veins.size)]
            val a = rng.next() * PI.toFloat() * 2f
            val len = r * (0.2f + rng.next() * 0.5f)
            val ex = src.first + cos(a) * len
            val ey = src.second + sin(a) * len
            clippedToCircle(canvas, cx, cy, r - 1f) {
                canvas.drawLine(src.first, src.second, ex, ey, paint)
            }
            veins.add(Pair(ex, ey))
        }
        // Hot-spot dots
        paint.color = spec.accentColor; paint.strokeWidth = 0.8f
        repeat(3 + rng.nextInt(3)) {
            val a = rng.next() * PI.toFloat() * 2f
            val d = rng.next() * r * 0.6f
            val ox = cx + cos(a) * d; val oy = cy + sin(a) * d
            if (sqrt((ox - cx).pow(2) + (oy - cy).pow(2)) < r - 6f) {
                canvas.drawCircle(ox, oy, 5f, paint)
            }
        }
    }

    private fun drawToxic(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(60, 80, 140, 0)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
            // Swirling cloud bands
            paint.color = Color.argb(80, 150, 220, 0); paint.strokeWidth = 0.8f
            repeat(5 + rng.nextInt(4)) {
                val y = cy + rng.nextInRange(-r * 0.9f, r * 0.9f)
                val dy = y - cy
                val dx = sqrt(max(0f, r * r - dy * dy))
                if (dx > 2f) canvas.drawLine(cx - dx, y, cx + dx, y, paint)
            }
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        // Outer toxic haze — extra thick atmosphere ring
        paint.color = Color.argb(60, 150, 255, 0); paint.strokeWidth = r * 0.08f
        canvas.drawCircle(cx, cy, r + r * 0.06f, paint)
    }

    private fun drawRadioactive(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        // Hex grid surface (same technique as HTML HEXWORLD)
        outline(canvas, cx, cy, r, spec.primaryColor)
        clippedToCircle(canvas, cx, cy, r) {
            val hexR = r * 0.22f
            val hexW = hexR * 2f
            val hexH = sqrt(3f) * hexR
            for (row in -3..3) for (col in -3..3) {
                val hx = cx + col * hexW * 0.75f
                val hy = cy + row * hexH + if (col % 2 == 0) 0f else hexH / 2f
                val dist = sqrt((hx - cx).pow(2) + (hy - cy).pow(2))
                if (dist > r * 0.9f) continue
                val scale = sqrt(1f - min(1f, (dist / (r * 0.95f)).pow(2))) * 0.85f + 0.15f
                paint.strokeWidth = 0.8f * scale
                paint.color = Color.argb(((0.3f + 0.6f * scale) * 255).toInt(),
                    Color.red(spec.primaryColor), Color.green(spec.primaryColor), Color.blue(spec.primaryColor))
                path.reset()
                for (v in 0..5) {
                    val a = PI.toFloat() / 6f + v * PI.toFloat() / 3f
                    val vx = hx + cos(a) * hexR * scale * 0.85f
                    val vy = hy + sin(a) * hexR * scale * 0.85f
                    if (v == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        // Magenta accent glow ring
        paint.color = Color.argb(100, 255, 0, 200); paint.strokeWidth = r * 0.05f
        canvas.drawCircle(cx, cy, r + r * 0.03f, paint)
    }

    private fun drawAlien(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        clippedToCircle(canvas, cx, cy, r) {
            fillPaint.color = Color.argb(100, 40, 0, 80)
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillPaint)
            // Alien landmasses
            repeat(3 + rng.nextInt(2)) {
                val a = rng.next() * PI.toFloat() * 2f
                val d = rng.next() * r * 0.55f
                val lx = cx + cos(a) * d; val ly = cy + sin(a) * d
                val lr = r * (0.15f + rng.next() * 0.25f)
                fillPaint.color = Color.argb(60, 120, 0, 200)
                canvas.drawCircle(lx, ly, lr, fillPaint)
                paint.color = spec.primaryColor; paint.strokeWidth = 1f
                canvas.drawCircle(lx, ly, lr, paint)
            }
        }
        outline(canvas, cx, cy, r, spec.primaryColor)
        // Bioluminescent haze
        paint.color = Color.argb(50, 180, 0, 255); paint.strokeWidth = r * 0.06f
        canvas.drawCircle(cx, cy, r + r * 0.04f, paint)
        latitudeLines(canvas, cx, cy, r, 3, spec.primaryColor, 0.22f)
    }

    private fun drawDeadRock(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        // No fill — just outline and craters
        outline(canvas, cx, cy, r, spec.primaryColor)
        // Craters with topographic rings + shadow arcs
        repeat(spec.craterCount) {
            val a = rng.next() * PI.toFloat() * 2f
            val d = rng.next() * r * 0.65f
            val ox = cx + cos(a) * d; val oy = cy + sin(a) * d
            craterAt(canvas, ox, oy, r * (0.08f + rng.next() * 0.18f), cx, cy, r, spec.primaryColor)
        }
        // Terminator shadow line (bezier)
        paint.color = Color.argb(100, Color.red(spec.primaryColor), Color.green(spec.primaryColor), Color.blue(spec.primaryColor))
        paint.strokeWidth = 1f
        val tOff = rng.nextInRange(-r * 0.2f, r * 0.3f)
        path.reset()
        path.moveTo(cx + tOff, cy - r)
        path.cubicTo(cx - r * 0.35f + tOff, cy - r * 0.3f, cx - r * 0.35f + tOff, cy + r * 0.3f, cx + tOff, cy + r)
        canvas.drawPath(path, paint)
        // Light hatch on un-shadowed side
        paint.color = Color.argb(40, Color.red(spec.primaryColor), Color.green(spec.primaryColor), Color.blue(spec.primaryColor))
        paint.strokeWidth = 0.5f
        clippedToCircle(canvas, cx, cy, r) {
            for (i in -4..4) {
                val y = cy + i * (r / 5f)
                val dx = sqrt(max(0f, r * r - (y - cy) * (y - cy)))
                canvas.drawLine(cx - dx, y, cx + dx, y, paint)
            }
        }
    }

    private fun drawStation(canvas: Canvas, spec: PlanetSpec, cx: Float, cy: Float, r: Float, rng: SeededRng) {
        outline(canvas, cx, cy, r, spec.primaryColor, 1.5f)
        // Latitude and longitude grid
        paint.color = Color.argb(90, 0, 255, 65); paint.strokeWidth = 0.7f
        clippedToCircle(canvas, cx, cy, r) {
            for (i in -3..3) {
                val y = cy + i * (r / 4f)
                val dx = sqrt(max(0f, r * r - (y - cy) * (y - cy)))
                if (dx > 2f) canvas.drawLine(cx - dx, y, cx + dx, y, paint)
            }
            for (i in 0 until 6) {
                val a = i * PI.toFloat() / 3f
                canvas.drawLine(cx + cos(a) * r * 0.1f, cy + sin(a) * r * 0.1f,
                    cx + cos(a) * r, cy + sin(a) * r, paint)
            }
        }
        // Docking arms — 4 directions
        paint.color = spec.primaryColor; paint.strokeWidth = 1.5f
        for ((dx, dy) in listOf(0f to -1f, 0f to 1f, 1f to 0f, -1f to 0f)) {
            canvas.drawLine(cx + dx * r, cy + dy * r,
                cx + dx * (r + r * 0.45f), cy + dy * (r + r * 0.45f), paint)
            val padCx = cx + dx * (r + r * 0.4f)
            val padCy = cy + dy * (r + r * 0.4f)
            canvas.drawRect(padCx - 5f, padCy - 5f, padCx + 5f, padCy + 5f, paint)
        }
        // Solar panel wings on left & right arms
        paint.strokeWidth = 1f
        for (sign in listOf(-1f, 1f)) {
            val bx = cx + sign * (r + r * 0.42f)
            for (p in -2..2) canvas.drawLine(bx - 8f, cy + p * 8f, bx + 8f, cy + p * 8f, paint)
            canvas.drawRect(bx - 9f, cy - 18f, bx + 9f, cy + 18f, paint)
        }
        // Central hub ring
        paint.color = Color.argb(150, 0, 255, 65); paint.strokeWidth = 1f
        canvas.drawCircle(cx, cy, r * 0.3f, paint)
    }
}

// =============================================================================
//  USAGE EXAMPLE — drop this comment into your game's MapEntity or World class:
//
//  // Each planet in the world gets a stable seed (e.g. hash of its grid coords)
//  val planetSeed = (gridX * 73856093L) xor (gridY * 19349663L)
//  val spec = PlanetGenerator.generate(planetSeed)
//
//  // On the map layer:
//  PlanetRenderer.draw(canvas, spec, worldX, worldY, displayRadius)
//
//  // When the player scans or approaches:
//  if (spec.hasLife)       showScanResult("BIOSPHERE DETECTED")
//  if (spec.isHostile)     activateShields()
//  if (spec.isRadioactive) startRadiationTimer()
//  if (spec.hasFuelScoop)  showFuelScoopPrompt()
//  if (spec.canLand)       showLandingApproach()
//  if (spec.type == PlanetType.STATION) showDockingMenu()
// =============================================================================
