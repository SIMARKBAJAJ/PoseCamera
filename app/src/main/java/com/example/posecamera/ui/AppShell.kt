package com.example.posecamera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.LocalHideTabBar
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.home.HomeScreen
import com.example.posecamera.ui.library.LibraryScreen
import com.example.posecamera.ui.nutrition.NutritionScreen
import com.example.posecamera.ui.plan.PlanScreen
import com.example.posecamera.ui.profile.ProfileScreen
import com.example.posecamera.ui.progress.ProgressScreen
import com.example.posecamera.ui.skills.SkillTreeScreen
import com.example.posecamera.ui.train.TrainScreen

enum class Tab(val label: String) { HOME("Home"), TRAIN("Train"), LIBRARY("Library"), PROGRESS("Progress"), YOU("You") }

/** Debug screenshot hook: "gallery" is a screen, the rest are tabs. */
fun tabOfRoute(route: String?): Tab? = Tab.entries.firstOrNull { it.name.equals(route, ignoreCase = true) }

/** Post-auth shell. [train] is the existing camera flow; it is composed (so the camera is bound) only while the Train tab is visible. */
@Composable
fun AppShell(repo: LocalRepository, initialRoute: String?, onLogout: () -> Unit, train: @Composable (onDemos: (Boolean) -> Unit, startType: String?) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(tabOfRoute(initialRoute) ?: Tab.HOME) }
    var trainExercise by rememberSaveable { mutableStateOf<String?>(null) } // survives tab switches
    var coaching by rememberSaveable { mutableStateOf(false) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) } // "plan" or "nutrition", drawn over the current tab
    var sheetOpen by remember { mutableStateOf(false) }
    val hideTabBar = remember { { open: Boolean -> sheetOpen = open } }
    val power = rememberPowerUi()
    val fullscreen = coaching || sheetOpen // camera and sheets own the whole screen
    val banner = power.banner != null && !coaching // the camera screen shows its own banner
    val motion = LocalMotion.current
    CompositionLocalProvider(LocalHideTabBar provides hideTabBar) { Column(Modifier.fillMaxSize().background(SahiColors.Ink)) {
        if (banner) PowerBanner(power, Modifier.statusBarsPadding().padding(horizontal = 8.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (fullscreen) Modifier else Modifier.consumeWindowInsets(WindowInsets.navigationBars))
                .then(if (banner) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    if (motion.level == MotionLevel.OFF) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(motion.state()) + slideInHorizontally(motion.state()) { dir * it / 8 }) togetherWith
                            (fadeOut(motion.micro()) + slideOutHorizontally(motion.state()) { -dir * it / 8 })
                    }
                },
                label = "tab",
            ) { t ->
                when (t) {
                    Tab.HOME -> HomeScreen(
                        repo, onPickPlan = { overlay = "plan" }, onTrain = { tab = Tab.TRAIN },
                        onOpenSkills = { overlay = "skills" }, onLogStep = { trainExercise = "slug:$it"; tab = Tab.TRAIN },
                    )
                    Tab.TRAIN -> TrainScreen(repo, trainExercise, { trainExercise = it }, coaching, { coaching = it }, { overlay = "plan" }, train)
                    Tab.LIBRARY -> LibraryScreen(repo, onLog = { trainExercise = it; tab = Tab.TRAIN }, onOpenSkills = { overlay = "skills" })
                    Tab.PROGRESS -> ProgressScreen(repo, onOpenNutrition = { overlay = "nutrition" }, onTrain = { tab = Tab.TRAIN })
                    Tab.YOU -> ProfileScreen(repo, onLogout)
                }
            }
            if (overlay != null) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {}) { // swallows taps so the tab underneath is inert
                when (overlay) {
                    "plan" -> PlanScreen(repo, onBack = { overlay = null }, onTrain = { overlay = null; tab = Tab.TRAIN })
                    "nutrition" -> NutritionScreen(repo, onBack = { overlay = null })
                    "skills" -> SkillTreeScreen(repo, onBack = { overlay = null }, onLog = { overlay = null; trainExercise = "slug:$it"; tab = Tab.TRAIN })
                }
            }
        }
        if (!fullscreen) TabBar(tab) { overlay = null; tab = it }
    } }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val motion = LocalMotion.current
    Column(Modifier.fillMaxWidth().background(SahiColors.Slate).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(SahiColors.Hairline))
        BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp)) {
            val itemW = maxWidth / Tab.entries.size
            val dotX by animateDpAsState(itemW * selected.ordinal + itemW / 2 - 3.dp, motion.snapSpring(), label = "dot")
            Row5(selected, onSelect)
            Box(Modifier.offset(x = dotX, y = 4.dp).size(6.dp).clip(CircleShape).background(SahiColors.Haldi))
        }
    }
}

@Composable
private fun Row5(selected: Tab, onSelect: (Tab) -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
        Tab.entries.forEach { t ->
            val color = if (t == selected) SahiColors.Chalk else SahiColors.Dust
            Column(
                Modifier.weight(1f).fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab,
                ) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Canvas(Modifier.size(22.dp)) { drawTabIcon(t, color) }
                Text(t.label.uppercase(), Modifier.padding(top = 4.dp), style = SahiType.micro.copy(color = color))
            }
        }
    }
}

private fun DrawScope.drawTabIcon(tab: Tab, c: Color) {
    val w = 1.8.dp.toPx(); val st = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val s = size.width
    when (tab) {
        Tab.HOME -> drawPath(Path().apply {
            moveTo(s * .1f, s * .5f); lineTo(s * .5f, s * .12f); lineTo(s * .9f, s * .5f)
            moveTo(s * .2f, s * .45f); lineTo(s * .2f, s * .88f); lineTo(s * .8f, s * .88f); lineTo(s * .8f, s * .45f)
        }, c, style = st)
        Tab.TRAIN -> { // dumbbell
            drawLine(c, Offset(s * .2f, s * .5f), Offset(s * .8f, s * .5f), w, StrokeCap.Round)
            drawLine(c, Offset(s * .22f, s * .22f), Offset(s * .22f, s * .78f), w * 2.2f, StrokeCap.Round)
            drawLine(c, Offset(s * .78f, s * .22f), Offset(s * .78f, s * .78f), w * 2.2f, StrokeCap.Round)
        }
        Tab.LIBRARY -> { // three spines
            drawRect(c, Offset(s * .12f, s * .15f), Size(s * .18f, s * .7f), style = st)
            drawRect(c, Offset(s * .41f, s * .15f), Size(s * .18f, s * .7f), style = st)
            drawLine(c, Offset(s * .72f, s * .2f), Offset(s * .88f, s * .85f), w * 1.2f, StrokeCap.Round)
        }
        Tab.PROGRESS -> drawPath(Path().apply {
            moveTo(s * .1f, s * .8f); lineTo(s * .38f, s * .52f); lineTo(s * .58f, s * .68f); lineTo(s * .9f, s * .2f)
        }, c, style = st)
        Tab.YOU -> {
            drawCircle(c, s * .17f, Offset(s * .5f, s * .3f), style = st)
            drawArc(c, 180f, 180f, false, Offset(s * .15f, s * .55f), Size(s * .7f, s * .7f), style = st)
        }
    }
}
