# #537 사전 생성 바우처 풀 설계 명세 리뷰

Date: 2026-07-20

Artifact: `docs/superpowers/specs/2026-07-20-issue-537-pre-generated-voucher-pool-design.md`

Final reviewed head: `f1bd200f8b36486e02df3fcdb47ca01ffc60f2cc`

Scope: 설계 명세만 검토했으며 구현 코드는 시작하지 않았다.

## 결론

- 승인된 Type A 설계 명세를 performance, stability, security, operator/Ops, developer/API,
  user/caller의 여섯 독립 관점으로 반복 검토했다.
- 최종 동일 HEAD `f1bd200f`에서 모든 관점이 `P0=0, P1=0`으로 수렴했다.
- PostgreSQL authority, one-time reveal, tenant-wide code non-reuse, durable idempotency fence,
  campaign/batch lifecycle, bounded worker recovery와 운영 복구 계약이 구현 계획을 작성할 수 있는
  수준으로 고정됐다.
- 다음 단계는 별도 구현 계획 작성과 여섯 관점 계획 리뷰다. 이 문서는 구현 승인을 대신하지 않는다.

## 반복 검토 결과

표의 숫자는 각 라운드에서 보고된 P1 수다. 모든 라운드의 P0는 0건이었다.

| 관점 | `8e62d0e7` 초안 | `6a131650` 1차 보정 | `ed7057a5` 2차 보정 | `55d4d6aa` 3차 보정 | `f1bd200f` 최종 |
|---|---:|---:|---:|---:|---:|
| Performance | 6 | 0 | 2 | 1 | 0 |
| Stability | 7 | 5 | 1 | 0 | 0 |
| Security | 4 | 2 | 1 | 0 | 0 |
| Operator/Ops | 7 | 4 | 3 | 1 | 0 |
| Developer/API | 7 | 6 | 2 | 1 | 0 |
| User/Caller | 5 | 2 | 0 | 0 | 0 |

초기 라운드의 P2/P3 중 설계 안전성, 테스트 가능성, 운영 진단과 직접 관련된 항목은 후속 보정에
통합했다. 구현 파일·명령·fixture 배치처럼 계획 단계에서 확정해야 하는 세부 사항은 다음 구현 계획의
traceability와 검증 명령에 배정한다.

## 주요 발견과 반영

### Authority와 경계

- PostgreSQL만 entry ownership, state transition, idempotency effect와 worker claim의 correctness
  authority로 유지했다.
- Redis, Bloom filter와 leader election은 admission/힌트/중복 실행 감소 역할로 제한하고 장애 시에도
  PostgreSQL invariant를 우회하지 않도록 했다.
- 별도 `commerce/pre-generated-voucher-pool` 모듈로 분리하고 #534의 구현을 복사하지 않고 계약과
  검증 패턴만 재사용하도록 했다.

### Code 보안과 재사용 금지

- 공개 전 code는 per-entry AES-GCM envelope encryption, 공개 후 digest-only로 보관한다.
- stable dedup은 `fixed purpose + tenant + canonical code`로 고정해 campaign, batch, operation과 key
  rotation을 넘어 tenant 전체에서 code 재사용을 차단한다.
- verification, user identity, command tombstone, Redis/Bloom, audit digest의 purpose와 key lifetime을
  분리했다.
- raw code는 최초 reveal 응답 외의 DB, descriptor, audit, log와 metric label에 포함하지 않는다.

### Idempotency와 retention

- terminal business effect, safe response descriptor와 minimal command tombstone을 같은 transaction에서
  기록하도록 했다.
- 24시간 descriptor purge 뒤에도 같은 key가 새 effect를 만들지 않고
  `410 REPLAY_WINDOW_EXPIRED`와 기존 effect ID로 닫히도록 했다.
- command-tombstone digest key/version을 tenant-lifetime replay fence와 backup/restore recovery unit에
  포함하고, tombstone이 존재하는 동안 key retirement를 금지했다.

### Campaign과 concurrency

- campaign은 `DRAFT -> ACTIVE <-> PAUSED -> REVOKING -> REVOKED` lifecycle과 create, policy,
  activate, pause/resume, revoke-preview/revoke 계약을 가진다.
- foreground command는 campaign과 batch를 호환 가능한 `FOR SHARE`로 보호한다. policy와 pause/revoke
  전이는 exclusive row update로 commit 순서를 정해 동일 campaign foreground 트래픽을 불필요하게
  직렬화하지 않는다.
- 전역 순서는 `campaign -> batch -> user-limit -> reservation -> entry -> audit/inbox`다.
- worker는 candidate ID를 lock 없이 bounded 조회한 뒤 canonical order로 다시 잠그고 마지막 entry에서
  `SKIP LOCKED` 또는 revision CAS를 수행한다. entry를 먼저 잠근 채 user-limit을 갱신하는 역순 경로는
  금지했다.

### 사용자와 운영 계약

- customer는 reserve, allocate, explicit one-time reveal, redeem/release와 reveal-loss replacement를
  안정적인 status/error vocabulary로 수행한다.
- operator는 import/generation checkpoint, campaign/batch lifecycle, revoke impact preview, progress,
  reconciliation, stuck reservation과 tenant-scoped diagnostics를 조회한다.
- campaign revoke는 `REVOKING`을 먼저 commit해 새 allocation/reveal을 차단하고 bounded worker가
  progress와 recovery evidence를 남긴 뒤 terminalize한다.
- key 누락, ciphertext quarantine, pool exhaustion, worker stall, Redis degradation, purge lag와 restore
  failure의 alert/recovery signal과 runbook 경계를 명세했다.

## 최종 관점 판정

| 관점 | 최종 판정 | 최종 확인 범위 |
|---|---|---|
| Performance | PASS | shared foreground guard, exclusive policy transition, bounded chunk/CAS, contention stress |
| Stability | PASS | campaign revoke race, canonical worker lock order, restart/lease/recovery, exact counter delta |
| Security | PASS | tenant-wide dedup, purpose-separated keys, tombstone replay fence, one-time raw-code exposure |
| Operator/Ops | PASS | key inventory/restore smoke, revoke preview/progress, diagnostics, alert와 runbook |
| Developer/API | PASS | campaign state/routes/errors, lock implementability, physical schema와 testability |
| User/Caller | PASS | bootstrap/revoke UX, explicit reveal/replacement, retry와 terminal error semantics |

Main-session integration review는 관점 간 중복을 제거하고 다음 모순이 남지 않았음을 확인했다.

- stable dedup scope와 tenant-wide non-reuse 선언이 일치한다.
- campaign lifecycle, route matrix, error vocabulary와 foreground eligibility가 일치한다.
- foreground와 worker의 lock mode/order가 동시에 구현 가능하다.
- descriptor purge, tombstone lookup, key retention과 backup/restore가 하나의 replay-safety chain을 이룬다.
- acceptance criteria와 PostgreSQL integration test 목록이 위 계약을 직접 검증한다.

## 다음 게이트

구현 전에 `docs/superpowers/plans/2026-07-20-issue-537-pre-generated-voucher-pool-plan.md`를 작성한다.
계획은 각 acceptance criterion을 정확한 파일, TDD RED/GREEN 순서, PostgreSQL concurrency/restore
command, module/workflow/stale-check 등록과 rollback 지점에 연결해야 한다. 작성된 계획도 같은 여섯
관점에서 `P0=0, P1=0`으로 수렴한 뒤에만 구현을 시작한다.
