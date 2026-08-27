# Epic #792 rebase PR train coordinator 7-Tier 검토

## 2026-08-27 P2 child base 재계획 갱신

F1 #815 rebase merge 이후 P2 #821의 live base가 `develop`으로 이동했지만,
`F1-P2-02` scope가 삭제된 F1 branch를 기대해 기존 checker가 F1과 P2를 모두
매칭했다(`found 2`). 부모 branch를 복구하거나 checker를 느슨하게 하지 않고,
fresh coordinator scope receipt로 P2의 `expected_base_ref=develop`을
재계획했다.

manifest의 follow-up scope는 이제 `base_ref_policy`를 명시한다. 기본
`parent-head`는 parent track head와의 동일성을 요구하고,
`repository-base-after-parent-merge`는 `scope_kind=child`와
`oid_policy=rebase-aware`인 경우에만 repository `base_ref`를 허용한다.
실제 PR scope는 여전히 exact head ref, changed-path allowlist, rebase-aware
null OID를 모두 검증한다.

- coordinator scope receipt: `20260827T093234Z-p2-base-replan`
- receipt checksum: `fe95df7379a758f62102054624c5e8c4c3b6fb0f4a0b3b48d8d0d87ac758bce4`
- target child: `F1-P2-02`, base `develop`, head `fix/ecosystem-reuse-shutdown-deadline`
- blocker resolved by contract update; P2 #821 was subsequently merged to `develop`, so the coordinator was re-based once more onto the new repository base before merge review

### 현재 coordinator PR #831 exact-head read-back

coordinator branch는 P2 #821의 병합으로 전진한 최신
`develop@f95ea45c1c053f3901d91d29bca58f4e18fb3bdf`에 다시 rebase되어 게시되었다.
train-scope 회귀 테스트와 stale evidence 보정을 포함한 reviewed implementation
ancestor는 `8b2cd5f47ce8c916160f2d8396a87b42c84fbdc5`이며, review-tail 뒤의 최종
exact head와 base는 PR body의 live metadata에서 읽는다.
PR #831의 변경 범위는 manifest/checker와 관련 한국어
review/lesson/plan/spec 7개 파일이며, P2 Kotlin production/test source는 포함하지
않는다.

- PR: [#831](https://github.com/bluetape4k/bluetape4k-workshop/pull/831)
- CI: 이전 base의 hosted 결과는 base drift로 폐기하고, 현재 rebase head의 새
  CI/Gate 결과를 exact-head read-back에서 재확인한다
- local proof: checker 88 tests, current/trusted manifest, PR-scope simulation,
  JSON, `py_compile`, and `git diff --check` PASS
- live review/thread read-back: review 0, comments 0, unresolved threads 0 at
  read time; independent 7-Tier review subsequently requested

## 최신 train closeout 이후의 historical 상태

위 coordinator 검토는 `develop@f95ea45c1c053f3901d91d29bca58f4e18fb3bdf`를
기준으로 수행한 historical evidence다. 이후 동일 train의 후속 PR #832와
serial closeout PR #833이 순차적으로 통합되었다.

- PR #832 merge: `c3c1319b47a6c73cc61ab3cf8d215c5dddbb99da`
- PR #833 merge: `283defb0a0ec5b3777968772b811d1164fae578e`
- 최신 `develop`: `283defb0a0ec5b3777968772b811d1164fae578e`
- PR #831: head `367002eb1c22645bd2a32946b4274018eb4bb368`,
  `OPEN/CONFLICTING`

따라서 이 문서의 88-test receipt, hosted checks, 독립 review, merge-ready
판정과 사용자 approval은 최신 closeout 이후 재사용할 수 없다. PR #831은
superseded 상태로 보존하며, 별도 scope 결정 없이 rebase·merge·close하지
않는다. 최신 lifecycle/receipt 판정은 #833의 live body와
`develop@283defb0…`을 기준으로 한다.

## 이전 coordinator PR #828 검토 범위

이번 coordinator 변경은 이미 `develop`에 통합된 P0/A1 이후의 train을 현재
ref에 다시 결속하고, 사용자 지시인 `rebase merge`를 실행 계획의 병합 계약으로
고정한다. Kotlin 예제 동작과 child source/test는 이 lane의 범위가 아니다.

- 변경 범위: `docs/ecosystem-reuse-train.json`, 승인 실행 plan/spec,
  이 review와 lesson
- 당시 기준 head: `develop`=`5c188021acf298dd9a1e21da80063fdd1ee4c2f8`
- 당시 coordinator head: PR #828의 `fix/ecosystem-reuse-train-replan`
- 관련 이슈: #792, #822, #826
- OID marker: coordinator 문서-only scope이므로 fixed-node implementation
  marker는 적용하지 않으며 child marker/receipt는 각 child exact head에서
  새로 발행한다.

## 해결한 stale scope

기존 `coordinator-child-scope`가 이미 삭제된
`fix/ecosystem-reuse-marker-transition`을 expected head로 가리키고 있었고,
동일한 #822 범위의 live coordinator PR #828이 이미 존재했다. 새 PR을 만들지
않고 #828의 `fix/ecosystem-reuse-train-replan` head를 재사용하며, plan/spec·새
review·lesson을 명시적 allowlist에 포함한다. 변경은 fresh
`coordinator_scope_receipt`로 식별하며, 기존 receipt ID/checksum을 재사용하지
않는다. F1/A2/R1/T1/I1의 planned base replan은
`planned_scope_replan_receipts`의 track별 승인 receipt로 남기며, fixed node의
실행 receipt는 `state=PLANNED`, `receipt_status=PENDING`, `receipt_id=null`,
`checksum=null`로 유지한다. implementation OID도 child evidence가 생길
때까지 채우지 않는다.

## 6-lens 통합 결과

| 관점 | 판정 | 근거 |
| --- | --- | --- |
| Performance | N/A | coordinator 변경은 manifest와 한국어 plan/spec/review/lesson 문서뿐이며 runtime·DB·container 경로를 변경하지 않는다. |
| Stability | PASS | P0/A1 historical ref를 재생하지 않고 PR #828의 planned replan checker와 fresh scope receipt를 사용한다. receipt event는 아래 `.bluetape` sequence로 연결한다. |
| Security | PASS | allowlist는 repository-relative 경로만 포함하고 credential, token, owner handle을 문서/manifest에 기록하지 않는다. |
| Operator/Ops | PASS | 동일 #822 coordinator scope의 live PR #828을 재사용해 중복 PR을 만들지 않으며, exact base/head·CI·review 후에만 rebase merge gate를 연다. |
| Developer/API | PASS | F1/A2/R1/T1/I1의 삭제된 foundation base를 `develop` 기준으로 재계획하고, child API/source 변경은 각 lane에 남긴다. |
| User/Caller | PASS | coordinator PR 자체는 CG-16 전까지 merge하지 않되, 이후 child closeout은 사용자가 지정한 `rebase merge`만 허용하도록 spec/plan을 분리했다. |

## 7-Tier 결과

| Tier | 판정 | 근거 |
| --- | --- | --- |
| 1. 요구사항·범위 | PASS | #792 continuation, #822/#826 coordinator scope, `rebase merge` 지시만 반영하고 child code는 건드리지 않았다. |
| 2. 계약·자료구조 | PASS | follow-up scope의 head/base/ref, allowlist, review artifact, fresh receipt 형식을 checker contract와 대조했다. |
| 3. 경계·보안 | PASS | 경로는 repository-relative allowlist이며 token/credential/실제 owner handle을 문서·manifest에 기록하지 않았다. |
| 4. 정확성·상태 | PASS | 기존 trusted manifest와 비교할 때 follow-up scope는 새 `coordinator_scope_receipt`, F1/A2/R1/T1/I1 planned ref 변경은 track별 `planned_scope_replan_receipts`가 필요하며 fixed node 실행 receipt는 PENDING/null로 유지한다. |
| 5. 성능·안정성 | N/A | 문서·JSON coordinator scope만 변경하고 runtime, DB, container, coroutine 경로를 변경하지 않는다. |
| 6. 테스트·운영 | PASS | `test_check_ecosystem_reuse.py -v` 83개 성공, current/trusted manifest checker PASS, JSON·py_compile·diff 검증 PASS. 각 planned base replan은 별도 track receipt와 PENDING/null 실행 receipt를 사용한다. 최종 candidate head `93f13c28eeee906e86cf5f5170c78ae103e4feda`에 대해 새 hosted CI를 실행하고, 결과는 PR exact-head read-back으로 확인한다. live review/thread는 비어 있음을 확인했다. |
| 7. 문서·유지보수 | PASS | 한국어 plan/spec/review/lesson에 stale ref 원인, rebase-only 정책, rollback/검증 순서를 기록했다. |

## 검증 계약

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -v
python3 .github/scripts/check-ecosystem-reuse.py --inventory docs/ecosystem-reuse-inventory.md --manifest docs/ecosystem-reuse-train.json --workflow .github/workflows/ecosystem-reuse-gate.yml --pins docs/governance/github-action-pins.json
python3 .github/scripts/check-ecosystem-reuse.py --inventory docs/ecosystem-reuse-inventory.md --manifest docs/ecosystem-reuse-train.json --workflow .github/workflows/ecosystem-reuse-gate.yml --pins docs/governance/github-action-pins.json --trusted-manifest <origin/develop manifest snapshot>
python3 -m json.tool docs/ecosystem-reuse-train.json
git diff --check
```

이전 PR #828 기준 결과: 83 tests `OK`; current/trusted checker,
JSON·py_compile·diff `PASS`. 당시 검증 기준 `develop`은
`5c188021acf298dd9a1e21da80063fdd1ee4c2f8`이며, planned-scope 승인 receipt와
실행 receipt는 서로 다른 필드로 보존했다.

## 적용한 규칙과 stop condition

- `$bluetape-workflow`: CG-01~CG-10과 coordinator-only manifest ownership을 적용한다.
- `$bluetape-kotlin-patterns`: Kotlin source 변경이 없으므로 KT-TEST-01~04는 N/A이며, child A2/F1 lane에서 별도로 증명한다.
- `rebase merge`: CG-16 fresh approval 전에는 merge하지 않는다. 승인 후에도 GitHub merge method가 `rebase`인지 확인하고, 다른 method/auto-merge는 중단한다.
- stop condition: manifest checker P0/P1 finding, stale exact head, unresolved live review, missing CI, 또는 merge strategy mismatch.

이전 PR #828 review verdict: `P0=0, P1=0`; fresh checker/test, 실제 receipt event,
PR #828 exact-head CI와 live review/thread read-back을 확인했다. PR #828은 이후
rebase merge되었다.

## 현재 coordinator replan 판정

현재 변경은 PR #831의 reviewed implementation ancestor와 review-tail이 구성하는
coordinator-only lane였다. 이 lane은 P2 #821 병합으로 발생한 base drift를 감지한 뒤
`develop@f95ea45c1c053f3901d91d29bca58f4e18fb3bdf`에 rebase되었고, 당시 exact
head에서 `base_ref_policy=repository-base-after-parent-merge`와 새
positive/negative train-scope 회귀 테스트를 포함한 checker 88개가 통과했다.
이후 #832/#833 closeout이 최신 `develop@283defb0a0ec5b3777968772b811d1164fae578e`
에 통합되었으므로, 이전 base의 green 결과·merge approval·receipt는 모두
historical evidence다. PR #831은 merge-ready가 아니며, 최신 lifecycle/receipt는
#833에서 완료되었다.
