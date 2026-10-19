package com.example.posecamera.skills

import com.example.posecamera.data.LocalSet
import com.example.posecamera.sync.payload
import com.google.gson.JsonParser
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private val day = 86_400_000L
    private val hour = 3_600_000L

    private fun set(slug: String?, reps: Int, ts: Long, rpe: Double? = null, type: String = "other", setType: String? = null, failed: Boolean = false) =
        LocalSet("c$ts$slug$reps", type, slug, reps, null, null, null, null, rpe, setType, ts, ts, true, failed)

    /** [n] qualifying-by-default sets on one UTC day. */
    private fun sets(slug: String, d: Int, reps: Int, n: Int = 3, rpe: Double? = null, offsetH: Long = 12) =
        (0 until n).map { set(slug, reps, d * day + offsetH * hour + it, rpe) }

    /** Two days of three sets at [reps]: achieves the step if reps >= its target. */
    private fun done(slug: String, reps: Int) = sets(slug, 1, reps) + sets(slug, 2, reps)

    private fun status(s: List<LocalSet>, tz: TimeZone = utc, marked: Set<String> = emptySet()) =
        statusOf(SKILL_STEPS, evidenceOf(s), marked, ::canLogPhase1, tz)

    private fun nextOf(s: List<LocalSet>, marked: Set<String> = emptySet()): Step? {
        val ev = evidenceOf(s)
        return nextUp(SKILL_STEPS, statusOf(SKILL_STEPS, ev, marked, ::canLogPhase1, utc), ev, ::canLogPhase1)
    }

    // ---- evidence ----

    @Test fun cameraRowsWithNullSlugMapToPoseSteps() {
        val ev = evidenceOf(listOf(set(null, 5, 1, type = "squat"), set(null, 6, 2, type = "push-up"), set(null, 4, 3, type = "other")))
        assertEquals(setOf("bodyweight-squat", "pushups"), ev.keys)
        assertEquals(5, ev.getValue("bodyweight-squat").single().value)
    }

    @Test fun invalidAndWarmupAndUnknownSetsAreExcluded() {
        val ev = evidenceOf(listOf(
            set("pushups", 12, 1, failed = true), set("pushups", 0, 2), set("pushups", 12, 3, setType = "warmup"),
            set("not-a-step", 9, 4), set("pushups", 12, 5),
        ))
        assertEquals(listOf(5L), ev.getValue("pushups").map { it.ts })
        assertEquals(setOf("pushups"), ev.keys)
    }

    // ---- status ----

    @Test fun dayBucketingDiffersUtcVsIst() {
        // 10:00 UTC and 20:00 UTC: one UTC day, but 20:00 UTC is 01:30 IST the next day.
        val s = sets("incline-push-up", 10, 15, offsetH = 10) + sets("incline-push-up", 10, 15, offsetH = 20)
        assertEquals(StepState.OPEN, status(s, utc).getValue("incline-push-up").state)
        assertEquals(1, status(s, utc).getValue("incline-push-up").qualifyingDays)
        assertEquals(StepState.ACHIEVED, status(s, ist).getValue("incline-push-up").state)
    }

    @Test fun rpeNullQualifiesAndAboveMaxDoesNot() {
        assertEquals(StepState.ACHIEVED, status(done("incline-push-up", 15)).getValue("incline-push-up").state)
        val hard = sets("incline-push-up", 1, 15, rpe = 8.5) + sets("incline-push-up", 2, 15, rpe = 8.5)
        assertEquals(StepState.OPEN, status(hard).getValue("incline-push-up").state)
        val ok = sets("incline-push-up", 1, 15, rpe = 8.0) + sets("incline-push-up", 2, 15, rpe = 8.0)
        assertEquals(StepState.ACHIEVED, status(ok).getValue("incline-push-up").state)
    }

    @Test fun threeSetsOnOneDayIsNotAchievedButTwoDaysIs() {
        assertEquals(StepState.OPEN, status(sets("incline-push-up", 1, 15)).getValue("incline-push-up").state)
        val two = status(sets("incline-push-up", 1, 15) + sets("incline-push-up", 2, 15)).getValue("incline-push-up")
        assertEquals(StepState.ACHIEVED, two.state); assertFalse(two.implied); assertNotNull(two.achievedAt)
        // two sets a day is not enough
        assertEquals(StepState.OPEN, status(sets("incline-push-up", 1, 15, n = 2) + sets("incline-push-up", 2, 15, n = 2)).getValue("incline-push-up").state)
    }

    @Test fun impliedAncestorsAreAchieved() {
        val st = status(done("pullups", 8))
        assertEquals(StepState.ACHIEVED, st.getValue("pullups").state); assertFalse(st.getValue("pullups").implied)
        for (id in listOf("chin-up", "band-assisted-pull-up", "negative-pull-up", "scapular-pull-up", "inverted-row", "dead-hang")) {
            assertEquals(id, StepState.ACHIEVED, st.getValue(id).state); assertTrue(id, st.getValue(id).implied)
        }
        assertEquals(StepState.OPEN, st.getValue("incline-push-up").state) // other branches are untouched
    }

    @Test fun advisoryDoesNotBlockAndLockedNeedsAllPrerequisites() {
        val empty = status(emptyList())
        assertEquals(StepState.ADVISORY, empty.getValue("dead-hang").state)
        assertEquals(StepState.OPEN, empty.getValue("scapular-pull-up").state) // only prerequisite is advisory
        assertEquals(StepState.LOCKED, empty.getValue("pushups").state)
        assertEquals(StepState.ADVISORY, empty.getValue("planche-lean").state) // hold + not in catalog: cannot be logged, so ADVISORY whatever its prerequisites
        assertEquals(StepState.LOCKED, empty.getValue("hanging-leg-raise").state) // loggable; dead-hang advisory but knee raise unmet
        val three = status(done("incline-push-up", 15) + done("pushups", 12) + done("decline-push-up", 10))
        assertEquals(StepState.OPEN, three.getValue("push-ups-close-triceps-position").state)
        assertEquals(StepState.ADVISORY, three.getValue("archer-push-up").state) // not in catalog
    }

    @Test fun multiPrerequisiteStepWaitsForAll() {
        // parallel-bar-dip (loggable) needs bench-dips + pushups
        val base = done("incline-push-up", 15) + done("pushups", 12)
        assertEquals(StepState.LOCKED, status(base).getValue("parallel-bar-dip").state) // bench-dips missing
        assertEquals(StepState.LOCKED, status(done("bench-dips", 12)).getValue("parallel-bar-dip").state) // pushups missing
        assertEquals(StepState.OPEN, status(base + done("bench-dips", 12)).getValue("parallel-bar-dip").state)
        // single-leg-high-box-squat needs split-squats
        assertEquals(StepState.LOCKED, status(done("bodyweight-squat", 20)).getValue("single-leg-high-box-squat").state)
        assertEquals(StepState.OPEN, status(done("bodyweight-squat", 20) + done("split-squats", 10)).getValue("single-leg-high-box-squat").state)
    }

    @Test fun selfMarkCountsAsAchieved() {
        val st = status(emptyList(), marked = setOf("chin-up"))
        assertTrue(st.getValue("chin-up").selfMarked); assertEquals(StepState.ACHIEVED, st.getValue("chin-up").state)
        assertTrue(st.getValue("band-assisted-pull-up").implied)
    }

    // ---- next up ----

    @Test fun nextUpEmptyDataIsInclinePushUp() = assertEquals("incline-push-up", nextOf(emptyList())!!.id)

    @Test fun nextUpMostRecentFamilyWins() {
        val s = done("incline-push-up", 15) + done("pushups", 12) + listOf(set("bodyweight-squat", 5, 10 * day))
        assertEquals("bodyweight-squat", nextOf(s)!!.id)
    }

    @Test fun nextUpInProgressBeatsUntouched() {
        val s = done("incline-push-up", 15) + done("pushups", 12) + listOf(set("push-ups-close-triceps-position", 3, 10 * day))
        assertEquals("push-ups-close-triceps-position", nextOf(s)!!.id) // decline-push-up is earlier in the table but untouched
    }

    @Test fun nextUpAllAchievedIsNull() = assertNull(nextOf(emptyList(), marked = SKILL_STEPS.map { it.id }.toSet()))

    @Test fun nextUpNeverPicksAdvisory() {
        val hold = listOf(Step("h", "H", Family.CORE, true, emptyList(), Metric.SECONDS, 30))
        val st = statusOf(hold, emptyMap(), emptySet(), ::canLogPhase1, utc)
        assertEquals(StepState.ADVISORY, st.getValue("h").state)
        assertNull(nextUp(hold, st, emptyMap(), ::canLogPhase1))
        for (id in listOf("dead-hang", "plank", "archer-push-up")) assertTrue(nextOf(emptyList())!!.id != id)
    }

    // ---- plateau ----

    private val pushups = SKILL_BY_ID.getValue("pushups")
    private fun e(d: Int, reps: Int, rpe: Double? = null, n: Int = 1) =
        (0 until n).map { Evidence("pushups", reps, rpe, d * day + 12 * hour + it) }

    /** incline achieved, so pushups is OPEN. */
    private val openSt = status(done("incline-push-up", 15))
    private fun plateau(ev: List<Evidence>, now: Long = 6 * day) = plateauOf(pushups, ev, openSt, now, utc)

    @Test fun plateauFourSessionsIsNone() = assertEquals(Plateau.None, plateau((1..4).flatMap { e(it, 10) }))

    @Test fun plateauFiveFlatRegressesToPrerequisite() {
        val p = plateau((1..5).flatMap { e(it, 10) })
        assertEquals("incline-push-up", (p as Plateau.Regress).to.id)
    }

    @Test fun plateauAtHighRpeIsDeload() = assertEquals(Plateau.Deload, plateau((1..5).flatMap { e(it, 10, 9.5) }))

    @Test fun plateauAtRootIsDeload() {
        val root = SKILL_BY_ID.getValue("incline-push-up")
        val ev = (1..5).flatMap { listOf(Evidence("incline-push-up", 10, null, it * day + 12 * hour)) }
        assertEquals(Plateau.Deload, plateauOf(root, ev, status(emptyList()), 6 * day, utc))
    }

    @Test fun plateauNewBestInWindowIsNone() =
        assertEquals(Plateau.None, plateau((1..4).flatMap { e(it, 10) } + e(5, 11)))

    @Test fun plateauMoreQualifyingSetsIsProgress() {
        val ev = (1..4).flatMap { e(it, 12) } + e(5, 12, n = 2) // same best, more qualifying sets
        assertEquals(Plateau.None, plateau(ev))
    }

    @Test fun plateauTiesAreNoProgress() = assertTrue(plateau((1..6).flatMap { e(it, 10) }) is Plateau.Regress)

    @Test fun plateauStaleIsNone() = assertEquals(Plateau.None, plateau((1..5).flatMap { e(it, 10) }, now = 5 * day + 22 * day))

    @Test fun plateauSameDaySetsAreOneSession() {
        assertEquals(Plateau.None, plateau((1..4).flatMap { e(it, 10, n = 3) })) // 12 sets, 4 sessions
        assertTrue(plateau((1..5).flatMap { e(it, 10, n = 3) }) is Plateau.Regress)
    }

    @Test fun plateauOnlyForOpenSteps() {
        assertEquals(Plateau.None, plateauOf(pushups, (1..5).flatMap { e(it, 10) }, status(emptyList()), 6 * day, utc)) // LOCKED
    }

    // ---- layout ----

    @Test fun layoutDepthIsLongestPathAndPositionsAreUnique() {
        val l = layoutOf(SKILL_STEPS)
        assertEquals(0, l.getValue("incline-push-up").second)
        assertEquals(4, l.getValue("pistol-squat").second) // bw 0, split 1, box 2, assisted 3, pistol 4
        assertEquals(4, l.getValue("l-sit").second)
        for (s in SKILL_STEPS) assertEquals(1 + (s.requires.maxOfOrNull { l.getValue(it).second } ?: -1), l.getValue(s.id).second)
        assertEquals(SKILL_STEPS.size, l.values.toSet().size)
    }

    // ---- manual set row / payload ----

    @Test fun manualSetIsOtherWithSlugAndNullFormFields() {
        val row = manualSkillSet("id", "chin-up", 5, null, 8.0, 1L)!!
        assertEquals("other", row.exerciseType); assertEquals("chin-up", row.exerciseSlug); assertEquals(5, row.repCount)
        assertNull(row.cleanCount); assertNull(row.attemptedCount); assertNull(row.formscore); assertFalse(row.synced)
        val p = JsonParser.parseString(row.payload()).asJsonObject
        assertEquals("other", p.get("exercise_type").asString); assertEquals("chin-up", p.get("exercise_slug").asString)
        assertEquals(5, p.get("rep_count").asInt); assertEquals(8.0, p.get("rpe").asDouble, 0.0)
        for (k in listOf("clean_count", "attempted_count", "formscore", "load_kg")) assertFalse(k, p.has(k))
        assertNull(manualSkillSet("id", "chin-up", 0, null, null, 1L))
    }
}
