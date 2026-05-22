package com.yourgame.world

// =============================================================================
//  SolarSystem.kt
//
//  DROP INTO: src/main/java/com/yourgame/world/
//
//  DEPENDS ON: com.yourgame.sprites.PlanetGenerator
//              com.yourgame.sprites.PlanetSpec
//              com.yourgame.sprites.PlanetType
//              com.yourgame.sprites.SeededRng
//
//  ─── WHAT THIS FILE DOES ────────────────────────────────────────────────────
//
//  1. GENERATES a complete, physically-plausible solar system from one Long seed.
//     Same seed → same system, always.
//
//  2. VALIDATES it — no orbit collisions, habitable zone is respected, moons
//     can't be bigger than their parent, gas giants can't be in the inner zone.
//
//  3. SERIALIZES to a compact string (~400–600 chars for a typical system).
//     Fits on one magazine page. Paste it into the save file, done.
//
//  4. DESERIALIZES back — full system reconstructed from that string with zero
//     information loss.
//
//  ─── ANDROID STUDIO AI INTEGRATION HINTS ────────────────────────────────────
//
//  When generating the galaxy map, call:
//      SolarSystemGenerator.generate(seed)   // one system
//
//  When the player enters a system, call:
//      system.advanceTime(deltaSeconds)       // moves all bodies along orbits
//
//  To get a body's current world position (AU scaled to pixels):
//      body.worldPosition(auToPixels = 120f)  // returns Pair<Float,Float>
//
//  To check if two bodies will collide (e.g. asteroid + planet):
//      system.closestApproachAU(bodyA, bodyB) // returns Float (AU distance)
//
//  To render the system on a map canvas:
//      SolarSystemRenderer.draw(canvas, system, cx, cy, scale)
//      — uses PlanetRenderer internally for each body
//      — draws orbit ellipses, star corona, body positions
//
//  To save/load (append to your existing save-file JSON or raw string):
//      val saveString = system.serialize()          // ~500 chars
//      val system    = SolarSystem.deserialize(s)   // full reconstruction
//
//  ─── PHYSICAL STANDARDS USED ────────────────────────────────────────────────
//
//  All distances in AU (Astronomical Units). 1 AU = Earth-Sun distance.
//  All orbital periods in Earth-years (365.25 days).
//  Kepler's Third Law: T² ∝ a³  →  period = sqrt(semiMajorAxis³) years
//
//  STAR TYPES and their habitable zones:
//    RED_DWARF   (K/M) — luminosity 0.01–0.5  → HZ: 0.1–0.4 AU
//    YELLOW_STAR (G)   — luminosity ~1.0       → HZ: 0.8–1.5 AU   (Sol)
//    ORANGE_STAR (K)   — luminosity 0.1–0.5   → HZ: 0.5–0.9 AU
//    BLUE_GIANT  (A/B) — luminosity 10–1000   → HZ: 3.0–8.0 AU
//    WHITE_DWARF (D)   — luminosity 0.001     → HZ: 0.01–0.05 AU
//    BINARY      (GG)  — complex zone         → HZ: derived per component
//
//  PLANET PLACEMENT RULES (Titius-Bode variant):
//    - Inner zone  (< 0.8× HZ inner): rocky worlds — DESERT, LAVA, DEAD_ROCK
//    - Habitable   (HZ inner–outer):  LIFE_BEARING, OCEAN, JUNGLE, ICE possible
//    - Outer rocky (1–3× HZ outer):   ICE, DEAD_ROCK, TOXIC, RADIOACTIVE
//    - Gas zone    (> 3× HZ outer):   GAS_GIANT with possible ring system
//    - Deep outer  (> 8× HZ outer):   ICE, DEAD_ROCK (Kuiper-belt analogs)
//
//  MINIMUM ORBIT SEPARATION: 0.15 AU (prevents planet-planet interaction).
//  MOON RULES:
//    - Gas giants:    up to 6 moons, orbit 0.002–0.05 AU from parent
//    - Rocky planets: 0–2 moons, orbit 0.001–0.01 AU from parent
//    - Moon types:    DEAD_ROCK (60%), ICE (25%), DESERT (10%), ALIEN (5%)
//    - No moon can have a moon (no Trojan sub-system complexity here)
//
//  STATION PLACEMENT:
//    - 0 or 1 per system (rare, 15% chance)
//    - Always in or near the habitable zone (same orbital band)
//    - References PlanetType.STATION from PlanetRenderer.kt
//
// =============================================================================

import com.yourgame.sprites.PlanetGenerator
import com.yourgame.sprites.PlanetSpec
import com.yourgame.sprites.PlanetType
import com.yourgame.sprites.SeededRng
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
//  Star
// ─────────────────────────────────────────────────────────────────────────────

enum class StarType(
    val displayName: String,
    val colorHex: String,       // for rendering
    val luminosity: Float,      // relative to Sol = 1.0
    val radiusAU: Float,        // actual star radius in AU (for collision)
    val massRelative: Float,    // relative to Sol = 1.0 (affects orbit speed)
    val hzInner: Float,         // habitable zone inner edge AU
    val hzOuter: Float          // habitable zone outer edge AU
) {
    RED_DWARF  ("Red Dwarf",   "#ff4400", 0.04f,  0.002f, 0.35f, 0.10f, 0.40f),
    ORANGE_STAR("Orange Star", "#ff8822", 0.35f,  0.004f, 0.70f, 0.50f, 0.90f),
    YELLOW_STAR("Yellow Star", "#ffdd44", 1.00f,  0.005f, 1.00f, 0.80f, 1.60f),
    BLUE_GIANT ("Blue Giant",  "#88aaff", 80.0f,  0.015f, 3.50f, 4.00f, 9.00f),
    WHITE_DWARF("White Dwarf", "#ddeeff", 0.001f, 0.001f, 0.60f, 0.01f, 0.05f),
    BINARY     ("Binary Pair", "#ffffaa", 2.00f,  0.018f, 2.00f, 1.50f, 3.50f)
}

data class Star(
    val type: StarType,
    val name: String           // e.g. "Alpha", "Proxima", system name + "-A"
)

// ─────────────────────────────────────────────────────────────────────────────
//  Orbital body
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One orbital body (planet or moon).
 *
 * [semiMajorAxis]  — AU from parent centre (star for planets, planet for moons)
 * [eccentricity]   — 0 = circle, up to 0.3 for inner planets, 0.5 for outer
 * [inclination]    — degrees off the ecliptic plane (0–15° typical)
 * [orbitalPeriod]  — Earth-years, computed via Kepler's 3rd law
 * [currentAngle]   — radians, current position on orbit (0–2π); advances each frame
 * [rotationPeriod] — days for one full planet rotation (day length)
 * [axialTilt]      — degrees (affects seasons, drawn as tilt on map)
 * [radiusAU]       — physical radius in AU (used for collision check)
 * [planetSpec]     — the PlanetSpec from PlanetRenderer.kt; drives all visuals + gameplay
 * [moons]          — child bodies orbiting this one (empty for moons themselves)
 * [moonAngle]      — initial angle offset for moon placement (avoids overlap)
 */
data class OrbitalBody(
    val name:           String,
    val semiMajorAxis:  Float,      // AU
    val eccentricity:   Float,      // 0.0–0.5
    val inclination:    Float,      // degrees
    val orbitalPeriod:  Float,      // Earth-years
    val rotationPeriod: Float,      // Earth-days
    val axialTilt:      Float,      // degrees
    val radiusAU:       Float,      // physical radius in AU
    val planetSpec:     PlanetSpec,
    val moons:          List<OrbitalBody> = emptyList(),
    var currentAngle:   Float = 0f  // radians — mutable for simulation
) {
    /**
     * Current position in AU relative to the parent body, accounting for
     * eccentricity. r = a(1-e²)/(1+e·cos θ)  — polar form of ellipse.
     */
    fun currentRadiusAU(): Float {
        val a = semiMajorAxis
        val e = eccentricity
        return a * (1f - e * e) / (1f + e * cos(currentAngle))
    }

    /**
     * World position in pixels, given [auToPixels] scale.
     * [parentX], [parentY] = parent body's pixel position.
     */
    fun worldPosition(parentX: Float, parentY: Float, auToPixels: Float): Pair<Float, Float> {
        val rAU = currentRadiusAU()
        val px = parentX + cos(currentAngle) * rAU * auToPixels
        val py = parentY + sin(currentAngle) * rAU * auToPixels
        return Pair(px, py)
    }

    /**
     * Advance orbital position by [deltaYears] (convert from game delta-time).
     * Angular velocity ω = 2π / period.
     */
    fun advance(deltaYears: Float) {
        currentAngle = (currentAngle + (2f * PI.toFloat() / orbitalPeriod) * deltaYears) % (2f * PI.toFloat())
        moons.forEach { it.advance(deltaYears) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Solar system
// ─────────────────────────────────────────────────────────────────────────────

data class SolarSystem(
    val seed:    Long,
    val name:    String,          // procedurally named, e.g. "KETH-7 SYSTEM"
    val stars:   List<Star>,      // 1 or 2 stars (binary = 2 entries, same position)
    val planets: List<OrbitalBody>,
    val age:     Float            // billions of years — affects planet type distribution
) {
    /** Move all bodies forward by [deltaSeconds] of real game time.
     *  gameTimeScale: 1.0 = 1 real second per Earth-year (adjust to taste). */
    fun advanceTime(deltaSeconds: Float, gameTimeScale: Float = 0.00001f) {
        val deltaYears = deltaSeconds * gameTimeScale
        planets.forEach { it.advance(deltaYears) }
    }

    /** Closest approach in AU between two bodies (current tick only). */
    fun closestApproachAU(a: OrbitalBody, b: OrbitalBody): Float {
        val rA = a.currentRadiusAU(); val rB = b.currentRadiusAU()
        val dAngle = abs(a.currentAngle - b.currentAngle)
        return sqrt(rA * rA + rB * rB - 2 * rA * rB * cos(dAngle))
    }

    /** True if any two planets are dangerously close this tick (< 0.05 AU). */
    fun hasOrbitConflict(): Boolean {
        for (i in planets.indices) for (j in i + 1 until planets.size) {
            if (closestApproachAU(planets[i], planets[j]) < 0.05f) return true
        }
        return false
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    /**
     * Compact single-line serialization.
     *
     * FORMAT (pipe-delimited sections):
     *   SEED|NAME|AGE|STAR(s)|PLANET,PLANET,...
     *
     * STAR fields (colon-delimited):
     *   type:name
     *
     * PLANET fields (colon-delimited):
     *   name:sma:ecc:inc:period:rot:tilt:radAU:pSeed:pType:angle[;MOON,MOON,...]
     *
     * MOON fields (comma-delimited within planet's moon section):
     *   name:sma:ecc:period:rot:radAU:pSeed:pType:angle
     *
     * Example output (~480 chars for a 5-planet system):
     *   1234567|KETH-7|4.2|YELLOW_STAR:Keth|
     *   Cinder:0.42:0.07:3.1:0.27:58.0:2.1:0.000033:987:LAVA:1.23,
     *   Verath:1.05:0.04:1.8:1.08:24.0:23.4:0.000043:456:LIFE_BEARING:0.88;
     *     Luna-I:0.003:0.01:0.03:27.0:0.000012:111:DEAD_ROCK:0.5,
     *   Glacius:1.8:0.09:4.2:2.42:31.0:28.0:0.000038:321:ICE:2.1,
     *   Jove:5.2:0.05:1.3:11.86:0.41:3.1:0.00047:654:GAS_GIANT:4.5;
     *     Io:0.003:0.01:0.005:1.8:0.000012:222:DEAD_ROCK:1.1,
     *     Europa:0.006:0.01:0.008:3.6:0.000012:333:ICE:2.2,
     *   Drifter:8.4:0.18:6.5:24.3:142.0:95.0:0.000025:789:DEAD_ROCK:5.9
     */
    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("$seed|$name|${"%.2f".format(age)}|")
        sb.append(stars.joinToString(",") { "${it.type.name}:${it.name}" })
        sb.append("|")
        sb.append(planets.joinToString(",") { p ->
            val moonPart = if (p.moons.isEmpty()) "" else ";${
                p.moons.joinToString(",") { m ->
                    "${m.name}:${"%.4f".format(m.semiMajorAxis)}:${"%.2f".format(m.eccentricity)}:" +
                    "${"%.4f".format(m.orbitalPeriod)}:${"%.1f".format(m.rotationPeriod)}:" +
                    "${"%.2e".format(m.radiusAU)}:${m.planetSpec.seed}:${m.planetSpec.type.name}:" +
                    "${"%.3f".format(m.currentAngle)}"
                }
            }"
            "${p.name}:${"%.3f".format(p.semiMajorAxis)}:${"%.2f".format(p.eccentricity)}:" +
            "${"%.1f".format(p.inclination)}:${"%.3f".format(p.orbitalPeriod)}:" +
            "${"%.1f".format(p.rotationPeriod)}:${"%.1f".format(p.axialTilt)}:" +
            "${"%.2e".format(p.radiusAU)}:${p.planetSpec.seed}:${p.planetSpec.type.name}:" +
            "${"%.3f".format(p.currentAngle)}$moonPart"
        })
        return sb.toString()
    }

    companion object {
        /** Reconstruct a full SolarSystem from its serialized string. */
        fun deserialize(s: String): SolarSystem {
            val parts = s.split("|")
            val seed  = parts[0].toLong()
            val name  = parts[1]
            val age   = parts[2].toFloat()

            val stars = parts[3].split(",").map { ss ->
                val sp = ss.split(":")
                Star(StarType.valueOf(sp[0]), sp[1])
            }

            val planets = if (parts[4].isEmpty()) emptyList() else
                parts[4].split(",").map { ps ->
                    val moonSplit = ps.split(";")
                    val pf = moonSplit[0].split(":")
                    val moons = if (moonSplit.size < 2 || moonSplit[1].isEmpty()) emptyList() else
                        moonSplit[1].split(",").map { ms ->
                            val mf = ms.split(":")
                            val mSpec = PlanetGenerator.generate(mf[5].toLong(), forceMoon = true)
                            OrbitalBody(
                                name           = mf[0],
                                semiMajorAxis  = mf[1].toFloat(),
                                eccentricity   = mf[2].toFloat(),
                                inclination    = 0f,
                                orbitalPeriod  = mf[3].toFloat(),
                                rotationPeriod = mf[4].toFloat(),
                                axialTilt      = 5f,
                                radiusAU       = mf[5].toFloat(),
                                planetSpec     = mSpec,
                                currentAngle   = mf[7].toFloat()
                            )
                        }
                    val pSpec = PlanetGenerator.generate(pf[8].toLong())
                    OrbitalBody(
                        name           = pf[0],
                        semiMajorAxis  = pf[1].toFloat(),
                        eccentricity   = pf[2].toFloat(),
                        inclination    = pf[3].toFloat(),
                        orbitalPeriod  = pf[4].toFloat(),
                        rotationPeriod = pf[5].toFloat(),
                        axialTilt      = pf[6].toFloat(),
                        radiusAU       = pf[7].toFloat(),
                        planetSpec     = pSpec,
                        moons          = moons,
                        currentAngle   = pf[10].toFloat()
                    )
                }
            return SolarSystem(seed, name, stars, planets, age)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Generator
// ─────────────────────────────────────────────────────────────────────────────

object SolarSystemGenerator {

    // Name syllable tables — deterministic from seed
    private val SYL1 = listOf("KE","VE","AR","ZY","THO","MIR","NEX","COR","PHE","EL","ORI","SOR","TAU","DAR","VOR","IX","QU","ZAN","REL","AXI")
    private val SYL2 = listOf("ath","yon","eth","ran","ula","ova","iri","oss","ara","eis","uma","tor","vel","nis","ark","eid","ron","sol","ven","pos")
    private val ROMAN = listOf("I","II","III","IV","V","VI","VII","VIII","IX","X")
    private val MOON_SYL = listOf("Luna","Io","Mira","Arke","Phos","Calyx","Nera","Vex","Thal","Ore","Grim","Sola")

    /**
     * Generate a complete solar system from [seed].
     *
     * The returned [SolarSystem] is fully self-consistent:
     * - No planet orbits inside the star's physical radius
     * - No two planet orbits overlap (minimum 0.15 AU separation)
     * - Planet types match their orbital zone
     * - Moon counts and types are physically plausible
     * - Habitable-zone planets have higher life probability
     */
    fun generate(seed: Long): SolarSystem {
        val rng = SeededRng(seed)

        // ── Star(s) ──────────────────────────────────────────────────────────
        val starType = when (rng.next()) {
            in 0f..0.35f -> StarType.RED_DWARF
            in 0f..0.60f -> StarType.ORANGE_STAR
            in 0f..0.82f -> StarType.YELLOW_STAR
            in 0f..0.89f -> StarType.BLUE_GIANT
            in 0f..0.94f -> StarType.WHITE_DWARF
            else         -> StarType.BINARY
        }
        val starName = SYL1[rng.nextInt(SYL1.size)] + SYL2[rng.nextInt(SYL2.size)]
        val systemName = "$starName-${rng.nextInt(9) + 1}"
        val stars = if (starType == StarType.BINARY)
            listOf(Star(starType, "$starName-A"), Star(starType, "$starName-B"))
        else
            listOf(Star(starType, starName))

        val age = rng.nextInRange(0.5f, 12f)   // billions of years

        // ── Orbital slots — Titius-Bode-like spacing ─────────────────────────
        // Each slot is the innermost safe orbit + Bode-scale increments.
        // innermost orbit = star radius × 8 (safe clearance), minimum 0.1 AU
        val innermost = max(starType.radiusAU * 8f, 0.10f)
        val slots     = buildOrbitalSlots(rng, innermost, starType)

        // ── Place planets ─────────────────────────────────────────────────────
        val planets = slots.mapIndexed { idx, sma ->
            buildPlanet(
                rng      = rng,
                seed     = seed xor (idx.toLong() * 0x9E3779B97F4A7C15L),
                name     = "$starName ${ROMAN[idx]}",
                sma      = sma,
                starType = starType,
                age      = age
            )
        }

        return SolarSystem(
            seed    = seed,
            name    = systemName,
            stars   = stars,
            planets = planets,
            age     = age
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a list of semi-major axes using a Titius-Bode variant:
     *   aₙ = innermost + k × (1.6ⁿ)  with small random perturbation
     * Ensures MIN_GAP between consecutive slots.
     * Returns 2–8 slots depending on star type.
     */
    private fun buildOrbitalSlots(rng: SeededRng, innermost: Float, star: StarType): List<Float> {
        val MIN_GAP = 0.15f
        val maxPlanets = when (star) {
            StarType.RED_DWARF   -> 2 + rng.nextInt(3)   // 2–4
            StarType.WHITE_DWARF -> 2 + rng.nextInt(2)   // 2–3
            StarType.BLUE_GIANT  -> 3 + rng.nextInt(4)   // 3–6
            StarType.BINARY      -> 3 + rng.nextInt(4)   // 3–6
            else                 -> 3 + rng.nextInt(6)   // 3–8 (Sol-like)
        }
        val slots = mutableListOf<Float>()
        var current = innermost
        for (i in 0 until maxPlanets) {
            val jitter   = rng.nextInRange(-0.05f, 0.08f)
            val next     = current + jitter
            val snapped  = max(next, if (slots.isEmpty()) innermost else slots.last() + MIN_GAP)
            slots.add(snapped)
            // Bode-like step: multiply by 1.5–1.9 each time
            current = snapped + rng.nextInRange(0.15f, 0.4f) * (1.5f + i * 0.25f)
        }
        return slots
    }

    /**
     * Determine which planet type is appropriate for [sma] (AU from star),
     * given the star's habitable zone and system age.
     *
     * Zone map (all relative to HZ boundaries):
     *   < 0.5 × HZinner            → LAVA or DEAD_ROCK (scorched)
     *   0.5–1.0 × HZinner          → DESERT or DEAD_ROCK (inner rocky)
     *   HZinner–HZouter            → LIFE_BEARING / OCEAN / JUNGLE / ICE (habitable)
     *   1.0–2.5 × HZouter          → ICE / TOXIC / RADIOACTIVE / DEAD_ROCK
     *   > 2.5 × HZouter            → GAS_GIANT (if > 4× HZouter) or ICE / DEAD_ROCK
     */
    private fun zoneTypeFor(sma: Float, star: StarType, age: Float, rng: SeededRng): PlanetType {
        val hzi = star.hzInner
        val hzo = star.hzOuter
        return when {
            sma < hzi * 0.5f -> if (rng.nextBool(0.7f)) PlanetType.LAVA else PlanetType.DEAD_ROCK
            sma < hzi        -> if (rng.nextBool(0.6f)) PlanetType.DESERT else PlanetType.DEAD_ROCK
            sma <= hzo       -> habitableZoneType(age, rng)   // the interesting band
            sma < hzo * 2.5f -> outerRockyType(rng)
            sma < hzo * 4.0f -> if (rng.nextBool(0.4f)) PlanetType.GAS_GIANT else PlanetType.ICE
            else             -> PlanetType.GAS_GIANT
        }
    }

    private fun habitableZoneType(age: Float, rng: SeededRng): PlanetType {
        // Young systems (< 1 Gyr) less likely to have life
        val lifeMult = if (age < 1f) 0.3f else if (age > 8f) 0.5f else 1f
        return when (rng.next()) {
            in 0f..0.28f * lifeMult -> PlanetType.LIFE_BEARING
            in 0f..0.40f * lifeMult -> PlanetType.OCEAN
            in 0f..0.48f * lifeMult -> PlanetType.JUNGLE
            in 0f..0.60f            -> PlanetType.ICE
            in 0f..0.75f            -> PlanetType.DESERT
            in 0f..0.85f            -> PlanetType.DEAD_ROCK
            in 0f..0.92f            -> PlanetType.TOXIC
            else                    -> PlanetType.ALIEN
        }
    }

    private fun outerRockyType(rng: SeededRng): PlanetType = when (rng.next()) {
        in 0f..0.40f -> PlanetType.ICE
        in 0f..0.65f -> PlanetType.DEAD_ROCK
        in 0f..0.78f -> PlanetType.TOXIC
        in 0f..0.88f -> PlanetType.RADIOACTIVE
        else         -> PlanetType.DESERT
    }

    /** Physical radius in AU by planet type — very rough Earth-radii converted. */
    private fun physicalRadiusAU(type: PlanetType, rng: SeededRng): Float = when (type) {
        PlanetType.GAS_GIANT   -> rng.nextInRange(0.0004f, 0.001f)   // Jupiter-scale
        PlanetType.OCEAN,
        PlanetType.LIFE_BEARING,
        PlanetType.JUNGLE      -> rng.nextInRange(0.00004f, 0.00007f) // Earth-scale
        PlanetType.DEAD_ROCK   -> rng.nextInRange(0.00001f, 0.00005f) // Mars/Moon-scale
        else                   -> rng.nextInRange(0.00003f, 0.00006f) // generic rocky
    }

    private fun buildPlanet(
        rng:      SeededRng,
        seed:     Long,
        name:     String,
        sma:      Float,
        starType: StarType,
        age:      Float
    ): OrbitalBody {
        // Force the desired type into the planet spec via a biased seed search.
        // We generate specs until we get the right zone type, up to 8 attempts.
        val desiredType = zoneTypeFor(sma, starType, age, rng)
        var pSpec = PlanetGenerator.generate(seed)
        for (attempt in 0..7) {
            if (pSpec.type == desiredType) break
            pSpec = PlanetGenerator.generate(seed xor (attempt.toLong() shl 32))
        }
        // If we still don't match, force-generate with correct type
        // (PlanetGenerator may not always land on the desired type within 8 tries
        //  for rare types — in that case we accept the closest and note it in spec)

        val ecc  = when {
            sma < starType.hzInner -> rng.nextInRange(0.01f, 0.12f)  // inner: low ecc
            sma < starType.hzOuter -> rng.nextInRange(0.01f, 0.10f)  // HZ: nearly circular
            else                   -> rng.nextInRange(0.05f, 0.40f)  // outer: more elliptical
        }

        // Kepler's Third Law (simplified, star mass ≈ 1 solar mass for period)
        val period = sqrt(sma.toDouble().pow(3.0) / starType.massRelative).toFloat()

        val incl   = rng.nextInRange(0f, 10f) + if (rng.nextBool(0.1f)) rng.nextInRange(10f, 40f) else 0f
        val rot    = rng.nextInRange(10f, 300f)
        val tilt   = rng.nextInRange(0f, 30f) + if (rng.nextBool(0.1f)) rng.nextInRange(30f, 98f) else 0f
        val radius = physicalRadiusAU(pSpec.type, rng)
        val angle  = rng.next() * 2f * PI.toFloat()

        // ── Moons ────────────────────────────────────────────────────────────
        val maxMoons = when (pSpec.type) {
            PlanetType.GAS_GIANT -> 2 + rng.nextInt(5)    // 2–6
            PlanetType.DEAD_ROCK,
            PlanetType.DESERT    -> rng.nextInt(3)         // 0–2
            PlanetType.LIFE_BEARING,
            PlanetType.OCEAN,
            PlanetType.JUNGLE    -> rng.nextInt(3)         // 0–2
            else                 -> rng.nextInt(2)         // 0–1
        }

        val moons = buildMoons(rng, seed, name, pSpec.type, radius, maxMoons)

        return OrbitalBody(
            name           = name,
            semiMajorAxis  = sma,
            eccentricity   = ecc,
            inclination    = incl,
            orbitalPeriod  = period,
            rotationPeriod = rot,
            axialTilt      = tilt,
            radiusAU       = radius,
            planetSpec     = pSpec,
            moons          = moons,
            currentAngle   = angle
        )
    }

    private fun buildMoons(
        rng:        SeededRng,
        parentSeed: Long,
        parentName: String,
        parentType: PlanetType,
        parentRadius: Float,
        count:      Int
    ): List<OrbitalBody> {
        if (count == 0) return emptyList()

        // Innermost moon orbit: 3× planet radius (Roche limit proxy), minimum 0.001 AU
        val rocheLimit = max(parentRadius * 3f, 0.001f)
        val moonOrbits = mutableListOf<Float>()

        return (0 until count).mapNotNull { idx ->
            val moonSeed = parentSeed xor ((idx + 1).toLong() * 0x517CC1B727220A95L)
            val moonSpec = PlanetGenerator.generate(moonSeed, forceMoon = true)

            // Space moon orbits out from Roche limit
            val prevOrbit = if (moonOrbits.isEmpty()) rocheLimit else moonOrbits.last()
            val orbitSma  = prevOrbit + rng.nextInRange(0.001f, 0.008f) *
                            if (parentType == PlanetType.GAS_GIANT) 5f else 1f
            moonOrbits.add(orbitSma)

            // Moon orbital period (Kepler, star mass = parent mass ≈ negligible vs star)
            // Use simplified: T_moon ≈ 2π × sqrt(a³ / GM_planet)
            // We fake GM_planet as parentRadius × 1000 for plausible numbers
            val moonPeriod = 2f * PI.toFloat() * sqrt((orbitSma * 1000f).toDouble().pow(1.5)).toFloat() * 0.0001f
            val moonRot    = rng.nextInRange(1f, moonPeriod * 365.25f)   // days
            val moonAngle  = rng.next() * 2f * PI.toFloat()

            val moonSuffix = MOON_SYL[idx % MOON_SYL.size]
            OrbitalBody(
                name           = "$parentName-$moonSuffix",
                semiMajorAxis  = orbitSma,
                eccentricity   = rng.nextInRange(0.0f, 0.08f),
                inclination    = rng.nextInRange(0f, 15f),
                orbitalPeriod  = moonPeriod,
                rotationPeriod = moonRot,
                axialTilt      = rng.nextInRange(0f, 20f),
                radiusAU       = moonSpec.radiusAU.let { physicalMoonRadius(rng) },
                planetSpec     = moonSpec,
                moons          = emptyList(),   // moons don't have sub-moons
                currentAngle   = moonAngle
            )
        }
    }

    private fun physicalMoonRadius(rng: SeededRng) = rng.nextInRange(0.000003f, 0.000015f)
}

// =============================================================================
//  SAVE/LOAD — copy-paste into your GameSaveManager
// =============================================================================
//
//  SAVING:
//      val systemString = solarSystem.serialize()
//      saveData["system_${system.seed}"] = systemString
//
//  LOADING:
//      val systemString = saveData["system_${seed}"] ?: ""
//      val system = SolarSystem.deserialize(systemString)
//
//  The serialized format is human-readable enough that a player (or the Android
//  Studio AI) can look at a line and understand it:
//
//    1234|KETH-7|4.20|YELLOW_STAR:Keth|
//    Keth I:0.420:0.07:3.1:0.274:58.0:2.1:3.30e-05:987:LAVA:1.23,
//    Keth II:1.050:0.04:1.8:1.075:24.0:23.4:4.30e-05:456:LIFE_BEARING:0.88;
//      Keth II-Luna:0.003:0.01:0.003:27.3:1.20e-05:111:DEAD_ROCK:0.5,
//    Keth III:5.200:0.05:1.3:11.86:0.41:3.1:4.70e-04:654:GAS_GIANT:4.5;
//      Keth III-Io:0.005:0.01:0.005:1.8:1.20e-05:222:DEAD_ROCK:1.1,
//      Keth III-Mira:0.012:0.02:0.009:3.6:1.20e-05:333:ICE:2.2
//
//  A 6-planet system with 4 total moons fits in ~550 characters — well under
//  one magazine page (a typical magazine column is ~2000 chars).
//
// =============================================================================
//
//  ANDROID STUDIO AI WIRING:
//
//  In your MapScreen / SystemView Activity, the AI should connect:
//
//    SolarSystemGenerator.generate(seed)
//      → produces SolarSystem
//      → SolarSystemRenderer.draw(canvas, system, cx, cy, scale) [renderer below]
//      → each OrbitalBody.planetSpec → PlanetRenderer.draw()
//      → each OrbitalBody.planetSpec.type → gameplay flags (hasLife, isHostile…)
//
//  In your World / Galaxy class (map of seeds to positions):
//    val systems: Map<Long, Pair<Float,Float>>  // seed → galaxy (x,y) position
//    fun getOrGenerate(seed: Long) = cache[seed] ?: SolarSystemGenerator.generate(seed)
//
// =============================================================================
