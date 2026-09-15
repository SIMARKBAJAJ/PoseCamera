package com.example.posecamera.network

import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.RepSetSummary
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class SahiRepApiMappingTest {
    @Test
    fun `maps squat summary to backend aggregate fields`() {
        val request = RepSetSummary(
            exerciseType = ExerciseType.SQUAT,
            attemptedReps = 10,
            cleanReps = 8,
            rejectedReps = 2,
            formScore = 80f,
            mostCommonRejectionReason = "not deep enough",
            rejectionReasons = listOf("not deep enough", "not deep enough"),
        ).toSubmitSetRequest()

        assertEquals("squat", request.exerciseType)
        assertEquals(10, request.repCount)
        assertEquals(8, request.cleanCount)
        assertEquals(2, request.attemptedCount)
        assertEquals(80f, request.formscore, 0f)
        assertEquals(
            setOf("exercise_type", "rep_count", "clean_count", "attempted_count", "formscore"),
            Gson().toJsonTree(request).asJsonObject.keySet(),
        )
    }

    @Test
    fun `maps push-up summary without rejection details`() {
        val request = RepSetSummary(
            exerciseType = ExerciseType.PUSH_UP,
            attemptedReps = 6,
            cleanReps = 5,
            rejectedReps = 1,
            formScore = 83.333f,
            mostCommonRejectionReason = "body line out of alignment",
            rejectionReasons = listOf("body line out of alignment"),
        ).toSubmitSetRequest()

        assertEquals("push-up", request.exerciseType)
        assertEquals(6, request.repCount)
        assertEquals(5, request.cleanCount)
        assertEquals(1, request.attemptedCount)
        assertEquals(83.333f, request.formscore, 0f)
    }
}
