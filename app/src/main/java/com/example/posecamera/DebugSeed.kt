package com.example.posecamera

import android.content.Context
import com.example.posecamera.data.AppDatabase
import com.example.posecamera.data.LocalFoodLog
import com.example.posecamera.data.LocalSet
import com.example.posecamera.data.LocalUserPlan
import com.example.posecamera.data.LocalWeight
import com.example.posecamera.data.PLAN_ID
import com.example.posecamera.sync.dayString
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val DAY_MS = 86_400_000L

/**
 * DEBUG BUILDS ONLY (MainActivity calls it behind BuildConfig.DEBUG): sample data for screenshots.
 * Rows are written straight to Room with synced = true and NO outbox op, so nothing is ever pushed to the server.
 * Runs once per install (SharedPreferences "debug_seeded") unless [force].
 */
suspend fun seedDebugData(context: Context, force: Boolean) = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences("debug_seed", Context.MODE_PRIVATE)
    if (!force && prefs.getBoolean("debug_seeded", false)) return@withContext
    val db = AppDatabase.get(context)
    val user = db.user()
    val now = System.currentTimeMillis()
    val tz = TimeZone.getDefault()
    val lifts = listOf("barbell-squat" to 100.0, "barbell-bench-press-medium-grip" to 70.0, "barbell-deadlift" to 120.0, "standing-military-press" to 40.0)
    // 12 sets over 10 days, 3 per lift, load creeping up so PRs exist.
    for (i in 0 until 12) {
        val (slug, base) = lifts[i % 4]
        val daysAgo = 9 - i * 9 / 11
        val ts = now - daysAgo * DAY_MS - i * 60_000L
        val reps = 5 + i % 3
        user.put(LocalSet(UUID.randomUUID().toString(), "other", slug, reps, reps - 1, reps, 62f + (i * 3) % 35, base + (i / 4) * 2.5, 7.5, null, ts, ts, true))
    }
    for (i in 0 until 8) {
        val date = dayString(now - (i * 4L) * DAY_MS, tz)
        user.put(LocalWeight(UUID.randomUUID().toString(), 78.0 - (7 - i) * 0.25, date, now, true))
    }
    val today = dayString(now, tz)
    listOf(
        Triple("breakfast", "Poha", 250.0), Triple("breakfast", "Chai", 150.0), Triple("lunch", "Dal tadka", 350.0),
        Triple("lunch", "Roti", 100.0), Triple("snack", "Paneer", 120.0),
    ).forEach { (meal, name, g) ->
        user.put(LocalFoodLog(UUID.randomUUID().toString(), meal, today, null, null, g, name, g * 1.4, g * 0.06, g * 0.18, g * 0.04, null, now, true))
    }
    db.catalog().plans().first().firstOrNull()?.let { user.put(LocalUserPlan(PLAN_ID, it.slug, dayString(now - 3 * DAY_MS, tz), null, now, true)) }
    prefs.edit().putBoolean("debug_seeded", true).apply()
}
