# Issue #867 구현 plan 통합 리뷰

검토 대상은 `docs/superpowers/plans/2026-08-30-issue-867-leader-audit-export-plan.md`와
연결된 `docs/superpowers/specs/2026-08-30-issue-867-leader-audit-export-design.md`다.
기준은 live Issue #867, `origin/develop`, upstream `bluetape4k-leader` PR #792의
public API다. 이 문서는 구현 전 readiness gate이며 코드 테스트 통과를 주장하지 않는다.

## 여섯 관점 결과

| 관점 | 실행 방식 | P0 | P1 | P2 | P3 | 최종 판정 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| performance | main fallback, aggregate/queue/store 경계 read-back | 0 | 0 | 0 | 0 | PASS |
| stability | native rerun | 0 | 0 | 0 | 0 | PASS |
| security | main fallback, trust/redaction/log ownership read-back | 0 | 0 | 0 | 0 | PASS |
| operator/Ops | native rerun | 0 | 0 | 0 | 0 | PASS |
| developer/API | native rerun | 0 | 0 | 0 | 0 | PASS |
| user/caller | main fallback, endpoint/default/docs contract read-back | 0 | 0 | 0 | 0 | PASS |

native lane은 최신 plan/spec 수정본을 다시 읽었다. child slot이 제한된 관점은 같은
artifact와 upstream source를 main이 독립적으로 대조했다. 모든 관점에서 P0/P1은
0이며, 구현 전 남은 검증은 아래 implementation gate로 분리한다.

## 발견 사항과 보강

| 우선순위 | 발견 | 반영한 보강 |
| --- | --- | --- |
| P1 | Spring `@DependsOn`만으로는 context close 시 공유 `remaining(deadline)`을 실제 전달할 수 없음 | `JobSafetyAuditShutdownCoordinator`를 파일 지도·bean 표·Task 5에 추가하고 유일한 destroy owner로 고정했다. context close 시작 시 `System.nanoTime()` 기반 deadline을 한 번 계산하고 client/scheduler/executor에 남은 시간을 전달한다. |
| P1 | closed context에서 scope를 다시 `getBean`하면 assertion이 실행되지 않음 | `ApplicationContextRunner.run { context -> ... }` callback 안에서 close 전 scope/executor/scheduler/client를 캡처하고 close 후 캡처한 참조로 상태를 확인한다. |
| P1 | close가 hang하면 elapsed assertion에 도달하지 않음 | `assertTimeoutPreemptively`로 context close를 감싸고, coordinator의 bounded 단계와 함께 검증한다. |
| P2 | production shutdown budget과 동일한 2초 watchdog는 CI overhead에 취약 | runtime fixture는 `shutdownTimeout=500ms`, outer watchdog는 3초로 분리했다. |
| P2 | queue retry request-count helper와 scheduler cancellation이 무제한/간접 검증 | fake helper 자체에 bounded timeout을 넣고, scheduler를 실제로 schedule→cancel한 뒤 `removeOnCancelPolicy=true`와 empty queue를 assertion한다. |
| P2 | MEMORY/HTTPS parity가 transport 경계와 fake 경로를 직접 입증하지 않음 | 동일한 고정 event를 두 fixture에 전달하고 `report.transport == transport.name`, HTTPS fake bounded request count, retained JSON/byte budget parity를 함께 assertion한다. |
| P2 | upstream `MicrometerNames`가 `internal`이라 consumer import이 불가 | public contract의 12개 unique meter name과 `dropped` outcome 변형을 local private catalog로 고정한다. |
| P2 | upstream `SafeLeaderHistoryRecorder` failure warning과 workshop raw-log 부재 주장이 충돌 | 보장을 workshop audit adapter의 report/payload/metric/local log로 한정하고 upstream core warning은 별도 logging contract로 제외했다. |

## 계약 확인

- `ListeningLeaderElector`/`withListeners()`와 `LeaderElectionEventExportSubscription`의
  public 생성자·publisher wiring이 실제 upstream source와 일치한다.
- `RecordingLeaderAuditPayloadEncoder`가 MEMORY/HTTPS 공통 capture 경계가 되어
  transport 교체와 무관하게 동일한 redacted serialized bytes를 report store에 남긴다.
- `AuditHeaders` equality/hash는 secret 원문이 아니라 authorization 존재 여부만
  비교하며, raw header는 report/metric/adapter local log에 나오지 않는다.
- Java 25 `javap java.net.http.HttpClient`에서 `shutdown`, `shutdownNow`,
  `awaitTermination(Duration)`, `isTerminated`, `close`를 확인했고 raw `close()`를
  Spring destroy method로 직접 호출하지 않는다.
- `bluetape4k-jackson3` alias는 versionless이고 root `bluetape4k-dependencies`
  BOM만 버전 authority로 사용한다.

## SPW writer gate

- **SPW-01:** 대상 독자·목적·근거·미확정 운영 경계를 기록했다.
- **SPW-02:** 여섯 관점 표, finding/repair, contract, implementation gate를 포함했다.
- **SPW-03:** 한국어 기술 문체를 사용하고 API/path/command/URL/metric 토큰은 보존했다.
- **SPW-04:** Issue #867, local source, upstream PR #792 public API를 대조했다.
- **SPW-05:** fences, placeholder, transport parity, aggregate shutdown, log ownership을
  최신 plan/spec와 read-back해 모순을 제거했다.

## 통합 판정

현재 plan은 구현을 시작할 수 있다. readiness 기준은 **PASS (P0=0, P1=0)**다.
아직 수행하지 않은 항목은 Task 1–10의 RED/GREEN, module/integration test, detekt,
README·workflow·stale/lesson 등록, PR/CI live evidence다.
