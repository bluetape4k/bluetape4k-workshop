# #888 native graph algorithm 실행 관찰 설계 review

## 검토 범위

- [설계 문서](2026-09-05-issue-888-native-algorithm-observation-design.md)
- Workshop Issue #888
- `graph/abuser-detection` blocking/suspend PageRank 경로
- `bluetape4k-graph 2.0.0`의 provider selector, execution observer와
  Neo4j/Memgraph observable 구현

## 독립 관점 findings

| 우선순위 | 관점 | 근거 | 조치 | 상태 |
|---|---|---|---|---|
| P1 | 안정성 | backend `lastAlgorithmExecution`은 호출 ID가 없는 공유 `@Volatile` 값이다. | 서비스가 mutable observable을 읽지 않고 호출별 selector 결과를 반환하도록 변경했다. | 해결 |
| P1 | 안정성·운영 | Neo4j/Memgraph PageRank는 caller policy를 받지 않아 `JVM_ONLY`도 `NO_PROVIDER`로 기록한다. | 호출자 policy를 서비스 selector의 authoritative execution으로 고정했다. | 해결 |
| P2 | 성능 | 새 API가 PageRank를 중복 실행하거나 `limit`을 `topK`로 전달하지 않을 위험이 있다. | recording fake의 호출 횟수 1회와 `topK` 전달 테스트를 설계에 추가했다. | 해결 |
| P2 | coroutine 안정성 | suspend API의 cancellation과 observer 호출 순서가 없었다. | 취소 시 observer 미호출과 cancellation 전파 테스트를 추가했다. | 해결 |
| P2 | 보안 | upstream provider ID는 non-blank만 검사해 로그·메트릭 경계가 bounded하지 않다. | 공개 projection은 64자와 안전 문자 집합으로 제한하고 raw execution을 저장하지 않는다. | 해결 |
| P2 | 운영 | `NATIVE_ONLY`가 실제 PageRank를 실행한 뒤 실패하면 비용과 오해가 남는다. | selector를 PageRank보다 먼저 실행하고 zero-call을 검증한다. | 해결 |

## Developer/API·User/Caller 통합 검토

- 기존 `rankSuspiciousUsers(limit)` 반환 타입과 cold Flow 계약은 유지한다.
- 실행 메타데이터는 점수 행이 아니라 한 PageRank 호출에 한 번만 붙인다.
- 현재 2.0.0에는 native executor가 없으므로 descriptor-only native 성공을 예제로
  만들지 않는다. `NATIVE_ONLY`는 명시적으로 unsupported다.
- 새 model과 두 opt-in API는 기존 backend 생성 방식이나 consumer 의존성을 바꾸지
  않는다.
- README는 `AUTO` fallback과 `JVM_ONLY`의 의미, `NATIVE_ONLY` 실패, native SDK가
  포함되지 않는다는 사실을 같은 수준으로 설명해야 한다.

## 통합 판정

- P0: 0
- P1: 0
- P2: 0
- P3: 0
- 판정: **PASS**

성능 수치나 native 처리량을 주장하지 않으므로 benchmark는 N/A다. 구현 계획은
single-call, concurrency attribution, cancellation, sanitization과 기존 결과 parity를
모두 실행 가능한 테스트로 매핑해야 한다.
