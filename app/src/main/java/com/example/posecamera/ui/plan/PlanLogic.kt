package com.example.posecamera.ui.plan

import com.google.gson.JsonParser

enum class DayState { DONE, TODAY, FUTURE }

/**
 * State of each of the plan's [n] rotation days, mirroring the server (next = last completed + 1, wrapping).
 * [lastDone] = day_index of the newest completion (null = none), [doneToday] = that completion is dated today.
 * Done today: days 0..lastDone are DONE and there is no TODAY node (rest of the day). A finished rotation on a later
 * day starts over: day 0 is TODAY, nothing is done.
 */
fun planDayStates(n: Int, lastDone: Int?, doneToday: Boolean): List<DayState> {
    if (n <= 0) return emptyList()
    val last = lastDone?.takeIf { it in 0 until n }
    if (last == null) return List(n) { if (it == 0) DayState.TODAY else DayState.FUTURE }
    if (doneToday) return List(n) { if (it <= last) DayState.DONE else DayState.FUTURE }
    val today = (last + 1) % n
    return List(n) { i -> if (i == today) DayState.TODAY else if (i < today) DayState.DONE else DayState.FUTURE }
}

/** Day names from the catalog plan's server json (`days[].name`); empty when absent or malformed. */
fun dayNamesOf(json: String): List<String> = runCatching {
    JsonParser.parseString(json).asJsonObject.getAsJsonArray("days")
        .map { it.asJsonObject.get("name")?.asString ?: "Day" }
}.getOrDefault(emptyList())
