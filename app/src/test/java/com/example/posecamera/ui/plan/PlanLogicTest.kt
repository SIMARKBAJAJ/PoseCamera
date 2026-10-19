package com.example.posecamera.ui.plan

import com.example.posecamera.ui.plan.DayState.DONE
import com.example.posecamera.ui.plan.DayState.FUTURE
import com.example.posecamera.ui.plan.DayState.TODAY
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanLogicTest {
    @Test fun noCompletionStartsAtDayZero() = assertEquals(listOf(TODAY, FUTURE, FUTURE), planDayStates(3, null, false))

    @Test fun nextDayIsTodayAndEarlierAreDone() = assertEquals(listOf(DONE, DONE, TODAY, FUTURE), planDayStates(4, 1, false))

    @Test fun doneTodayHasNoTodayNode() = assertEquals(listOf(DONE, DONE, FUTURE), planDayStates(3, 1, true))

    @Test fun finishedRotationOnLaterDayStartsOver() = assertEquals(listOf(TODAY, FUTURE, FUTURE), planDayStates(3, 2, false))

    @Test fun finishedRotationDoneTodayShowsAllDone() = assertEquals(listOf(DONE, DONE, DONE), planDayStates(3, 2, true))

    @Test fun outOfRangeAndEmptyAreSafe() {
        assertEquals(listOf(TODAY, FUTURE), planDayStates(2, 9, false))
        assertEquals(emptyList<DayState>(), planDayStates(0, null, false))
    }

    @Test fun dayNamesParseOrFallBackToEmpty() {
        assertEquals(listOf("A", "B"), dayNamesOf("""{"days":[{"name":"A"},{"name":"B"}]}"""))
        assertEquals(emptyList<String>(), dayNamesOf("nope"))
    }
}
