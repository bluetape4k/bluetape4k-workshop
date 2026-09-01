# Issue #871 S3 Resource pattern resolver 구현 검토

- 검토일: 2026-09-01
- 저장소: `bluetape4k-workshop`
- 이슈: [#871](https://github.com/bluetape4k/bluetape4k-workshop/issues/871)
- 브랜치: `feat/issue-871-s3-resource-pattern-resolver`
- 기준 구현 commit: `a9b29dd8` 및 후속 정리 실패 전파·stale guard 보정 작업본

## 검토 범위

기존 `aws/s3-spring-cloud` consumer가 Bluetape4k S3 Resource pattern resolver를
실제로 주입해 exact 및 `s3://<bucket>/config/**/*.yml` wildcard를 읽는지 검토했다.
Floci 기반 paginator·정렬·중복 제거·empty match와 metadata/stream lifecycle을
확인하고, Spring Cloud AWS의 `s3ObjectConverter` 이름 충돌을 transfer 자동 구성
하나의 exclusion으로 격리했는지 확인했다. README 두 언어, coverage matrix,
stale-check, ecosystem child scope와 lesson의 등록도 함께 검토했다.

## 독립 리뷰 결과

| 관점 | P0 | P1 | P2 | 판정 및 근거 |
| --- | ---: | ---: | ---: | --- |
| 코드/API 경계 | 0 | 0 | 0 | `SpringCloudAwsS3Sample`과 test가 `@Qualifier("s3ResourcePatternResolver")`를 사용한다. exact와 wildcard는 Bluetape resolver를 사용하고 기존 `ResourceLoader` 경로는 Spring Cloud AWS 회귀로 보존한다. wildcard bucket, root/empty prefix, cross-bucket pattern은 parser 경계에 맡긴다. |
| 테스트/회귀 | 0 | 0 | 0 | 5개 Floci 테스트가 exact `S3Resource`, deterministic wildcard, 1,001개 generated object를 포함한 1,004개 match, all-page pagination, metadata, stream read, empty result과 parser rejection을 고정한다. 기존 S3Template/list/ResourceLoader 회귀도 유지한다. |
| 보안/신뢰 | 0 | 0 | 0 | 실제 AWS credential 없이 Floci endpoint와 synthetic credential만 사용한다. pattern은 literal single bucket으로 제한되고, write/output stream을 지원한다고 주장하지 않는다. 로그는 fixture key/content만 다루며 credential을 출력하지 않는다. |
| 성능/안정성 | 0 | 0 | 0 | paginator 결과를 resolver에 위임하고 test fixture 정리는 `DeleteObjects` 1,000개 chunk를 사용한다. 모든 반환 stream을 `use`로 닫고 cleanup 실패는 primary failure에 suppressed로 보존하거나 단독 실패로 전파한다. |
| 문서/사용성 | 0 | 0 | 0 | `README.md`/`README.ko.md`가 qualifier, exact/pattern, read-only, stream ownership, empty/unsupported boundary, Floci 실행법과 multipart gap을 같은 코드/API 순서로 설명한다. |
| 빌드/운영 | 0 | 0 | 0 | version authority는 root `bluetape4k-dependencies` BOM이며 module alias에는 개별 version pin이 없다. transfer auto-configuration만 exclusion하고 resolver auto-configuration은 활성화한다. workflow 기존 test job을 재사용하고 stale-check·coverage·ecosystem scope·lesson을 등록했다. |
| 범위/소유권 | 0 | 0 | 0 | upstream parser/pagination 구현을 복제하지 않고 consumer wiring과 재현 가능한 Floci 증거만 추가했다. multipart upload와 실제 AWS signing/IAM은 별도 gap으로 남긴다. |

## 이전 finding과 해소 내용

| 등급 | finding | 해소 증거 |
| --- | --- | --- |
| P1 | pagination test의 `finally` cleanup이 `runCatching`으로 실패를 삼켜 test/환경 실패를 숨길 수 있음 | `SpringCloudAwsS3Test.kt`의 cleanup은 primary failure를 보존하고, cleanup만 실패하면 해당 예외를 throw한다. 삭제는 `DeleteObjects` chunk와 `deleteBucket`으로 검증 가능한 경로를 사용한다. |
| P2 | stale-check가 resolver 문자열만 확인해 구형 `bluetape4k.aws.s3.enabled: false`를 놓칠 수 있음 | `scripts/smoke-validate.sh`가 YAML 계층을 추적해 global Bluetape S3 switch가 남아 있으면 명시적으로 실패한다. 현재 설정은 `S3TransferAutoConfiguration`만 제외한다. |
| P2 | 계획의 Lore `Rejected` 문구가 실제 class-level exclusion 보정 전의 `transfer.enabled`만 설명함 | 계획 문구를 `S3TransferAutoConfiguration` 유지와 transfer 토글의 converter 충돌까지 설명하도록 갱신했다. |

## fresh verification evidence

| 명령 | 결과 |
| --- | --- |
| `./gradlew :aws-s3-spring-cloud:cleanTest :aws-s3-spring-cloud:test --no-build-cache --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`; exact, wildcard, pagination, parser, 기존 Spring Cloud AWS 회귀 5개 테스트 통과 |
| `./gradlew :aws-s3-spring-cloud:build --no-build-cache --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL` |
| `./gradlew detekt --no-daemon --console=plain` | `BUILD SUCCESSFUL` |
| `bash scripts/smoke-validate.sh aws` | AWS smoke test `BUILD SUCCESSFUL` |
| `bash scripts/smoke-validate.sh stale-check` | active module count, required registration, S3 resolver guard와 broken image 검사 PASS |
| `./gradlew projects --no-daemon --console=plain` | `:aws-s3-spring-cloud` 단일 등록 확인, `BUILD SUCCESSFUL` |
| `node scripts/validate-readme-parity.mjs aws/s3-spring-cloud` | `{"failures":0}` |
| `node scripts/validate-readme-language.mjs aws/s3-spring-cloud/README.md aws/s3-spring-cloud/README.ko.md` | `offenders: 0`, `totalHits: 0` |
| Korean terminology audit (설계·계획·lesson·review) | findings 0 |
| `git diff --check` | PASS |

의존성 확인에서 `io.github.bluetape4k:bluetape4k-dependencies:2.0.0-SNAPSHOT`이
선택되었고, AWS Spring Boot module은 BOM이 제공하는 자체 버전 메타데이터로
해석될 수 있음을 확인했다. 개별 Bluetape BOM 또는 명시적 module version은
추가하지 않았다. LSP 서버와 모듈 전용 `detekt` task는 제공되지 않았으나 root
`detekt`가 통과했다.

## 남은 위험

현재 diff에서 P0/P1/P2 blocker는 확인되지 않았다. 다음은 이번 consumer의
범위 밖인 P3 gap이다.

- multipart upload와 writable S3 resource는 별도 예제로 추적한다.
- 실제 AWS IAM/signing, 네트워크 retry와 upstream resolver 내부 parser/paginator
  구현은 upstream 소유이며 Floci test 성공을 실제 AWS 운영 보증으로 해석하지 않는다.
- resolver 결과 stream을 호출자가 닫아야 하므로 README의 `inputStream.use` 규칙을
  유지한다.

## 최종 판정

**READY FOR PR — P0=0, P1=0, P2=0.** 현재 보정본은 local test/build,
stale/README/언어/정적 검증과 등록 증거를 충족한다. PR 생성 뒤에는 exact live
head의 CI·review·thread·mergeability를 다시 읽고, 최신 head에 대한 사용자의
새 `승인` 없이는 merge하지 않는다.
