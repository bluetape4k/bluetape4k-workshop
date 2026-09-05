# Issue #888 native graph algorithm 실행 관찰 구현 리뷰

## 리뷰 범위

- Issue: `#888`
- Base: `origin/develop`
- Head: `feat/issue-888-native-algorithm-observation`
- 의존성 기준: `bluetape4k-dependencies:2.0.0`
- 대상: `graph/abuser-detection`의 호출별 provider 정책·fallback 결과, observer,
  cancellation 경계, 양국어 문서와 검증 표면

## 요구사항 추적성

| ID | 요구사항 | 구현·검증 증거 | 상태 |
|---|---|---|---|
| A-VER-01 | 기존 PageRank API 호환 | blocking/suspend backend parity 테스트 | PASS |
| A-VER-02 | `AUTO` fallback 관찰 | `NO_PROVIDER` policy 테스트 | PASS |
| A-VER-03 | `JVM_ONLY` fallback 관찰 | `JVM_ONLY_POLICY` policy 테스트 | PASS |
| A-VER-04 | `NATIVE_ONLY` 조기 실패 | PageRank 호출 0회 검증 | PASS |
| A-VER-05 | 호출별 실행 귀속 | blocking/suspend 20개 동시 호출 테스트 | PASS |
| A-VER-06 | observer 실패·취소 경계 | 일반 예외 격리, 취소 전파와 callback 경합 회귀 테스트 | PASS |
| A-VER-07 | consumer 검증 표면 | workflow, smoke guard, coverage, lesson, manifest 갱신 | PASS |

## Six-lens review

| 관점 | 판정 | 근거 |
|---|---|---|
| 성능 | P0=0, P1=0, P2=0, P3=0 | PageRank를 호출당 한 번만 실행하고 20개 동시 호출에서 execution 귀속을 검증했다. 성능 향상 주장은 없어 benchmark는 적용하지 않는다. |
| 안정성 | P0=0, P1=0, P2=0, P3=0 | 최초 리뷰에서 callback 시작 직후 취소 시 결과가 반환되는 P1 경합을 재현했다. Callback 직후 `ensureActive()`를 추가하고 event 최대 1회·결과 반환 0회 회귀 테스트를 통과시켰다. |
| 보안 | P0=0, P1=0, P2=0, P3=0 | Provider ID를 64자 안전 패턴으로 제한하고 observer 오류 로그에 원문 예외·provider ID를 노출하지 않는다. 새 credential·secret 표면은 없다. |
| 운영 | P0=0, P1=0, P2=0, P3=0 | 정책과 fallback reason을 호출 결과에 결속하고 `NATIVE_ONLY`는 작업 시작 전에 실패한다. Observer 실패는 안정적인 경고 문자열만 남긴다. |
| API | P0=0, P1=0, P2=0, P3=0 | 기본값이 있는 생성자 인자와 별도 메서드를 추가해 기존 호출을 보존했다. TinkerGraph·Neo4j·Memgraph에서 기존 API와 점수 parity를 검증한다. |
| 사용자 | P0=0, P1=0, P2=0, P3=0 | 양국어 README가 동등하며 현재 native executor가 없다는 사실과 정책 행렬, cancellation 경계를 명시한다. |

## 저장소 위험 점검

- 기존 module 변경이므로 `settings.gradle.kts` 등록과 신규 Kover wiring은 해당하지 않는다.
- `Examples.yml` path filter, smoke/integration test, artifact 목록에 module을 추가했다.
- `actionlint`, README 언어·동등성 검사, stale guard, ecosystem reuse unit 검사를 실행한다.
- Container backend는 Neo4j와 Memgraph `integrationTest`로 검증한다.

## 알려진 범위 제한

외부 GDS/MAGE native executor 실행은 이 consumer에 SDK가 없으므로 검증하지 않는다. 이를
native 실행으로 가장하지 않고 `AUTO=NO_PROVIDER`, `JVM_ONLY=JVM_ONLY_POLICY`,
`NATIVE_ONLY=실행 전 실패`로 문서화했다. 향후 native provider 추가는 별도 Type A 의존성
검토 대상이다.

## 최종 판정

구현 리뷰에서 발견한 cancellation 경합은 회귀 테스트와 함께 수정했다. 수정 뒤 다음 fresh
검증이 모두 통과했다.

- module test: 66 passed
- Neo4j·Memgraph integration test: 94 passed
- repository `detekt`: 108 tasks executed, build successful
- README language/parity: offenders 0, failures 0
- Korean terminology audit: findings 0
- `actionlint`, JSON parse, `git diff --check`: passed
- stale-check: graph abuser guard 포함 전체 passed
- ecosystem reuse checker unit tests: 110 passed
- dependency insight: `bluetape4k-dependencies:2.0.0` constraint에서 graph-core resolved

- P0: 0
- P1: 0
- P2: 0
- P3: 0
