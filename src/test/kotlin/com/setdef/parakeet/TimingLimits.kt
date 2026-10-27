package com.setdef.parakeet

import java.lang.management.ManagementFactory

/**
 * Timing limits, as Cuttlebone's tests/latency.rs has them. With [STRICT_VARIABLE] set, as CI's Cuttlebone job
 * sets it (build.yml, and release.yml through it), every limit is as written. Without it, as in the check of
 * every commit on a machine doing other work, each is scaled by how loaded the machine is ([load]), so that
 * other processes can't fail a commit (AGENTS.md, "Where checks count"). Correctness never scales.
 */
object TimingLimits {
    const val STRICT_VARIABLE = "PARAKEET_TIMING_STRICT"

    val strict: Boolean get() = !System.getenv(STRICT_VARIABLE).isNullOrEmpty()

    /** How many times [base] may be taken: 1 when [strict], else the load, at least 1. */
    fun factor(): Double = if (strict) 1.0 else load()

    /** A limit in milliseconds (or any unit): [base], or [base] times the load. */
    fun scaled(base: Long): Long = scaled(base.toDouble()).toLong()

    fun scaled(base: Double): Double {
        val factor = factor()
        if (factor > 1.0) println("TIMING limit $base scaled by the load, ${"%.1f".format(factor)} per core, to ${base * factor} ($STRICT_VARIABLE not set)")
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
