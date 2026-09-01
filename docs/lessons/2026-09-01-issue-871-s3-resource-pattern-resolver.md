# Issue #871 S3 Resource pattern resolver 소비자 예제 교훈

## 결과

기존 `aws/s3-spring-cloud` 예제에 `bluetape4k-aws 2.0.0-SNAPSHOT`의
`S3ResourcePatternResolver`를 실제 consumer 경로로 연결했다. 고정 qualifier
`s3ResourcePatternResolver`로 exact `s3://bucket/key`와
`s3://bucket/config/**/*.yml` wildcard를 읽고, Floci에서 1,001개 generated
object를 포함한 1,004개 match의 paginator·정렬 계약을 검증한다.

근거는 [workshop Issue #871](https://github.com/bluetape4k/bluetape4k-workshop/issues/871),
upstream [bluetape4k-aws Issue #463](https://github.com/bluetape4k/bluetape4k-aws/issues/463)와
[PR #538](https://github.com/bluetape4k/bluetape4k-aws/pull/538)이다. upstream 구현의
parser, `ListObjectsV2` paginator, deduplication, `String.compareTo` 정렬을
consumer에서 재구현하지 않고 주입해 사용했다.

## 변경과 경계

- `SpringCloudAwsS3Sample`은 기존 Floci `S3Client`와 Spring Cloud AWS
  `S3Template` 소유권을 유지하면서 `ResourcePatternResolver`를 qualifier로
  주입한다. exact·wildcard 결과의 stream은 `inputStream.use { ... }`로 닫는다.
- `S3Template.store(String)`가 raw text가 아닌 JSON 문자열 표현을 만들 수 있어,
  샘플 fixture는 content length와 `text/plain` metadata를 명시한 `uploadText`
  helper로 저장한다.
- Spring Cloud AWS와 `s3ObjectConverter` bean 이름이 충돌하므로
  `bluetape4k.aws.s3.enabled=false` 같은 전체 switch를 사용하지 않고,
  `spring.autoconfigure.exclude`에
  `io.bluetape4k.aws.spring.s3.S3TransferAutoConfiguration`만 등록했다.
  `S3ResourceAutoConfiguration`과 exact protocol은 계속 활성화된다.
- wildcard bucket, cross-bucket glob, `s3://bucket/*.yml` root/empty prefix,
  write/output stream은 parser와 read-only `Resource` 계약에 따라 지원하지 않는다.
  match가 없으면 per-object HEAD/GET 없이 빈 배열을 반환한다.

## 검증 증거

| 검증 | 결과 |
| --- | --- |
| `./gradlew :aws-s3-spring-cloud:cleanTest :aws-s3-spring-cloud:test --no-build-cache --no-daemon --max-workers=1 --console=plain` | 5개 테스트 PASS. exact `S3Resource`, wildcard deterministic order, Floci pagination 1,004 match, metadata·stream close, empty result, parser boundary를 포함한다. |
| `./gradlew :aws-s3-spring-cloud:build --no-build-cache --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL` |
| `./gradlew detekt --no-build-cache --no-daemon --console=plain` | `BUILD SUCCESSFUL` |
| `bash scripts/smoke-validate.sh aws` | `BUILD SUCCESSFUL` |
| `bash scripts/smoke-validate.sh stale-check` | active module count, required registration, S3 resolver guard와 broken image 검사 PASS |
| `./gradlew projects --no-build-cache --no-daemon --console=plain` | `:aws-s3-spring-cloud` 단일 등록, `BUILD SUCCESSFUL` |
| ecosystem reuse scope canonical JSON SHA-256 | `28274493cad95e925c9bba1be0244f8025ad0ee427a6e5a9a97e37965f78c120`; fresh coordinator receipt `20260901T-issue-871-aws-s3-resource-pattern-scope` |
| `./gradlew :aws-s3-spring-cloud:dependencyInsight --dependency bluetape4k-dependencies --configuration testRuntimeClasspath --console=plain` | `io.github.bluetape4k:bluetape4k-dependencies:2.0.0-SNAPSHOT` 선택 확인. module alias는 versionless이며 개별 BOM/pin을 추가하지 않았다. |
| `node scripts/validate-readme-parity.mjs aws/s3-spring-cloud` | `failures: 0` |
| `node scripts/validate-readme-language.mjs aws/s3-spring-cloud/README.md aws/s3-spring-cloud/README.ko.md` | `offenders: 0`, `totalHits: 0` |
| `node .../audit-korean-terms.mjs` (설계·계획·lesson·review) | `findings=0` |
| `git diff --check` | PASS |

Floci integration은 unique bucket을 만들고 1,006개 object를 업로드한 뒤
`DeleteObjects` 최대 1,000개 chunk로 정리하고 bucket을 삭제한다. 고정 sample
bucket과 Floci singleton은 기존 예제 수명주기를 따르며, 실제 AWS credential이나
endpoint는 사용하지 않는다.

## 남은 gap과 재사용 규칙

- AWS S3 coverage의 multipart upload 예제는 별도 gap으로 남아 있다.
- Spring Cloud AWS의 기본 `ResourceLoader` 호출은 호환성 회귀로 유지하지만,
  Bluetape exact/pattern 동작과 wildcard는 `@Qualifier("s3ResourcePatternResolver")`
  주입을 사용한다. `ApplicationContext.getResources(...)`가 이 bean으로 자동
  interception된다고 가정하지 않는다.
- 다음 S3 consumer 예제도 single literal bucket, non-empty prefix, read-only
  stream ownership, all-page ordering과 empty-match 의미를 README·테스트에 함께
  기록한다. converter 이름 충돌이 있는 조합은 class-level
  `S3TransferAutoConfiguration` exclusion을 먼저 확인한다.
