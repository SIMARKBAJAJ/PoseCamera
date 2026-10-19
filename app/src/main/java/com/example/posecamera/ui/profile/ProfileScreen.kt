package com.example.posecamera.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.AppDatabase
import com.example.posecamera.data.LocalProfile
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.power.OverlayDetail
import com.example.posecamera.power.PowerPolicy
import com.example.posecamera.sync.SyncRuntime
import com.example.posecamera.sync.SyncScheduler
import com.example.posecamera.sync.syncLabel
import com.example.posecamera.ui.design.syncPillState
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiChip
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiStepper
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.cameraQualityLabel
import com.example.posecamera.ui.home.RecoveryPrefs
import com.example.posecamera.ui.home.RecoverySpeed
import com.example.posecamera.ui.rememberPowerUi
import com.example.posecamera.ui.tierLabel
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

/** What the current power policy turns off, in one line; null = nothing reduced. */
fun powerNote(p: PowerPolicy): String? = when {
    p.animationScale <= 0f -> "Battery critical: animations off, camera ${p.analysisFpsCap} fps, simple overlay"
    p.animationScale < 1f || p.overlayDetail == OverlayDetail.MINIMAL ->
        "Battery saver: animations reduced, camera ${p.analysisFpsCap} fps, simple overlay"
    else -> null
}

private const val RECOVERY_HOW =
    "Recovery times are rough guidelines, not medical advice. Studies show no single recovery number; how long you need " +
        "depends on training experience, volume, effort, sleep and nutrition. The hours here (large muscles 48h, medium 36h, " +
        "small 24h, adjusted for volume and effort) are SahiRep project defaults, not validated values. Use the speed setting " +
        "if you recover faster or slower. Pain or injury: follow professional advice."

private val SEX =listOf("male", "female")
private val GOALS = listOf("lose", "maintain", "gain")
private val ACTIVITY = listOf("sedentary", "light", "moderate", "active", "very_active")

@Composable
fun ProfileScreen(repo: LocalRepository, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by remember(repo) { repo.profile() }.collectAsStateWithLifecycle(null)
    val sync by remember(ctx) { SyncRuntime.get(ctx).status }.collectAsStateWithLifecycle()
    val queued by remember(ctx) { AppDatabase.get(ctx).sync().pendingCountFlow() }.collectAsStateWithLifecycle(0)
    val power = rememberPowerUi()
    val motion = LocalMotion.current

    Column(
        Modifier.fillMaxSize().background(SahiColors.Ink).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("You", style = SahiType.display.copy(color = SahiColors.Chalk))

        // Edits are staged locally and re-seeded whenever the stored profile changes (e.g. a pull).
        key(profile) {
            var sex by remember { mutableStateOf(profile?.sex) }
            var year by remember { mutableStateOf(profile?.birthYear ?: 1995) }
            var height by remember { mutableStateOf(profile?.heightCm ?: 170.0) }
            var weight by remember { mutableStateOf(profile?.weightKg ?: 70.0) }
            var goal by remember { mutableStateOf(profile?.goal ?: "maintain") }
            var activity by remember { mutableStateOf(profile?.activityLevel ?: "light") }
            ChamferCard(Modifier.fillMaxWidth()) {
                Text("PROFILE", style = SahiType.label.copy(color = SahiColors.Dust))
                PlumbDivider(Modifier.padding(vertical = 8.dp))
                ChipRow("Sex", SEX, sex) { sex = it }
                Field("Birth year", year.toString(), "year", { year = (year - 1).coerceAtLeast(1920) }, { year = (year + 1).coerceAtMost(Calendar.getInstance().get(Calendar.YEAR)) })
                Field("Height", String.format(Locale.US, "%.0f", height), "cm", { height = (height - 1).coerceAtLeast(100.0) }, { height = (height + 1).coerceAtMost(250.0) })
                Field("Weight", String.format(Locale.US, "%.1f", weight), "kg", { weight = (Math.round((weight - 0.1) * 10) / 10.0).coerceAtLeast(20.0) }, { weight = (Math.round((weight + 0.1) * 10) / 10.0).coerceAtMost(400.0) })
                ChipRow("Goal", GOALS, goal) { goal = it }
                ChipRow("Activity", ACTIVITY, activity) { activity = it }
                Text("Units: kg and cm only (metric).", Modifier.padding(top = 8.dp), style = SahiType.micro.copy(color = SahiColors.Dust))
                SahiButton(if (sex == null) "Pehle sex chuno" else "Save", {
                    if (sex != null) scope.launch {
                        val base = profile ?: LocalProfile(sex = null, birthYear = null, heightCm = null, weightKg = null, activityLevel = null, goal = null, dietPref = null, updatedAt = 0, synced = false)
                        repo.saveProfile(base.copy(sex = sex, birthYear = year, heightCm = height, weightKg = weight, goal = goal, activityLevel = activity))
                    }
                }, Modifier.padding(top = 12.dp))
            }
        }

        ChamferCard(Modifier.fillMaxWidth()) {
            Text("SYNC", style = SahiType.label.copy(color = SahiColors.Dust))
            PlumbDivider(Modifier.padding(vertical = 8.dp))
            Text(syncPillState(sync).label, style = SahiType.title.copy(color = SahiColors.Chalk)) // same derived label as the Home pill
            Text(syncLabel(sync), style = SahiType.micro.copy(color = SahiColors.Dust))
            if (sync.running && sync.stage.isNotEmpty()) {
                Text("${sync.stage}${if (sync.total > 0) " ${sync.done}/${sync.total}" else ""}", style = SahiType.micro.copy(color = SahiColors.Dust))
            }
            Text("Last result: ${sync.lastOutcome?.name ?: "none yet"}", style = SahiType.micro.copy(color = SahiColors.Dust))
            Text("Queued changes: $queued", style = SahiType.micro.copy(color = SahiColors.Dust))
            SahiButton("Sync now", { SyncScheduler.syncNow(ctx, userInitiated = true) }, Modifier.padding(top = 12.dp))
        }

        var speed by remember { mutableStateOf(RecoveryPrefs.get(ctx)) }
        ChamferCard(Modifier.fillMaxWidth()) {
            Text("RECOVERY", style = SahiType.label.copy(color = SahiColors.Dust))
            PlumbDivider(Modifier.padding(vertical = 8.dp))
            ChipRow("Recovery speed", RecoverySpeed.entries.map { it.name.lowercase() }, speed.name.lowercase()) { pick ->
                speed = RecoverySpeed.valueOf(pick.uppercase())
                RecoveryPrefs.set(ctx, speed)
            }
        }

        ChamferCard(Modifier.fillMaxWidth()) {
            Text("HOW THIS WORKS", style = SahiType.label.copy(color = SahiColors.Dust))
            PlumbDivider(Modifier.padding(vertical = 8.dp))
            Text(RECOVERY_HOW, style = SahiType.body.copy(color = SahiColors.Chalk))
        }

        ChamferCard(Modifier.fillMaxWidth()) {
            Text("POWER AND MOTION", style = SahiType.label.copy(color = SahiColors.Dust))
            PlumbDivider(Modifier.padding(vertical = 8.dp))
            Text("Tier: ${tierLabel(power.tier)}", style = SahiType.body.copy(color = SahiColors.Chalk))
            Text(cameraQualityLabel(power.policy), style = SahiType.micro.copy(color = SahiColors.Dust))
            powerNote(power.policy)?.let { Text(it, style = SahiType.micro.copy(color = SahiColors.Dust)) }
            Text(
                "Reduce motion follows Android's \"Remove animations\" setting (now: ${if (motion.level == MotionLevel.OFF) "no animations" else if (motion.level == MotionLevel.REDUCED) "reduced" else "full"}). No switch here.",
                Modifier.padding(top = 8.dp), style = SahiType.micro.copy(color = SahiColors.Dust),
            )
        }

        SahiButton("Log out", onLogout, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Field(label: String, value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = SahiType.body.copy(color = SahiColors.Chalk))
        SahiStepper(value, unit, onMinus, onPlus)
    }
}

@Composable
private fun ChipRow(label: String, options: List<String>, selected: String?, onPick: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = SahiType.body.copy(color = SahiColors.Chalk))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { SahiChip(it.replace('_', ' '), it == selected, { onPick(it) }) }
        }
    }
}
