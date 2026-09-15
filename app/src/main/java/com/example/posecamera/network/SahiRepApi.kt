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

data class SyncedSet(
    @SerializedName("exercise_type") val exerciseType: String,
    @SerializedName("rep_count") val repCount: Int,
    @SerializedName("clean_count") val cleanCount: Int,
    @SerializedName("attempted_count") val attemptedCount: Int,
    val formscore: Float,
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

    @GET("api/history")
    suspend fun getHistory(): Response<HistoryResponse>
}

fun RepSetSummary.toSubmitSetRequest() = SubmitSetRequest(
    exerciseType = when (exerciseType) {
        ExerciseType.SQUAT -> "squat"
        ExerciseType.PUSH_UP -> "push-up"
    },
    repCount = attemptedReps,
    cleanCount = cleanReps,
    attemptedCount = rejectedReps,
    formscore = formScore,
)
