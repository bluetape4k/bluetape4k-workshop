# Issue #525 Field Service 계획 검토

## 검토 범위와 근거

- 대상 계획: `docs/superpowers/plans/2026-08-20-issue-525-field-service.md`
- 기준 설계: `docs/superpowers/specs/2026-08-20-issue-525-field-service-design.md`
- 저장소 기준: `optimization/planning-contracts`의 Gradle/Spring/Exposed/Testcontainers 패턴, `commerce/reservation-control-plane`의 CAS, `operations/job-console-core`의 `EXPLAIN` 검증
- 검토 목적: 구현 전 Type-A Step 3-R에서 설계 수용 기준, 실행 순서, 실패 경로, 문서·CI·운영 증거를 계획에 고정했는지 확인한다.
- 언어·독자: 구현자와 리뷰어를 위한 한국어 기술 문서. 코드, 명령, 식별자, 경로, 상태 코드는 원문 토큰을 유지한다.

## 독립 관점 결과

| 관점 | 방식 | 최신 결과 | 근거와 조치 |
|---|---|---|---|
| Performance | 독립 `test-engineer` lane, 수정 후 재실행 | PASS | Task 3의 `PlannerComplexityContractTest`가 100 worker/500 visit/10,000 cell과 `candidateEvaluations <= V*W`, 외부 호출 0을 검증한다(계획 216–270행). Task 6의 statement 5개·`lock_wait_ms <= 2000`·`EXPLAIN` index/`Seq Scan` 금지와 Task 9의 warmup 2·반복 5·invariant hard gate를 확인했다(계획 341–345, 377–390, 487–513행). |
| Stability | 독립 `verifier` lane, 수정 후 재실행 | PASS | CPU 4개/queue 8, 전역 lock 순서, `lock_timeout=2s`, statement timeout 5초, shutdown submit race, `CancellationException` 전파를 고정했다(계획 341–345행). lease owner/token/expiry, fencing, maxAttempts 5, backoff, `DEAD_LETTER`, stale ack와 취소 후 `RETRYABLE`/`PENDING` 재처리를 명시했다(계획 345, 503행). |
| Security | native lane이 bounded retry 안에 응답하지 않아 main session fallback | PASS | duplicate key·unknown property·body/collection/depth 상한과 raw body 비저장을 고정했다(계획 177–212행). signature/digest에 `MessageDigest.isEqual`, preflight 실패 시 local write와 #524 호출 금지, strict score/reason parser를 명시했다(계획 394–424행). demo loopback/operator header, CSP, `textContent`/DOM API, `innerHTML`/`eval` 금지와 redaction 테스트를 포함한다(계획 445–474행). |
| Operator/Ops | 첫 lane은 잘못된 cwd를 사용했고 수정 retry가 bounded timeout되어 main session fallback | PASS | actuator `health,info,prometheus`, 민감정보 없는 메트릭을 Task 1·5에 고정했다(계획 155–157, 345행). Docker/Colima preflight, 실패 artifact, 순차 Testcontainers, restart/replay artifact, smoke/stale/nightly/Examples 등록과 README runbook을 Task 9–10에 고정했다(계획 487–558행). disposable schema와 branch 단위 rollback, production migration 금지, PR/merge 별도 승인 경계를 명시한다(계획 637–642행). |
| Developer/API | native lane이 bounded timeout되어 main session fallback | PASS | 실제 파일 경로와 기준 구현 경로, Java 25/BOM, no-`project(":optimization-planning-contracts")` 경계를 계획 13–33, 35–118행에 고정했다. Task 1→11 의존성과 각 RED/GREEN 명령, Exposed deprecation/receiver-shadowing 검사, auto-configuration·Kover N/A 근거를 계획 274–326, 628–650행에 기록했다. |
| User/caller | native lane이 bounded timeout되어 main session fallback | PASS | 안정적인 endpoint/error 목록과 operator/idempotency 요구, ETag/CSP/XSS/304/polling 계약을 Task 8에 고정했다(계획 428–474행). 두 locale README에 synthetic-only curl 흐름, unsupported Timefold/provider 경계, Java 25/Docker와 health/metrics/runbook을 기록하도록 Task 10에 명시했다(계획 517–558행). |

native lane timeout은 검토 결함으로 승격하지 않았다. 각 fallback 관점은 동일한 계획·설계·저장소 근거를 main session에서 다시 읽고, 아래 통합 검토에서 P0/P1을 재분류했다.

## Main integration

| 우선순위 | 영역 | 검토 결과 | 처리 |
|---|---|---|---|
| P0 | 경계·권위 | 새 모듈이 domain/persistence/web 상태를 소유하고 #524는 lifecycle adapter로만 남는다. proposal approval과 committed worker route를 분리한다. | 없음. 계획 5–7, 57–72행과 설계 선택 C에 매핑됨. |
| P0 | 동시성·복구 | event digest, set-based CAS, worker-route 전체 rollback, bounded executor, outbox fencing/replay가 각각 별도 Task와 테스트에 연결된다. | 없음. 계획 204, 341–390, 487–513행과 추적성 표 613–626행에 매핑됨. |
| P1 | 명령·HTTP·브라우저 | 명령 → replan → query → approval → dispatch 흐름과 invalid/duplicate/stale/oversized/redaction/CSP/ETag 계약이 모두 targeted test 명령을 가진다. | 없음. 계획 428–474행. |
| P1 | 성능·운영 증거 | max-envelope 계측, SQL/lock/`EXPLAIN` 예산, Docker preflight, benchmark JSON, workflow/nightly/smoke/stale 등록이 계획에 포함된다. | 없음. Performance/Stability 최신 lane PASS. |
| P1 | 계획 순서·실행성 | 각 task의 입력 파일은 이전 task에서 만들며, 계획 review 산출물은 Task 1 전에 생성하도록 별도 gate로 분리했다. | 없음. 계획 120–128, 628–635행. |
| P2 | 실제 provider와 운영 인증 | 실제 Timefold tenant/API, production auth/CSRF, production migration, route quality는 승인 설계의 비목표다. | 구현 README에 demo loopback/operator header가 production auth가 아님을 명시하고, live provider 증거 없이 PASS로 승격하지 않는다. 후속 범위로 보류한다. |
| P2 | wall-clock과 allocation | benchmark는 elapsed/p95/p99/allocation/GC를 환경과 함께 report-only로 기록하며 CI invariant만 hard gate다. | SLO로 해석하지 않는다는 문구와 `UNAVAILABLE` 정책을 계획 501행에 고정했다. |

## Traceability와 writer gate

- 설계 수용 기준 9개를 계획의 Task와 주 검증 증거에 매핑했다(계획 611–626행). 여기에는 hard constraint/pin/version CAS, duplicate event, sick call conflict, stale callback, restart/replay, browser flow/redaction, module/CI 등록이 포함된다.
- `SPW-01`: 대상 독자·언어·Issue/설계/저장소 기준 경로와 보존해야 할 기술 토큰을 이 문서의 범위 절에 기록했다.
- `SPW-02`: 관점별 finding 표, main integration, P0/P1 종료 조건, P2 보류 사유와 구현 Task를 포함했다.
- `SPW-03`: `/Users/debop/.codex/skills/bluetape-writer/references/korean-naturalness-checklist.md`를 적용했고, 기술 토큰은 보존했다.
- `SPW-04`: 승인 설계와 계획의 traceability 표, 정확한 계획 행, 현재 저장소 기준 파일을 대조했다. 계획 수정으로 stability/performance 차단 항목을 반영한 뒤 두 lane을 재실행했다.
- `SPW-05`: 계획 Markdown을 read-back하고 fence 62개가 짝수인지, placeholder scan 결과가 비어 있는지, `git diff --no-index --check`와 terminology audit 결과를 확인한다.

## 최종 판정

| 항목 | 결과 |
|---|---:|
| P0 | 0 |
| P1 | 0 |
| P2 | 2 (설계 비목표·report-only로 보류) |
| P3 | 0 |

**Step 3-R 상태: PASS.** 계획과 이 검토 산출물을 같은 Lore commit에 기록한다. 구현은 사용자가 선택한 실행 방식과 TDD gate가 시작된 뒤에만 진행한다. 이 문서 시점에는 새 모듈 코드, Gradle 실행, Testcontainers 실행, PR, merge를 완료했다고 주장하지 않는다.
