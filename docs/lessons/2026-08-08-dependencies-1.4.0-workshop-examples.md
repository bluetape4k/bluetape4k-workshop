# bluetape4k-dependencies 1.4.0 예제 갱신

## 배경

2026-08-06 배포된 `bluetape4k-dependencies` 1.4.0은 Kotlinx Serialization BOM을 중앙
catalog authority로 이동하고, Graph 0.6.0과 Image 0.4.0의 신규 API를 소비자 프로젝트에서
일관되게 해석하도록 갱신했습니다. workshop은 개별 bluetape4k 모듈 버전을 고정하지 않고
`bluetape4k-dependencies` BOM만 사용해야 하므로, 이번 변경도 versionless alias와 BOM 해석을
그대로 유지했습니다.

## 반영 내용

- `graph/io-pipeline`: `GraphExportOptions.exportChunkSize`를 pipeline 생성 인자로 노출해
  bounded NDJSON/GraphML export를 직접 학습할 수 있게 했습니다. 0 이하 chunk는 생성 시
  fail-fast합니다.
- `graph/social-network`: 자체 endpoint 검증 helper를 Graph 0.6.0의
  `GraphVertexRepository.requireEndpoint`와 suspend overload로 교체했습니다. 누락 정점과
  label 불일치 오류 계약을 library와 workshop이 공유합니다.
- `image-processing/barcode-api`: `bluetape4k-images-barcode-api`의 provider-neutral 계약과
  `bluetape4k-images-barcode-zxing` 구현을 사용하는 새 Spring Boot 소비자 예제를 추가했습니다.
  encoded/decoded 입력 제한, WebP metadata fallback, cancellation 전파, 안전한 오류 응답,
  결정적 QR/no-result/malformed fixture를 함께 보여줍니다.

## 발굴한 후속 예제 후보

이번 범위에서는 배포된 artifact를 즉시 검증할 수 있고 로컬 fixture로 재현 가능한 barcode를
신규 모듈로 선택했습니다. 같은 1.4.0 생태계에서 다음 후보도 확인했지만, 외부 서비스나 더 큰
운영 계약이 필요한 별도 작업으로 남겼습니다.

- AWS EventBridge/Bedrock 경계: local adapter 계약을 먼저 확장한 뒤 AWS credential 없이 검증
- Leader 0.5.0 observability: tenant scheduler에 leader metric snapshot을 추가하는 후속 lab
- JaVers 0.3.0 Spring Boot 4 auto-configuration: 기존 approval workflow에 auto-config 경로 추가
- Image OCR structured detail: 기존 `ocr-api`의 plain-text 결과와의 호환/마이그레이션 설계 필요

## 검증

- `:graph-io-pipeline:test`: 11개 통과
- `:graph-social-network:test`: 80개 통과
- `:image-processing-barcode-api:test`: 8개 통과
- fixture SHA-256은 upstream barcode quickstart와 일치하며, 새 module은
  `Examples.yml`과 `smoke-validate.sh all-smoke`에 등록했습니다.
- 기본 로컬 저장소로 `all-smoke`를 처음 실행하면 `mavenLocal()`의 오래된
  `bluetape4k-dependencies:1.4.0` POM이 Exposed `1.12.0-SNAPSHOT`을 가리켜
  `org.jetbrains.exposed:exposed-dao:` 버전 누락으로 중단될 수 있습니다. 중앙 1.4.0 POM은
  Exposed `1.12.1`을 가리키므로, `-Dmaven.repo.local=<임시 경로>`로 빈 Maven 로컬
  저장소를 지정하면 이 해석 문제를 피할 수 있습니다.
- Colima/Docker를 기동한 뒤 깨끗한 Maven 로컬 저장소에서 `all-smoke`를 재실행했고,
  336개 Gradle task가 `BUILD SUCCESSFUL`로 완료되었습니다(1분 29초). Testcontainers
  테스트를 포함한 smoke 경로도 실패 없이 종료되었으며, 종료 후 Docker 컨테이너가 남지
  않았습니다.

## 운영 메모

동일 repository에서 복수 Codex를 실행하면 repo-local `.omx/state/session.json` 단일 포인터가
서로의 session을 덮어쓸 수 있습니다. 이번 작업은 `using-git-worktrees`에 따라 별도 worktree와
`.bluetape` receipt를 사용해 현재 `develop` worktree와 runtime state를 건드리지 않았습니다.
