package com.example.posecamera.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.CatalogPlan
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.skills.criterionText
import com.example.posecamera.skills.nextUpInfo
import com.example.posecamera.skills.plateauText
import com.example.posecamera.skills.progressText
import com.example.posecamera.sync.streakOf
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.RepRing
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiChip
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.design.StatBlock
import com.example.posecamera.ui.design.SyncPill
import com.example.posecamera.ui.design.g3d.Cam3
import com.example.posecamera.ui.design.g3d.MuscleMap
import com.example.posecamera.ui.design.g3d.MuscleSide
import com.example.posecamera.ui.design.g3d.Scene3
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Cards arrive 40 ms apart: rise from below with the snap spring (overshoot, then lock). Instant when motion is OFF. */
private fun Modifier.staggerIn(index: Int): Modifier = composed {
    val motion = LocalMotion.current
    val p = remember { Animatable(if (motion.level == MotionLevel.OFF) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motion.level == MotionLevel.OFF) return@LaunchedEffect
        delay(index * 40L)
        p.animateTo(1f, motion.snapSpring())
    }
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * 24.dp.toPx()
    }
}

private fun bandColor(b: FormBand): Color = when (b) {
    FormBand.GOOD -> SahiColors.Sahi
    FormBand.OK -> SahiColors.Haldi
    FormBand.LOW -> SahiColors.Sindoor
    FormBand.NONE -> SahiColors.Dust
}

@Composable
fun HomeScreen(
    repo: LocalRepository, onPickPlan: () -> Unit, onTrain: () -> Unit, modifier: Modifier = Modifier,
    onOpenSkills: () -> Unit = {}, onLogStep: (String) -> Unit = {},
) {
    val motion = LocalMotion.current
    val sets by remember(repo) { repo.recentSets() }.collectAsStateWithLifecycle(emptyList())
    val profile by remember(repo) { repo.profile() }.collectAsStateWithLifecycle(null)
    val userPlan by remember(repo) { repo.userPlan() }.collectAsStateWithLifecycle(null)
    val planSlug = userPlan?.slug?.takeIf { it.isNotEmpty() }
    val plan by remember(repo, planSlug) { planSlug?.let(repo::catalogPlan) ?: flowOf(null) }.collectAsStateWithLifecycle(null)

    val now = System.currentTimeMillis()
    val tz = TimeZone.getDefault()
    val stamps = remember(sets) { validSets(sets).map { it.timestamp } }
    val chain = remember(stamps, now / 60_000) { chainOf(stamps, now, tz) }
    val streak = remember(stamps, now / 60_000) { streakOf(stamps, now, tz) }
    val prs = remember(sets) { prsOf(sets) }
    val session = remember(sets) { lastSession(sets) }
    val heat = remember(sets, now / 60_000) { muscleHeat(sets, now) }
    val recovery = rememberRecovery(repo, sets)
    val skillSets by remember(repo) { repo.skillSets() }.collectAsStateWithLifecycle(emptyList())
    val nextSkill = remember(skillSets, now / 60_000) { nextUpInfo(skillSets, now, tz) }
    val recColors = remember(recovery) { recoveryColors(recovery) }
    // Default view: recovery when anything is still recovering, else this week's heat; a chip tap overrides.
    var pick by remember { mutableStateOf<Boolean?>(null) }
    val showRecovery = pick ?: recovery.values.any { it.state == RecoveryState.RECOVERING }

    // The bob swings once when Home appears and whenever the last-session form changes; REDUCED/OFF stay still.
    val swing = remember { Animatable(0f) }
    val formKey = session?.avgForm?.roundToInt()
    LaunchedEffect(formKey, motion.level) {
        if (motion.level == MotionLevel.FULL) {
            swing.snapTo(1f)
            swing.animateTo(0f, motion.snapSpring())
        } else swing.snapTo(0f)
    }
    val scroll = rememberScrollState()

    Box(modifier.fillMaxSize().background(SahiColors.Ink)) {
        PlumbBackdrop(scroll, formBandOf(session?.avgForm), swing, Modifier.fillMaxSize().statusBarsPadding())
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll)
                .padding(start = 32.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().staggerIn(0), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // ponytail: LocalProfile has no name field, so the greeting is the fixed fallback; goal is the subline.
                    Text("Sahi ho?", style = SahiType.display.copy(color = SahiColors.Chalk))
                    profile?.goal?.let { Text(prettyName(it), style = SahiType.label.copy(color = SahiColors.Dust)) }
                }
                SyncPill()
            }

            ChamferCard(Modifier.fillMaxWidth().staggerIn(1)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SahiChip("THIS WEEK", !showRecovery, { pick = false })
                    SahiChip("RECOVERY", showRecovery, { pick = true })
                }
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                // Card is capped near 45% of the screen: the figure (with its hint line) gets what is left after header, caption and padding.
                HeroFigure(heat, if (showRecovery) recColors else null, Modifier.fillMaxWidth().height(heroFigureHeightDp(LocalConfiguration.current.screenHeightDp, heatCaption(heat) != null).dp))
                val caption = heatCaption(heat)
                if (showRecovery) {
                    Text(recoveryCaption(recovery), style = SahiType.title.copy(color = SahiColors.Chalk), modifier = Modifier.padding(top = 8.dp))
                } else if (caption != null) {
                    Text(caption, style = SahiType.title.copy(color = SahiColors.Chalk), modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text("Is hafte kuch train nahi hua", style = SahiType.title.copy(color = SahiColors.Chalk), modifier = Modifier.padding(top = 8.dp))
                    SahiButton("Pehla set karo", onTrain, Modifier.padding(top = 12.dp))
                }
            }

            ChamferCard(Modifier.fillMaxWidth().staggerIn(2)) {
                Text("NEXT UP", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                val n = nextSkill
                if (n == null) {
                    Text("Map complete for now", style = SahiType.title.copy(color = SahiColors.Chalk))
                } else {
                    Text(n.step.name, style = SahiType.title.copy(color = SahiColors.Chalk))
                    Text(criterionText(n.step), style = SahiType.body.copy(color = SahiColors.Dust))
                    Text(progressText(n.step, n.status), style = SahiType.micro.copy(color = SahiColors.Dust))
                    plateauText(n.plateau)?.let { Text(it, style = SahiType.micro.copy(color = SahiColors.Dust)) }
                    SahiButton("Log it", { onLogStep(n.step.id) }, Modifier.padding(top = 12.dp))
                }
                SahiButton("Skill map", onOpenSkills, Modifier.padding(top = 8.dp))
            }

            ChamferCard(Modifier.fillMaxWidth().staggerIn(3)) { RecoveryContent(recovery) }

            ChamferCard(Modifier.fillMaxWidth().staggerIn(4)) {
                Text("STREAK", style = SahiType.label.copy(color = SahiColors.Dust))
                StatBlock(streak.toFloat(), if (streak == 1) "day" else "days")
                StreakChain(chain, Modifier.fillMaxWidth().height(72.dp).padding(top = 8.dp))
            }

            ChamferCard(Modifier.fillMaxWidth().staggerIn(5)) { PlanContent(plan, planSlug, onPickPlan) }

            ChamferCard(Modifier.fillMaxWidth().staggerIn(6)) {
                Text("RECENT PRS", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                if (prs.isEmpty()) {
                    Text("Pehla PR banao", style = SahiType.title.copy(color = SahiColors.Chalk))
                } else prs.forEach { pr ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(prettyName(pr.key), style = SahiType.body.copy(color = SahiColors.Chalk))
                        val v = pr.loadKg?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f kg x %d", it, pr.reps) } ?: plural(pr.reps, "rep")
                        Text(v, style = SahiType.body.copy(color = SahiColors.Haldi))
                    }
                }
            }

            ChamferCard(Modifier.fillMaxWidth().staggerIn(7)) {
                Text("LAST SESSION", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                if (session == null) {
                    Text("Abhi koi session nahi", style = SahiType.title.copy(color = SahiColors.Chalk))
                    Text("Ek set karo, yahan form score dikhega.", style = SahiType.body.copy(color = SahiColors.Dust))
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                        val form = session.avgForm
                        if (form != null) {
                            RepRing(form / 100f, Modifier.fillMaxSize())
                            Text(String.format(Locale.US, "%.0f", form), style = SahiType.display.copy(color = SahiColors.Chalk))
                        } else Text("${session.reps}", style = SahiType.display.copy(color = SahiColors.Chalk))
                    }
                    Column {
                        Text(if (session.avgForm != null) "Avg form score" else "Reps - form judge nahi hua", style = SahiType.label.copy(color = SahiColors.Dust))
                        Text("${plural(session.sets, "set")}, ${plural(session.reps, "rep")}", style = SahiType.body.copy(color = SahiColors.Chalk))
                    }
                }
            }
        }
    }
}

/**
 * Persistent plumb line in the left gutter, behind the cards: hairline with a joint at the top and a bob whose
 * height follows scroll progress. [swing] (1 -> 0 with overshoot) swings the bob once. Scroll and swing are read
 * in the draw scope only, so scrolling never recomposes.
 */
@Composable
private fun PlumbBackdrop(scroll: ScrollState, band: FormBand, swing: Animatable<Float, *>, modifier: Modifier) {
    val glow = LocalMotion.current.allowGlow
    val tint = bandColor(band)
    Canvas(modifier) {
        val x = 16.dp.toPx(); val top = 10.dp.toPx(); val len = 28.dp.toPx()
        val lo = top + 24.dp.toPx(); val hi = size.height - 96.dp.toPx() - len
        drawLine(SahiColors.Hairline, Offset(x, top), Offset(x, size.height), 1.dp.toPx())
        drawCircle(SahiColors.Hairline, 4.dp.toPx(), Offset(x, top))
        val prog = if (scroll.maxValue > 0) scroll.value / scroll.maxValue.toFloat() else 0f
        val py = lo + prog * (hi - lo).coerceAtLeast(0f)
        val a = swing.value * 0.3f
        val b = Offset(x + sin(a) * len, py + cos(a) * len)
        drawCircle(SahiColors.Hairline, 3.dp.toPx(), Offset(x, py))
        drawLine(tint.copy(alpha = 0.6f), Offset(x, py), b, 1.5.dp.toPx())
        if (glow) drawCircle(tint.copy(alpha = 0.25f), 11.dp.toPx(), b)
        drawCircle(tint, 6.dp.toPx(), b)
    }
}

/**
 * Figure area height (figure plus its hint line) so the whole hero card stays near 45% of the screen. Overhead =
 * card padding, label, rule, hint, caption (+ button when there is no caption); never below 160 dp.
 */
fun heroFigureHeightDp(screenHeightDp: Int, hasCaption: Boolean): Float =
    (screenHeightDp * 0.45f - (if (hasCaption) 104f else 164f)).coerceAtLeast(160f)

/**
 * Front/back muscle figure with this week's heat. FULL: drag turns it (rotationY, flips at 90 degrees, springs back
 * on release) and a gentle one-shot tilt hints at it. REDUCED/OFF: no tilt animation, tap flips instantly.
 */
@Composable
private fun HeroFigure(heat: Map<String, Float>, colors: Map<String, Color>?, modifier: Modifier) {
    val motion = LocalMotion.current
    val scope = rememberCoroutineScope()
    var side by remember { mutableStateOf(MuscleSide.FRONT) }
    var angle by remember { mutableFloatStateOf(0f) }
    var tilt by remember { mutableFloatStateOf(0f) } // rotationX, +-12 degrees from vertical drag
    var job by remember { mutableStateOf<Job?>(null) }
    val camDist = 12f * LocalDensity.current.density
    fun flip() { side = if (side == MuscleSide.FRONT) MuscleSide.BACK else MuscleSide.FRONT }
    fun settle() {
        job?.cancel()
        job = scope.launch {
            launch { animate(tilt, 0f, animationSpec = motion.snapSpring()) { v, _ -> tilt = v } }
            animate(angle, 0f, animationSpec = motion.snapSpring()) { v, _ -> angle = v }
        }
    }
    LaunchedEffect(motion.level) {
        if (!motion.allow3dAutoMotion) return@LaunchedEffect
        delay(500)
        job = launch {
            animate(0f, 14f, animationSpec = tween(450)) { v, _ -> angle = v }
            animate(14f, 0f, animationSpec = motion.snapSpring()) { v, _ -> angle = v }
        }
    }
    Column(modifier) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { job?.cancel(); angle = 0f; tilt = 0f; flip() } }
                .pointerInput(motion.level) {
                    if (motion.level != MotionLevel.FULL) return@pointerInput
                    // Horizontal-dominant drags turn the figure; vertical-dominant ones are left to the page scroll.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, over ->
                            if (abs(over.x) >= abs(over.y)) change.consume()
                        } ?: return@awaitEachGesture
                        job?.cancel()
                        drag(slop.id) { change ->
                            // Read the delta BEFORE consuming: positionChange() is zero for a consumed change (that was the dead drag).
                            val d = change.positionChange()
                            change.consume()
                            var a = angle + d.x * 0.4f
                            if (a > 90f) { a -= 180f; flip() } else if (a < -90f) { a += 180f; flip() }
                            angle = a
                            tilt = (tilt - d.y * 0.15f).coerceIn(-12f, 12f)
                        }
                        settle()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            MuscleMap(
                heat, side, Modifier.fillMaxSize().graphicsLayer { rotationY = angle; rotationX = tilt; cameraDistance = camDist },
                animateFlip = false, colors = colors,
            )
        }
        val hint = if (motion.level == MotionLevel.FULL) "drag to turn" else "tap to flip"
        Text("${side.name} - $hint", style = SahiType.micro.copy(color = SahiColors.Dust))
    }
}

@Composable
private fun PlanContent(plan: CatalogPlan?, slug: String?, onPickPlan: () -> Unit) {
    Text("TODAY'S PLAN", style = SahiType.label.copy(color = SahiColors.Dust))
    PlumbDivider(Modifier.padding(vertical = 8.dp))
    if (slug == null) {
        Text("Pick a plan", style = SahiType.title.copy(color = SahiColors.Chalk))
        Text("Ek plan chuno, phir har din bas follow karo.", style = SahiType.body.copy(color = SahiColors.Dust))
        SahiButton("Plan chuno", onPickPlan, Modifier.padding(top = 12.dp))
    } else {
        Text(plan?.name ?: prettyName(slug), style = SahiType.title.copy(color = SahiColors.Chalk))
        val meta = listOfNotNull(plan?.level, plan?.let { "${it.daysPerWeek} days/week" }, plan?.split).joinToString(" - ")
        if (meta.isNotEmpty()) Text(meta, style = SahiType.body.copy(color = SahiColors.Dust))
        SahiButton("Plan dekho", onPickPlan, Modifier.padding(top = 12.dp))
    }
}

private const val LINK_GAP = 0.5f
private const val JOINT_R = 0.1f

/**
 * 14-day chain in real perspective (Cam3/Scene3): today is the nearest, largest, brightest link on the right, the
 * past recedes. Done day = Haldi joint, bone to the previous day when that was done too. Missed = ghost ring, no bone.
 * Today pulses (FULL only).
 */
@Composable
private fun StreakChain(chain: List<Boolean>, modifier: Modifier) {
    val motion = LocalMotion.current
    val ping: State<Float> =
        if (motion.allowIdleLoops) {
            rememberInfiniteTransition(label = "today").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "ping",
            )
        } else remember { mutableFloatStateOf(0f) }
    val n = chain.size
    val cam = remember { Cam3(yaw = 1.0f, pitch = 0.12f, dist = 5f) }
    val scene = remember { Scene3(cam) }
    fun worldX(i: Int) = (i - (n - 1) / 2f) * LINK_GAP
    // Projected bounds (incl. joint radius) so the chain fits the canvas whatever the card width.
    val bounds = remember(n) {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until n) {
            val p = cam.project(worldX(i), 0f, 0f); val r = JOINT_R * cam.scaleAt(p.depth)
            x0 = minOf(x0, p.sx - r); x1 = maxOf(x1, p.sx + r); y0 = minOf(y0, p.sy - r); y1 = maxOf(y1, p.sy + r)
        }
        floatArrayOf(x0, x1, y0, y1)
    }
    Canvas(modifier) {
        val sc = minOf(size.width / (bounds[1] - bounds[0]), size.height / (bounds[3] - bounds[2]))
        val center = Offset(size.width / 2 - (bounds[0] + bounds[1]) / 2 * sc, size.height / 2 + (bounds[2] + bounds[3]) / 2 * sc)
        scene.reset(cam)
        for (i in 0 until n) {
            val last = i == n - 1
            val done = chain[i]
            val c = if (done) SahiColors.Haldi else if (last) SahiColors.Chalk else SahiColors.Hairline
            scene.dot(worldX(i), 0f, 0f, c, JOINT_R, ring = !done)
            if (done && i > 0 && chain[i - 1]) scene.line(worldX(i - 1), 0f, 0f, worldX(i), 0f, 0f, SahiColors.Haldi, 0.04f)
        }
        scene.render(this, center, sc)
        if (motion.allowIdleLoops) {
            val p = cam.project(worldX(n - 1), 0f, 0f)
            val o = Offset(center.x + p.sx * sc, center.y - p.sy * sc)
            val r = JOINT_R * cam.scaleAt(p.depth) * sc
            val t = ping.value
            val jc = if (chain[n - 1]) SahiColors.Haldi else SahiColors.Chalk
            drawCircle(jc.copy(alpha = 1f - t), r * (1.2f + 1.2f * t), o, style = Stroke(2.dp.toPx()))
        }
    }
}
