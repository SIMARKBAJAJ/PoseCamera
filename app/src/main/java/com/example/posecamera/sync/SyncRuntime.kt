package com.example.posecamera.sync

import android.content.Context
import com.example.posecamera.data.AppDatabase
import com.example.posecamera.network.BackendConfig
import com.example.posecamera.network.TokenStore
import com.example.posecamera.power.PowerState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import com.google.gson.JsonObject

interface SyncService {
    @POST("api/sync/push")
    fun push(@Body body: JsonObject): Call<ResponseBody>

    @GET("api/sync/pull")
    fun pull(@Query("cursor") cursor: Long, @Query("limit") limit: Int): Call<ResponseBody>

    @GET("api/catalog/version")
    fun catalogVersion(): Call<ResponseBody>

    /** @Streaming: Retrofit must not buffer the page; the loader reads it incrementally. OkHttp gunzips. */
    @Streaming
    @GET("api/catalog/export")
    fun export(
        @Query("entity") entity: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Header("If-None-Match") etag: String?,
    ): Call<ResponseBody>
}

class HttpCatalogFetcher(private val service: SyncService) : CatalogFetcher {
    override fun fetch(entity: String, limit: Int, offset: Int, etag: String?): PageResponse {
        val r = service.export(entity, limit, offset, etag).execute()
        return when {
            r.code() == 304 -> PageResponse.NotModified
            r.isSuccessful -> PageResponse.Body(r.body()!!.charStream(), r.headers()["ETag"])
            else -> {
                r.errorBody()?.close()
                throw IOException("catalog export HTTP ${r.code()}")
            }
        }
    }
}

/** Blocking Retrofit implementation of [SyncApi]. 401 -> [UnauthorizedException]; any other failure -> IOException. */
class RetrofitSyncApi(
    private val service: SyncService,
    private val deviceId: () -> String,
    private val hasToken: () -> Boolean,
    private val loader: CatalogLoader,
) : SyncApi {
    private fun body(r: Response<ResponseBody>): String {
        if (r.code() == 401) {
            r.errorBody()?.close()
            throw UnauthorizedException()
        }
        if (!r.isSuccessful) {
            r.errorBody()?.close()
            throw IOException("HTTP ${r.code()}")
        }
        return r.body()!!.string()
    }

    private fun needToken() {
        if (!hasToken()) throw UnauthorizedException()
    }

    override fun push(ops: List<OutboxOp>): List<PushResult> {
        needToken()
        return parsePushResults(body(service.push(pushBody(deviceId(), ops)).execute()))
    }

    override fun pull(cursor: Long, limit: Int): PullPage {
        needToken()
        return parsePull(body(service.pull(cursor, limit).execute()))
    }

    override fun catalogVersions() = parseCatalogVersions(body(service.catalogVersion().execute()))

    override fun downloadCatalog(entity: String) = loader.download(entity)
}

data class SyncUi(
    val running: Boolean = false,
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    /** Set when a sync hit 401; the UI shows "Please log in again to sync" until the next login. */
    val needsLogin: Boolean = false,
    val lastOutcome: SyncOutcome? = null,
)

/** Process-wide wiring for sync. Everything here runs off the main thread (Dispatchers.IO) except [status]. */
class SyncRuntime private constructor(context: Context) {
    private val db = AppDatabase.get(context)
    private val tokens = TokenStore(context)
    private val prefs = context.applicationContext.getSharedPreferences("sync_flags", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(SyncUi(needsLogin = prefs.getBoolean(NEEDS_LOGIN, false)))
    val status: StateFlow<SyncUi> = _status
    private val mutex = Mutex()

    private val store = RoomSyncStore(db)
    private val service = Retrofit.Builder()
        .baseUrl(BackendConfig.BASE_URL)
        .client(
            OkHttpClient.Builder().addInterceptor { chain ->
                val req = chain.request()
                val token = if (req.url.encodedPath.startsWith("/api/sync/")) tokens.getToken() else null
                chain.proceed(if (token == null) req else req.newBuilder().header("Authorization", "Bearer $token").build())
            }.build(),
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SyncService::class.java)
    private val loader = CatalogLoader(
        HttpCatalogFetcher(service), RoomCatalogSink(db),
        CatalogProgressListener { entity, done, total ->
            _status.value = _status.value.copy(stage = "Downloading $entity", done = done, total = total)
        },
    )
    private val engine = SyncEngine(
        store,
        RetrofitSyncApi(service, { store.deviceId }, { tokens.getToken() != null }, loader),
    )

    /** One run at a time; a concurrent caller waits and then runs (it may find nothing left to do). */
    suspend fun run(power: PowerState, userInitiated: Boolean): SyncOutcome {
        if (tokens.getToken() == null) return SyncOutcome.PAUSED_AUTH // fresh install / logged out: no request, no login prompt
        return runLocked(power, userInitiated)
    }

    private suspend fun runLocked(power: PowerState, userInitiated: Boolean): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val hadToken = tokens.getToken() != null
            _status.value = _status.value.copy(running = true, stage = "Syncing", done = 0, total = 0)
            val outcome = try {
                engine.run(power, userInitiated)
            } catch (e: Exception) {
                SyncOutcome.RETRY // e.g. a malformed server body; never crash the worker, never lose the outbox
            }
            if (needsLoginAfter(outcome, hadToken)) {
                tokens.clearToken() // same as the REST client on 401: the app returns to the login screen
                setNeedsLogin(true)
            }
            _status.value = _status.value.copy(running = false, stage = "", lastOutcome = outcome)
            outcome
        }
    }

    fun clearNeedsLogin() = setNeedsLogin(false)

    private fun setNeedsLogin(v: Boolean) {
        prefs.edit().putBoolean(NEEDS_LOGIN, v).apply()
        _status.value = _status.value.copy(needsLogin = v)
    }

    companion object {
        private const val NEEDS_LOGIN = "needs_login"
        @Volatile private var instance: SyncRuntime? = null
        fun get(context: Context): SyncRuntime = instance ?: synchronized(this) {
            instance ?: SyncRuntime(context.applicationContext).also { instance = it }
        }
    }
}

/** A 401 only means "log in again" if we actually sent a token. */
fun needsLoginAfter(outcome: SyncOutcome, hadToken: Boolean) = outcome == SyncOutcome.PAUSED_AUTH && hadToken

enum class AccountSwitch { PROCEED, WIPE, CONFIRM }

/** Different user than last time: wipe silently if nothing is unsynced, else ask first. */
fun accountSwitch(previous: String?, next: String, pendingOps: Int): AccountSwitch = when {
    previous == null || previous == next -> AccountSwitch.PROCEED
    pendingOps == 0 -> AccountSwitch.WIPE
    else -> AccountSwitch.CONFIRM
}

/** Text for the one-line sync button: progress while running, the re-login prompt, else the action. */
fun syncLabel(s: SyncUi): String = when {
    s.needsLogin -> "Please log in again to sync"
    s.running && s.total > 0 -> "${s.stage} ${s.done}/${s.total}"
    s.running -> "Syncing..."
    else -> "Sync now"
}
