package com.example.posecamera.pose

import java.util.ArrayDeque

class FpsTracker(private val windowMillis: Long = 1_000L) {
    private val samples = ArrayDeque<Long>()

    fun record(timestampMillis: Long): Float {
        samples.addLast(timestampMillis)
        while (samples.size > 1 && timestampMillis - samples.first() > windowMillis) {
            samples.removeFirst()
        }
        if (samples.size < 2) return 0f
        val elapsed = samples.last() - samples.first()
        return if (elapsed <= 0L) 0f else (samples.size - 1) * 1_000f / elapsed
    }
}
