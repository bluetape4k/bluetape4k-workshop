# Resilience4j 2.x Prometheus Metric Name 변경(Issue #153)

**Date**: 2026-05-24  
**Branch**: fix/issue-153-resilience4j-prometheus  
**Module**: `spring-boot/resilience4j-coroutines`

## 근본 원인

Resilience4j 2.x는 `circuitbreaker.calls`가 Micrometer에 등록되는 방식을 변경했다.

| Metric | Resilience4j 1.x | Resilience4j 2.x |
|--------|------------------|------------------|
| `circuitbreaker.calls` | **Counter** → `_total` suffix | **Timer** → `_seconds_count` / `_seconds_sum` |
| `circuitbreaker.not.permitted.calls` | Counter → `_total` | Counter → `_total` (unchanged) |
| `circuitbreaker.state` | Gauge → no suffix | Gauge → no suffix (unchanged) |
| `circuitbreaker.buffered.calls` | Gauge → no suffix | Gauge → no suffix (unchanged) |

test assertion은 Resilience4j 1.x format
(`resilience4j_circuitbreaker_calls_total`)을 확인하고 있었지만, 이 metric은 2.x에
더 이상 존재하지 않는다.

## 수정

올바른 2.x metric name을 문서화하는 `PrometheusMetricsTest`를 추가했다.

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

## Prometheus Metric Type Naming Rule

| Micrometer Type | Prometheus suffix |
|-----------------|-------------------|
| Counter | `_total` |
| Timer | `_seconds_count`, `_seconds_sum`, `_seconds_bucket` (histogram) |
| Gauge | no suffix |
| DistributionSummary | `_count`, `_sum`, `_bucket` |

## 왜 `calls`에 Timer를 쓰는가?

Resilience4j 2.x에서는 call duration tracking이 단일 Timer metric으로 병합되었다
(별도 Counter + Histogram을 대체). 이를 통해 별도 distribution summary 없이 circuit
breaker outcome별 latency percentile을 얻을 수 있다.

## 향후 지침

Prometheus metric name을 assertion하는 테스트를 작성할 때는 다음을 따른다.

- duration을 세는 항목(Timer)에는 `_seconds_count` / `_seconds_sum`을 사용한다.
- 순수 event counter(Counter)에만 `_total`을 사용한다.
- Gauge metric에는 suffix가 없다.
- Resilience4j 1.x → 2.x upgrade 시 `calls` metric의 `_total` assertion을 검색하고
  `_seconds_count`로 교체한다.
