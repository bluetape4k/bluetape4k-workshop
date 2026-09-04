# Issue #883 ImageStorage metadata capability Implementation Plan

## 실행 순서

1. `ImageDerivativeWorkflowService`에 optional `ImageObjectMetadataReader` 탐색과
   metadata 기준 정보 정규화를 추가한다. 기존 `ImageStorage` 인터페이스는 변경하지 않는다.
2. `ImageDerivativeWorkflowServiceTest`와 test support에 capability/fallback/fail-closed/
   metrics-wrapper fixture를 먼저 추가하고 targeted test로 RED/GREEN을 확인한다.
3. `README.md`·`README.ko.md`에 metadata 경계, opaque ETag, unsupported fallback,
   `MetricImageStorageWithMetadata` 사용법을 추가한다.
4. root `README` 양국과 `docs/coverage-matrix.md`의 advanced-workflow 설명을 갱신한다.
5. `.github/workflows/Examples.yml`에 advanced-workflow path와 smoke job을 등록하고,
   `scripts/smoke-validate.sh stale-check`의 required module 목록에 추가한다.
6. 설계 review와 lesson을 작성하고 writer/terminology/diff checks를 통과시킨다.

## 파일별 책임과 검증

| 파일 | 변경 책임 | 증거 |
| --- | --- | --- |
| `image-processing/advanced-workflow/src/main/kotlin/.../ImageDerivativeWorkflowService.kt` | metadata 기준 정보 정규화, fallback, counters, event payload | compile + targeted test |
| `image-processing/advanced-workflow/src/test/kotlin/.../ImageProcessingTestSupport.kt` | metadata reader와 event capture fixture | test compile |
| `image-processing/advanced-workflow/src/test/kotlin/.../ImageDerivativeWorkflowServiceTest.kt` | one-read/no-download, fallback, fail-closed, wrapper preservation | 7 passing |
| `image-processing/advanced-workflow/README*.md` | 독자용 capability 설명과 코드 예제 | locale parity/readme validators |
| `README*.md`, `docs/coverage-matrix.md` | module coverage 설명 | `git diff --check`, readme parity |
| `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh` | path/smoke/stale registration | YAML parse + `stale-check` |
| `docs/superpowers/specs/...`, `docs/lessons/...` | 설계·review·재발 방지 지식 | SPW-01~05 read-back |

## 회귀·롤백

- 가장 작은 검증: `./gradlew :image-processing-advanced-workflow:test --tests '*ImageDerivativeWorkflowServiceTest' --no-build-cache --rerun-tasks --max-workers=1`
- 모듈 검증: `./gradlew :image-processing-advanced-workflow:test --no-build-cache --rerun-tasks --max-workers=1`
- 문서/guard 검증: `git diff --check`, `node scripts/validate-readme-language.mjs`,
  `node scripts/validate-readme-parity.mjs image-processing/advanced-workflow`,
  `bash scripts/smoke-validate.sh stale-check`.
- 실패 시 metadata 조회와 event payload 변경만 되돌리면 기존 upload result 경로로
  복귀할 수 있다. dependency catalog와 public response schema는 변경하지 않는다.

## 완료 게이트

- [ ] issue #883 acceptance와 파일 traceability를 review한다.
- [ ] Kotlin pattern, test, writer, repository hazard 검사를 기록한다.
- [ ] Lore commit trailer가 있는 한국어 commit을 만들고 exact remote head를 확인한다.
- [ ] PR body에 `## DoD Status`와 CG-11~15 상태를 넣고 CI/review가 수렴한 뒤 다음
  순차 issue로 이동한다. 병합은 다섯 issue의 최종 승인 전까지 보류한다.
