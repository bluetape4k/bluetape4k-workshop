# Epic #792 누적 stacked train 승격 7-Tier 검토

## 범위와 목적

이 문서는 이미 rebase merge 된 #847, #848, #849, #850의 누적 트리를 보호된
`develop`에 승격하기 위한 terminal promotion PR의 검토 증거다. promotion은
#741, #742, #776의 변경을 다시 구현하지 않고, 선행 stacked train의 결과를
저장소 기본 브랜치에 하나의 rebase merge 경로로 반영한다.

## 기준점과 lineage

- repository base: `develop@c488d1e578999273a50e6f05ede5e79288fd89cc`
- terminal stacked tree: `feat/aws-settings-boundary-742@5fe79fcbcbe37d34bd4e4dbbd1dcd9ad3c5af6f2`
- promotion replay before scope evidence: `8839a947329ddbb793cf150d7c17c4de7f51f9c2`
- promotion ref: `feat/epic-792-train-promotion`
- source tree equivalence: terminal stacked tree와 promotion replay tree의 파일 내용이 동일함

promotion branch는 `origin/develop` 위에 terminal stacked tree의 child commit을
rebase하여 coordinator 중복 commit을 제거했다. 따라서 protected `develop`에
직접 push하지 않고, base/head ref와 CI가 고정된 promotion PR로만 반영한다.

## 7-Tier 판정

| Tier | 검토 결과 |
| --- | --- |
| T1 요구사항 | #741/#742/#776 구현과 Epic #792 누적 승격 범위가 분리되어 있고, 이미 병합된 child PR을 재실행하지 않는다. |
| T2 설계 | `develop`을 base로 하는 terminal promotion scope를 별도 manifest entry로 식별한다. |
| T3 구현 | promotion 자체로 새 production behavior를 추가하지 않고, 이미 검증·병합된 child 변경을 누적 트리로 승격한다. |
| T4 테스트 | checker unit 95개, assertion governance unit 4개, assertion governance scan, `smoke-validate.sh assertion-governance`가 통과했다. |
| T5 통합 | promotion PR의 hosted Ecosystem Reuse Gate와 Examples CI를 exact head에서 확인한다. |
| T6 운영 | protected branch에는 PR rebase merge만 사용하고, 자동 merge를 활성화하지 않는다. |
| T7 회귀·보안 | credential-free 예제 계약과 assertion governance guard를 유지하며 새 dependency나 secret을 추가하지 않는다. |

## 리스크와 판정

- P0: 0
- P1: 0
- known risk: GitHub의 source branch 자동 삭제 설정이 non-default stacked ref를
  삭제할 수 있으므로, 최종 검증에서 필요한 branch ref를 보존하거나 복원한다.
- final status: promotion hosted CI와 새 exact-head 승인 전까지 `PENDING`.

## 로컬 검증 증거

- `python3 .github/scripts/test_check_ecosystem_reuse.py -v` — 95 PASS
- `python3 .github/scripts/test_check_assertion_governance.py -v` — 4 PASS
- `python3 .github/scripts/check-assertion-governance.py` — PASS
- `./scripts/smoke-validate.sh assertion-governance` — PASS
- `python3 .github/scripts/check-ecosystem-reuse.py ...` 일반 manifest 검사 — PASS
- promotion replay와 terminal stacked tree `git diff --exit-code` — PASS
- `git diff --check` — PASS

Hosted promotion CI와 protected `develop` rebase merge는 이 문서 작성 시점에
아직 실행하지 않았다.
