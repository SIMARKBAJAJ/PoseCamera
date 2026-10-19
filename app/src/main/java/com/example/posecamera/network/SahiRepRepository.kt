package com.example.posecamera.network

import android.content.Context
import com.example.posecamera.data.AppDatabase
import com.example.posecamera.data.LocalRepository
import com.example.posecamera.pose.RepSetSummary
import com.example.posecamera.sync.AccountSwitch
import com.example.posecamera.sync.SaveResult
import com.example.posecamera.sync.SyncRuntime
import com.example.posecamera.sync.accountSwitch
import com.example.posecamera.sync.SyncScheduler
import com.example.posecamera.sync.SyncUi
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(
        val message: String,
        val requiresLogin: Boolean = false,
        val needsDiscardConfirm: Boolean = false,
    ) : ApiResult<Nothing>
}

const val NO_REPS_MESSAGE = "No reps counted - set not saved"

class SahiRepRepository(context: Context) {
    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(context)
    private val db = AppDatabase.get(context)
    private val syncPrefs = appContext.getSharedPreferences("sync_user", Context.MODE_PRIVATE)
    private val runtime = SyncRuntime.get(context)

    /** Offline-first data layer (Room + outbox). Saves poke SyncWorker. */
    val local = LocalRepository(db, onSaved = { SyncScheduler.syncNow(appContext) })

    /** Progress / needs-login / running state of the sync, incl. the first-run catalog download. */
    val syncStatus: StateFlow<SyncUi> = runtime.status

    fun syncNow() = SyncScheduler.syncNow(appContext, userInitiated = true)

    /** On app start (logged in): periodic ~6 h job + one catch-up run. */
    fun startSync() {
        SyncScheduler.schedulePeriodic(appContext)
        SyncScheduler.syncNow(appContext)
    }

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

    /** Ends the session: drops the token only. Local rows and the outbox stay (a different next login is handled by [login]'s account-switch check). */
    fun logout() = tokenStore.clearToken()

    suspend fun register(username: String, password: String): ApiResult<Unit> = networkCall {
        val response = api.register(CredentialsRequest(username, password))
        if (response.isSuccessful) {
            ApiResult.Success(Unit)
        } else {
            ApiResult.Failure(response.errorMessage("Registration failed"))
        }
    }

    /**
     * Local rows/outbox belong to one account: a different user must never inherit (and push) them. If the previous
     * account still has unsynced ops, nothing changes (no token saved) and Failure.needsDiscardConfirm is set; the UI
     * asks and calls again with discardUnsynced = true, or drops it (Cancel).
     */
    suspend fun login(username: String, password: String, discardUnsynced: Boolean = false): ApiResult<Unit> = networkCall {
        val response = api.login(CredentialsRequest(username, password))
        val token = response.body()?.accessToken
        if (response.isSuccessful && token != null) {
            val previous = syncPrefs.getString("username", null)
            val pending = withContext(Dispatchers.IO) { db.sync().pendingCount() }
            when (accountSwitch(previous, username.trim().lowercase(), pending)) {
                AccountSwitch.PROCEED -> {}
                AccountSwitch.WIPE -> withContext(Dispatchers.IO) { db.clearAllTables() }
                AccountSwitch.CONFIRM -> if (discardUnsynced) {
                    withContext(Dispatchers.IO) { db.clearAllTables() }
                } else {
                    return@networkCall ApiResult.Failure("Unsynced data from another account will be deleted", needsDiscardConfirm = true)
                }
            }
            syncPrefs.edit().putString("username", username.trim().lowercase()).apply()
            tokenStore.saveToken(token)
            runtime.clearNeedsLogin()
            // First run after login: pull from cursor 0 + catalog download (progress in syncStatus).
            SyncScheduler.syncNow(appContext, userInitiated = true)
            SyncScheduler.schedulePeriodic(appContext)
            ApiResult.Success(Unit)
        } else {
            ApiResult.Failure(response.errorMessage("Login failed"))
        }
    }

    /** Offline-first: one Room transaction (set + outbox op); SyncWorker pushes it via /api/sync/push. */
    suspend fun submitSet(summary: RepSetSummary): ApiResult<Unit> {
        return when (local.saveSet(summary)) {
            SaveResult.SAVED -> ApiResult.Success(Unit)
            SaveResult.NO_REPS -> ApiResult.Failure(NO_REPS_MESSAGE)
            SaveResult.UNSUPPORTED -> ApiResult.Failure("Plank holds are not synced yet.")
        }
    }

    /** From Room, pending rows included. */
    suspend fun getHistory(): ApiResult<HistoryResponse> = ApiResult.Success(local.history().first())

    /**
     * FALLBACK ONLY, not called by the app: direct POST /api/sets. A set saved through [submitSet] is also in the
     * outbox, so never call both for the same set or it is posted twice.
     */
    suspend fun submitSetViaRest(summary: RepSetSummary): ApiResult<Unit> = networkCall {
        if (!hasSession()) return@networkCall ApiResult.Failure("Please log in again", true)
        val response = if (summary.exerciseType.lift) {
            api.submitLiftSet(summary.toSubmitLiftSetRequest())
        } else {
            api.submitSet(summary.toSubmitSetRequest())
        }
        if (response.isSuccessful) {
            ApiResult.Success(Unit)
        } else {
            response.protectedFailure("Set upload failed")
        }
    }

    /** FALLBACK ONLY (server-side history); the app reads [getHistory] from Room. */
    suspend fun getHistoryViaRest(): ApiResult<HistoryResponse> = networkCall {
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
