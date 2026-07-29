# Issue #553 Event-Sourced Usage Billing 구현 리뷰

## 범위와 판정

- 기준: issue #553, 승인된 설계/구현 계획, `develop` 기준 전체 변경
- 방식: subagent 없이 inline 6관점 리뷰
- 판정: P0 0건, 미해결 P1 0건
- merge 조건: local 전체 Gradle/정적/문서/diagram 검증은 통과했다. exact-head CI와 review thread가 통과해야 한다.

## 수정한 주요 finding

| 심각도 | 관점 | Finding | 수정 |
|---|---|---|---|
| P1 | 데이터 일관성 | ACTIVE projector가 batch 일부만 처리해도 high watermark를 checkpoint로 축소해 lag를 0으로 표시 | append head를 별도로 관측하고 `max(previous, observedHead, checkpoint)`로 유지, HTTP 통합 테스트에서 lag 1 검증 |
| P1 | 감사 추적 | 저장 metadata가 항상 `{}`라 command/correlation/causation/actor 계약을 충족하지 않음 | bounded `EventMetadata`와 command receipt/actor 전파를 추가하고 저장 JSON을 통합 테스트로 검증 |
| P1 | 시간 권위 | `recordedAt`을 application clock으로 기록해 설계의 PostgreSQL 기록 시각 계약과 불일치 | Exposed `CurrentTimestamp` default를 사용하고 고정 application clock과 다른 DB 시각을 PostgreSQL 테스트로 검증 |
| P1 | 운영 복구 | admin API가 ACTIVE 상태 조회만 제공하고 rebuild 생성 이후 실제 catch-up/switch runtime이 없음 | 멱등 rebuild 시작, generation/quarantine/reconciliation 조회, ACTIVE/BUILDING fenced scheduler, fresh high-watermark switch를 추가 |
| P1 | 관측 가능성 | replay/snapshot/rebuild/close/reconciliation metric method가 런타임 호출 경로에 연결되지 않음 | port interface로 계층 역참조 없이 연결하고 Spring 통합 테스트에서 append/replay/projection/rebuild metric을 검증 |
| P1 | 보안 | `IllegalStateException` message를 그대로 응답해 stream/event 식별자가 노출될 수 있음 | colon 뒤 context를 제거하고 allowlist 형식의 stable error code만 반환 |
| P1 | 입력 경계 | allowlisted event type이어도 serialized payload byte 상한이 없음 | payload 64 KiB, metadata 4 KiB UTF-8 상한을 codec 경계에서 검사하고 회귀 테스트 추가 |
| P1 | 운영 정보 | public health와 operator metric endpoint의 공개 범위가 불명확 | health status는 public, detail과 `/actuator/metrics/**`는 `ROLE_OPERATOR`로 제한하고 통합 테스트 추가 |
| P1 | PNG diagram | architecture의 projection 진입/이탈 connector가 같은 하단 통로에 과도하게 붙고, correction의 multi-segment marker 화살촉이 SVG/PNG에서 방향 drift 가능 | connector 포트를 분리한 rounded-corner route로 재배치하고, correction의 세 화살촉을 선 endpoint에 고정한 direct polygon으로 교체; 방향·색·dash·marker override를 QA에서 검증 |
| P1 | Diagram 생성 계약 | 위 correction만 direct polygon으로 바꿔도 나머지 generated edge가 `marker orient=auto`를 쓰면 같은 SVG/PNG 방향 drift와 renderer 문자 대체가 재발 | 생성기의 모든 connector 54개를 endpoint/최종 tangent 기반 direct polygon으로 통일하고, eight-asset QA가 direct head·terminal clearance·renderer-safe ASCII를 전수 검증하도록 확장 |

## PNG authoritative 전수 재검수

- 최초 diagram PASS 판정은 일부 자산만 full-size PNG에서 connector 통로 분리와 cross-renderer arrowhead parity를 입증했으므로 철회한다. 개별 correction이 아니라 생성 계약 전체를 수정했다.
- architecture는 event store → projection route를 projection 좌측 포트로 종료하고, projection → read model은 별도 하단 좌측 포트와 독립 corridor로 분리했다. connector audit은 `crossings=0`, `shared_segments=0`, `q_bends=8`, `failures=0`이다.
- generated SVG 여덟 개의 모든 connector는 CSS `marker-end`를 명시적으로 끄고 endpoint-tip direct polygon을 사용한다. QA는 connector endpoint와 tip의 일치, 최종 선분 방향, semantic stroke 색, dash 차단을 모두 검사한다.
- generator 재생성, SVG XML parse, CairoSVG PNG render, text normalize, geometry/endpoint/connector/mixed-corner audit, QA wrapper, full-size PNG inspection을 여덟 자산 각각에 다시 수행했다. direct-head audit 합계는 `heads=54 failures=0`이고 connector audit은 모든 자산에서 `intrusions=0 crossings=0 shared_segments=0`이다.
- terminal clearance도 전수 검사했다. architecture의 `store-projection`과 microservices의 `kafka-projection`은 마지막 직선 구간을 각각 `20px`로 조정해 최소 `16px` 요구를 충족한다.
- CairoSVG가 `→`, `≥`, `•`, `±` 같은 glyph을 대체 문자로 렌더링할 수 있는 것을 확인했다. generator가 SVG 출력 단계에서 `->`, `>=`, `;`, `+/-`로 정규화하므로 SVG와 PNG는 동일한 renderer-safe ASCII 문자 집합을 사용한다.

## 6관점 결과

### 1. Architecture

- command, event store, replay, projection, operator runtime 경계가 분리돼 있다.
- application/projection은 `config.EventSourcingMetrics` 구현체가 아니라 telemetry port에 의존한다.
- 모든 concrete persistence repository는 `ExposedJdbcRepository` 계열을 구현한다.
- raw SQL, `JdbcTemplate`, `java.sql.*`, `Transaction.exec` 의존은 없다.

### 2. Data and consistency

- stream head lock, expected-version append, canonical hash chain, global-position keyset가 PostgreSQL authority 아래 있다.
- `recordedAt`은 PostgreSQL이 기록하고 `occurredAt`만 business event time으로 받는다.
- projection checkpoint, high watermark, generation alias switch는 fencing token과 transaction 경계로 보호된다.
- rebuild generation 생성은 alias row lock으로 직렬화되며 동시에 두 BUILDING generation을 허용하지 않는다.

### 3. Security and tenant isolation

- tenant API는 authority와 principal/path tenant를 함께 검증한다.
- operator API, detailed health, metrics는 `ROLE_OPERATOR` 경계 안에 있다.
- event metadata와 HTTP error는 credential/request body/internal identifier를 노출하지 않는다.
- reconciliation의 tenant 범위는 operator가 명시적으로 선택하며 일반 tenant principal은 접근할 수 없다.

### 4. Failure and recovery

- duplicate projection delivery는 applied-event marker로 억제한다.
- poison event는 failed position/digest/attempt를 남기고 해당 generation을 `FAILED`로 만든다.
- BUILDING failure는 ACTIVE query generation을 바꾸지 않는다.
- scheduler는 fresh event-store head까지 따라잡은 경우에만 BUILDING을 ACTIVE로 전환한다.
- snapshot invalidation은 원본을 수정하지 않고 genesis replay로 fallback하며 metric을 남긴다.

### 5. Tests and operations

- reducer/hash/upcast/replay는 unit test, concurrency/fencing/snapshot/projection/HTTP는 PostgreSQL 통합 테스트로 분리됐다.
- 10,000 usage stress test는 시간 SLA가 아니라 exactly-one financial effect와 restart/rebuild correctness를 검증한다.
- operator rebuild는 생성, 멱등 replay, concurrent rebuild 거부, scheduler ACTIVE switch를 한 통합 시나리오로 검증한다.
- fresh full run에서 unit 19건, PostgreSQL integration 35건, 10,000 usage stress 1건이 실패 없이 통과했다.
- Kover XML, `build`, `detekt`, `detektTest`, baseline ledger test를 포함한 30개 Gradle task가 `--rerun-tasks`로 모두 실행됐다.
- module README validator, stale-check, actionlint, generator/shell syntax, 8개 diagram QA와 `git diff --check`가 통과했다. 이후 PNG diagram 재검수에서 발견된 두 결함은 위의 별도 재검수로 보완했다.
- repo-wide README parity validator의 기존 3개 실패 모듈은 `origin/develop`과 동일하고 이번 branch가 수정하지 않았다. 대상 모듈 전용 locale/heading/image/contract 검사는 통과했다.

### 6. Documentation and adoption

- 영어/한국어 README가 baseline 선택 기준, state/sequence/rebuild diagram, 운영 API, metric/health 보안 경계와 일치한다.
- microservice 추출은 shared DB/XA가 아니라 local transaction + outbox/inbox + consumer dedup 방향으로 안내한다.
- 범용 fencing lease 후속 기능은 `bluetape4k-projects#1070`, microservice 심화는 workshop #555로 분리돼 있다.

## 남은 non-blocking trade-off

- Basic Auth와 Exposed schema bootstrap은 local demonstration 범위다. 실제 배포에서는 조직의 OAuth2/JWT와 별도 schema delivery 절차가 필요하다.
- snapshot 생성 threshold/retention은 저장/replay contract로 분리돼 있으며 workload별 자동 생성 정책은 운영자가 결정해야 한다.
- scheduler batch/delay와 connection pool은 예제 기본값이므로 production traffic과 lag SLO에 맞춰 부하 시험 후 조정해야 한다.
