package com.kalize.recorder

import kotlin.math.sqrt

data class CpuBenchmarkResult(
    val overallScore: Int,
    val singleCoreScore: Int,
    val multiCoreScore: Int,
    val stabilityPercent: Int,
    val rating: String,
    val explanation: String,
    val coreCount: Int
)

/** A measured local CPU workload, not a universal cross-device or gaming-FPS score. */
object CpuBenchmark {
    fun run(): CpuBenchmarkResult {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val singleRaw = runWorkload(1_000L, 1)
        val workerCount = cores.coerceIn(1, 8)
        val multiRaw = runWorkload(1_000L, workerCount)
        val samples = LongArray(3) { runWorkload(350L, 1) }
        val minSample = samples.minOrNull() ?: 0L
        val maxSample = samples.maxOrNull() ?: 0L
        val stability = if (maxSample <= 0L) 0 else ((minSample.toDouble() / maxSample) * 100.0).toInt().coerceIn(0, 100)
        val single = (singleRaw / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val multi = (multiRaw / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val total = (single * 0.35 + multi * 0.65).toInt()
        val rating = when {
            total < 2_000 -> "Very weak"
            total < 4_000 -> "Weak"
            total < 6_000 -> "Average"
            total < 8_000 -> "Good"
            total < 10_000 -> "Very good"
            else -> "Excellent"
        }
        val explanation = "The rating combines single-core and multi-core throughput, with extra weight on multi-core performance. Stability shows consistency across three repeat runs. Heat, battery mode and background activity can change results, so compare runs under similar conditions."
        return CpuBenchmarkResult(total, single, multi, stability, rating, explanation, workerCount)
    }

    private fun runWorkload(durationMs: Long, workers: Int): Long {
        val deadline = System.nanoTime() + durationMs * 1_000_000L
        val counts = LongArray(workers)
        val threads = ArrayList<Thread>(workers)
        repeat(workers) { index ->
            threads += Thread {
                var iterations = 0L
                var value = 1.0001
                while (System.nanoTime() < deadline) {
                    value = sqrt(value * 1.0000001 + (iterations % 97L) * 0.000001)
                    iterations++
                }
                counts[index] = if (value > 0.0) iterations else 0L
            }.apply { start() }
        }
        threads.forEach { it.join() }
        return counts.sum()
    }
}
