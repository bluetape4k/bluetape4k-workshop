# Issue #872 S3 CSE transfer PR 직전 검토

- 검토일: 2026-09-02
- 저장소: `bluetape4k-workshop`
- 이슈: [#872](https://github.com/bluetape4k/bluetape4k-workshop/issues/872)
- base: `develop`
- head 후보: `feat/issue-872-s3-cse-transfer`
- 구현 기준 head: `3a1537b4845e063f4d66f3fe5c868f07508d6466`

## 제출 게이트

| 게이트 | 상태 | 증거 |
| --- | --- | --- |
| Issue/milestone | PASS | Issue #872 open, milestone `2.0.0`, title prefix `[2.0.0]` |
| 구현 범위 | PASS | AES/RSA opt-in profile, versionless transfer alias, staging promotion/cleanup, upstream authoritative file bound |
| 회귀 | PASS | module test `SUCCESS: Executed 50 tests`, `BUILD SUCCESSFUL` (`--rerun-tasks`, `--max-workers=1`) |
| build/static | PASS | storage-abstraction build and repository detekt `BUILD SUCCESSFUL` |
| AWS smoke | PASS | `MAX_WORKERS=1 bash scripts/smoke-validate.sh aws` `BUILD SUCCESSFUL` |
| stale/registration | PASS | CSE stale guard, lesson, module registration and broken-image checks PASS |
| docs | PASS | README parity `failures: 0`, Korean terminology audit `findings=0` |
| workflow/manifest | PASS | `actionlint .github/workflows/Examples.yml` exit 0; ecosystem manifest JSON valid |
| diff hygiene | PASS | `git diff --check` |
| hosted CI | PENDING | PR 생성 후 exact head status checks 확인 필요 |
| review threads | PENDING | PR 생성 후 unresolved thread/review decision 확인 필요 |

## 구현·운영 잔여 위험

- canonical copy 이후 staging 삭제 실패는 best-effort 로그 경계이므로 bounded
  reaper 또는 S3 lifecycle 정책이 운영 승격의 후속 조건이다.
- AES key/RSA key pair는 JVM memory에 생성되며 managed KMS/HSM, zeroization,
  rotation 계약은 이 학습 예제의 범위가 아니다.
- per-call file ciphertext bound는 consumer preflight TOCTOU를 피하기 위해
  upstream API 확장 범위로 남겼다. 현재 file path는 upstream의 단일
  authoritative HEAD/ETag와 global bound를 사용한다.

## PR 본문 필수 항목

- `Closes #872`
- `## DoD Status`에 50 tests, build/detekt, AWS smoke/stale, parity/audit,
  actionlint/manifest와 위 잔여 위험을 기록한다.
- PR 생성 직후 exact head의 `statusCheckRollup`, `reviews`,
  `mergeStateStatus`를 재확인한다.
- merge는 hosted CI와 review thread가 최신 exact head 기준으로 확인된 뒤
  새 사용자 `승인`을 받은 경우에만 실행한다.
