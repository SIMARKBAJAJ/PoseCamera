package com.example.posecamera.ui.nutrition

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.CatalogFood
import com.example.posecamera.data.LocalFoodLog
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.sync.dayString
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.PlumbDivider
import com.example.posecamera.ui.design.RepRing
import com.example.posecamera.ui.design.SahiButton
import com.example.posecamera.ui.design.SahiChip
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiSheet
import com.example.posecamera.ui.design.SahiStepper
import com.example.posecamera.ui.design.SahiType
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun today() = dayString(System.currentTimeMillis(), TimeZone.getDefault())
private fun n0(v: Double) = String.format(Locale.US, "%.0f", v)

@Composable
fun NutritionScreen(repo: LocalRepository, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val todayIso = remember { today() }
    var date by rememberSaveable { mutableStateOf(todayIso) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val logs by remember(repo, date) { repo.foodLogs(date) }.collectAsStateWithLifecycle(emptyList())
    val profile by remember(repo) { repo.profile() }.collectAsStateWithLifecycle(null)
    val targets = remember(profile) { targetsOf(profile, Calendar.getInstance().get(Calendar.YEAR)) }
    val total = remember(logs) { totalsOf(logs) }
    val meals = remember(logs) { byMeal(logs) }

    Box(Modifier.fillMaxSize().background(SahiColors.Ink)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("< BACK", Modifier.clickable(onClick = onBack), style = SahiType.label.copy(color = SahiColors.Dust))
            Text("Aaj ka khana", style = SahiType.display.copy(color = SahiColors.Chalk))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateArrow("<") { date = shiftDay(date, -1) }
                Text(
                    if (date == todayIso) "Today  $date" else date, Modifier.weight(1f),
                    style = SahiType.body.copy(color = SahiColors.Chalk),
                )
                if (date != todayIso) SahiChip("Today", true, { date = todayIso })
                DateArrow(">") { date = shiftDay(date, 1) }
            }

            ChamferCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroRing("KCAL", total.kcal, targets.kcal, "", Modifier.weight(1f))
                    MacroRing("PROTEIN", total.proteinG, targets.proteinG, "g", Modifier.weight(1f))
                    MacroRing("CARBS", total.carbsG, targets.carbsG, "g", Modifier.weight(1f))
                    MacroRing("FAT", total.fatG, targets.fatG, "g", Modifier.weight(1f))
                }
                if (targets.isDefault) {
                    Text(
                        "Default targets (2000 kcal / 120 g protein). You tab mein sex, saal, height, weight bharo.",
                        Modifier.padding(top = 8.dp), style = SahiType.micro.copy(color = SahiColors.Dust),
                    )
                }
            }

            meals.forEach { (meal, items) ->
                ChamferCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(meal.uppercase(), Modifier.weight(1f), style = SahiType.label.copy(color = SahiColors.Dust))
                        Text("${n0(totalsOf(items).kcal)} kcal", style = SahiType.micro.copy(color = SahiColors.Dust))
                    }
                    PlumbDivider(Modifier.padding(vertical = 8.dp))
                    if (items.isEmpty()) Text("Kuch nahi", style = SahiType.body.copy(color = SahiColors.Dust))
                    items.forEach { l -> FoodRow(l) { scope.launch { repo.deleteFoodLog(l.clientId) } } }
                }
            }
            SahiButton("+ Add food", { adding = true }, Modifier.fillMaxWidth())
        }
        if (adding) AddFoodSheet(repo, date, onDone = { adding = false })
    }
}

@Composable
fun MacroRing(label: String, value: Double, target: Double, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            // Inset: the end knob sticks out past the ring stroke and must not reach the labels below.
            RepRing((value / target).toFloat().coerceIn(0f, 1f), Modifier.fillMaxSize().padding(8.dp))
            Text(n0(value), style = SahiType.title.copy(color = SahiColors.Chalk, fontSize = 15.sp, lineHeight = 20.sp)) // 4-digit kcal stays inside the ring
        }
        Text(label, Modifier.padding(top = 4.dp), style = SahiType.micro.copy(color = SahiColors.Dust))
        Text("/ ${n0(target)}$unit", style = SahiType.micro.copy(color = SahiColors.Dust))
    }
}

@Composable
private fun FoodRow(l: LocalFoodLog, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(foodLabel(l.name, l.foodSlug), maxLines = 2, overflow = TextOverflow.Ellipsis, style = SahiType.body.copy(color = SahiColors.Chalk))
            val q = l.quantityG?.let { "${n0(it)} g - " } ?: ""
            Text("$q${n0(macrosOf(l).kcal)} kcal${if (l.synced) "" else " - queued"}", style = SahiType.micro.copy(color = SahiColors.Dust))
        }
        Text(
            "x", Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onDelete).wrapContentSize(Alignment.Center),
            style = SahiType.title.copy(color = SahiColors.Sindoor),
        )
    }
}

private fun defaultMeal(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 4..10 -> "breakfast"; in 11..15 -> "lunch"; in 16..18 -> "snack"; else -> "dinner"
}

@Composable
private fun AddFoodSheet(repo: LocalRepository, date: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var meal by remember { mutableStateOf(defaultMeal()) }
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<CatalogFood?>(null) }
    var pickedLabel by remember { mutableStateOf<String?>(null) } // curated COMMON label, else null (short name derived)
    var grams by remember { mutableDoubleStateOf(100.0) }
    LaunchedEffect(query) { delay(250); debounced = query } // debounce: the DB query runs once typing pauses

    SahiSheet(onDismiss = onDone) {
        Text("Khana add karo", style = SahiType.title.copy(color = SahiColors.Chalk))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MEAL_ORDER.forEach { m -> SahiChip(m.replaceFirstChar { it.uppercase() }, m == meal, { meal = m }) }
        }
        val food = picked
        if (food == null) {
            BasicTextField(
                query, { query = it }, Modifier.fillMaxWidth().clip(CircleShape).background(SahiColors.Slate2).padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = SahiType.body.copy(color = SahiColors.Chalk), singleLine = true, cursorBrush = SolidColor(SahiColors.Haldi),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search food (dal, roti, paneer...)", style = SahiType.body.copy(color = SahiColors.Dust))
                    inner()
                },
            )
            val results by remember(repo, debounced) { repo.foods(debounced) }.collectAsStateWithLifecycle(emptyList())
            val found by remember(repo) { repo.foodsBySlugs(COMMON_FOODS.map { it.slug }) }.collectAsStateWithLifecycle(emptyList())
            val common = remember(found) { resolveCommon(found) }
            val showCommon = debounced.isBlank() && common.size >= MIN_COMMON // empty query: curated staples first; fewer than 4 resolved -> plain alphabetical, no header
            if (showCommon) Text("COMMON", style = SahiType.label.copy(color = SahiColors.Dust))
            val shown = if (showCommon) common.map { (c, f) -> c.label to f }
            else results.sortedByDescending { it.verified }.take(12).map { shortFoodName(it.name) to it } // stable: curated (verified) foods first, then the catalog order
            if (shown.isEmpty()) Text("Koi food nahi mila", style = SahiType.body.copy(color = SahiColors.Dust))
            shown.forEach { (label, f) ->
                Row(Modifier.fillMaxWidth().clickable { picked = f; pickedLabel = if (showCommon) label else null; grams = 100.0 }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = SahiType.body.copy(color = SahiColors.Chalk))
                    Text("${n0(f.kcal)} kcal/100 g", style = SahiType.micro.copy(color = SahiColors.Dust))
                }
            }
        } else {
            val display = pickedLabel ?: shortFoodName(food.name)
            Text(display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = SahiType.title.copy(color = SahiColors.Haldi), modifier = Modifier.clickable { picked = null })
            val servings by remember(repo, food.slug) { repo.servings(food.slug) }.collectAsStateWithLifecycle(emptyList())
            if (servings.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                servings.forEach { s -> SahiChip("${s.label} (${n0(s.grams)} g)", grams == s.grams, { grams = s.grams }) }
            }
            SahiStepper(n0(grams), "grams", { grams = stepGrams(grams, -1) }, { grams = stepGrams(grams, 1) }, Modifier.align(Alignment.CenterHorizontally))
            val m = scaled(Macros(food.kcal, food.proteinG, food.carbsG, food.fatG), grams)
            Text(
                "${n0(m.kcal)} kcal  -  P ${n0(m.proteinG)}  C ${n0(m.carbsG)}  F ${n0(m.fatG)}",
                Modifier.align(Alignment.CenterHorizontally), style = SahiType.body.copy(color = SahiColors.Chalk),
            )
            SahiButton("Save", {
                scope.launch {
                    // Sync sends food_slug + quantity_g only; the macros are kept locally so totals work offline (server numbers replace them on pull).
                    repo.editFoodLog(LocalFoodLog(UUID.randomUUID().toString(), meal, date, food.slug, null, grams, display, m.kcal, m.proteinG, m.carbsG, m.fatG, null, 0, false))
                    onDone()
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

/** Small icon button with a full 48 dp touch target. */
@Composable
private fun DateArrow(label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(SahiColors.Slate2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = SahiType.title.copy(color = SahiColors.Chalk))
    }
}
