package com.yourapp.hud  // ← change this to match your package

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlin.math.*

// ─── Palette ──────────────────────────────────────────────────────────────────
private val G       = Color(0xFF00FF41)
private val GDim    = Color(0xFF005518)
private val GBright = Color(0xFF00CC33)
private val Amber   = Color(0xFFFFAA00)
private val Red     = Color(0xFFFF3300)
private val BG      = Color(0xFF000000)

// ─── Callback interface — wire these up in your Activity/Fragment ──────────────
data class HudCallbacks(
    val onJoystickMove: (x: Float, y: Float) -> Unit = { _, _ -> },  // -1..1 each axis
    val onFire:   () -> Unit = {},
    val onScan:   () -> Unit = {},
    val onLock:   () -> Unit = {},
    val onBoost:  () -> Unit = {},
    val onCloak:  () -> Unit = {},
    val onDock:   () -> Unit = {},
    val onTabChanged: (tab: String) -> Unit = {},
    val onCommand: (cmd: String) -> Unit = {},  // numpad ENT
)

// ─── Main composable — drop this into your game Activity/Fragment ─────────────
@Composable
fun TacticalHUD(
    // Pass live game state down from your engine:
    shieldPct:  Float = 0.85f,   // 0..1
    hullPct:    Float = 0.62f,
    powerPct:   Float = 0.98f,
    fuelPct:    Float = 0.45f,
    ammoPct:    Float = 0.30f,
    altText:    String = "---m",
    velText:    String = "---ms",
    hdgText:    String = "---°",
    callbacks:  HudCallbacks = HudCallbacks(),
) {
    val tabs = listOf("NAV_01", "DAT_02", "LOG_03", "CARGO", "FLEET")
    var activeTab by remember { mutableStateOf("NAV_01") }

    Box(
        Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        Column(Modifier.fillMaxSize()) {

            // ══════════════════════════════════════════════════════
            //  TOP ~55% — GAME RENDER SURFACE (left intentionally blank)
            //  Your engine should render here. In your Activity,
            //  place your GLSurfaceView / SurfaceView BEHIND this
            //  composable, sized to ~55% of screen height, then
            //  this HUD sits on top with a transparent top section.
            // ══════════════════════════════════════════════════════
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.52f)  // ← adjust this ratio to match your game
            )

            // ══════════════════════════════════════════════════════
            //  BOTTOM HUD PANEL
            // ══════════════════════════════════════════════════════
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(BG)
                    .border(1.dp, GDim)
            ) {
                // Tab bar
                HudTabBar(
                    tabs = tabs,
                    activeTab = activeTab,
                    onTabSelected = {
                        activeTab = it
                        callbacks.onTabChanged(it)
                    }
                )

                when (activeTab) {
                    "NAV_01" -> NavPanel(
                        shieldPct, hullPct, powerPct, fuelPct, ammoPct,
                        altText, velText, hdgText, callbacks
                    )
                    "DAT_02" -> DataPanel(shieldPct, powerPct, hullPct)
                    "LOG_03" -> LogPanel()
                    else     -> PlaceholderPanel(activeTab)
                }
            }
        }
    }
}

// ─── Tab bar ──────────────────────────────────────────────────────────────────
@Composable
fun HudTabBar(tabs: List<String>, activeTab: String, onTabSelected: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF000A04))
            .border(BorderStroke(1.dp, GDim))
    ) {
        tabs.forEach { tab ->
            val isActive = tab == activeTab
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabSelected(tab) }
                    .background(if (isActive) Color(0x1400FF41) else Color.Transparent)
                    .then(
                        if (isActive) Modifier.border(
                            BorderStroke(1.dp, G),
                            RoundedCornerShape(0.dp)
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab,
                    color = if (isActive) G else GDim,
                    fontSize = 7.sp,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ─── NAV_01 Panel ─────────────────────────────────────────────────────────────
@Composable
fun NavPanel(
    shieldPct: Float, hullPct: Float, powerPct: Float, fuelPct: Float, ammoPct: Float,
    altText: String, velText: String, hdgText: String,
    callbacks: HudCallbacks,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Status bars + Joystick pad  |  Data boxes + numpad
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // Left: status strip + joystick
            Row(
                Modifier.weight(1.1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                StatusStrip(shieldPct, hullPct, powerPct, fuelPct, ammoPct)
                JoystickPad(Modifier.weight(1f).fillMaxHeight(), callbacks.onJoystickMove)
            }

            // Right: data boxes + numpad
            Column(
                Modifier.weight(0.9f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DataBoxes(altText, velText, hdgText, powerPct)
                NumPad(onCommand = callbacks.onCommand)
            }
        }

        // Command buttons row
        CmdButtonRow(callbacks)
    }
}

// ─── Status strip (8 vertical bars) ──────────────────────────────────────────
@Composable
fun StatusStrip(shield: Float, hull: Float, power: Float, fuel: Float, ammo: Float) {
    val values = listOf(shield, (shield + hull) / 2, hull, (hull + power) / 2, power, fuel, ammo * 1.5f, ammo)
    val colors  = listOf(G, G, GBright, Color(0xFF55CC00), Color(0xFFAAcc00), Amber, Color(0xFFFF7700), Red)

    Column(
        Modifier
            .width(14.dp)
            .fillMaxHeight()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        values.forEachIndexed { i, v ->
            val fill = v.coerceIn(0.05f, 0.98f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .border(1.dp, colors[i])
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fill)
                        .background(colors[i])
                )
            }
        }
    }
}

// ─── Joystick pad ─────────────────────────────────────────────────────────────
@Composable
fun JoystickPad(modifier: Modifier, onMove: (Float, Float) -> Unit) {
    var reticleX by remember { mutableStateOf(0.5f) }  // 0..1 within pad
    var reticleY by remember { mutableStateOf(0.5f) }
    var padWidth  by remember { mutableStateOf(1f) }
    var padHeight by remember { mutableStateOf(1f) }

    Canvas(
        modifier
            .border(1.5.dp, GBright)
            .background(Color(0xFF000A04))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        padWidth  = size.width.toFloat()
                        padHeight = size.height.toFloat()
                        reticleX = (offset.x / padWidth).coerceIn(0f, 1f)
                        reticleY = (offset.y / padHeight).coerceIn(0f, 1f)
                        onMove((reticleX - 0.5f) * 2f, (reticleY - 0.5f) * 2f)
                    },
                    onDrag = { change, _ ->
                        reticleX = (change.position.x / padWidth).coerceIn(0f, 1f)
                        reticleY = (change.position.y / padHeight).coerceIn(0f, 1f)
                        onMove((reticleX - 0.5f) * 2f, (reticleY - 0.5f) * 2f)
                    },
                    onDragEnd = {
                        reticleX = 0.5f; reticleY = 0.5f
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        reticleX = 0.5f; reticleY = 0.5f
                        onMove(0f, 0f)
                    }
                )
            }
    ) {
        padWidth  = size.width
        padHeight = size.height
        val cx = size.width / 2; val cy = size.height / 2

        // Grid lines
        val gColor = GDim.copy(alpha = 0.5f)
        for (i in 1..4) {
            drawLine(gColor, Offset(size.width * i / 5, 0f), Offset(size.width * i / 5, size.height), 0.8f)
            drawLine(gColor, Offset(0f, size.height * i / 5), Offset(size.width, size.height * i / 5), 0.8f)
        }

        // Concentric rings
        listOf(0.25f, 0.5f, 0.85f).forEach { r ->
            val radius = minOf(cx, cy) * r
            drawCircle(GDim, radius, Offset(cx, cy), style = Stroke(0.8f))
        }

        // Crosshairs
        drawLine(GDim.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), 0.8f)
        drawLine(GDim.copy(alpha = 0.6f), Offset(0f, cy), Offset(size.width, cy), 0.8f)

        // Reticle
        val rx = reticleX * size.width
        val ry = reticleY * size.height
        val s = 10f
        val path = Path().apply {
            moveTo(rx, ry - s); lineTo(rx + s, ry)
            lineTo(rx, ry + s); lineTo(rx - s, ry); close()
        }
        drawPath(path, G, style = Stroke(1.5f))

        // Track lines to reticle
        val trackColor = G.copy(alpha = 0.35f)
        drawLine(trackColor, Offset(0f, ry), Offset(size.width, ry), 0.8f)
        drawLine(trackColor, Offset(rx, 0f), Offset(rx, size.height), 0.8f)

        // Center dot
        drawCircle(GBright, 3f, Offset(cx, cy))
    }
}

// ─── Data boxes ───────────────────────────────────────────────────────────────
@Composable
fun DataBoxes(alt: String, vel: String, hdg: String, pwr: Float) {
    val labels = listOf("ALT", "VEL", "HDG", "PWR")
    val values = listOf(alt, vel, hdg, "${(pwr * 100).toInt()}%")

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            labels.take(2).forEachIndexed { i, label ->
                DataBox(label, values[i], Modifier.weight(1f))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            labels.drop(2).forEachIndexed { i, label ->
                DataBox(label, values[i + 2], Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DataBox(label: String, value: String, modifier: Modifier) {
    Box(
        modifier
            .height(36.dp)
            .border(1.dp, GBright)
            .background(BG)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column {
            Text(label, color = GDim, fontSize = 6.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = G,    fontSize = 10.sp, letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

// ─── Numpad ───────────────────────────────────────────────────────────────────
@Composable
fun NumPad(onCommand: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val digits = listOf("7","8","9","4","5","6","1","2","3")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 3x3 digit grid
        for (row in 0..2) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (col in 0..2) {
                    val d = digits[row * 3 + col]
                    CalcBtn(d, Modifier.weight(1f)) {
                        if (input.length < 6) input += d
                    }
                }
            }
        }
        // CLR / 0 / ENT
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CalcBtn("CLR", Modifier.weight(1f), color = Red)   { input = "" }
            CalcBtn("0",   Modifier.weight(1f))                { if (input.length < 6) input += "0" }
            CalcBtn("ENT", Modifier.weight(1f), color = Amber) {
                if (input.isNotEmpty()) { onCommand(input); input = "" }
            }
        }

        // Input display
        if (input.isNotEmpty()) {
            Text(
                text = "INPUT: ${input.padEnd(6, '_')}",
                color = G, fontSize = 7.sp,
                letterSpacing = 1.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun CalcBtn(label: String, modifier: Modifier, color: Color = G, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(28.dp)
            .border(1.dp, color)
            .background(if (pressed) color.copy(alpha = 0.2f) else color.copy(alpha = 0.05f))
            .clickable { onClick() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pressed = true },
                    onDragEnd   = { pressed = false },
                    onDragCancel= { pressed = false },
                    onDrag      = { _, _ -> }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}

// ─── Command buttons ──────────────────────────────────────────────────────────
@Composable
fun CmdButtonRow(callbacks: HudCallbacks) {
    val cmds = listOf(
        Triple("T-FWD", G,     { callbacks.onCommand("THRUST_FWD") }),
        Triple("T-AFT", G,     { callbacks.onCommand("THRUST_AFT") }),
        Triple("SCAN",  G,     callbacks.onScan),
        Triple("LOCK",  Amber, callbacks.onLock),
        Triple("FIRE",  Red,   callbacks.onFire),
        Triple("BOOST", Amber, callbacks.onBoost),
        Triple("CLOAK", G,     callbacks.onCloak),
        Triple("DOCK",  G,     callbacks.onDock),
    )
    Row(
        Modifier.fillMaxWidth().height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        cmds.forEach { (label, color, fn) ->
            CmdBtn(label, color, Modifier.weight(1f), fn)
        }
    }
}

@Composable
fun CmdBtn(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .border(1.dp, color)
            .background(color.copy(alpha = 0.07f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = 6.5.sp, letterSpacing = 0.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

// ─── DAT_02 Panel ─────────────────────────────────────────────────────────────
@Composable
fun DataPanel(shield: Float, power: Float, hull: Float) {
    Column(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("// SYSTEM DIAGNOSTICS", color = GDim, fontSize = 8.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
        listOf(
            "SHIELD_GEN"  to "${(shield * 100).toInt()}%",
            "POWER_CORE"  to "${(power  * 100).toInt()}%",
            "HULL_INTEG"  to "${(hull   * 100).toInt()}%",
            "NAV_SYSTEM"  to "NOMINAL",
            "COMMS_ARRAY" to "LINKED",
            "WEAP_SYS"    to "STANDBY",
        ).forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().height(28.dp).border(1.dp, GDim).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = GDim, fontSize = 8.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
                Text(value, color = G,    fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── LOG_03 Panel ─────────────────────────────────────────────────────────────
@Composable
fun LogPanel() {
    // In your real app, pass the log list in as a parameter from your ViewModel
    val sampleLog = remember {
        listOf(
            "> FC-9 TACTICAL GRID ONLINE",
            "> SENSOR ARRAY NOMINAL",
            "> NAV SYSTEM CALIBRATED",
            "> WEAPONS COLD :: STANDBY",
        )
    }
    Column(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("// COMBAT LOG", color = GDim, fontSize = 8.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
        sampleLog.forEach { line ->
            Text(line, color = G.copy(alpha = 0.8f), fontSize = 8.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─── Placeholder for CARGO / FLEET ────────────────────────────────────────────
@Composable
fun PlaceholderPanel(tab: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("[ $tab ]", color = GDim, fontSize = 10.sp, letterSpacing = 3.sp, fontFamily = FontFamily.Monospace)
    }
}
