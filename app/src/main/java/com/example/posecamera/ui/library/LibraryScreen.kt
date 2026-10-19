package com.example.posecamera.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.posecamera.data.CatalogExercise
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.data.PAGE
import com.example.posecamera.ui.design.ChamferCard
import com.example.posecamera.ui.design.ChamferShape
import com.example.posecamera.ui.design.LocalMotion
import com.example.posecamera.ui.design.MotionLevel
import com.example.posecamera.ui.design.SahiColors
import com.example.posecamera.ui.design.SahiType
import com.example.posecamera.ui.design.g3d.MuscleMap
import com.example.posecamera.ui.design.pressScale
import kotlinx.coroutines.delay

/**
 * Library tab: searchable, muscle-filterable exercise list; tapping a card zooms the 3D viewer in over the list
 * (the list stays composed underneath, so query, filter and scroll position survive). [onLog] receives an ExerciseType name.
 */
@Composable
fun LibraryScreen(repo: LocalRepository, onLog: (String) -> Unit, onOpenSkills: () -> Unit = {}) {
    val motion = LocalMotion.current
    var opened by remember { mutableStateOf<CatalogExercise?>(null) }
    Box(Modifier.fillMaxSize()) {
        LibraryList(repo, onOpen = { opened = it }, onOpenSkills = onOpenSkills)
        AnimatedContent(
            targetState = opened,
            transitionSpec = {
                if (motion.level == MotionLevel.OFF) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(motion.state()) + scaleIn(motion.snapSpring(), initialScale = 0.86f)) togetherWith fadeOut(motion.micro())
            },
            label = "viewer",
        ) { ex ->
            if (ex != null) ExerciseViewerScreen(repo, ex, onBack = { opened = null }, onLog = onLog)
            else Box(Modifier.fillMaxSize()) // takes no input, so the list below stays touchable
        }
    }
}

@Composable
private fun LibraryList(repo: LocalRepository, onOpen: (CatalogExercise) -> Unit, onOpenSkills: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<String?>(null) }
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query != debounced) { delay(250); debounced = query } }
    var limit by remember(debounced, muscle) { mutableIntStateOf(PAGE) }
    val items by remember(repo, debounced, muscle, limit) {
        repo.browseExercises(searchKey(debounced), muscleLike(muscle), nameLikes(muscle), limit)
    }.collectAsStateWithLifecycle(null) // null = first load; later emissions replace the list in place
    val listState = rememberLazyListState()
    LaunchedEffect(listState, items?.size, limit) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (shouldLoadMore(last, items?.size ?: 0, limit)) limit += PAGE
        }
    }

    Column(Modifier.fillMaxSize().background(SahiColors.Ink).statusBarsPadding()) {
        Text("Library", Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp), style = SahiType.display.copy(color = SahiColors.Chalk))
        SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp))
        LazyRow(
            Modifier.padding(vertical = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SahiChip("SKILLS", false) { onOpenSkills() } }
            item { SahiChip("All", muscle == null) { muscle = null } }
            items(FILTER_MUSCLES) { k -> SahiChip(muscleLabel(k), muscle == k) { muscle = if (muscle == k) null else k } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = items
            when {
                list == null -> BoneLoader(Modifier.align(Alignment.Center))
                list.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (debounced.isBlank() && muscle == null) "Catalog abhi download ho raha hai" else "Kuch nahi mila",
                        style = SahiType.title.copy(color = SahiColors.Chalk),
                    )
                    Text(
                        if (debounced.isBlank() && muscle == null) "Pehli baar sync hone par exercises yahan aayenge." else "Naam ya muscle filter badal ke dekho.",
                        Modifier.padding(top = 4.dp), style = SahiType.body.copy(color = SahiColors.Dust),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(list, key = { _, e -> e.slug }) { i, e -> ExerciseCard(i, e) { onOpen(e) } }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = ChamferShape(12.dp)
    // TextFieldValue (not String) so focusing can select everything: re-typing replaces, never appends.
    var tfv by remember { mutableStateOf(TextFieldValue(query)) }
    Row(
        modifier.fillMaxWidth().clip(shape).background(SahiColors.Slate2).border(1.dp, SahiColors.Hairline, shape)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (tfv.text.isEmpty()) Text("Search exercises", style = SahiType.body.copy(color = SahiColors.Dust))
            BasicTextField(
                tfv, { tfv = it; onQuery(it.text) }, // text state is written synchronously here and never written back from the lagging query
                Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) tfv = tfv.copy(selection = TextRange(0, tfv.text.length)) },
                singleLine = true,
                textStyle = SahiType.body.copy(color = SahiColors.Chalk), cursorBrush = SolidColor(SahiColors.Haldi),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        // Always laid out (invisible when empty) so the field does not change height or width as you type.
        Box(Modifier.size(48.dp).clickable(enabled = tfv.text.isNotEmpty()) { tfv = TextFieldValue(""); onQuery("") }, contentAlignment = Alignment.Center) {
            if (tfv.text.isNotEmpty()) Canvas(Modifier.size(14.dp)) {
                val w = 2.dp.toPx()
                drawLine(SahiColors.Dust, Offset(0f, 0f), Offset(size.width, size.height), w, StrokeCap.Round)
                drawLine(SahiColors.Dust, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
            }
        }
    }
}

/** Round toggle chip (controls are joints: fully round). */
@Composable
internal fun SahiChip(text: String, selected: Boolean, accent: Color = SahiColors.Haldi, onClick: () -> Unit) {
    Box(
        Modifier.pressScale().clip(RoundedCornerShape(50)).background(if (selected) accent else SahiColors.Slate2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), style = SahiType.label.copy(color = if (selected) SahiColors.Ink else SahiColors.Chalk)) }
}

/** The first 6 cards snap to plumb in 40 ms apart (FULL only); every other card is simply there. */
@Composable
private fun ExerciseCard(index: Int, ex: CatalogExercise, onClick: () -> Unit) {
    val motion = LocalMotion.current
    val staggered = motion.level == MotionLevel.FULL && index < 6
    val p = remember { Animatable(if (staggered) 0f else 1f) }
    LaunchedEffect(Unit) { if (staggered) { delay(index * 40L); p.animateTo(1f, motion.snapSpring()) } }
    val demo = remember(ex.slug) { demoOf(ex.slug) }
    ChamferCard(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { alpha = p.value.coerceIn(0f, 1f); translationY = (1f - p.value) * 36.dp.toPx() }
            .pressScale().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Slate backdrop; untouched groups 0xFF2A3036 with a lighter 0xFF3A4249 outline, trained groups full Haldi
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(SahiColors.Slate).border(1.dp, SahiColors.Hairline, RoundedCornerShape(12.dp))) {
                // Rows never host a live 3D figure (smudged at this size and costly in a list): muscle mini, 3D only in the viewer.
                val heat = remember(ex.slug, ex.primaryMuscles) { heatOf(ex.slug, ex.primaryMuscles) }
                MuscleMap(heat, sideFor(heat), Modifier.fillMaxSize().padding(2.dp), animateFlip = false,
                    untouched = Color(0xFF2A3036), outline = Color(0xFF3A4249))
            }
            Column(Modifier.weight(1f)) {
                Text(ex.name, maxLines = 2, style = SahiType.body.copy(color = SahiColors.Chalk, fontWeight = FontWeight.SemiBold))
                val muscles = ex.primaryMuscles.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                if (muscles.isNotEmpty()) Text(muscles, maxLines = 1, style = SahiType.micro.copy(color = SahiColors.Dust))
                ex.machine?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, style = SahiType.micro.copy(color = SahiColors.Dust)) }
            }
            if (demo != null) Text("3D", style = SahiType.label.copy(color = SahiColors.Neel))
        }
    }
}

/** Indeterminate loader: a bone draws joint to joint (static, fully drawn bone when idle loops are off). */
@Composable
fun BoneLoader(modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val t: State<Float> = if (motion.allowIdleLoops)
        rememberInfiniteTransition(label = "bone").animateFloat(0f, 1f, infiniteRepeatable(tween(1100)), label = "t")
    else remember { mutableFloatStateOf(1f) }
    Canvas(modifier.size(72.dp, 24.dp)) {
        val y = size.height / 2; val r = 4.dp.toPx(); val x1 = size.width - r
        val end = r + (x1 - r) * t.value
        drawCircle(SahiColors.Hairline, r, Offset(x1, y))
        drawLine(SahiColors.Haldi, Offset(r, y), Offset(end, y), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(SahiColors.Haldi, r, Offset(r, y))
        drawCircle(SahiColors.Haldi, r, Offset(end, y), style = Stroke(2.dp.toPx()))
    }
}
