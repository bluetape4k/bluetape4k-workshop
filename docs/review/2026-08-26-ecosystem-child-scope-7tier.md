# Fixed child reviewed marker 전환 7-Tier 검토

## 판정과 범위

이번 Type C 수정은 coordinator의 squash/rebase 뒤 fixed child PR의
`reviewed_implementation_oid`가 더 이상 같은 manifest 값과 일치할 수 없는
재현 가능한 gate 결함을 수리한다. Kotlin 예제의 실행 동작이나
`bluetape4k` API 사용 코드는 변경하지 않고, 다음 네 가지 governance 경계만
수정한다.

- `.github/scripts/check-ecosystem-reuse.py`의 marker 전환 계약과 검증
- `.github/scripts/test_check_ecosystem_reuse.py`의 RED/GREEN 회귀
- `docs/ecosystem-reuse-train.json`의 A1 전환 receipt와 coordinator scope
- 이 문서와 `docs/lessons/2026-08-27-fixed-child-marker-lineage-transition.md`의
  7-Tier·운영 기록

고정 9-track, `reviewed-ancestor`의 선조성, 단일 scope path/ref 경계,
문서-only evidence tail은 그대로 유지한다. marker 완화는 coordinator가
명시한 전환 receipt가 있을 때만 허용한다.

## 재현 증거와 근본 원인

- 최신 `develop` 기준 commit: `96a7eb829fb0cc625a3080553d9811a7b4df4dea`
- 실패한 hosted run: `33001959078` (A1 #812 exact head 당시)
- 실패한 scope: `A1`, `P0`
- 실패 메시지: `execution scope changed without a fresh coordinator receipt`,
  `state transition READY -> PLANNED is not allowed`,
  `PR changed paths must map to exactly one manifest track (found 0)`
- 이전 marker 관계에서 관찰된 추가 오류: `reviewed_implementation_oid must be
  an ancestor of the PR head`, `reviewed_implementation_oid must descend from
  the PR base`

실패한 A1 branch는 coordinator 전환 전의 `PLANNED` manifest와 이전 base/ref를
되돌려 놓았고, 이를 최신 trusted manifest와 비교하면서 실제 execution scope도
변경했다. manifest를 최신 기준으로 맞추면 rebase로 새로 만들어진 review
artifact marker가 기존 manifest marker와 달라진다. 즉 source/test 동작의
실패가 아니라, squash/rebase로 implementation OID가 재생성될 때 marker의
기준을 표현할 상태가 없었던 contract 결함이다. stale manifest를 허용하거나
실패한 gate를 PASS로 승격하지 않는다.

## 결정한 전환 계약

1. fixed node의 `reviewed_marker_binding`은 선택 필드이며 생략하면 기존
   동작과 같은 `manifest`로 해석한다. 허용 값은 `manifest`와 `lineage`뿐이다.
2. `manifest` binding은 기존처럼 review artifact marker가 active node의
   `reviewed_implementation_oid`와 정확히 같아야 한다.
3. coordinator가 squash/rebase로 marker를 새 lineage에 붙이는 경우 node를
   `lineage`로 전환하고, top-level `reviewed_marker_transitions[track]`에
   `from`, `to`, `receipt_id`, `checksum`을 모두 기록한다. `to`는 node 값과
   같고 `from`은 trusted manifest의 binding과 같아야 한다.
4. trusted manifest 비교에서 binding 전환은 fresh coordinator receipt를
   요구한다. 이전 transition의 `receipt_id`나 `checksum`을 재사용할 수 없다.
   binding 전환 없이 transition만 바꾸는 것도 거부한다.
5. `lineage`에서도 marker는 반드시 실제 PR base 이후, PR head 이전의
   ancestor여야 하며 `base -> marker -> head`를 확인한다. marker 이후 tail은
   review artifact 하나만 바꿀 수 있으므로 코드 변경·self-reference·비선조
   marker를 우회할 수 없다.
6. bootstrap context는 재현 가능한 초기 상태를 위해 모든 node를
   `manifest` binding으로 제한한다. 기존 fixed node의 state/receipt/OID,
   parent, issue, path, workflow 검증은 변경하지 않는다.

## 현재 manifest와 coordinator receipt

현재 contract branch는 `develop`을 base로 하는
`fix/ecosystem-reuse-marker-transition`이며, coordinator-owned scope는 이
branch와 issue `#822`, `#826`을 함께 기록한다.

- scope receipt: `20260827T033641Z-marker-transition-scope`
- scope checksum:
  `347b99710d7441308ee9d6928dffe0f84c75e96638184c63d18f747089219ce7`
- A1 `reviewed_marker_binding`: `lineage`
- A1 trusted marker: `b3711b30a0c51f78750b3cdf2718692d40af08de`
- A1 transition receipt: `20260827T033641Z-a1-marker-lineage`
- A1 transition checksum:
  `a68977d01c16d85f22db97bfd49f9e7c2468e2e120e1396db2f690e9133626de`

두 checksum은 각각 scope/전환 설명의 canonical 문자열에서 SHA-256으로
계산했다. 이것은 secret이 아니라 manifest transition의 변경·재사용을
탐지하는 receipt 식별자다.

## 7-Tier 점검

| Tier | 판정 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | PASS | #826의 fixed child marker 전환만 다루며 Kotlin source와 예제 동작은 scope 밖으로 유지했다. |
| 2. 계약·자료구조 | PASS | legacy `manifest` default, 명시적 `lineage`, exact transition fields, trusted binding/from/to, fresh receipt를 manifest/checker에 고정했다. |
| 3. 경계·보안 | PASS | 허용 binding, unknown track/field, control character, SHA 형식, receipt 재사용, path/ref/OID/ancestor 경계를 fail-closed로 거부한다. |
| 4. 정확성·상태 | PASS | `base -> marker -> head`와 review-only tail을 두 binding 모두 검증하고, binding 변경을 execution receipt와 혼동하지 않도록 별도 transition contract로 분리했다. |
| 5. 동시성·자원 | N/A | 실행 코드·DB·container·coroutine lifecycle을 변경하지 않는다. 기존 Testcontainers 직렬화와 workflow concurrency 정책을 유지한다. |
| 6. 테스트·운영 | IN PROGRESS | 의도적 RED 4건을 새 계약과 함께 GREEN으로 전환했고, fresh/reused receipt와 rebase lineage 이력을 검증했다. hosted exact-head 검증은 PR 이후 남아 있다. |
| 7. 문서·유지보수 | PASS | 이 문서와 lesson에 원인, 전환 규칙, receipt, 향후 child rebase 순서와 남은 검증을 기록했다. |

## Bluetape4k ecosystem 및 Kotlin 지침

- 이번 변경은 Python checker·JSON manifest·문서·테스트 harness에 한정되어
  `$bluetape-kotlin-patterns`가 요구하는 Kotlin source 변경은 없다.
- workshop consumer의 `bluetape4k-dependencies` BOM 단일 사용, 개별 BOM 및
  명시적 Bluetape 버전 pin 금지 규칙을 변경하지 않는다.
- 실제 예제의 Bluetape API 재사용과 `bluetape4k-assertions` 활용은 A1/F1
  module review의 별도 7-Tier evidence로 계속 확인한다. 이 contract는 그
  review artifact와 stacked ref가 rebase 후에도 누락되지 않게 할 뿐, 사용
  품질을 대신 판정하지 않는다.

## 문서 품질 점검 (SPW-01~05)

- **SPW-01 독자·근거:** workshop maintainer와 workflow reviewer를 독자로
  정하고, run `33001959078`, `develop` `96a7...`, checker/test diff, manifest,
  issue `#826`을 근거 ledger로 삼았다.
- **SPW-02 구조:** 범위 → 재현/원인 → 전환 계약 → manifest receipt → 7-Tier
  → ecosystem/Kotlin 경계 → 검증/남은 위험 순서로 배치했다.
- **SPW-03 한국어 자연스러움:** 설명과 판정은 한국어로 작성하고, 명령·SHA·ref·
  API/필드명·정확한 오류는 원문 토큰을 보존했다.
- **SPW-04 사실 대조:** 로컬 checker의 current/trusted 비교와 회귀 테스트,
  live issue/PR 상태를 다시 대조했다. hosted PASS라고 추정하지 않는다.
- **SPW-05 최종 read-back:** 아래 명령으로 JSON, 전체 checker test, diff 공백을
  다시 읽어 문서와 manifest의 receipt 값이 일치하는지 확인한다.

## 검증 명령과 남은 위험

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -v  # 77 tests PASS
python3 -m json.tool docs/ecosystem-reuse-train.json  # PASS
python3 .github/scripts/check-ecosystem-reuse.py --inventory docs/ecosystem-reuse-inventory.md --manifest docs/ecosystem-reuse-train.json --workflow .github/workflows/ecosystem-reuse-gate.yml  # PASS
git diff --check  # PASS after final edits
```

계약 PR의 exact head에 대해 Ecosystem Reuse Gate와 required review/CI를
확인한 뒤에야 A1 #812를 최종 develop head로 rebase하고 review marker를 새
implementation commit으로 갱신한다. 그 다음 F1 #815와 P2 #821의 base/ref/path
scope를 순서대로 다시 계산한다. 이 문서 시점에는 hosted exact-head PASS,
fresh human approval, merge, canonical sync, worktree cleanup이 아직
완료되지 않았다.
