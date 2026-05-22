package com.yourgame.galaxy

// =============================================================================
//  EncounterEngine.kt
//
//  DROP INTO: src/main/java/com/yourgame/galaxy/
//  DEPENDS ON: Galaxy.kt (GalaxyRoute, GalaxyObstacle, ObstacleType)
//              com.yourgame.sprites.SeededRng
//              com.yourgame.sprites.ShipType  (for NPC ship visuals)
//
//  ─── WHAT THIS FILE DOES ────────────────────────────────────────────────────
//
//  Every time the player travels a route, EncounterEngine.roll() is called.
//  It returns null (safe passage) or a SpaceEncounter to trigger.
//
//  Encounters are NOT random on replay — they are seeded from:
//      (galaxySeed XOR routeSeed XOR playTimeSec / 300)
//  So the same route at different times produces different encounters,
//  but the game never has non-deterministic behavior in testing.
//
//  ─── ENCOUNTER CATEGORIES ───────────────────────────────────────────────────
//
//  COMBAT
//    PIRATE_AMBUSH       — standard; 1–4 pirate ships, flee or fight
//    PIRATE_FLEET        — major; 5–8 ships, flagged system nearby
//    BOUNTY_HUNTER       — targeted; hunting the player specifically
//    EMPIRE_PATROL       — law enforcement; papers check or combat
//    RIVAL_EXPLORER      — another faction explorer, race/fight for system data
//    ALIEN_SWARM         — fast small ships, overwhelming numbers
//    ROGUE_DRONE_FIELD   — automated weapons, no negotiation
//
//  SOCIAL / NEGOTIATION
//    DERELICT_DISTRESS   — disabled ship; help = reward/trap; ignore = rep loss
//    REFUGEE_CONVOY      — civilian ships fleeing danger; escort or ignore
//    TRADER_MEET         — wandering merchant; buy/sell special goods
//    BOUNTY_OFFER        — NPC offers contract; accept or decline
//    ALLY_REQUEST        — NPC wants to join fleet temporarily
//    INFORMATION_BROKER  — offers system data for credits
//
//  ENVIRONMENTAL
//    GRAVITY_WAVE        — shockwave from nearby stellar event; skill check
//    SOLAR_FLARE         — nearby star erupts; shields drain, nav disrupted
//    METEOR_SHOWER       — dodge or take hull damage; mineral loot if survive
//    WORMHOLE_SURGE      — unstable wormhole opens briefly; enter or avoid
//    GHOST_SIGNAL        — sensor echo; false system on map until investigated
//    DEBRIS_FIELD        — wreckage from old battle; loot available, nav risk
//
//  MYSTERY / LORE
//    ANCIENT_RUIN_BEACON — signal from pre-civilization structure
//    ALIEN_ARTIFACT      — unknown object drifting; scan/collect/destroy
//    DISTRESS_CIPHER     — encrypted message; decode for map data or quest
//    VOID_ANOMALY        — physics break; portal, time dilation, or nothing
//    GENERATION_SHIP     — vast old vessel; neutral until provoked
//
//  ─── ANDROID STUDIO AI WIRING ───────────────────────────────────────────────
//
//  In your travel/navigation code:
//
//      val encounter = EncounterEngine.roll(route, galaxy, playerState, saveData)
//      when (encounter?.type) {
//          null                        -> beginTravel()
//          EncounterType.PIRATE_AMBUSH -> launchCombatScreen(encounter)
//          EncounterType.TRADER_MEET   -> launchTradeScreen(encounter)
//          EncounterType.DERELICT_DISTRESS -> launchEventScreen(encounter)
//          // ... etc.  Each branch passes the SpaceEncounter which has
//          //     all the NPC ships, rewards, dialogue, and outcome callbacks
//      }
//
//  After resolution:
//      saveData.addFlag(encounter.systemSeed, "encounter_${encounter.seed}_resolved")
//      // prevents repeat of same encounter at same location
//
// =============================================================================

import com.yourgame.sprites.SeededRng
import com.yourgame.sprites.ShipType
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
//  Encounter types
// ─────────────────────────────────────────────────────────────────────────────

enum class EncounterType {
    // Combat
    PIRATE_AMBUSH, PIRATE_FLEET, BOUNTY_HUNTER, EMPIRE_PATROL,
    RIVAL_EXPLORER, ALIEN_SWARM, ROGUE_DRONE_FIELD,
    // Social
    DERELICT_DISTRESS, REFUGEE_CONVOY, TRADER_MEET,
    BOUNTY_OFFER, ALLY_REQUEST, INFORMATION_BROKER,
    // Environmental
    GRAVITY_WAVE, SOLAR_FLARE, METEOR_SHOWER,
    WORMHOLE_SURGE, GHOST_SIGNAL, DEBRIS_FIELD,
    // Mystery
    ANCIENT_RUIN_BEACON, ALIEN_ARTIFACT, DISTRESS_CIPHER,
    VOID_ANOMALY, GENERATION_SHIP;

    val category: EncounterCategory get() = when (this) {
        PIRATE_AMBUSH, PIRATE_FLEET, BOUNTY_HUNTER, EMPIRE_PATROL,
        RIVAL_EXPLORER, ALIEN_SWARM, ROGUE_DRONE_FIELD -> EncounterCategory.COMBAT
        DERELICT_DISTRESS, REFUGEE_CONVOY, TRADER_MEET,
        BOUNTY_OFFER, ALLY_REQUEST, INFORMATION_BROKER -> EncounterCategory.SOCIAL
        GRAVITY_WAVE, SOLAR_FLARE, METEOR_SHOWER,
        WORMHOLE_SURGE, GHOST_SIGNAL, DEBRIS_FIELD     -> EncounterCategory.ENVIRONMENTAL
        else                                            -> EncounterCategory.MYSTERY
    }
}

enum class EncounterCategory { COMBAT, SOCIAL, ENVIRONMENTAL, MYSTERY }

// ─────────────────────────────────────────────────────────────────────────────
//  NPC ship
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One NPC ship in an encounter.
 * [shipType] maps directly to ShipSystem.kt ShipType for rendering.
 * [tier] 1–3 matches ShipType tier (DART=1, FALCON=2, WRAITH=3, etc.)
 */
data class NpcShip(
    val seed:       Long,
    val name:       String,
    val shipType:   ShipType,        // from ShipSystem.kt — drives visual
    val tier:       Int,             // 1=scout, 2=fighter, 3=apex
    val hullPct:    Float = 1f,
    val shieldPct:  Float = 1f,
    val aggressive: Boolean = true,
    val canFlee:    Boolean = true,
    val lootCredits:Long = 0L,
    val lootItems:  List<String> = emptyList()  // item IDs from your inventory system
)

// ─────────────────────────────────────────────────────────────────────────────
//  Encounter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A fully-described encounter ready to be handed to the game's event system.
 *
 * [seed]           — deterministic ID; use to check "already resolved" in saveData
 * [type]           — drives which screen/handler to launch
 * [title]          — short display name, e.g. "PIRATE AMBUSH"
 * [description]    — flavour text for the event notification
 * [npcShips]       — ships involved (empty for environmental encounters)
 * [options]        — player choices available (always includes "flee" for combat)
 * [rewards]        — what the player gets for the best outcome
 * [penalties]      — consequences of failure / bad choice
 * [timeoutSec]     — if > 0, encounter auto-resolves after this time (default 0)
 * [locationLY]     — where in the galaxy it occurs (for map marker)
 */
data class SpaceEncounter(
    val seed:        Long,
    val type:        EncounterType,
    val title:       String,
    val description: String,
    val npcShips:    List<NpcShip>             = emptyList(),
    val options:     List<EncounterOption>     = emptyList(),
    val rewards:     EncounterReward           = EncounterReward(),
    val penalties:   EncounterPenalty          = EncounterPenalty(),
    val timeoutSec:  Int                       = 0,
    val locationLY:  Pair<Float, Float>        = Pair(0f, 0f),
    val obstacleRef: GalaxyObstacle?           = null   // the obstacle that triggered it
)

data class EncounterOption(
    val id:    String,
    val label: String,   // e.g. "HAIL", "FIRE", "FLEE", "SCAN", "BOARD"
    val requiresTechLevel: Int  = 1,
    val requiresItem:      String = ""
)

data class EncounterReward(
    val credits:      Long          = 0L,
    val fuelBonus:    Float         = 0f,
    val items:        List<String>  = emptyList(),
    val mapData:      Boolean       = false,   // reveals nearby systems on map
    val reputationDelta: Int        = 0        // +/- faction rep
)

data class EncounterPenalty(
    val hullDamage:   Float = 0f,
    val fuelLoss:     Float = 0f,
    val creditLoss:   Long  = 0L,
    val reputationDelta: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────────
//  Player state snapshot (what the encounter engine needs to know)
// ─────────────────────────────────────────────────────────────────────────────

data class PlayerState(
    val hullPct:     Float = 1f,
    val shieldPct:   Float = 1f,
    val fuelPct:     Float = 1f,
    val credits:     Long  = 1000L,
    val techLevel:   Int   = 1,
    val shipTier:    Int   = 1,       // 1–3, matches ShipType tier
    val reputation:  Int   = 0,       // -100 (pirate) to +100 (law-abiding)
    val hasCloak:    Boolean = false,
    val hasScanner:  Boolean = false,
    val fleetSize:   Int   = 1        // player + escort ships
)

// ─────────────────────────────────────────────────────────────────────────────
//  Engine
// ─────────────────────────────────────────────────────────────────────────────

object EncounterEngine {

    private val SHIP_NAMES = listOf(
        "Razorwing","Deathblow","Voidclaw","Ironjaw","Darkstar","Scrapheap",
        "Vendetta","Reckoning","Oblivion","Forsaken","Maelstrom","Tempest",
        "Seeker","Nomad","Pathfinder","Beacon","Courier","Envoy","Herald",
        "Ancient","Relic","Drifter","Wanderer","Echo","Phantom","Specter"
    )

    /**
     * Roll for an encounter on [route].
     * Returns null if the route is uneventful.
     * Call this once when the player initiates travel.
     */
    fun roll(
        route:       GalaxyRoute,
        galaxy:      GalaxyMap,
        player:      PlayerState,
        saveData:    GalaxySaveData,
        timeSeed:    Long = System.currentTimeMillis() / 300_000L  // changes every 5 min
    ): SpaceEncounter? {

        val routeSeed = route.from.first.toBits().toLong() xor
                        route.to.first.toBits().toLong()   xor timeSeed
        val rng = SeededRng(saveData.galaxySeed xor routeSeed)

        // Base encounter chance per LY traveled, scaled by danger
        val avgDanger   = route.hazards.maxOfOrNull { it.type.dangerWeight } ?: 0.1f
        val encounterP  = (route.totalLY * 0.04f + avgDanger * 0.4f).coerceIn(0.05f, 0.95f)

        if (!rng.nextBool(encounterP)) return null

        // Already resolved this encounter? (same seed = same event)
        val encounterSeed = routeSeed xor 0xBEEFCAFEL
        val flagKey = "encounter_${encounterSeed}_resolved"
        val nearestSystem = galaxy.starsInRect(
            route.to.first - 5f, route.to.second - 5f, 10f, 10f
        ).minByOrNull { it.distanceTo(route.to.first, route.to.second) }

        if (nearestSystem != null && saveData.hasFlag(nearestSystem.seed, flagKey)) return null

        // Pick encounter type weighted by context
        val type = pickType(rng, route, player, avgDanger)

        return buildEncounter(type, encounterSeed, rng, route, player, galaxy)
    }

    // ── Type selection ────────────────────────────────────────────────────────

    private fun pickType(
        rng:       SeededRng,
        route:     GalaxyRoute,
        player:    PlayerState,
        danger:    Float
    ): EncounterType {
        // Hazard-specific triggers
        val hasPirateTerr = route.hazards.any { it.type == ObstacleType.PIRATE_TERRITORY }
        val hasNebula     = route.hazards.any {
            it.type in listOf(ObstacleType.NEBULA_ION, ObstacleType.NEBULA_PLASMA,
                              ObstacleType.NEBULA_PROTOSTELLAR)
        }
        val hasBlackHole  = route.hazards.any { it.type == ObstacleType.BLACK_HOLE }
        val hasRadiation  = route.hazards.any { it.type == ObstacleType.RADIATION_ZONE }
        val hasAsteroid   = route.hazards.any { it.type == ObstacleType.ASTEROID_FIELD }
        val hasWormhole   = route.hazards.any { it.type == ObstacleType.WORMHOLE }
        val hasDarkMatter = route.hazards.any { it.type == ObstacleType.DARK_MATTER_RIFT }
        val hasEmpire     = route.hazards.any { it.type == ObstacleType.EMPIRE_BORDER }
        val hasPulsar     = route.hazards.any { it.type == ObstacleType.PULSAR }

        // Forced encounters from specific hazard types
        if (hasPirateTerr && rng.nextBool(0.65f))
            return if (rng.nextBool(0.7f)) EncounterType.PIRATE_AMBUSH else EncounterType.PIRATE_FLEET
        if (hasEmpire && rng.nextBool(0.55f))
            return EncounterType.EMPIRE_PATROL
        if (hasBlackHole && rng.nextBool(0.5f))
            return if (rng.nextBool(0.4f)) EncounterType.VOID_ANOMALY else EncounterType.GRAVITY_WAVE
        if (hasNebula && rng.nextBool(0.4f))
            return if (rng.nextBool(0.5f)) EncounterType.GHOST_SIGNAL else EncounterType.SOLAR_FLARE
        if (hasAsteroid && rng.nextBool(0.45f))
            return if (rng.nextBool(0.6f)) EncounterType.METEOR_SHOWER else EncounterType.DEBRIS_FIELD
        if (hasWormhole && rng.nextBool(0.5f))
            return EncounterType.WORMHOLE_SURGE
        if (hasRadiation && rng.nextBool(0.4f))
            return EncounterType.ALIEN_ARTIFACT
        if (hasDarkMatter && rng.nextBool(0.45f))
            return if (rng.nextBool(0.5f)) EncounterType.DISTRESS_CIPHER else EncounterType.VOID_ANOMALY
        if (hasPulsar && rng.nextBool(0.4f))
            return EncounterType.SOLAR_FLARE

        // General probability table
        val r = rng.next()
        return when {
            r < 0.18f              -> EncounterType.PIRATE_AMBUSH
            r < 0.24f              -> EncounterType.DERELICT_DISTRESS
            r < 0.30f              -> EncounterType.TRADER_MEET
            r < 0.36f              -> EncounterType.DEBRIS_FIELD
            r < 0.41f              -> EncounterType.METEOR_SHOWER
            r < 0.46f              -> EncounterType.BOUNTY_OFFER
            r < 0.51f              -> EncounterType.SOLAR_FLARE
            r < 0.55f              -> EncounterType.REFUGEE_CONVOY
            r < 0.59f              -> EncounterType.RIVAL_EXPLORER
            r < 0.63f              -> EncounterType.ALIEN_ARTIFACT
            r < 0.67f              -> EncounterType.ANCIENT_RUIN_BEACON
            r < 0.70f              -> EncounterType.INFORMATION_BROKER
            r < 0.73f + danger*0.1f-> EncounterType.BOUNTY_HUNTER
            r < 0.77f              -> EncounterType.GHOST_SIGNAL
            r < 0.80f              -> EncounterType.VOID_ANOMALY
            r < 0.83f              -> EncounterType.ALIEN_SWARM
            r < 0.86f              -> EncounterType.DISTRESS_CIPHER
            r < 0.89f              -> EncounterType.ALLY_REQUEST
            r < 0.92f              -> EncounterType.ROGUE_DRONE_FIELD
            r < 0.95f              -> EncounterType.GENERATION_SHIP
            else                   -> EncounterType.WORMHOLE_SURGE
        }
    }

    // ── Encounter builders ────────────────────────────────────────────────────

    private fun buildEncounter(
        type:    EncounterType,
        seed:    Long,
        rng:     SeededRng,
        route:   GalaxyRoute,
        player:  PlayerState,
        galaxy:  GalaxyMap
    ): SpaceEncounter {
        val loc = Pair(
            route.from.first + (route.to.first - route.from.first) * rng.next(),
            route.from.second + (route.to.second - route.from.second) * rng.next()
        )
        return when (type.category) {
            EncounterCategory.COMBAT       -> buildCombat      (type, seed, rng, route, player, loc)
            EncounterCategory.SOCIAL       -> buildSocial      (type, seed, rng, route, player, loc)
            EncounterCategory.ENVIRONMENTAL-> buildEnvironmental(type, seed, rng, route, player, loc)
            EncounterCategory.MYSTERY      -> buildMystery     (type, seed, rng, route, player, loc)
        }
    }

    private fun buildCombat(
        type: EncounterType, seed: Long, rng: SeededRng,
        route: GalaxyRoute, player: PlayerState, loc: Pair<Float,Float>
    ): SpaceEncounter {
        val shipCount = when (type) {
            EncounterType.PIRATE_FLEET   -> 5 + rng.nextInt(4)
            EncounterType.ALIEN_SWARM    -> 6 + rng.nextInt(6)
            EncounterType.ROGUE_DRONE_FIELD -> 4 + rng.nextInt(5)
            EncounterType.PIRATE_AMBUSH  -> 1 + rng.nextInt(4)
            else                         -> 1 + rng.nextInt(2)
        }
        val npcTier = when {
            type == EncounterType.PIRATE_FLEET  -> 3
            type == EncounterType.BOUNTY_HUNTER -> player.shipTier
            route.hazards.any { it.type == ObstacleType.PIRATE_TERRITORY } -> 2
            else -> (1 + rng.nextInt(3)).coerceAtMost(3)
        }
        val ships = buildNpcShips(seed, rng, shipCount, npcTier, type)

        val totalLoot = ships.sumOf { it.lootCredits }
        val (title, desc) = encounterText(type, ships, rng)

        return SpaceEncounter(
            seed        = seed,
            type        = type,
            title       = title,
            description = desc,
            npcShips    = ships,
            options     = combatOptions(player, type),
            rewards     = EncounterReward(
                credits      = totalLoot,
                items        = if (rng.nextBool(0.3f)) listOf("salvage_parts") else emptyList(),
                mapData      = type == EncounterType.BOUNTY_HUNTER,
                reputationDelta = if (type == EncounterType.EMPIRE_PATROL) -5 else 0
            ),
            penalties   = EncounterPenalty(
                hullDamage = rng.nextInRange(0.05f, 0.25f),
                fuelLoss   = rng.nextInRange(0.02f, 0.08f)
            ),
            locationLY  = loc
        )
    }

    private fun buildSocial(
        type: EncounterType, seed: Long, rng: SeededRng,
        route: GalaxyRoute, player: PlayerState, loc: Pair<Float,Float>
    ): SpaceEncounter {
        val (title, desc) = encounterText(type, emptyList(), rng)
        val credits = when (type) {
            EncounterType.TRADER_MEET        -> rng.nextInt(500).toLong() + 100L
            EncounterType.INFORMATION_BROKER -> rng.nextInt(800).toLong() + 200L
            EncounterType.BOUNTY_OFFER       -> rng.nextInt(2000).toLong() + 500L
            EncounterType.DERELICT_DISTRESS  -> if (rng.nextBool(0.6f)) rng.nextInt(400).toLong() + 50L else 0L
            else -> 0L
        }
        val npcShip = if (type != EncounterType.REFUGEE_CONVOY) {
            listOf(buildNpcShips(seed, rng, 1, 1 + rng.nextInt(2), type).first())
        } else {
            buildNpcShips(seed, rng, 3 + rng.nextInt(4), 1, type)
        }
        return SpaceEncounter(
            seed        = seed,
            type        = type,
            title       = title,
            description = desc,
            npcShips    = npcShip,
            options     = socialOptions(type, player),
            rewards     = EncounterReward(
                credits         = credits,
                mapData         = type == EncounterType.INFORMATION_BROKER,
                reputationDelta = if (type == EncounterType.REFUGEE_CONVOY) 10 else 0,
                items           = if (type == EncounterType.TRADER_MEET) listOf("trade_goods") else emptyList()
            ),
            penalties   = EncounterPenalty(reputationDelta = -8),
            locationLY  = loc
        )
    }

    private fun buildEnvironmental(
        type: EncounterType, seed: Long, rng: SeededRng,
        route: GalaxyRoute, player: PlayerState, loc: Pair<Float,Float>
    ): SpaceEncounter {
        val (title, desc) = encounterText(type, emptyList(), rng)
        val hullDmg = when (type) {
            EncounterType.GRAVITY_WAVE  -> rng.nextInRange(0.08f, 0.20f)
            EncounterType.SOLAR_FLARE   -> rng.nextInRange(0.05f, 0.15f)
            EncounterType.METEOR_SHOWER -> rng.nextInRange(0.03f, 0.18f)
            else -> 0f
        }
        return SpaceEncounter(
            seed        = seed,
            type        = type,
            title       = title,
            description = desc,
            options     = environmentalOptions(type, player),
            rewards     = EncounterReward(
                credits = if (type == EncounterType.DEBRIS_FIELD || type == EncounterType.METEOR_SHOWER)
                    rng.nextInt(300).toLong() + 50L else 0L,
                fuelBonus = if (type == EncounterType.WORMHOLE_SURGE) rng.nextInRange(0.1f, 0.3f) else 0f,
                mapData = type == EncounterType.GHOST_SIGNAL
            ),
            penalties   = EncounterPenalty(hullDamage = hullDmg, fuelLoss = rng.nextInRange(0f, 0.05f)),
            timeoutSec  = if (type == EncounterType.WORMHOLE_SURGE) 15 else 0,
            locationLY  = loc
        )
    }

    private fun buildMystery(
        type: EncounterType, seed: Long, rng: SeededRng,
        route: GalaxyRoute, player: PlayerState, loc: Pair<Float,Float>
    ): SpaceEncounter {
        val (title, desc) = encounterText(type, emptyList(), rng)
        return SpaceEncounter(
            seed        = seed,
            type        = type,
            title       = title,
            description = desc,
            npcShips    = if (type == EncounterType.GENERATION_SHIP)
                buildNpcShips(seed, rng, 1, 3, type) else emptyList(),
            options     = mysteryOptions(type, player),
            rewards     = EncounterReward(
                credits         = if (rng.nextBool(0.5f)) rng.nextInt(1000).toLong() + 200L else 0L,
                items           = if (rng.nextBool(0.4f)) listOf("artifact_fragment") else emptyList(),
                mapData         = true,
                reputationDelta = 5
            ),
            penalties   = EncounterPenalty(
                hullDamage = if (type == EncounterType.VOID_ANOMALY) rng.nextInRange(0f, 0.3f) else 0f
            ),
            locationLY  = loc
        )
    }

    // ── NPC ship factory ──────────────────────────────────────────────────────

    private fun buildNpcShips(
        baseSeed: Long, rng: SeededRng, count: Int, tier: Int, type: EncounterType
    ): List<NpcShip> {
        val shipTypesForTier = when (tier) {
            1    -> listOf(ShipType.DART, ShipType.WASP, ShipType.ARROW)
            2    -> listOf(ShipType.FALCON, ShipType.RAPTOR, ShipType.VIPER)
            else -> listOf(ShipType.WRAITH, ShipType.SOVEREIGN, ShipType.NEMESIS)
        }
        val isAggressive = type.category == EncounterCategory.COMBAT
        return (0 until count).map { i ->
            val shipSeed = baseSeed xor ((i + 1).toLong() * 0x9E3779B97F4A7C15L)
            NpcShip(
                seed       = shipSeed,
                name       = SHIP_NAMES[rng.nextInt(SHIP_NAMES.size)],
                shipType   = shipTypesForTier[rng.nextInt(shipTypesForTier.size)],
                tier       = tier,
                hullPct    = rng.nextInRange(0.6f, 1f),
                shieldPct  = rng.nextInRange(0.4f, 1f),
                aggressive = isAggressive,
                canFlee    = type != EncounterType.ROGUE_DRONE_FIELD,
                lootCredits= if (isAggressive) rng.nextInt(300).toLong() + 50L else 0L,
                lootItems  = if (rng.nextBool(0.25f)) listOf("salvage_${shipSeed % 100}") else emptyList()
            )
        }
    }

    // ── Option sets ───────────────────────────────────────────────────────────

    private fun combatOptions(player: PlayerState, type: EncounterType) = buildList {
        add(EncounterOption("fire",  "OPEN FIRE"))
        if (type != EncounterType.ROGUE_DRONE_FIELD && type != EncounterType.ALIEN_SWARM)
            add(EncounterOption("hail", "HAIL"))
        add(EncounterOption("flee",  "EMERGENCY WARP"))
        if (player.hasCloak)
            add(EncounterOption("cloak","CLOAK"))
        if (player.hasScanner)
            add(EncounterOption("scan", "DEEP SCAN", requiresTechLevel = 2))
    }

    private fun socialOptions(type: EncounterType, player: PlayerState) = buildList {
        when (type) {
            EncounterType.TRADER_MEET        -> { add(EncounterOption("trade","TRADE")); add(EncounterOption("pass","PASS BY")) }
            EncounterType.DERELICT_DISTRESS  -> { add(EncounterOption("help","RENDER AID")); add(EncounterOption("scan","SCAN ONLY")); add(EncounterOption("ignore","IGNORE")) }
            EncounterType.REFUGEE_CONVOY     -> { add(EncounterOption("escort","ESCORT")); add(EncounterOption("supplies","GIVE SUPPLIES")); add(EncounterOption("ignore","IGNORE")) }
            EncounterType.BOUNTY_OFFER       -> { add(EncounterOption("accept","ACCEPT CONTRACT")); add(EncounterOption("decline","DECLINE")) }
            EncounterType.ALLY_REQUEST       -> { add(EncounterOption("accept","ACCEPT ALLY")); add(EncounterOption("decline","DECLINE")) }
            EncounterType.INFORMATION_BROKER -> { add(EncounterOption("buy","BUY DATA")); add(EncounterOption("haggle","HAGGLE")); add(EncounterOption("pass","DECLINE")) }
            else -> add(EncounterOption("continue","CONTINUE"))
        }
    }

    private fun environmentalOptions(type: EncounterType, player: PlayerState) = buildList {
        when (type) {
            EncounterType.WORMHOLE_SURGE -> { add(EncounterOption("enter","ENTER WORMHOLE")); add(EncounterOption("avoid","AVOID")) }
            EncounterType.DEBRIS_FIELD,
            EncounterType.METEOR_SHOWER  -> { add(EncounterOption("salvage","SALVAGE")); add(EncounterOption("navigate","NAVIGATE THROUGH")); add(EncounterOption("avoid","GO AROUND")) }
            EncounterType.GHOST_SIGNAL   -> { add(EncounterOption("investigate","INVESTIGATE")); add(EncounterOption("ignore","IGNORE SIGNAL")) }
            else -> { add(EncounterOption("brace","BRACE FOR IMPACT")); add(EncounterOption("evade","EVASIVE MANEUVERS")) }
        }
    }

    private fun mysteryOptions(type: EncounterType, player: PlayerState) = buildList {
        add(EncounterOption("scan",    "DEEP SCAN"))
        add(EncounterOption("approach","APPROACH"))
        if (type == EncounterType.ALIEN_ARTIFACT || type == EncounterType.ANCIENT_RUIN_BEACON)
            add(EncounterOption("collect","COLLECT"))
        if (type == EncounterType.GENERATION_SHIP)
            add(EncounterOption("hail","HAIL VESSEL"))
        add(EncounterOption("avoid",  "AVOID"))
    }

    // ── Flavour text ──────────────────────────────────────────────────────────

    private fun encounterText(
        type: EncounterType, ships: List<NpcShip>, rng: SeededRng
    ): Pair<String, String> = when (type) {
        EncounterType.PIRATE_AMBUSH      -> Pair("PIRATE AMBUSH",
            "${ships.size} hostile vessel${if(ships.size>1)"s" else ""} on intercept course. Weapons hot.")
        EncounterType.PIRATE_FLEET       -> Pair("PIRATE FLEET",
            "A full pirate squadron — ${ships.size} ships. This is a coordinated attack.")
        EncounterType.BOUNTY_HUNTER      -> Pair("BOUNTY HUNTER",
            "A ${ships.firstOrNull()?.shipType?.displayName ?: "ship"} on a direct intercept. They know your registry.")
        EncounterType.EMPIRE_PATROL      -> Pair("EMPIRE PATROL",
            "Imperial vessels hailing you. They want to see your transit permits.")
        EncounterType.RIVAL_EXPLORER     -> Pair("RIVAL EXPLORER",
            "Another explorer vessel. They reached this system first — and they know it.")
        EncounterType.ALIEN_SWARM        -> Pair("ALIEN SWARM",
            "${ships.size} unknown vessels. No known design. Hostile posture.")
        EncounterType.ROGUE_DRONE_FIELD  -> Pair("ROGUE DRONES",
            "Autonomous combat drones — no crew, no negotiation. Targeting locked.")
        EncounterType.DERELICT_DISTRESS  -> Pair("DISTRESS SIGNAL",
            "A vessel adrift. Life signs intermittent. Distress beacon active.")
        EncounterType.REFUGEE_CONVOY     -> Pair("REFUGEE CONVOY",
            "${ships.size} civilian vessels fleeing an unknown threat. They're requesting escort.")
        EncounterType.TRADER_MEET        -> Pair("WANDERING TRADER",
            "A merchant vessel hailing you. Special inventory — limited time.")
        EncounterType.BOUNTY_OFFER       -> Pair("BOUNTY CONTRACT",
            "An encoded contract burst. High-value target in the next sector.")
        EncounterType.ALLY_REQUEST       -> Pair("HAIL: ALLY REQUEST",
            "A pilot requesting to join your wing. Combat-experienced.")
        EncounterType.INFORMATION_BROKER -> Pair("INFO BROKER",
            "Encrypted hail. They have system data you haven't scanned.")
        EncounterType.GRAVITY_WAVE       -> Pair("GRAVITY SHOCKWAVE",
            "A stellar shockwave — source unknown. Brace or attempt to outrun it.")
        EncounterType.SOLAR_FLARE        -> Pair("SOLAR FLARE",
            "CME burst detected. Shields will take a hit. Get clear.")
        EncounterType.METEOR_SHOWER      -> Pair("METEOR SHOWER",
            "Dense debris field crossing your path. Navigate, salvage, or detour.")
        EncounterType.WORMHOLE_SURGE     -> Pair("WORMHOLE SURGE",
            "An unstable wormhole has opened nearby. Destination unknown. 15 seconds.")
        EncounterType.GHOST_SIGNAL       -> Pair("GHOST SIGNAL",
            "A system signature that doesn't match any known star. Scanner anomaly.")
        EncounterType.DEBRIS_FIELD       -> Pair("BATTLE DEBRIS",
            "Wreckage from an old engagement. Salvage risk: moderate.")
        EncounterType.ANCIENT_RUIN_BEACON-> Pair("RUIN BEACON",
            "A pre-civilisation structure broadcasting on a dead frequency.")
        EncounterType.ALIEN_ARTIFACT     -> Pair("UNKNOWN ARTIFACT",
            "An object of non-human origin. Your scanner can't classify it.")
        EncounterType.DISTRESS_CIPHER    -> Pair("ENCRYPTED SIGNAL",
            "A coded message looping on repeat. Origin indeterminate.")
        EncounterType.VOID_ANOMALY       -> Pair("VOID ANOMALY",
            "Physics are wrong here. Your instruments don't agree with each other.")
        EncounterType.GENERATION_SHIP    -> Pair("GENERATION SHIP",
            "A vessel measured in kilometres. Ancient. Still running.")
    }
}

// Extension on ObstacleType for danger weighting
private val ObstacleType.dangerWeight: Float get() = when (this) {
    ObstacleType.BLACK_HOLE        -> 0.9f
    ObstacleType.PIRATE_TERRITORY  -> 0.8f
    ObstacleType.RADIATION_ZONE    -> 0.7f
    ObstacleType.EMPIRE_BORDER     -> 0.5f
    ObstacleType.NEBULA_PLASMA     -> 0.4f
    ObstacleType.DARK_MATTER_RIFT  -> 0.4f
    ObstacleType.PULSAR            -> 0.4f
    ObstacleType.NEBULA_ION        -> 0.3f
    ObstacleType.ASTEROID_FIELD    -> 0.3f
    ObstacleType.WORMHOLE          -> 0.2f
    else                           -> 0.1f
}

// Extension on ShipType for display name (mirrors ShipSystem.kt)
private val ShipType.displayName: String get() = when (this) {
    ShipType.DART     -> "Dart Mk-I"
    ShipType.WASP     -> "Wasp Scout"
    ShipType.ARROW    -> "Arrow-9"
    ShipType.FALCON   -> "Falcon-X"
    ShipType.RAPTOR   -> "Raptor-II"
    ShipType.VIPER    -> "Viper-VII"
    ShipType.WRAITH   -> "Wraith Zero"
    ShipType.SOVEREIGN-> "Sovereign"
    ShipType.NEMESIS  -> "Nemesis-∞"
}
