package com.example.posecamera.network

import android.content.Context
import com.example.posecamera.pose.RepSetSummary
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(
        val message: String,
        val requiresLogin: Boolean = false,
    ) : ApiResult<Nothing>
}

class SahiRepRepository(context: Context) {
    private val tokenStore = TokenStore(context)
    private val api = Retrofit.Builder()
        .baseUrl(BackendConfig.BASE_URL)
        .client(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val protectedPath = request.url.encodedPath in PROTECTED_PATHS
                    val token = if (protectedPath) tokenStore.getToken() else null
                    chain.proceed(
                        if (token == null) request else request.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build(),
                    )
                }
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SahiRepApi::class.java)

    fun hasSession() = tokenStore.getToken() != null

    suspend fun register(username: String, password: String): ApiResult<Unit> = networkCall {
        val response = api.register(CredentialsRequest(username, password))
        if (response.isSuccessful) {
            ApiResult.Success(Unit)
        } else {
            ApiResult.Failure(response.errorMessage("Registration failed"))
        }
    }

    suspend fun login(username: String, password: String): ApiResult<Unit> = networkCall {
        val response = api.login(CredentialsRequest(username, password))
        val token = response.body()?.accessToken
        if (response.isSuccessful && token != null) {
            tokenStore.saveToken(token)
            ApiResult.Success(Unit)
        } else {
            ApiResult.Failure(response.errorMessage("Login failed"))
        }
    }

    suspend fun submitSet(summary: RepSetSummary): ApiResult<Unit> = networkCall {
        if (!hasSession()) return@networkCall ApiResult.Failure("Please log in again", true)
        val response = api.submitSet(summary.toSubmitSetRequest())
        if (response.isSuccessful) {
            ApiResult.Success(Unit)
        } else {
            response.protectedFailure("Set upload failed")
        }
    }

    suspend fun getHistory(): ApiResult<HistoryResponse> = networkCall {
        if (!hasSession()) return@networkCall ApiResult.Failure("Please log in again", true)
        val response = api.getHistory()
        val history = response.body()
        if (response.isSuccessful && history != null) {
            ApiResult.Success(history)
        } else {
            response.protectedFailure("History refresh failed")
        }
    }

    private suspend fun <T> networkCall(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: IOException) {
        ApiResult.Failure("Can't reach SahiRep. Check Wi-Fi and the Flask server.")
    } catch (_: Exception) {
        ApiResult.Failure("SahiRep returned an unreadable response.")
    }

    private fun Response<*>.protectedFailure(defaultMessage: String): ApiResult.Failure {
        val requiresLogin = code() == 401
        if (requiresLogin) tokenStore.clearToken()
        return ApiResult.Failure(errorMessage(defaultMessage), requiresLogin)
    }

    private fun Response<*>.errorMessage(defaultMessage: String): String {
        val backendMessage = runCatching {
            errorBody()?.string()?.let { body ->
                JsonParser.parseString(body).asJsonObject.get("error")?.asString
            }
        }.getOrNull()
        return backendMessage ?: "$defaultMessage (HTTP ${code()})"
    }

    private companion object {
        val PROTECTED_PATHS = setOf("/api/sets", "/api/history")
    }
}
