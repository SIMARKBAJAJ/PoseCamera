package com.example.posecamera.skills

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillTextTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun criterionTextCoversRepsSecondsAndPerSide() {
        assertEquals("3 sets of 12 at RPE 8 or easier, on 2 different days", criterionText(SKILL_BY_ID.getValue("pushups")))
        assertEquals("3 sets of 45 s at RPE 8 or easier, on 2 different days", criterionText(SKILL_BY_ID.getValue("plank")))
        assertEquals("3 sets of 6 per side at RPE 8 or easier, on 2 different days", criterionText(SKILL_BY_ID.getValue("archer-push-up")))
    }

    @Test fun progressTextShowsNoSetsOrBestAndDays() {
        val s = SKILL_BY_ID.getValue("pushups")
        assertEquals("no sets yet", progressText(s, StepStatus(StepState.OPEN, null, false, 0, null, false)))
        assertEquals("best 6 / 12, 1 / 2 days", progressText(s, StepStatus(StepState.OPEN, null, false, 1, 6, false)))
        // days are capped at the number of sessions needed
        assertEquals("best 12 / 12, 2 / 2 days", progressText(s, StepStatus(StepState.ACHIEVED, 1L, false, 5, 12, false)))
    }

    @Test fun stateTextNamesUnmetPrerequisitesAndAdvisoryReasons() {
        val st = statusOf(SKILL_STEPS, emptyMap(), emptySet(), ::canLogPhase1, utc)
        assertEquals("Locked: needs Incline push-up", stateText(SKILL_BY_ID.getValue("pushups"), st, false))
        assertEquals("Camera counts this", cameraNote(SKILL_BY_ID.getValue("pushups")))
        assertEquals("Log by hand", cameraNote(SKILL_BY_ID.getValue("decline-push-up")))
        assertEquals("Not logged yet", cameraNote(SKILL_BY_ID.getValue("plank")))
        assertEquals("Not logged yet", cameraNote(SKILL_BY_ID.getValue("archer-push-up")))
        assertEquals("Not in our library yet", stateText(SKILL_BY_ID.getValue("archer-push-up"), st, false))
        assertEquals("Hold: tracking soon", stateText(SKILL_BY_ID.getValue("plank"), st, false))
        assertEquals("Next up", stateText(SKILL_BY_ID.getValue("incline-push-up"), st, true))
        assertEquals("Open", stateText(SKILL_BY_ID.getValue("incline-push-up"), st, false))
    }

    @Test fun plateauTextOnlyWhenPlateaued() {
        assertNull(plateauText(Plateau.None))
        assertNotNull(plateauText(Plateau.Deload))
        assertEquals("Plateau: try Incline push-up to rebuild", plateauText(Plateau.Regress(SKILL_BY_ID.getValue("incline-push-up"))))
    }

    @Test fun nextUpInfoWithNoDataIsTheInclinePushUp() {
        val n = nextUpInfo(emptyList(), 0L, utc)
        assertEquals("incline-push-up", n?.step?.id)
        assertEquals(Plateau.None, n?.plateau)
    }
}
