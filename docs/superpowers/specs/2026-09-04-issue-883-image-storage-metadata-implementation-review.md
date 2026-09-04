# Issue #883 metadata capability 구현 통합 review

## 검토 범위와 기준

현재 branch의 `ImageDerivativeWorkflowService`, 테스트 fixture, 양국 README, root
coverage/workflow/stale guard와 설계·계획 문서를 검토했다. 기준은 issue #883의
optional capability, object별 1회 조회, no-download, fallback, fail-closed, `2.0.0`
BOM 계약이다.

| 관점 | 확인한 위치/증거 | 결과 |
| --- | --- | --- |
| 기능/요구사항 | `resolveStorageMetadata`, S3_UPLOAD payload, 8개 targeted test | PASS, P0=0/P1=0 |
| API/호환성 | `ImageStorage` 미변경, `storage as? ImageObjectMetadataReader`, versionless aliases 유지 | PASS, P0=0/P1=0 |
| Kotlin/취소 | suspend reader 예외 전파, 기존 `CancellationException` cleanup catch 보존, nullable content type fallback | PASS, P0=0/P1=0 |
| 보안/데이터 | metadata key/size 불일치 거부, ETag opaque 기록, body `download` 호출 0회 | PASS, P0=0/P1=0 |
| 테스트/운영 | metadata wrapper·unsupported·reader 오류·size mismatch·역순 cleanup·counter 검증 | PASS, P0=0/P1=0 |
| 문서/지역화 | module/root README, matrix, Examples path/smoke, stale-check, Korean lesson 및 English parity | PASS, P0=0/P1=0 |
| 통합/단순성 | public response/schema와 새 dependency를 늘리지 않고 기존 upload 결과 fallback 재사용 | PASS, P0=0/P1=0 |

## 검증 결과

- `./gradlew :image-processing-advanced-workflow:test --tests '*ImageDerivativeWorkflowServiceTest' --no-build-cache --rerun-tasks --max-workers=1`: **8 passing**.
- `./gradlew :image-processing-advanced-workflow:test --no-build-cache --rerun-tasks --max-workers=1`: **47 passing, 1 pending**, BUILD SUCCESSFUL.
- `node scripts/validate-readme-language.mjs`: `offenders=0`, `totalHits=0`.
- `node scripts/validate-readme-parity.mjs image-processing/advanced-workflow`: `failures=0`.
- `bash scripts/smoke-validate.sh stale-check`: 모든 등록 guard PASS, broken image links 없음.
- changed Korean artifacts terminology audit: 5 files, findings=0; `git diff --check`: PASS.

## 판단

P0/P1 blocker와 미해결 review thread는 없다. module별 `detekt` task는 repository
구성상 존재하지 않아 N/A로 기록하며, 전체 `detekt`는 다섯 issue train 통합 검증에서
실행한다. 구현은 PR을 만들 수 있는 상태이나 hosted CI와 live review는 PR 생성 후
확인해야 한다.
