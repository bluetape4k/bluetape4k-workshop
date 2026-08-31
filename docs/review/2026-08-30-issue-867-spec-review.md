# Issue #867 설계 spec 통합 리뷰

검토 대상은 `docs/superpowers/specs/2026-08-30-issue-867-leader-audit-export-design.md`의
현재 작업본이다. 기준은 `origin/develop`과 Issue #867의 live acceptance이며,
upstream `bluetape4k-leader`의 public audit API와 현재
`leader/job-safety-lab` 구현을 함께 대조했다.

## 독립 관점 결과

| 관점 | 실행 방식 | P0 | P1 | P2 | P3 | 판정 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| performance | main fallback, latest aggregate/queue/store read-back | 0 | 0 | 0 | 0 | PASS |
| stability | native latest rerun | 0 | 0 | 0 | 0 | PASS |
| security | main fallback, latest trust/redaction/log ownership read-back | 0 | 0 | 0 | 0 | PASS |
| operator/Ops | native latest rerun | 0 | 0 | 0 | 0 | PASS |
| developer/API | native latest rerun | 0 | 0 | 0 | 0 | PASS |
| user/caller | child-slot 한도로 native lane을 만들 수 없어 main fallback | 0 | 0 | 0 | 0 | PASS |

무응답 lane은 각각 30초 bounded wait를 세 번 적용한 뒤 중단했다. main
fallback은 동일한 artifact·issue·upstream source를 읽고 수행했으며, lane
누락을 PASS로 간주하지 않고 이 문서에 실행 경계를 남긴다.

## 발견 사항과 반영한 수정

| 우선순위 | 관점 | 근거 | 수정 |
| --- | --- | --- | --- |
| P2 | performance | 이전 spec의 MEMORY deque 설명이 sentinel/status/event view 보관과 serialized payload 보관을 동시에 주장했다. | deque가 serialized JSON payload `ByteArray`만 보관하고 요청 시 transient decode한다는 단일 계약으로 통일했다. |
| P1 | security | aggregate byte 식이 upstream 최대 queue/payload 조합에서 32-bit overflow로 guard를 우회할 수 있었다. | 모든 중간 계산을 `Long`으로 하고 `Math.addExact`/`Math.multiplyExact` checked arithmetic를 적용하며 executor/queue/client 생성 전에 fail closed하도록 고정했다. upstream maximum/overflow 경계 테스트를 요구했다. |
| P2 | security | upstream `LeaderAuditPendingContextStore`가 raw `lockName`/`nodeId`/`slotId`를 entry/TTL로만 제한하고 byte budget에는 포함하지 않는다. | 해당 raw identity가 aggregate byte budget 밖인 upstream 경계임을 명시하고 report/payload/metric/log에 전달하지 않는 회귀 테스트와 no-heap-byte-bound 고지를 추가했다. |

위 세 항목은 현재 spec에 반영되었고, 영향을 받은 performance/security lane을
최종 재실행했다. 최종 결과는 각각 P0=0/P1=0이다.

## 후속 보강 및 최종 재검토

초기 plan review에서 발견한 lifecycle·watchdog·parity·log 경계 문제를 다음처럼
수정한 뒤 최신 artifact를 다시 읽었다.

- `JobSafetyAuditShutdownCoordinator`가 Spring context close의 유일한 destroy owner가
  되어 하나의 monotonic deadline과 단계별 `remaining(deadline)`을 전달한다.
- runtime fixture는 close 전 참조를 캡처하고 `shutdownTimeout=500ms`와 outer
  `assertTimeoutPreemptively=3s`를 분리하며, scheduler schedule→cancel 후 queue empty,
  executor/scheduler/client termination과 scope cancellation을 확인한다.
- MEMORY/HTTPS는 동일한 fixed event와 `RecordingLeaderAuditPayloadEncoder`를 사용하고,
  `report.transport` 및 HTTPS fake request-count를 직접 비교한다. fake helper에도
  bounded timeout이 있다.
- upstream `MicrometerNames`가 `internal`임을 반영해 local meter catalog를 사용하고,
  upstream `SafeLeaderHistoryRecorder` warning은 workshop local raw-log 보장 범위에서
  제외한다.

후속 native review 결과는 stability/API/Ops 모두 P0=0/P1=0/P2=0/P3=0이었다. performance,
security, user/caller는 main fallback으로 동일한 최신 spec/plan/source를 독립 대조했고
P0/P1/P2/P3=0으로 판정했다. 구현·Gradle·CI는 plan 이후 단계이므로 이 문서의 범위에
포함하지 않는다.

## 관점별 통합 확인

- **stability:** `ExportingLeaderHistorySink`의 delegate-first 및 `finally` pending
  정리, exporter의 retry/timeout/cancellation/close, caller-owned executor와
  scheduler의 종료 순서가 명시되어 있다. submit admission만 non-blocking이고
  recorder의 제한된 동기 sanitization 비용은 별도로 고지한다.
- **operator/Ops:** 기본 MEMORY 경로는 DNS/socket/credential 없이 실행되고,
  HTTPS는 명시적 opt-in이다. operator-only report, fixed low-cardinality meter,
  Actuator `show-values=never`, rollback/rerun 순서가 있어 운영 경계를 재현할 수
  있다. 외부 audit system과 DNS rebinding 정책을 자동 보장한다고 주장하지 않는다.
- **developer/API:** versionless catalog alias와 root dependencies BOM만 사용하고,
  `LettuceLeaderElector` 기존 호출 호환성을 유지한다. 새 adapter는 upstream
  public `HttpLeaderAuditExporter`/`MicrometerLeaderAuditExporter`와 Jackson
  alias를 사용하며 internal bounded exporter를 복제하지 않는다.
- **user/caller:** default MEMORY와 explicit HTTPS 설정 차이, payload redaction,
  bounded history report, operator 권한, unsupported exactly-once/DB authority
  replacement 범위가 예제·README 계획과 함께 드러난다. misuse 시 startup fail
  closed와 queue/drop 상태를 report/snapshot으로 확인할 수 있다.

## 통합 판정

Issue acceptance, local AGENTS, upstream public API, compatibility, failure
modes, security boundary, docs/rollback evidence를 중복 제거해 대조했다. 현재
P0=0, P1=0, P2=0, P3=0이며 plan 작성으로 진행할 수 있다.

## SPW writer gate

- **SPW-01:** artifact=spec integrated review, 독자=contributor/operator, 목적=설계의
  여섯 관점 수렴과 다음 plan 진입 판단, 근거=Issue #867·현재 spec·local/upstream
  source, 미확정=upstream raw identity의 heap byte bound는 이 범위 밖이다.
- **SPW-02:** lane 표, finding/repair 표, 관점별 검증, 통합 판정과 실행 경계를
  포함했다.
- **SPW-03:** 한국어 기술 문체로 작성하고 API·path·command·URL·metric·priority
  토큰은 원문을 보존했다. 번역투나 승인되지 않은 홍보 문장은 없다.
- **SPW-04:** upstream public exporter, payload, endpoint, pending context 계약과
  local job-safety wiring을 대조한 뒤 각 finding에 근거를 연결했다.
- **SPW-05:** Markdown heading/table/code fence와 P0/P1 수치를 read-back했고,
  placeholder와 contradiction을 제거했다. `git diff --check`와 placeholder
  scan을 통과했다.

**결론: PASS (P0=0, P1=0).**
