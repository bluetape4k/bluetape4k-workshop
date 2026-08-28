# AWS Bedrock stacked follow-up 7-Tier 검토

## 검토 범위와 판정

이번 후속 lane은 Epic #792의 고정 9-track을 확장하지 않고, 선행 coordinator
PR branch 위에 새 AWS consumer train을 쌓을 수 있도록 scope binding 계약을
보강한다. #741 구현과 함께 checker의 `stacked-parent-head` 정책, 전용
follow-up scope, unit test와 review evidence를 추가한다.

현재 구현 판정은 **P0=0, P1=0, P2=0 / IMPLEMENTATION VERIFIED**다. 고정
`fixed_tracks`와 기존 node는 변경하지 않으며, 새 scope는 #741의 변경 경로와
정확한 base/head ref를 한 번에 묶는다.

## CI 실패와 결정

초기 PR #848의 Ecosystem Reuse Gate는 다음 이유로 실패했다.

```text
FAIL PR changed paths must map to exactly one manifest track (found 0)
```

원인은 #741의 AWS 모듈 경로가 기존 fixed node와 follow-up scope에 없고,
기존 `parent-head` 정책은 P0의 `expected_head_ref`만 base로 허용하기 때문이다.
따라서 fixed nine-track manifest를 늘리거나 Epic #792의 의미를 바꾸지 않고,
선행 PR branch를 직접 base로 삼는 후속 child에만
`base_ref_policy=stacked-parent-head`를 허용한다.

## 수용 증거

| 항목 | 수용 기준 | 구현·테스트 증거 |
|---|---|---|
| Scope policy | stacked child는 `child`와 `rebase-aware`를 함께 사용하고 exact base/head ref를 기록 | `.github/scripts/check-ecosystem-reuse.py`의 policy enum·scope invariant·train binding |
| Fixed train 보존 | `P0`~`I1` fixed track와 기존 Epic manifest node는 그대로 유지 | `docs/ecosystem-reuse-train.json`의 `fixed_tracks` 및 `nodes` unchanged |
| Path binding | #741 module, catalog, workflow, smoke, docs, review artifact, checker/manifest 변경을 하나의 scope에 묶음 | `aws-bedrock-followup` scope의 `allowed_paths`와 exact ref selection |
| Ref safety | parent track ref와 달라도 PR base/head가 scope 기록과 일치해야 함 | `validate_train_scope` stacked positive test와 hosted PR scope |
| Overlap safety | 기존 coordinator scope가 공유 파일을 소유해도 stacked scope는 exact ref로 분기 | follow-up overlap positive/legacy overlap negative unit tests |
| Local-first AWS | 기본 실행과 테스트에 credential/network가 없음 | `BedrockConverseServiceTest`, credential-free `:aws-bedrock-converse:run` |

## 7-Tier 결과

| Tier | 판정 | 확인 내용 |
|---|---|---|
| 1. 의미·범위 | PASS | #741을 별도 stacked AWS train으로 처리하고 #792 fixed nine-track은 보존했다. |
| 2. 정확성·계약 | PASS | `stacked-parent-head`의 scope-kind/OID 조합, exact refs, changed-path one-scope binding을 검증한다. |
| 3. 수명주기·동시성 | PASS | Bedrock ConverseStream cold Flow와 collector cancellation/client close 순서를 fake client로 검증했다. |
| 4. 보안·비밀 | PASS | prompt, response, credential, endpoint와 raw SDK 값을 log/report에 기록하지 않으며 live AWS는 opt-in이다. |
| 5. 성능·자원 | PASS | checker에 policy 분기만 추가하고 별도 dependency나 런타임 retry/buffering을 넣지 않았다. |
| 6. API·유지보수 | PASS | upstream Bedrock helper와 기존 `useSafe`, logging, assertion pattern을 재사용했다. |
| 7. 운영·검증 | PASS | checker unit, module test/build, root detekt, AWS smoke, stale-check, local run과 diff/term audit을 실행했다. hosted CI는 보수 후 exact head에서 다시 확인한다. |

## Fresh verification receipt

```text
RED: initial PR #848 Ecosystem Reuse Gate rejected the new AWS paths with
"found 0" because no manifest scope accepted the stacked base.
GREEN: checker unit tests cover stacked policy acceptance, shared coordinator
path overlap, and exact base/head train binding.
GREEN: :aws-bedrock-converse:test — 5 tests passed; module build and root
detekt passed; scripts/smoke-validate.sh aws and stale-check passed.
STATIC: Korean terminology audit findings=0; git diff --check passed.
BASE/HEAD: PR #848 remains based on
chore/ecosystem-reuse-manifest-transition-1 and points to the pushed exact
implementation head after this contract repair.
```

## 남은 게이트와 DoD

- [x] fixed nine-track manifest 보존
- [x] stacked child scope policy와 checker 회귀 테스트
- [x] #741 Bedrock consumer 구현·local-first 검증
- [x] 7-Tier review artifact
- [ ] hosted CI 재실행, PR metadata/thread read-back, final fresh approval,
  rebase merge, canonical sync — 전체 train closeout

현재 상태: **AWS BEDROCK STACKED FOLLOW-UP IMPLEMENTATION VERIFIED / HOSTED
CI AND COORDINATOR CLOSEOUT PENDING**.
