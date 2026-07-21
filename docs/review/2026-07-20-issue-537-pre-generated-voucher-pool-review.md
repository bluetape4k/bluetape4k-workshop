# Issue #537 사전 생성 바우처 풀 구현 리뷰

## 결론

- 검토 기준: `origin/develop...b2c17ba6`
- 대상: `commerce/pre-generated-voucher-pool`과 등록, runbook, diagram, CI 변경
- 최종 판정: **P0=0, P1=0, APPROVE**
- 로컬 중단 조건은 충족했다. GitHub CI와 현재 PR thread 검증은 PR 생성 뒤의 별도 증거다.

성능, 안정성, 보안, 운영, 개발자/API, 사용자/caller의 여섯 관점과 통합 검토를 수행했다. 초기 검토에서 발견된 모든 P1은 회귀 테스트를 먼저 실패시키고 수정한 뒤 영향 lane을 다시 실행했다.

## P1 수리 추적

| 관점 | 초기 문제 | 테스트 우선 수리와 결과 |
|---|---|---|
| 사용자/API | 응답 유실 뒤 여섯 command가 새 idempotency key를 사용 | reserve, allocate, reveal, replacement, redeem, revoke를 매개변수화한 browser contract가 동일 key와 단일 effect를 검증한다. |
| 사용자/API | malformed `2xx` JSON을 성공으로 오판해 key를 폐기 | `SyntaxError`를 ambiguous outcome으로 전파한다. 여섯 command 모두 같은 key로 재시도하는 RED/GREEN 계약을 추가했다. |
| 사용자/API | retryable non-2xx도 terminal로 취급해 key를 폐기 | payload의 `retryAfterSeconds`와 `Retry-After`를 소비한다. retryable response는 key를 유지하고 확인된 terminal response만 폐기한다. |
| 성능 | configured wait를 실제 wait처럼 보고 | permit lane과 Hikari connection 획득을 `System.nanoTime()` 기반으로 직접 관측하고 sample 수와 최대값을 hard gate에 포함했다. Foreground gate는 shared CI scheduling까지 포함하는 500ms 상한이며 실제 초과값을 진단에 노출한다. Hikari pending peak는 report-only로 두고 acquisition/drain deadline을 gate로 유지한다. |
| 안정성 | lifecycle의 SSE shutdown이 실제 stream/poller를 닫지 않음 | 실제 `VoucherPoolEventStream`이 shutdown contract를 구현하고 executor와 datasource보다 먼저 닫히는 순서를 검증했다. |
| 안정성 | `close()`와 blocking initial snapshot 사이 race로 종료 후 poller 등록 가능 | poller registry lock 안에서 `closed`를 다시 검사한다. blocking initial과 concurrent close 회귀 테스트가 `SERVICE_SHUTTING_DOWN`, subscription 미생성, poller 0을 검증한다. |
| 안정성/운영 | owner release가 DB timeout 한 번에 중단되고 operator timeout이 안전한 API 오류로 변환되지 않음 | owner release를 8회로 제한해 재시도하고, JDBC timeout을 `503 BACKEND_TIMEOUT`으로 변환하는 단위/HTTP 테스트를 추가했다. |
| 보안/복구 | restore가 manifest 누락 key reference를 허용 | 독립 key inventory와 manifest의 category별 완전성을 import 전에 비교하고 누락 시 `INCOMPLETE_MANIFEST`로 fail-closed한다. |
| 보안/운영 | migration과 live referenced-key preflight가 production startup에 연결되지 않음 | migration 후 KEK/digest key reference를 bounded scan하고 readiness를 연다. checksum drift와 missing live key가 startup을 실패시키는 PostgreSQL 테스트를 추가했다. |
| 운영/안정성 | durable worker trigger가 production에 없고 Redis 장애 시 claim이 진행되지 않음 | 250ms dispatcher가 PostgreSQL runnable claim을 찾는다. Redis leader는 advisory이며 미가용 시 PostgreSQL claim fencing으로 계속 진행한다. |

## 여섯 관점 최종 판정

| 관점 | 판정 | 최종 근거 |
|---|---|---|
| Performance | PASS | 실제 permit/Hikari wait sample, 64/128 virtual client와 Redis healthy/unavailable matrix, permit max 15, deadline violation 0 |
| Stability | PASS | PostgreSQL claim fencing, bounded chunk/queue/subscriber, owner release 재시도, 실제 SSE close와 concurrent registration 차단 |
| Security | PASS | mounted key material, live referenced-key fail-closed, raw code/credential redaction, restore category completeness |
| Operator/Ops | PASS | migration→key preflight→readiness, Redis 비가용 fallback, safe 503 mapping, shutdown ownership 순서, runbook/alert 계약 |
| Developer/API | PASS | stable code/status/retry/action catalog, exact idempotency replay semantics, JDK25/Exposed JDBC/Lettuce/Bucket4j/leader resolution |
| User/Caller | PASS | one-time reveal와 replacement 확인, 여섯 command의 ambiguous retry, terminal/retryable 구분, keyboard/ARIA contract |

## 검증 증거

| 검증 | 결과 |
|---|---|
| `:commerce-pre-generated-voucher-pool:test --rerun-tasks --max-workers=1` | PASS, 362 tests |
| `:commerce-pre-generated-voucher-pool:migrationCompatibilityTest --rerun-tasks --max-workers=1` | PASS, 3 tests |
| `:commerce-pre-generated-voucher-pool:stressTest -PvoucherPoolStressRun=task15-final-exact-head --rerun-tasks --max-workers=1` | PASS, 5 tests |
| `:commerce-pre-generated-voucher-pool:detekt :commerce-pre-generated-voucher-pool:detektTest` | PASS |
| browser Node contract / `VoucherPoolBrowserContractTest` | PASS, 7 JUnit tests; six commands의 network loss, malformed 2xx, retryable response를 검증 |
| `:commerce-pre-generated-voucher-pool:koverXmlReport` | PASS, XML 1,058,448 bytes |
| Kover | instruction 89.53%, branch 63.45%, line 92.43%, method 89.29%, class 93.53% |
| post-CI hardening stress evidence | permit max 15, deadline violation 0; foreground max 237/201/204/201ms; worker max 1ms; SSE max 234/73/111/95ms; pending drain 0/11/6/5ms |
| runtime dependency graph | JDK25 provider, Exposed JDBC, Lettuce, Bucket4j, leader present; JDK21 provider absent |
| `./gradlew build -x test --parallel --continue` | PASS, 619 tasks |
| project/stale/runbook/diagram/workflow checks | 108 projects; stale-check, runbook, architecture/sequence, actionlint, `bash -n` PASS |
| Commerce lane | PASS, 네 PostgreSQL-backed commerce module 27 tasks |
| forbidden scan | 운영/테스트 위반 0; compatibility CLI의 의도된 stdout 1건만 별도 source set에 존재 |
| `git diff --check` | PASS |

재정렬 직후 첫 fresh module run은 PostgreSQL Testcontainer가 중간 종료되어 197개 통과 후 94개가 connection-refused initialization failure로 끝났다. assertion failure가 아니었고, 깨끗한 재실행 361/361 및 최종 수리 뒤 362/362가 통과했다. 환경 실패를 성공 증거로 숨기지 않고 원인 분류와 clean rerun을 모두 남겼다.

`validate-readme-parity.mjs`는 이 변경과 무관한 기존 `image-processing/profile-image-moderation/README.md`의 English language switch 누락 1건으로만 실패한다. 새 module 두 locale과 runbook parity는 PASS다.

## 비차단 P2와 후속 guard

- worker runnable scan은 `next_attempt_at` 선두 partial index가 없어 이력이 커질 때 scan 비용을 관찰해야 한다.
- owner release는 8회로 제한되지만 전체 elapsed budget과 backoff는 없다.
- restore inventory는 manifest와 비교되지만 backup payload digest/signature에 직접 결합되지는 않았다.
- dispatcher는 최대 16개 후보를 직렬로 처리하므로 claim별 time budget이나 one-chunk fairness를 검토할 수 있다.
- allocation 완료 후 Allocate 버튼 비활성화, replacement 취소 시 focus/announce 복원, 실제 dialog 재진입 e2e는 UX 보강 후보다.

이 항목들은 현재 correctness, 보안 경계, DoD를 깨지 않으며 PR 차단 조건이 아니다.
