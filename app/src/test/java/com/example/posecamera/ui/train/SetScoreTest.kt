package com.example.posecamera.ui.train

import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.RepSetSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetScoreTest {
    private fun sum(attempted: Int, clean: Int, cannotSee: List<String> = emptyList()) = RepSetSummary(
        ExerciseType.values().first(), attempted, clean, attempted - clean,
        if (attempted == 0) 0f else clean * 100f / attempted, null, emptyList(), cannotSee = cannotSee,
    )

    @Test fun zeroJudgedIsNull() = assertNull(setScore(sum(0, 0)))
    @Test fun allCleanIs100() = assertEquals(100, setScore(sum(5, 5)))
    @Test fun threeOfFourIs75() = assertEquals(75, setScore(sum(4, 3)))
    @Test fun rounds() { assertEquals(67, setScore(sum(3, 2))); assertEquals(33, setScore(sum(3, 1))) }
    @Test fun cannotSeeIsNull() = assertNull(setScore(sum(4, 4, listOf("knees"))))

    @Test fun rowScoreRules() {
        assertNull(rowScore(null, 5)); assertNull(rowScore(80f, 0)); assertNull(rowScore(80f, 5, syncFailed = true))
        assertEquals(81, rowScore(80.5f, 5))
    }

    @Test fun bandBoundaries() {
        assertEquals(ScoreBand.STRONG, scoreBand(85)); assertEquals(ScoreBand.OK, scoreBand(84))
        assertEquals(ScoreBand.OK, scoreBand(60)); assertEquals(ScoreBand.NEEDS_WORK, scoreBand(59))
    }

    @Test fun topFaultsCountsAndCapsAtThree() {
        val f = topFaults(listOf("a", "b", "b", "c", "c", "c", "d"))
        assertEquals(listOf("c" to 3, "b" to 2, "a" to 1), f)
        assertEquals(emptyList<Pair<String, Int>>(), topFaults(emptyList()))
    }
}
