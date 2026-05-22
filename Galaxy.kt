package com.yourgame.galaxy

// =============================================================================
//  Galaxy.kt
//
//  DROP INTO: src/main/java/com/yourgame/galaxy/
//
//  DEPENDS ON:
//    com.yourgame.world.SolarSystem          (SolarSystem, SolarSystemGenerator)
//    com.yourgame.world.StarType             (for star color on map)
//    com.yourgame.sprites.SeededRng          (shared deterministic RNG)
//
//  ─── THE CORE IDEA ──────────────────────────────────────────────────────────
//
//  A Galaxy is an infinite 2D field of star systems.  Rather than pre-generating
//  all of them (impossible), systems are VIRTUAL until visited:
//
//    • Every (chunkX, chunkY) grid cell contains a fixed number of star seeds.
//    • A seed is a Long derived from the chunk coords — deterministic forever.
//    • When the player enters a system, SolarSystemGenerator.generate(seed) runs.
//    • The result is written to the save file as a compact string (~500 chars).
//    • On subsequent visits the save string is loaded instead of regenerating.
//
//  So the save file only grows for systems the player has actually visited.
//  An entire unexplored galaxy costs zero bytes.
//
//  ─── COORDINATE SYSTEM ──────────────────────────────────────────────────────
//
//  Galaxy coordinates are in LIGHT-YEARS (LY).
//  The galaxy is divided into 20×20 LY chunks.  Each chunk holds 1–4 systems.
//  The Milky Way analogue is ~100,000 LY across — fully explorable in theory.
//
//  TRAVEL:
//    Impulse drive  : within a system (AU scale, handled by SolarSystem.kt)
//    Warp drive     : between systems, costs fuel proportional to distance (LY)
//    Jump drive     : long-range, unlockable, max range grows with tech level
//
//  TRAVEL TIME (real game-time seconds, adjust gameWarpSpeed to taste):
//    travelSeconds = distanceLY / gameWarpSpeed
//    default gameWarpSpeed = 2.5 LY/sec  →  10 LY = 4 sec real time
//
//  FUEL COST:
//    fuelCost = distanceLY * fuelPerLY * (1 + hazardMultiplier)
//    fuelPerLY = 0.01 (ship fuel tank = 1.0 = 100 LY range baseline)
//
//  ─── OBSTACLES & HAZARDS ────────────────────────────────────────────────────
//
//  GalaxyObstacle types — each blocks, slows, or damages travel through it:
//
//    BLACK_HOLE     — point mass.  Gravity well: ships within 2 LY are pulled.
//                     Crossing costs double fuel, risk of hull damage.
//                     Loot: exotic matter (rare resource) if player survives.
//
//    NEBULA         — cloud 5–15 LY radius.  Reduces warp speed by 60%.
//                     Shields regenerate faster inside.  Good hiding spot.
//                     Sub-types: ION (disables electronics), PLASMA (damages hull),
//                     PROTOSTELLAR (higher chance of young LAVA/DESERT systems).
//
//    ASTEROID_FIELD — dense belt between systems.  Navigating costs time + risk.
//                     Rich in minerals if player has a mining ship.
//
//    DARK_MATTER_RIFT— navigation disrupted.  Jump drive range halved.
//                     Coordinates scrambled — scanner unreliable.
//
//    RADIATION_ZONE — continuous hull/shield damage while inside.
//                     RADIOACTIVE planet types cluster here.
//
//    PIRATE_TERRITORY— NPC faction zone.  Triggers encounter on entry.
//                     Avoid or fight.  Defeat pirate base → claim the zone.
//
//    EMPIRE_BORDER  — NPC faction boundary.  Requires permit or triggers patrol.
//                     Can be allied with for bonuses, or hostile.
//
//    WORMHOLE       — rare pair.  Instant travel between two distant points.
//                     Destination unknown until entered.  May not be stable.
//
//    PULSAR         — periodic radiation burst on a timer (pulsePeriodSec).
//                     Safe between pulses.  Navigation requires timing.
//
//  ─── ANDROID STUDIO AI WIRING ───────────────────────────────────────────────
//
//  In your GameViewModel or WorldManager:
//
//      val galaxy = GalaxyMap(seed = 42L)
//
//      // Get all visible stars near player (for map rendering):
//      val nearbyStars = galaxy.starsInRect(playerX - 50, playerY - 50, 100f, 100f)
//
//      // Player moves to a new system:
//      val target = nearbyStars.first { it.seed == selectedSeed }
//      val route  = galaxy.plotRoute(playerPos, target.pos, playerJumpRange)
//      // route.waypoints = list of intermediate positions if multi-hop required
//      // route.totalLY, route.fuelCost, route.hazards
//
//      // On arrival — generate or load the system:
//      val system = saveData.getSystem(target.seed)
//                ?: SolarSystemGenerator.generate(target.seed).also {
//                       saveData.putSystem(target.seed, it.serialize())
//                   }
//
//      // Check for encounters on this route:
//      val encounter = EncounterEngine.roll(route, galaxy, playerState)
//      if (encounter != null) triggerEncounter(encounter)
//
//  SAVE FILE FORMAT:
//      One line per visited system:  "SEED:serializedSystemString"
//      One line for galaxy state:    "GALAXY:seed:playerX:playerY:fuel:credits:..."
//      One line per cleared obstacle: "CLEAR:obstacleSeed"
//      One line per known wormhole:   "WORMHOLE:seedA:seedB"
//
//  Full galaxy save overhead per visited system ≈ 550 chars → 1000 visited
//  systems ≈ 550 KB.  Well within Android storage limits.
//
// =============================================================================

import com.yourgame.sprites.SeededRng
import com.yourgame.world.SolarSystemGenerator
import com.yourgame.world.SolarSystem
import com.yourgame.world.StarType
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
//  Galaxy-scale constants
// ─────────────────────────────────────────────────────────────────────────────

object GalaxyConfig {
    const val CHUNK_SIZE_LY      = 20f       // LY per chunk cell
    const val SYSTEMS_PER_CHUNK  = 2         // average star systems per chunk
    const val GALAXY_RADIUS_LY   = 50_000f   // playable radius (Milky Way ÷ 2)
    const val CORE_RADIUS_LY     = 5_000f    // dense core — more systems, more hazards
    const val WARP_SPEED_LY_SEC  = 2.5f      // LY per real second at warp
    const val FUEL_PER_LY        = 0.01f     // fraction of full tank per LY
    const val JUMP_RANGE_BASE_LY = 25f       // base jump drive range (upgradeable)
    const val MIN_SYSTEM_GAP_LY  = 1.5f      // minimum distance between any two systems
}

// ─────────────────────────────────────────────────────────────────────────────
//  Star node (galaxy map entry — NOT the same as SolarSystem.Star)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A star as seen from the galaxy map — lightweight, no system details.
 * The full SolarSystem is only generated/loaded when the player arrives.
 *
 * [seed]     — deterministic ID.  Pass to SolarSystemGenerator.generate(seed).
 * [x], [y]   — position in light-years from galaxy centre.
 * [starType] — visual type for map rendering (color, size).
 * [name]     — pre-generated name (same as SolarSystem.name when generated).
 * [visited]  — true if player has been here; system data in save file.
 * [hasLife]  — pre-rolled hint (shown on long-range scanner, not guaranteed).
 * [danger]   — 0.0–1.0.  Used by route planner + encounter engine.
 */
data class StarNode(
    val seed:     Long,
    val x:        Float,    // LY
    val y:        Float,    // LY
    val starType: StarType,
    val name:     String,
    var visited:  Boolean  = false,
    val hasLife:  Boolean  = false,
    val danger:   Float    = 0f
) {
    fun distanceTo(other: StarNode)    = hypot(x - other.x, y - other.y)
    fun distanceTo(ox: Float, oy: Float) = hypot(x - ox, y - oy)
    fun pos() = Pair(x, y)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Galaxy obstacles
// ─────────────────────────────────────────────────────────────────────────────

enum class ObstacleType {
    BLACK_HOLE,         // point, gravity well, exotic matter loot
    NEBULA_ION,         // cloud, disables electronics, slow warp
    NEBULA_PLASMA,      // cloud, hull damage, shield recharge boost
    NEBULA_PROTOSTELLAR,// cloud, young systems, slow warp
    ASTEROID_FIELD,     // belt, mineral rich, navigation risk
    DARK_MATTER_RIFT,   // area, scrambles nav, halves jump range
    RADIATION_ZONE,     // area, continuous damage
    PIRATE_TERRITORY,   // zone, NPC faction, combat on entry
    EMPIRE_BORDER,      // line/zone, NPC patrol, permit required
    WORMHOLE,           // point pair, instant travel, unknown dest
    PULSAR              // point, periodic radiation burst
}

data class GalaxyObstacle(
    val seed:            Long,
    val type:            ObstacleType,
    val x:               Float,      // LY, centre
    val y:               Float,      // LY, centre
    val radiusLY:        Float,      // influence radius (0 for point obstacles)
    val name:            String,
    // Type-specific fields
    val pulsePeriodSec:  Float  = 30f,   // PULSAR only
    val wormholePairSeed:Long   = 0L,    // WORMHOLE only — seed of exit wormhole
    val factionName:     String = "",    // PIRATE_TERRITORY / EMPIRE_BORDER
    val cleared:         Boolean = false // true = player defeated / pacified
) {
    /** True if point (px,py) in LY is inside this obstacle's influence. */
    fun contains(px: Float, py: Float) =
        radiusLY > 0f && hypot(px - x, py - y) <= radiusLY

    /** Warp speed multiplier while inside this obstacle (1.0 = normal). */
    val warpMultiplier: Float get() = when (type) {
        ObstacleType.NEBULA_ION,
        ObstacleType.NEBULA_PLASMA,
        ObstacleType.NEBULA_PROTOSTELLAR -> 0.40f
        ObstacleType.DARK_MATTER_RIFT    -> 0.60f
        ObstacleType.ASTEROID_FIELD      -> 0.70f
        ObstacleType.RADIATION_ZONE      -> 0.80f
        else                             -> 1.00f
    }

    /** Hull damage per second while inside (0 = none). */
    val hullDamagePerSec: Float get() = when (type) {
        ObstacleType.NEBULA_PLASMA    -> 0.004f
        ObstacleType.RADIATION_ZONE   -> 0.008f
        ObstacleType.BLACK_HOLE       -> 0.020f   // if within 0.5×radius
        else                          -> 0.000f
    }

    /** Fuel cost multiplier for travelling through this obstacle. */
    val fuelMultiplier: Float get() = when (type) {
        ObstacleType.BLACK_HOLE       -> 2.5f
        ObstacleType.DARK_MATTER_RIFT -> 1.8f
        ObstacleType.NEBULA_ION       -> 1.4f
        else                          -> 1.0f
    }

    fun serialize() =
        "${seed}:${type.name}:${"%.2f".format(x)}:${"%.2f".format(y)}:" +
        "${"%.2f".format(radiusLY)}:${name}:${pulsePeriodSec}:${wormholePairSeed}:" +
        "${factionName}:${if (cleared) 1 else 0}"

    companion object {
        fun deserialize(s: String): GalaxyObstacle {
            val p = s.split(":")
            return GalaxyObstacle(
                seed = p[0].toLong(), type = ObstacleType.valueOf(p[1]),
                x = p[2].toFloat(), y = p[3].toFloat(), radiusLY = p[4].toFloat(),
                name = p[5], pulsePeriodSec = p[6].toFloat(),
                wormholePairSeed = p[7].toLong(), factionName = p[8],
                cleared = p[9] == "1"
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Route
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A planned route from A to B.
 * If totalLY > jumpRange, [waypoints] contains intermediate systems to hop through.
 * The game should check [hazards] and warn the player before departure.
 */
data class GalaxyRoute(
    val from:       Pair<Float, Float>,
    val to:         Pair<Float, Float>,
    val waypoints:  List<StarNode>,      // empty = direct jump
    val totalLY:    Float,
    val fuelCost:   Float,               // fraction of full tank
    val travelSec:  Float,               // real seconds at current warp speed
    val hazards:    List<GalaxyObstacle> // obstacles the route passes through
) {
    val isMultiHop: Boolean get() = waypoints.size > 1
    val isSafe:     Boolean get() = hazards.none {
        it.type in listOf(ObstacleType.PIRATE_TERRITORY, ObstacleType.RADIATION_ZONE,
                          ObstacleType.BLACK_HOLE)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Galaxy map
// ─────────────────────────────────────────────────────────────────────────────

class GalaxyMap(val seed: Long) {

    // Name tables — deterministic
    private val SYL1 = listOf("KE","VE","AR","ZY","THO","MIR","NEX","COR","PHE","EL",
                               "ORI","SOR","TAU","DAR","VOR","IX","QU","ZAN","REL","AXI",
                               "BEL","CYG","DEN","ERI","FOR","GRU","HYD","IND","LEP","LUP")
    private val SYL2 = listOf("ath","yon","eth","ran","ula","ova","iri","oss","ara","eis",
                               "uma","tor","vel","nis","ark","eid","ron","sol","ven","pos",
                               "dra","phe","cep","ori","lyr","aql","sgr","cyg","per","cas")
    private val OBS_NAMES = mapOf(
        ObstacleType.BLACK_HOLE        to listOf("Maw","Void","Abyss","Rift","Singularity","Event","Omega"),
        ObstacleType.NEBULA_ION        to listOf("Veil","Shroud","Curtain","Haze","Wraith","Ghost"),
        ObstacleType.NEBULA_PLASMA     to listOf("Furnace","Forge","Cauldron","Pyre","Blaze"),
        ObstacleType.NEBULA_PROTOSTELLAR to listOf("Cradle","Nursery","Genesis","Dawn","Birth"),
        ObstacleType.ASTEROID_FIELD    to listOf("Belt","Scatter","Rubble","Gravel","Debris"),
        ObstacleType.DARK_MATTER_RIFT  to listOf("Shadow","Blind","Null","Static","Warp"),
        ObstacleType.RADIATION_ZONE    to listOf("Flux","Burn","Scar","Blister","Fallout"),
        ObstacleType.PIRATE_TERRITORY  to listOf("Cutthroat","Raider","Scavenger","Corsair","Marauder"),
        ObstacleType.EMPIRE_BORDER     to listOf("Frontier","Boundary","March","Threshold","Perimeter"),
        ObstacleType.WORMHOLE          to listOf("Gate","Bridge","Fold","Passage","Transit"),
        ObstacleType.PULSAR            to listOf("Beacon","Strobe","Pulse","Clock","Metronome")
    )
    private val FACTION_NAMES = listOf("Nexari","Velthari","Corspan","Redwing","Iron Claw",
                                        "Void Runners","Scrap Kings","Eclipse","Phantom Wing")

    // ── Chunk-based star generation ───────────────────────────────────────────

    /**
     * Get all star nodes within [rect] (x, y, width, height in LY).
     * Results are deterministic — same rect always returns same stars.
     * Calling this does NOT generate SolarSystems — just lightweight StarNodes.
     */
    fun starsInRect(x: Float, y: Float, width: Float, height: Float): List<StarNode> {
        val chunkX0 = floor(x / GalaxyConfig.CHUNK_SIZE_LY).toInt()
        val chunkX1 = ceil((x + width) / GalaxyConfig.CHUNK_SIZE_LY).toInt()
        val chunkY0 = floor(y / GalaxyConfig.CHUNK_SIZE_LY).toInt()
        val chunkY1 = ceil((y + height) / GalaxyConfig.CHUNK_SIZE_LY).toInt()
        val result = mutableListOf<StarNode>()
        for (cx in chunkX0..chunkX1)
            for (cy in chunkY0..chunkY1)
                result += starsInChunk(cx, cy)
        return result.filter { it.x in x..(x+width) && it.y in y..(y+height) }
    }

    /** All star nodes in one 20×20 LY chunk. */
    fun starsInChunk(chunkX: Int, chunkY: Int): List<StarNode> {
        val chunkSeed = seed xor (chunkX.toLong() * 0x9E3779B97F4A7C15L) xor
                        (chunkY.toLong() * 0x6C62272E07BB0142L)
        val rng = SeededRng(chunkSeed)

        // Density: core is denser, edges are sparse
        val distFromCentre = hypot(
            chunkX * GalaxyConfig.CHUNK_SIZE_LY,
            chunkY * GalaxyConfig.CHUNK_SIZE_LY
        )
        val densityFactor = when {
            distFromCentre > GalaxyConfig.GALAXY_RADIUS_LY -> 0f
            distFromCentre < GalaxyConfig.CORE_RADIUS_LY   -> 2.5f
            else -> 1f - (distFromCentre - GalaxyConfig.CORE_RADIUS_LY) /
                        (GalaxyConfig.GALAXY_RADIUS_LY - GalaxyConfig.CORE_RADIUS_LY)
        }
        val count = (GalaxyConfig.SYSTEMS_PER_CHUNK * densityFactor).toInt()
            .coerceIn(0, 5)

        return (0 until count).map { i ->
            val systemSeed = chunkSeed xor (i.toLong() shl 20)
            val srng = SeededRng(systemSeed)
            val sx = chunkX * GalaxyConfig.CHUNK_SIZE_LY + srng.next() * GalaxyConfig.CHUNK_SIZE_LY
            val sy = chunkY * GalaxyConfig.CHUNK_SIZE_LY + srng.next() * GalaxyConfig.CHUNK_SIZE_LY
            val starType = starTypeFromDensity(srng, densityFactor)
            val name = SYL1[srng.nextInt(SYL1.size)] + SYL2[srng.nextInt(SYL2.size)] +
                       "-${srng.nextInt(9) + 1}"
            StarNode(
                seed     = systemSeed,
                x = sx, y = sy,
                starType = starType,
                name     = name,
                hasLife  = srng.nextBool(lifeChance(distFromCentre)),
                danger   = dangerLevel(distFromCentre, densityFactor, srng)
            )
        }
    }

    /** Get a specific star by seed (for travel target lookup). */
    fun starBySeed(targetSeed: Long): StarNode? {
        // Reverse-engineer chunk from seed — brute force nearby chunks
        // In practice the player always selects from starsInRect results
        // so this is a fallback. Search ±5 chunks from origin.
        for (cx in -5..5) for (cy in -5..5) {
            val found = starsInChunk(cx, cy).firstOrNull { it.seed == targetSeed }
            if (found != null) return found
        }
        return null
    }

    // ── Obstacle generation ───────────────────────────────────────────────────

    /**
     * Obstacles in [rect] (LY coords).
     * Obstacles are on a coarser grid (50×50 LY cells) so they're rarer.
     * Same determinism guarantee as starsInChunk.
     */
    fun obstaclesInRect(x: Float, y: Float, width: Float, height: Float): List<GalaxyObstacle> {
        val cellSize = 50f
        val cx0 = floor(x / cellSize).toInt()
        val cx1 = ceil((x + width) / cellSize).toInt()
        val cy0 = floor(y / cellSize).toInt()
        val cy1 = ceil((y + height) / cellSize).toInt()
        val result = mutableListOf<GalaxyObstacle>()
        for (cx in cx0..cx1) for (cy in cy0..cy1)
            result += obstaclesInCell(cx, cy, cellSize)
        return result.filter { obs ->
            obs.x + obs.radiusLY >= x && obs.x - obs.radiusLY <= x + width &&
            obs.y + obs.radiusLY >= y && obs.y - obs.radiusLY <= y + height
        }
    }

    private fun obstaclesInCell(cellX: Int, cellY: Int, cellSize: Float): List<GalaxyObstacle> {
        val cellSeed = seed xor (cellX.toLong() * 0xBF58476D1CE4E5B9L) xor
                       (cellY.toLong() * 0x94D049BB133111EBL)
        val rng = SeededRng(cellSeed)
        val distFromCentre = hypot(cellX * cellSize, cellY * cellSize)
        if (distFromCentre > GalaxyConfig.GALAXY_RADIUS_LY) return emptyList()

        // Roll for obstacle presence: ~35% chance of any obstacle per cell
        if (!rng.nextBool(0.35f)) return emptyList()

        val obsSeed = cellSeed xor 0xFACEFEEDL
        val srng = SeededRng(obsSeed)
        val ox = cellX * cellSize + srng.next() * cellSize
        val oy = cellY * cellSize + srng.next() * cellSize

        // Type distribution — core has more black holes, outer rim has more pirate zones
        val coreRatio = (1f - (distFromCentre / GalaxyConfig.CORE_RADIUS_LY).coerceIn(0f, 1f))
        val type = when (srng.next()) {
            in 0f..0.06f * (1f + coreRatio)  -> ObstacleType.BLACK_HOLE
            in 0f..0.20f                       -> ObstacleType.NEBULA_ION
            in 0f..0.30f                       -> ObstacleType.NEBULA_PLASMA
            in 0f..0.38f                       -> ObstacleType.NEBULA_PROTOSTELLAR
            in 0f..0.50f                       -> ObstacleType.ASTEROID_FIELD
            in 0f..0.58f                       -> ObstacleType.DARK_MATTER_RIFT
            in 0f..0.64f                       -> ObstacleType.RADIATION_ZONE
            in 0f..0.76f + (1f - coreRatio) * 0.1f -> ObstacleType.PIRATE_TERRITORY
            in 0f..0.84f                       -> ObstacleType.EMPIRE_BORDER
            in 0f..0.90f                       -> ObstacleType.WORMHOLE
            else                               -> ObstacleType.PULSAR
        }

        val radius = when (type) {
            ObstacleType.BLACK_HOLE    -> srng.nextInRange(0.5f, 2.0f)
            ObstacleType.NEBULA_ION,
            ObstacleType.NEBULA_PLASMA,
            ObstacleType.NEBULA_PROTOSTELLAR -> srng.nextInRange(5f, 15f)
            ObstacleType.ASTEROID_FIELD-> srng.nextInRange(2f, 8f)
            ObstacleType.DARK_MATTER_RIFT,
            ObstacleType.RADIATION_ZONE-> srng.nextInRange(3f, 10f)
            ObstacleType.PIRATE_TERRITORY,
            ObstacleType.EMPIRE_BORDER -> srng.nextInRange(8f, 20f)
            ObstacleType.WORMHOLE      -> 0.5f
            ObstacleType.PULSAR        -> srng.nextInRange(1f, 4f)
        }

        val baseName = OBS_NAMES[type]?.let { it[srng.nextInt(it.size)] } ?: "Unknown"
        val suffix   = SYL1[srng.nextInt(SYL1.size)]
        val faction  = if (type in listOf(ObstacleType.PIRATE_TERRITORY, ObstacleType.EMPIRE_BORDER))
            FACTION_NAMES[srng.nextInt(FACTION_NAMES.size)] else ""

        // Wormhole: pair seed is a different cell's obstacle seed
        val wormPair = if (type == ObstacleType.WORMHOLE)
            obsSeed xor 0x123456789ABCDEFL else 0L

        return listOf(GalaxyObstacle(
            seed             = obsSeed,
            type             = type,
            x = ox, y = oy,
            radiusLY         = radius,
            name             = "$baseName $suffix",
            pulsePeriodSec   = if (type == ObstacleType.PULSAR) srng.nextInRange(15f, 60f) else 0f,
            wormholePairSeed = wormPair,
            factionName      = faction
        ))
    }

    // ── Route planning ────────────────────────────────────────────────────────

    /**
     * Plan a route from [fromPos] to [toNode].
     * If distance > [jumpRangeLY], finds intermediate hops via nearest stars.
     * [warpSpeedLY] defaults to GalaxyConfig.WARP_SPEED_LY_SEC.
     */
    fun plotRoute(
        fromPos:      Pair<Float, Float>,
        toNode:       StarNode,
        jumpRangeLY:  Float = GalaxyConfig.JUMP_RANGE_BASE_LY,
        warpSpeedLY:  Float = GalaxyConfig.WARP_SPEED_LY_SEC
    ): GalaxyRoute {
        val directDist = hypot(fromPos.first - toNode.x, fromPos.second - toNode.y)
        val searchRect = 60f  // LY search radius for waypoints

        // Collect obstacles along the route
        val routeObstacles = obstaclesAlongLine(
            fromPos.first, fromPos.second, toNode.x, toNode.y
        )

        // Fuel cost accounting for obstacles
        val baseFuel = directDist * GalaxyConfig.FUEL_PER_LY
        val fuelMulti = routeObstacles.fold(1f) { acc, obs ->
            if (obs.contains((fromPos.first + toNode.x) / 2f,
                             (fromPos.second + toNode.y) / 2f))
                acc * obs.fuelMultiplier else acc
        }

        // Warp speed reduced by worst obstacle along route
        val worstMulti = routeObstacles.minOfOrNull { it.warpMultiplier } ?: 1f
        val effectiveSpeed = warpSpeedLY * worstMulti

        if (directDist <= jumpRangeLY) {
            // Direct jump — no waypoints needed
            return GalaxyRoute(
                from       = fromPos,
                to         = toNode.pos(),
                waypoints  = listOf(),
                totalLY    = directDist,
                fuelCost   = baseFuel * fuelMulti,
                travelSec  = directDist / effectiveSpeed,
                hazards    = routeObstacles
            )
        }

        // Multi-hop: greedy nearest-star pathfinding
        val waypoints = mutableListOf<StarNode>()
        var current = fromPos
        var remaining = directDist
        var safety = 0

        while (remaining > jumpRangeLY && safety++ < 20) {
            val nearby = starsInRect(
                current.first - searchRect, current.second - searchRect,
                searchRect * 2, searchRect * 2
            ).filter { star ->
                star.distanceTo(current.first, current.second) <= jumpRangeLY &&
                star.distanceTo(toNode) < remaining - 0.5f
            }.minByOrNull { it.distanceTo(toNode) } ?: break

            waypoints.add(nearby)
            current = nearby.pos()
            remaining = nearby.distanceTo(toNode)
        }
        waypoints.add(toNode)

        val totalDist = run {
            var prev = fromPos; var d = 0f
            for (wp in waypoints) { d += hypot(wp.x - prev.first, wp.y - prev.second); prev = wp.pos() }
            d
        }

        return GalaxyRoute(
            from      = fromPos,
            to        = toNode.pos(),
            waypoints = waypoints,
            totalLY   = totalDist,
            fuelCost  = totalDist * GalaxyConfig.FUEL_PER_LY * fuelMulti,
            travelSec = totalDist / effectiveSpeed,
            hazards   = routeObstacles
        )
    }

    /** Obstacles whose influence radius intersects the line from A to B. */
    fun obstaclesAlongLine(x1: Float, y1: Float, x2: Float, y2: Float): List<GalaxyObstacle> {
        val margin = 15f  // search margin around bounding box of the line
        val minX = min(x1, x2) - margin; val maxX = max(x1, x2) + margin
        val minY = min(y1, y2) - margin; val maxY = max(y1, y2) + margin
        return obstaclesInRect(minX, minY, maxX - minX, maxY - minY).filter { obs ->
            distPointToSegment(obs.x, obs.y, x1, y1, x2, y2) <= obs.radiusLY
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun starTypeFromDensity(rng: SeededRng, density: Float): StarType = when (rng.next()) {
        in 0f..0.35f -> StarType.RED_DWARF
        in 0f..0.58f -> StarType.ORANGE_STAR
        in 0f..0.80f -> StarType.YELLOW_STAR
        in 0f..0.87f + density * 0.05f -> StarType.BLUE_GIANT
        in 0f..0.94f -> StarType.WHITE_DWARF
        else         -> StarType.BINARY
    }

    private fun lifeChance(distFromCentre: Float): Float {
        // Peak life probability in the "galactic habitable zone" (10k–30k LY)
        val ratio = distFromCentre / GalaxyConfig.GALAXY_RADIUS_LY
        return when {
            ratio < 0.1f  -> 0.03f   // core: radiation too high
            ratio < 0.3f  -> 0.12f
            ratio < 0.6f  -> 0.18f   // sweet spot
            ratio < 0.85f -> 0.10f
            else          -> 0.04f   // outer rim: too sparse/cold
        }
    }

    private fun dangerLevel(dist: Float, density: Float, rng: SeededRng): Float {
        val baseDanger = when {
            dist < GalaxyConfig.CORE_RADIUS_LY -> 0.7f + rng.next() * 0.3f
            dist > GalaxyConfig.GALAXY_RADIUS_LY * 0.8f -> 0.5f + rng.next() * 0.4f
            else -> 0.1f + rng.next() * 0.4f
        }
        return baseDanger.coerceIn(0f, 1f)
    }

    private fun distPointToSegment(px: Float, py: Float,
                                    ax: Float, ay: Float,
                                    bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return hypot(px - ax, py - ay)
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val tc = t.coerceIn(0f, 1f)
        return hypot(px - (ax + tc * dx), py - (ay + tc * dy))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Galaxy save/load
// ─────────────────────────────────────────────────────────────────────────────

/**
 * All persistent galaxy state. Append to your existing save file.
 *
 * FORMAT (one line per entry, prefix identifies type):
 *
 *   GALAXY:seed:playerX:playerY:fuel:credits:jumpRange:techLevel:playTime
 *   VISITED:systemSeed:serializedSolarSystemString
 *   CLEAR:obstacleSeed
 *   KNOWN_WORMHOLE:seedA:seedB
 *   FLAG:systemSeed:flagName          ← e.g. colonized, quest-target, etc.
 *
 * Total size: ~80 bytes base + ~560 per visited system.
 * 500 visited systems ≈ 280 KB.
 */
data class GalaxySaveData(
    val galaxySeed:   Long,
    var playerX:      Float = 0f,
    var playerY:      Float = 0f,
    var fuel:         Float = 1.0f,
    var credits:      Long  = 1000L,
    var jumpRangeLY:  Float = GalaxyConfig.JUMP_RANGE_BASE_LY,
    var techLevel:    Int   = 1,
    var playTimeSec:  Long  = 0L,
    val visitedSystems:  MutableMap<Long, String> = mutableMapOf(),  // seed → serialized
    val clearedObstacles:MutableSet<Long>          = mutableSetOf(),
    val knownWormholes:  MutableMap<Long, Long>    = mutableMapOf(),
    val systemFlags:     MutableMap<Long, MutableSet<String>> = mutableMapOf()
) {
    fun getSystem(seed: Long): SolarSystem? =
        visitedSystems[seed]?.let { SolarSystem.deserialize(it) }

    fun putSystem(seed: Long, system: SolarSystem) {
        visitedSystems[seed] = system.serialize()
    }

    fun markCleared(obstacleSeed: Long) { clearedObstacles.add(obstacleSeed) }
    fun addFlag(systemSeed: Long, flag: String) {
        systemFlags.getOrPut(systemSeed) { mutableSetOf() }.add(flag)
    }
    fun hasFlag(systemSeed: Long, flag: String) =
        systemFlags[systemSeed]?.contains(flag) == true

    fun serialize(): String {
        val sb = StringBuilder()
        sb.appendLine("GALAXY:$galaxySeed:${"%.3f".format(playerX)}:${"%.3f".format(playerY)}:" +
                      "${"%.4f".format(fuel)}:$credits:${"%.1f".format(jumpRangeLY)}:$techLevel:$playTimeSec")
        visitedSystems.forEach  { (k, v) -> sb.appendLine("VISITED:$k:$v") }
        clearedObstacles.forEach{ k      -> sb.appendLine("CLEAR:$k") }
        knownWormholes.forEach  { (a, b) -> sb.appendLine("KNOWN_WORMHOLE:$a:$b") }
        systemFlags.forEach     { (s, flags) ->
            flags.forEach { f -> sb.appendLine("FLAG:$s:$f") }
        }
        return sb.toString().trimEnd()
    }

    companion object {
        fun deserialize(s: String): GalaxySaveData {
            val lines = s.lines().filter { it.isNotBlank() }
            var save: GalaxySaveData? = null
            for (line in lines) {
                val prefix = line.substringBefore(":")
                val rest   = line.substringAfter(":")
                when (prefix) {
                    "GALAXY" -> {
                        val p = rest.split(":")
                        save = GalaxySaveData(
                            galaxySeed  = p[0].toLong(),
                            playerX     = p[1].toFloat(),
                            playerY     = p[2].toFloat(),
                            fuel        = p[3].toFloat(),
                            credits     = p[4].toLong(),
                            jumpRangeLY = p[5].toFloat(),
                            techLevel   = p[6].toInt(),
                            playTimeSec = p[7].toLong()
                        )
                    }
                    "VISITED" -> {
                        val seed = rest.substringBefore(":").toLong()
                        val sys  = rest.substringAfter(":")
                        save?.visitedSystems?.set(seed, sys)
                    }
                    "CLEAR"         -> save?.clearedObstacles?.add(rest.toLong())
                    "KNOWN_WORMHOLE"-> {
                        val (a, b) = rest.split(":"); save?.knownWormholes?.set(a.toLong(), b.toLong())
                    }
                    "FLAG" -> {
                        val seed = rest.substringBefore(":").toLong()
                        val flag = rest.substringAfter(":")
                        save?.addFlag(seed, flag)
                    }
                }
            }
            return save ?: GalaxySaveData(galaxySeed = 0L)
        }
    }
}
