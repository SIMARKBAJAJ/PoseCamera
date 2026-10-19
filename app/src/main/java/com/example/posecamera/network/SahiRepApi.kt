package com.example.posecamera.network

import com.example.posecamera.pose.ExerciseType
import com.example.posecamera.pose.RepSetSummary
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class CredentialsRequest(
    val username: String,
    val password: String,
)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
)

data class SubmitSetRequest(
    @SerializedName("exercise_type") val exerciseType: String,
    @SerializedName("rep_count") val repCount: Int,
    @SerializedName("clean_count") val cleanCount: Int,
    @SerializedName("attempted_count") val attemptedCount: Int,
    val formscore: Float,
)

/** Lifts upload as exercise_type "other"; form fields stay null unless every counted rep was pose-judged. */
data class SubmitLiftSetRequest(
    @SerializedName("exercise_type") val exerciseType: String,
    @SerializedName("exercise_slug") val exerciseSlug: String,
    @SerializedName("rep_count") val repCount: Int,
    @SerializedName("clean_count") val cleanCount: Int?,
    @SerializedName("attempted_count") val attemptedCount: Int?,
    val formscore: Float?,
)

data class SyncedSet(
    @SerializedName("exercise_type") val exerciseType: String,
    @SerializedName("exercise_slug") val exerciseSlug: String?,
    @SerializedName("rep_count") val repCount: Int,
    @SerializedName("clean_count") val cleanCount: Int?,
    @SerializedName("attempted_count") val attemptedCount: Int?,
    val formscore: Float?,
    val timestamp: String,
)

data class HistoryResponse(
    val streak: Int,
    val sets: List<SyncedSet>,
)

interface SahiRepApi {
    @POST("api/auth/register")
    suspend fun register(@Body request: CredentialsRequest): Response<Unit>

    @POST("api/auth/login")
    suspend fun login(@Body request: CredentialsRequest): Response<LoginResponse>

    @POST("api/sets")
    suspend fun submitSet(@Body request: SubmitSetRequest): Response<Unit>

    @POST("api/sets")
    suspend fun submitLiftSet(@Body request: SubmitLiftSetRequest): Response<Unit>

    @GET("api/history")
    suspend fun getHistory(): Response<HistoryResponse>
}

fun RepSetSummary.toSubmitSetRequest() = SubmitSetRequest(
    exerciseType = when (exerciseType) {
        ExerciseType.SQUAT -> "squat"
        ExerciseType.PUSH_UP -> "push-up"
        else -> throw IllegalArgumentException("$exerciseType uses toSubmitLiftSetRequest")
    },
    repCount = attemptedReps,
    cleanCount = cleanReps,
    attemptedCount = rejectedReps,
    formscore = formScore,
)

fun RepSetSummary.toSubmitLiftSetRequest(): SubmitLiftSetRequest {
    // The server requires clean + attempted == rep_count; unjudged reps (baseline, unseen) must not be faked.
    val allJudged = totalReps > 0 && attemptedReps == totalReps
    return SubmitLiftSetRequest(
        exerciseType = "other",
        exerciseSlug = requireNotNull(catalogSlug) { "lift summary has no catalog slug" },
        repCount = totalReps,
        cleanCount = cleanReps.takeIf { allJudged },
        attemptedCount = rejectedReps.takeIf { allJudged },
        formscore = formScore.takeIf { allJudged },
    )
}
