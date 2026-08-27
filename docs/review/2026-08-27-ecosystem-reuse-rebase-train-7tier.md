# Epic #792 rebase PR train coordinator 7-Tier 검토

## 판정과 범위

이번 coordinator 변경은 이미 `develop`에 통합된 P0/A1 이후의 train을 현재
ref에 다시 결속하고, 사용자 지시인 `rebase merge`를 실행 계획의 병합 계약으로
고정한다. Kotlin 예제 동작과 child source/test는 이 lane의 범위가 아니다.

- 변경 범위: `docs/ecosystem-reuse-train.json`, 승인 실행 plan/spec,
  이 review와 lesson
- 기준 head: `develop`=`5c188021acf298dd9a1e21da80063fdd1ee4c2f8`
- coordinator head: 기존 coordinator PR #828의 `fix/ecosystem-reuse-train-replan`
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
| 6. 테스트·운영 | PASS | `test_check_ecosystem_reuse.py -v` 83개 성공, current/trusted manifest checker PASS, JSON·py_compile·diff 검증 PASS. 각 planned base replan은 별도 track receipt와 PENDING/null 실행 receipt를 사용한다. PR #828 exact-head `21f53a030e24a9dc305ea21b7333b140bd258cb8` hosted CI `33051206844` (Ecosystem Reuse Gate)와 `33051206902` (CI)가 모두 PASS이며, live review/thread는 비어 있음을 재확인했다. |
| 7. 문서·유지보수 | PASS | 한국어 plan/spec/review/lesson에 stale ref 원인, rebase-only 정책, rollback/검증 순서를 기록했다. |

## 검증 계약

```text
python3 .github/scripts/test_check_ecosystem_reuse.py -v
python3 .github/scripts/check-ecosystem-reuse.py --inventory docs/ecosystem-reuse-inventory.md --manifest docs/ecosystem-reuse-train.json --workflow .github/workflows/ecosystem-reuse-gate.yml --pins docs/governance/github-action-pins.json
python3 .github/scripts/check-ecosystem-reuse.py --inventory docs/ecosystem-reuse-inventory.md --manifest docs/ecosystem-reuse-train.json --workflow .github/workflows/ecosystem-reuse-gate.yml --pins docs/governance/github-action-pins.json --trusted-manifest <origin/develop manifest snapshot>
python3 -m json.tool docs/ecosystem-reuse-train.json
git diff --check
```

현재 결과: 83 tests `OK`; current/trusted checker `PASS`; JSON·py_compile·diff
`PASS`. 검증 기준 `develop`은
`5c188021acf298dd9a1e21da80063fdd1ee4c2f8`이며, planned-scope 승인 receipt와
실행 receipt는 서로 다른 필드로 보존한다. 모든 결과는 coordinator branch의
현재 diff와 연결해 기록한다. checker 또는 trusted-manifest 비교가 실패하면
PR 생성 전 문서/receipt를 수리하고, child branch의 green 결과를 재사용하지
않는다.

## 적용한 규칙과 stop condition

- `$bluetape-workflow`: CG-01~CG-10과 coordinator-only manifest ownership을 적용한다.
- `$bluetape-kotlin-patterns`: Kotlin source 변경이 없으므로 KT-TEST-01~04는 N/A이며, child A2/F1 lane에서 별도로 증명한다.
- `rebase merge`: CG-16 fresh approval 전에는 merge하지 않는다. 승인 후에도 GitHub merge method가 `rebase`인지 확인하고, 다른 method/auto-merge는 중단한다.
- stop condition: manifest checker P0/P1 finding, stale exact head, unresolved live review, missing CI, 또는 merge strategy mismatch.

현재 review verdict: `P0=0, P1=0`; fresh checker/test, 실제 receipt event, PR #828 exact-head CI와 live review/thread read-back을 모두 확인했다. coordinator는 CG-15까지 PASS이며, CG-16 exact-head 승인 전에는 merge하지 않는다.
