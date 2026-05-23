# Resilience4j 2.x Prometheus Metric Name Change (Issue #153)

**Date**: 2026-05-24  
**Branch**: fix/issue-153-resilience4j-prometheus  
**Module**: `spring-boot/resilience4j-coroutines`

## Root Cause

Resilience4j 2.x changed how `circuitbreaker.calls` is registered with Micrometer:

| Metric | Resilience4j 1.x | Resilience4j 2.x |
|--------|------------------|------------------|
| `circuitbreaker.calls` | **Counter** → `_total` suffix | **Timer** → `_seconds_count` / `_seconds_sum` |
| `circuitbreaker.not.permitted.calls` | Counter → `_total` | Counter → `_total` (unchanged) |
| `circuitbreaker.state` | Gauge → no suffix | Gauge → no suffix (unchanged) |
| `circuitbreaker.buffered.calls` | Gauge → no suffix | Gauge → no suffix (unchanged) |

Test assertions were checking for the Resilience4j 1.x format
(`resilience4j_circuitbreaker_calls_total`), which no longer exists in 2.x.

## Fix

Added `PrometheusMetricsTest` documenting the correct 2.x metric names:

```kotlin
// Resilience4j 2.x: calls → Timer
body shouldContain "resilience4j_circuitbreaker_calls_seconds_count"
body shouldContain "resilience4j_circuitbreaker_calls_seconds_sum"

// Not-permitted stays Counter
body shouldContain "resilience4j_circuitbreaker_not_permitted_calls_total"

// State and buffered remain Gauges
body shouldContain "resilience4j_circuitbreaker_state"
body shouldContain "resilience4j_circuitbreaker_buffered_calls"
```

## Prometheus Metric Type Naming Rules

| Micrometer Type | Prometheus suffix |
|-----------------|-------------------|
| Counter | `_total` |
| Timer | `_seconds_count`, `_seconds_sum`, `_seconds_bucket` (histogram) |
| Gauge | no suffix |
| DistributionSummary | `_count`, `_sum`, `_bucket` |

## Why Timer for `calls`?

In Resilience4j 2.x, call duration tracking was merged into a single Timer metric
(replacing separate Counter + Histogram). This gives latency percentiles per circuit
breaker outcome without requiring a separate distribution summary.

## Future Guidance

When writing tests that assert on Prometheus metric names:
- Use `_seconds_count` / `_seconds_sum` for anything that counts durations (Timers)
- Use `_total` only for pure event counters (Counters)
- Gauge metrics have no suffix
- When upgrading Resilience4j 1.x → 2.x, search for `_total` assertions on `calls` metrics
  and replace with `_seconds_count`
