package com.example.posecamera.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.CatalogExercise
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.data.LocalSet
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.library.muscleLabel
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

/** Wall-clock minute, ticking once a minute (one wake-up per minute, nothing per frame). */
@Composable
private fun rememberMinute(): Long {
    var m by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            m = System.currentTimeMillis() / 60_000
        }
    }
    return m
}

/** Recovery per muscle at minute granularity, with catalog-only exercises resolved through their primary muscles. */
@Composable
fun rememberRecovery(repo: LocalRepository, sets: List<LocalSet>): Map<String, MuscleRecovery> {
    val minute = rememberMinute()
    val speed = RecoveryPrefs.get(LocalContext.current) // cached SharedPreferences; re-read on each recomposition so the You setting applies
    val slugs = remember(sets) { validSets(sets).mapNotNull { it.exerciseSlug }.filter { musclesOf(it) == null }.distinct() }
    val catalog by remember(repo, slugs) { if (slugs.isEmpty()) flowOf(emptyList<CatalogExercise>()) else repo.exercisesBySlugs(slugs) }
        .collectAsStateWithLifecycle(emptyList())
    val resolver = remember(catalog) { catalogResolver(catalog.associate { it.slug to it.primaryMuscles }) }
    return remember(sets, speed, resolver, minute) { recoveryFor(sets, minute * 60_000, speed, TimeZone.getDefault(), resolver) }
}

/** MuscleMap paint for recovery mode: recovering = Haldi to Sindoor by share of window left, ready = Sahi, never = unpainted (Slate2). */
fun recoveryColors(map: Map<String, MuscleRecovery>): Map<String, Color> = map.mapNotNull { (k, r) ->
    when (r.state) {
        RecoveryState.RECOVERING -> k to lerp(SahiColors.Haldi, SahiColors.Sindoor, recoveryFraction(k, r))
        RecoveryState.READY -> k to SahiColors.Sahi
        RecoveryState.NEVER -> null
    }
}.toMap()

/** Hero caption for RECOVERY mode, e.g. "3 muscles recovering, next ready Chest in 26h 58m". */
fun recoveryCaption(map: Map<String, MuscleRecovery>): String {
    val next = recoveringList(map)
    if (next.isNotEmpty()) {
        val (k, r) = next.first()
        return "${next.size} ${if (next.size == 1) "muscle" else "muscles"} recovering, next ready ${muscleLabel(k)} in ${formatLeft(r.hoursLeft ?: 0.0)}"
    }
    return if (map.values.all { it.state == RecoveryState.NEVER }) "Nothing logged yet" else "All muscles ready"
}

/** Card body for Home: recovering countdowns, ready list, never-trained note. */
@Composable
fun RecoveryContent(map: Map<String, MuscleRecovery>) {
    val tz = remember { TimeZone.getDefault() }
    val lines = recoveringLines(map, tz, System.currentTimeMillis())
    val ready = readyNames(map)
    val never = neverNames(map)
    Text("RECOVERY", style = SahiType.label.copy(color = SahiColors.Dust))
    PlumbDivider(Modifier.padding(vertical = 8.dp))
    if (lines.isEmpty() && ready.isEmpty()) {
        Text("Abhi koi muscle track nahi hua", style = SahiType.title.copy(color = SahiColors.Chalk))
    }
    lines.forEach { Text(it, Modifier.padding(vertical = 2.dp), style = SahiType.body.copy(color = SahiColors.Chalk)) }
    if (ready.isNotEmpty()) {
        Text("READY TO TRAIN", Modifier.padding(top = 12.dp), style = SahiType.label.copy(color = SahiColors.Dust))
        Text(ready.joinToString(", "), style = SahiType.body.copy(color = SahiColors.Sahi))
    }
    if (never.isNotEmpty() && (lines.isNotEmpty() || ready.isNotEmpty())) {
        Text("Not trained yet: " + never.joinToString(", "), Modifier.padding(top = 8.dp), style = SahiType.micro.copy(color = SahiColors.Dust))
    }
    Text("Rough guidelines, not medical advice. Tune it in You.", Modifier.padding(top = 8.dp), style = SahiType.micro.copy(color = SahiColors.Dust))
}

/** Warn-only pill; callers show it only when [text] is non-null and never gate anything on it. */
@Composable
fun RecoveryChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(CircleShape).background(SahiColors.Slate2).padding(horizontal = 12.dp, vertical = 6.dp),
        style = SahiType.label.copy(color = SahiColors.Haldi),
    )
}
