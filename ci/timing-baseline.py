#!/usr/bin/env python3
"""The timing limits as written are for the reference machine; on CI they're scaled by how this runner
compares with it (AGENTS.md, "Where checks count"), as Cuttlebone's latency job does (its
docs/history/MILESTONE-14.md, section 6).

The baseline is a fixed workload nothing in Parakeet can make faster or slower: the installed `inko`
checking a copy of its own standard library as a project (`inko check`), BASELINE_RUNS times, the
median of their wall times. The scale is that over the reference machine's, never below 1 (a runner
faster than the reference is held to the limits as written): TimingLimits multiplies every timing limit
by it when PARAKEET_TIMING_STRICT is set.

Prints the baseline and the scale, adds them to a GitHub Actions job's summary, and sets
PARAKEET_TIMING_SCALE for the job's later steps. Usage: ci/timing-baseline.py
"""
import os
import resource
import shutil
import statistics
import subprocess
import sys
import tempfile
import time

# An Apple M1 Max, where the limits were measured (docs/history/SESSION-typing-replay.md). It runs at load
# averages of 50 to 600 from other work, so its wall times aren't what an idle one takes: this is the median
# CPU time (user and system) of 15 runs, 244 to 277 ms, which for `inko check` (one thread) is the wall time
# on an idle machine. No run's wall time was below it.
REFERENCE_MS = 255.0
BASELINE_RUNS = 7


def main():
    std = subprocess.run(["inko", "print", "std"], capture_output=True, text=True, check=True).stdout.strip()
    project = tempfile.mkdtemp(prefix="parakeet-baseline")
    try:
        shutil.copytree(os.path.join(std, "std"), os.path.join(project, "src", "std"))
        walls, cpus = [], []
        for _ in range(BASELINE_RUNS):
            before = resource.getrusage(resource.RUSAGE_CHILDREN)
            start = time.perf_counter()
            checked = subprocess.run(["inko", "check"], cwd=project, capture_output=True, text=True)
            walls.append((time.perf_counter() - start) * 1000)
            after = resource.getrusage(resource.RUSAGE_CHILDREN)
            cpus.append((after.ru_utime - before.ru_utime + after.ru_stime - before.ru_stime) * 1000)
            if checked.returncode != 0:
                sys.exit(f"timing-baseline.py: inko check of std failed:\n{checked.stdout}{checked.stderr}")
    finally:
        shutil.rmtree(project, ignore_errors=True)

    baseline = statistics.median(walls)
    scale = max(baseline / REFERENCE_MS, 1.0)
    report = (
        f"baseline {baseline:.1f} ms on this machine ({os.cpu_count()} cores, the median wall time of {BASELINE_RUNS} runs; "
        f"CPU time {statistics.median(cpus):.1f} ms)\n"
        f"reference {REFERENCE_MS:.1f} ms (an Apple M1 Max)\n"
        f"scale {scale:.3f}: every timing limit times this (the baseline over the reference, at least 1)\n"
    )
    print(report, end="")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write("Timing baseline:\n\n```\n" + report + "```\n")
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a") as env:
            env.write(f"PARAKEET_TIMING_SCALE={scale:.3f}\n")


if __name__ == "__main__":
    main()
