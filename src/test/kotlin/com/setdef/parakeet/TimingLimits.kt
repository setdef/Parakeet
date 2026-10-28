package com.setdef.parakeet

import java.lang.management.ManagementFactory

/**
 * Timing limits, as Cuttlebone's tests/latency.rs has them. With [STRICT_VARIABLE] set, as CI's Cuttlebone job
 * sets it (build.yml, and release.yml through it), every limit is as written for the reference machine, times
 * [SCALE_VARIABLE] if it's set: how much slower than the reference the runner measured a fixed baseline workload
 * at the start of the job (ci/timing-baseline.py), never below 1. Without it, as in the check of every commit on
 * a machine doing other work, each is scaled by how loaded the machine is ([load]), so that other processes can't
 * fail a commit (AGENTS.md, "Where checks count"). Correctness never scales.
 */
object TimingLimits {
    const val STRICT_VARIABLE = "PARAKEET_TIMING_STRICT"
    const val SCALE_VARIABLE = "PARAKEET_TIMING_SCALE"

    val strict: Boolean get() = !System.getenv(STRICT_VARIABLE).isNullOrEmpty()

    /** How many times [base] may be taken: the runner's scale when [strict], else the load. */
    fun factor(): Double = factor(strict, System.getenv(SCALE_VARIABLE)?.takeIf { it.isNotEmpty() }?.toDouble(), ::load)

    /** [scale] (1 if it's null) when [strict], else [load]; at least 1 either way. */
    fun factor(strict: Boolean, scale: Double?, load: () -> Double): Double = (if (strict) scale ?: 1.0 else load()).coerceAtLeast(1.0)

    /** A limit in milliseconds (or any unit): [base], or [base] times the load. */
    fun scaled(base: Long): Long = scaled(base.toDouble()).toLong()

    fun scaled(base: Double): Double {
        val factor = factor()
        if (factor > 1.0) {
            val by = if (strict) "the runner's baseline ($SCALE_VARIABLE)" else "the load, per core ($STRICT_VARIABLE not set)"
            println("TIMING limit $base scaled by $by, ${"%.2f".format(factor)}, to ${base * factor}")
        }
        return base * factor
    }

    /** A wait in seconds, which only ever stops a test that would otherwise hang. */
    fun seconds(base: Int): Int = Math.ceil(base * factor()).toInt()

    /**
     * The load average over the last minute per core, at least 1: how many times slower than on an idle machine
     * a process here may run, as other processes take their share of the cores.
     */
    fun load(): Double {
        val system = ManagementFactory.getOperatingSystemMXBean()
        val average = system.systemLoadAverage.takeIf { it >= 0 } ?: 0.0
        return (average / system.availableProcessors).coerceAtLeast(1.0)
    }
}
