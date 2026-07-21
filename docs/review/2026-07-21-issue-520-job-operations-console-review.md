# #520 Job Operations Console 검토

Date: 2026-07-21
Modules: `:operations-job-console-core`, `:operations-job-console-spring`, `:operations-job-console-ktor`
Branch: `feature/issue-520-job-operations-console`
Reviewed head: `826eaec4`

## 결론

- 최종 exact-head 검토 결과 P0 0건, P1 0건이다.
- PostgreSQL이 제출, tenant FIFO, lease, checkpoint, 취소, 상태 이력, outbox의 유일한 권위다.
- Redis와 SSE는 유실 가능한 알림 경로이며, Redis 장애와 SSE 누락 후에도 REST snapshot으로 수렴한다.
- Spring MVC와 Ktor는 같은 core DTO, browser UI, live HTTP/SSE fixture를 사용한다.
- Java 25 toolchain은 신규 세 모듈에만 적용되고 저장소 기본 Java 21 설정은 유지된다.

## 검토 관점별 결과

| 관점 | 결과 | 핵심 근거 |
|---|---|---|
| Performance | PASS | tenant-local claim index, 최대 100행 cursor page, ETA 표본 100개 제한, bounded SSE fan-out |
| Stability | PASS | PostgreSQL server-time lease, checkpoint 갱신, stale lease write fencing, worker-owned lifecycle, outbox/worker 실행 분리 |
| Security | PASS | demo profile opt-in, tenant/submitter scope 검증, operator fail-closed header, redacted DTO와 low-cardinality tag 제한 |
| Operator/Ops | PASS | PostgreSQL-authoritative readiness, Redis `DEGRADED`, `/healthz`, `/readyz`, stale-module/README 검증 등록 |
| Developer/API | PASS | 공통 wire DTO와 공통 live fixture, idempotent replay, stable problem code, REST snapshot source-of-truth |
| User/Caller | PASS | 공통 browser UI에서 queue position, jobs ahead, ETA confidence/sample, progress/checkpoint, durable cancel acknowledgement 표시 |
| Architecture/Parity | PASS | core와 두 adapter 분리, Spring virtual-thread worker, Ktor application-owned coroutine, 동일 상태도와 API 경로 |

## 초기 차단 finding과 해소

| 초기 finding | 해소 내용 |
|---|---|
| adapter가 worker를 실제로 실행하지 않음 | Spring scheduler + virtual-thread executor, Ktor IO worker coroutine을 adapter lifecycle에 연결 |
| 만료된 `cancel_requested`가 다시 `running`으로 회복될 수 있음 | reclaim 시 PostgreSQL에서 즉시 `cancelled`로 수렴 |
| 만료 lease가 write fence가 아니고 checkpoint가 lease를 갱신하지 않음 | 모든 checkpoint/terminal write에 current token, version, server-time expiry 조건 적용 |
| Spring/Ktor 계약 및 SSE 경로 drift | 공통 `JobConsoleV1LiveContract`와 `/v1/jobs/{jobId}/events`로 고정 |
| 101개 이상 queue에서 snapshot 실패 | DB count 기반 position과 최대 100행 public page 분리 |
| cursor가 DB query까지 전달되지 않음 | cursor sequence를 repository query에 전달하고 one-row lookahead pagination 검증 |
| Ktor worker가 outbox publication을 직렬로 막음 | worker와 outbox를 독립 coroutine loop로 분리 |
| `queue.updated`/heartbeat가 선언만 되고 발행되지 않음 | transition마다 두 outbox event 발행, 연결 heartbeat와 Ktor 주기 heartbeat 추가 |
| UI가 placeholder만 표시함 | 실제 submit, REST refresh, header 기반 SSE stream, cancel 동작을 공통 HTML에 구현 |
| 성공 duration이 기록되지 않아 ETA가 영구적으로 표본 부족 | 성공 terminal commit 후 bounded duration sample 기록 |

두 외부 read-only 검토 lane은 최신 SHA의 line evidence까지 재확인했지만 watchdog 제한 안에 최종 verdict를 반환하지 못해 중단했다. 그 전 단계에서 보고된 P1 finding은 위 표와 같이 모두 수정했고, 최종 SHA는 리더가 전체 diff와 회귀 테스트로 재검토했다.

## 검증 결과

- 여섯 test/integrationTest task `--rerun-tasks --max-workers=1`: PASS, 41 tests, failures 0, errors 0
- `./gradlew detekt build -x test --rerun-tasks --continue`: PASS, 599 tasks
- `./scripts/smoke-validate.sh operations`: PASS
- `./scripts/smoke-validate.sh stale-check`: PASS, stale refs 0, broken README image links 0
- `node --check` on extracted shared UI script: PASS
- README architecture/sequence/state diagram QA: PASS, targets 3
- state diagram: 7 states, 8 transitions, terminal zone, terminal outgoing transition 0
- `git diff --check`: PASS

## 비차단 잔여 위험

- browser UI JavaScript는 syntax와 live-served byte 계약을 검증했지만 실제 graphical browser 자동화는 수행하지 않았다.
- Spring SSE는 연결 시 heartbeat를 보내고 Ktor는 10초 주기 heartbeat도 보낸다. 두 adapter 모두 durable event와 REST snapshot 계약은 동일하지만 heartbeat 주기는 wire invariant가 아니다.
- exact `jobsAhead` count 비용의 backlog별 용량 수치는 후속 load-profile 이슈 #522 범위다.
