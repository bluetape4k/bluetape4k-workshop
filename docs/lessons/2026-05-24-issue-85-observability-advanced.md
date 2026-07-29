# Issue #85 — Observability/Performance Advanced README 개선

**Date**: 2026-05-24
**Branch**: `docs/issue-85-observability-advanced`
**Modules**: 5 modules updated

## 요약

5개 모듈 README에 advanced content를 보강했다. 범위에는
`Used bluetape4k Features` 표, Before/After code comparison, Mermaid
architecture/sequence diagram, Gatling simulation 실행 가이드, Virtual Thread 성능
비교 표, `withObservationSuspending` coroutine span propagation 설명이 포함된다.

## 갱신한 모듈

### 1. `observability/micrometer-observation`

추가 항목:

- `@Observed` AOP chain을 보여주는 Mermaid `flowchart TD` architecture diagram
- `observeOrNull` Before/After pattern(`ObservationSupport`의 null-safe observation wrapper)
- layer 간 observation propagation 섹션(outer → service → nested span)
- 실행 명령(`bootRun`, `test`)

### 2. `observability/micrometer-tracing-coroutines`

추가 항목:

- Mermaid `flowchart TD` architecture diagram(3 service types → ObservationRegistry → OTel → Zipkin)
- suspension point를 가로지르는 coroutine span propagation을 보여주는 Mermaid
  `sequenceDiagram`
- 단순화한 `withObservationSuspending` 내부 구조가 포함된 `CancellationException`
  safety 섹션
- coroutine boundary를 가로지르는 trace propagation 설명
- prerequisites 섹션(Docker, JDK 25, Zipkin auto-start)

### 3. `gatling/virtualthread-simulation`

추가 항목:

- Mermaid `flowchart TD` architecture diagram(Gatling → Spring Boot → MongoDB)
- simulation structure table(class, endpoint, injection profile, description)
- 단계별 simulation 실행 가이드(bootRun → gatlingRun → report)
- report 해석 가이드(key metrics, good/investigate threshold)
- Virtual Thread impact 설명(400 concurrent에서 platform vs virtual thread)
- stop condition assertion code example
- prerequisites 섹션

### 4. `virtualthreads/spring-mvc-tomcat`

추가 항목:

- Mermaid `flowchart TD` architecture diagram(Client → Tomcat → VT → bluetape4k APIs → DB)
- Virtual Thread vs Platform Thread performance comparison table
- scenario 설명(400 concurrent DB query, queue wait analysis)
- stop condition이 포함된 Gatling load testing 실행 가이드
- prerequisites 섹션

### 5. `virtualthreads/spring-webflux`

추가 항목:

- Mermaid `flowchart TD` architecture diagram(Netty → 4 dispatcher paths → `Dispatchers.VT`)
- thread model과 best-fit scenario가 포함된 dispatcher comparison table(Default, IO,
  Custom 16, VT)
- Virtual Thread vs Platform Thread performance comparison table
- throughput comparison table(indicative number, 400 concurrent users)
- Gatling 4-simulation table(DefaultCoroutineSimulation, IOCoroutineSimulation 등)
- stop condition이 포함된 단계별 Gatling 실행 가이드
- prerequisites 섹션

## 문서화한 주요 pattern

### `withObservationSuspending` CancellationException Safety

핵심 pattern은 `CancellationException`을 span error로 기록하면 안 된다는 것이다.
`withObservationSuspending`은 `obs.error(e)` 호출 전에 `CancellationException`을
다시 던져 이를 처리한다. coroutine code에서 Micrometer를 사용할 때 이 동작을
명시적으로 문서화하고 테스트한다.

### Span Propagation Across Coroutine Boundaries

thread-local 기반 context는 coroutine suspension point를 통과해 살아남지 않는다.
Micrometer의 `ObservationRegistry`는 기본적으로 thread-local을 사용한다.
`withObservationSuspending`은 observation을 coroutine context element에 저장하고,
coroutine이 어떤 carrier thread에서 재개되든 복원함으로써 이를 해결한다.

### Virtual Thread Suitability

Virtual Thread는 I/O-bound workload에서만 throughput을 개선한다. CPU-bound 작업에는
여전히 `Dispatchers.Default`가 적합하다. 오용을 막기 위해 모든 Virtual Thread
README에 이 구분을 문서화한다.

### Gatling Stop Conditions

production Gatling simulation에는 regression을 잡기 위해 항상 `.assertions()` block을
추가한다.

- `global().responseTime().percentile(95.0).lt(500)` — p95 < 500ms
- `global().successfulRequests().percent().gt(99.0)` — error rate < 1%

이 assertion이 없으면 모든 요청이 실패해도 simulation이 "성공적으로" 끝날 수 있다.

## 결정

- architecture에는 Mermaid `flowchart TD`를 사용했다(GitHub, IntelliJ, Obsidian에서 렌더링).
- coroutine span propagation sequence에는 Mermaid `sequenceDiagram`을 사용했다.
- comparison table의 performance number는 이 repo에서 benchmark한 값이 아니라
  indicative/relative 값이다.
- 각 README 상단의 한국어 module description line은 project convention에 따라 유지했다.
- 모든 신규 section header와 table content는 CLAUDE.md policy에 따라 English로 유지했다.
