# Issue #871 S3 Resource pattern resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `bluetape4k-dependencies 2.0.0-SNAPSHOT`의 S3 exact protocol과 single-bucket wildcard `ResourcePatternResolver`를 기존 Spring Cloud AWS S3 소비자 예제에 연결하고, Floci에서 deterministic·read-only 계약을 재현한다.

**Architecture:** 샘플이 소유한 Floci `S3Client`는 그대로 두고, `S3ResourceAutoConfiguration`이 등록한 `s3ResourcePatternResolver`를 qualifier로 주입한다. Bluetape exact와 `s3://bucket/config/**/*.yml` listing은 모두 이 `ResourcePatternResolver`를 사용하고, 기존 `ResourceLoader` 호출은 Spring Cloud AWS 회귀 경로로 유지한다. Spring Cloud AWS transfer 자동 구성의 `s3ObjectConverter` 이름 충돌은 `spring.autoconfigure.exclude`로 `io.bluetape4k.aws.spring.s3.S3TransferAutoConfiguration`만 제외해 차단한다.

**Tech Stack:** Kotlin 2.4, Java 25, Spring Boot 4, Spring Cloud AWS S3, AWS SDK v2, bluetape4k AWS Spring Boot 2.0.0-SNAPSHOT, JUnit 5, Floci/Testcontainers, Gradle version catalog.

---

## 변경 파일 지도

| 파일 | 책임 |
| --- | --- |
| `aws/s3-spring-cloud/src/main/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Sample.kt` | qualifier resolver 주입, exact Resource read-only 사용, wildcard sample fixture와 로그 |
| `aws/s3-spring-cloud/src/test/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Test.kt` | Floci exact/pattern/pagination/metadata/stream/parser 경계 통합 검증 |
| `aws/s3-spring-cloud/src/main/resources/application.yml` | transfer auto-configuration만 제외하고 S3 resource 자동 구성 활성화 |
| `aws/s3-spring-cloud/README.md` | English exact/pattern 사용법과 read-only 제약 |
| `aws/s3-spring-cloud/README.ko.md` | 위 문서의 한국어 동등 내용 |
| `docs/coverage-matrix.md` | S3 Resource pattern coverage와 남은 multipart gap |
| `scripts/smoke-validate.sh` | stale-check의 Issue #871 sample contract guard |
| `docs/ecosystem-reuse-train.json` | Issue #871 child scope와 허용 경로/검증 task |
| `docs/lessons/2026-09-01-issue-871-s3-resource-pattern-resolver.md` | 실제 구현·검증 결과와 재사용 교훈 |
| `docs/review/2026-09-01-issue-871-s3-resource-pattern-resolver-review.md` | 독립 7-Tier review와 P0/P1/P2 판정 |
| `docs/superpowers/specs/2026-09-01-issue-871-s3-resource-pattern-design.md` | 승인된 설계 근거 |
| `docs/superpowers/plans/2026-09-01-issue-871-s3-resource-pattern-plan.md` | 이 실행 계획 |

`gradle/libs.versions.toml`과 `.github/workflows/Examples.yml`은 변경하지 않는다.
전자는 이미 `bluetape4k-dependencies-version = "2.0.0-SNAPSHOT"`과 versionless
alias를 사용하고, 후자는 `:aws-s3-spring-cloud:test`와 report path를 이미
workflow에 등록하고 있으므로 dependency/CI drift를 먼저 검증한다.

## Task 1: S3 자동 구성 경계를 RED로 고정

**Files:**

- Modify: `aws/s3-spring-cloud/src/main/resources/application.yml`
- Test: `aws/s3-spring-cloud/src/test/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Test.kt`

- [x] **Step 1: qualifier와 read-only 기대를 먼저 테스트에 선언한다**

기존 테스트 constructor에 다음 parameter를 추가하고, exact resource가
`S3Resource` 읽기 전용 resource여도 본문을 읽을 수 있음을
보여 주는 테스트를 추가한다.

```kotlin
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.support.ResourcePatternResolver
import io.bluetape4k.aws.spring.s3.S3Resource

@SpringBootTest(classes = [SpringCloudAwsS3Sample::class])
class SpringCloudAwsS3Test @Autowired constructor(
    private val s3Client: S3Client,
    private val s3Template: S3Template,
    @Qualifier("s3ResourcePatternResolver")
    private val resourcePatternResolver: ResourcePatternResolver,
) : AbstractSpringCloudAwsS3SampleTest() {

    @Test
    fun `auto configured resolver reads an exact S3 resource`() {
        val resource = resourcePatternResolver.getResource(
            "s3://spring-cloud-aws-sample-bucket1/test-file.txt",
        )

        resource.exists().shouldBeTrue()
        (resource is S3Resource).shouldBeTrue()
        resource.readContent() shouldBeEqualTo "test file content"
    }
}
```

`S3Resource` 타입 assertion으로 읽기 전용 구현과 기존 fixture 재사용을 고정한다.

- [x] **Step 2: test를 실행해 기능 부재의 실패를 확인한다**

Run:

```bash
./gradlew :aws-s3-spring-cloud:test --tests '*SpringCloudAwsS3Test.auto configured resolver reads an exact S3 resource' --no-build-cache --no-daemon --max-workers=1 --console=plain
```

Expected: 현재 `application.yml`의 `bluetape4k.aws.s3.enabled=false` 때문에
`s3ResourcePatternResolver` bean을 찾지 못하거나 exact resource가
`S3Resource`가 아닌 상태로 남는 **RED**가 발생한다. `NoSuchBeanDefinitionException`이
아닌 다른 오류면 fixture/context 설정을 먼저 수정하고 같은 테스트가 resolver
부재를 가리키도록 다시 실행한다.

- [x] **Step 3: 충돌하는 transfer auto-configuration만 제외한다**

`application.yml`의 기존 전체 switch를 다음으로 교체한다.

```yaml
# Spring Cloud AWS가 S3Client/S3Template을 소유한다. transfer 자동 구성의
# s3ObjectConverter bean 이름 충돌만 제외하고, S3 Resource auto-configuration은 켠다.
spring:
    autoconfigure:
        exclude:
            - io.bluetape4k.aws.spring.s3.S3TransferAutoConfiguration
```

`bluetape4k.aws.s3.enabled=false`를 남기지 않는다. 수동 `s3Client` bean은
upstream `S3ResourceAutoConfiguration`의 `@ConditionalOnBean(S3Client::class)`를
만족해야 한다.

- [x] **Step 4: exact RED를 GREEN으로 확인한다**

Run the same targeted test. Expected: Spring context가 시작되고
`s3ResourcePatternResolver` 주입, exact body read, `S3Resource` read-only 확인이
PASS한다. 이 단계에서 실패하면 converter bean name collision과 S3 client
조건을 context startup failure 원인으로 분리해 수정한다.

## Task 2: 기존 sample에 wildcard resolver를 연결

**Files:**

- Modify: `aws/s3-spring-cloud/src/main/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Sample.kt`
- Test: `aws/s3-spring-cloud/src/test/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Test.kt`

- [x] **Step 1: wildcard 결과를 요구하는 RED 테스트를 추가한다**

test class에 다음 상수와 테스트를 추가한다. `ResourcePatternResolver`는
qualifier로 이미 주입된 실제 bean이며, 테스트는 pattern API가 없으면 컴파일
또는 context 단계에서 실패한다.

```kotlin
private const val SAMPLE_BUCKET = "spring-cloud-aws-sample-bucket1"
private const val CONFIG_PATTERN = "s3://$SAMPLE_BUCKET/config/**/*.yml"

@Test
fun `lists matching config resources in deterministic order`() {
    val resources = resourcePatternResolver.getResources(CONFIG_PATTERN)
    val keys = resources.map { (it as S3Resource).location.key }

    keys shouldBeEqualTo listOf(
        "config/application.yml",
        "config/nested/application.yml",
        "config/z.yml",
    )
}
```

이 RED는 먼저 sample runner에 `config/application.yml`,
`config/nested/application.yml`, `config/z.yml` fixture가 없으므로 기대한
목록을 얻지 못해야 한다. 테스트 fixture가 다른 test와 공유되면 bucket/key를
먼저 확인해 이름 충돌을 제거한다.

- [x] **Step 2: sample runner에 resolver와 최소 fixture를 연결한다**

`SpringCloudAwsS3Sample.applicationRunner` signature에 다음 parameter를
추가하고 exact cast를 제거한다.

```kotlin
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.support.ResourcePatternResolver

private const val CONFIG_PATTERN =
    "s3://spring-cloud-aws-sample-bucket1/config/**/*.yml"

@Bean
fun applicationRunner(
    s3Client: S3Client,
    s3Template: S3Template,
    @Qualifier("s3ResourcePatternResolver")
    resourcePatternResolver: ResourcePatternResolver,
): ApplicationRunner = ApplicationRunner {
    s3Client.ensureBucketExists("spring-cloud-aws-sample-bucket1")
    s3Client.ensureBucketExists("spring-cloud-aws-sample-bucket2")
    s3Template.uploadText("spring-cloud-aws-sample-bucket1", "test-file.txt", "test file content")
    s3Template.uploadText("spring-cloud-aws-sample-bucket1", "my-file.txt", "my file content")
    s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/application.yml", "name: sample\n")
    s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/nested/application.yml", "name: nested\n")
    s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/z.yml", "name: z\n")
    s3Template.uploadText("spring-cloud-aws-sample-bucket1", "config/readme.txt", "not yaml\n")

    s3Client.listObjects { it.bucket("spring-cloud-aws-sample-bucket1") }
        .contents()
        .forEach { log.info { "Object in bucket: ${it.key()}" } }

    val exact = resourcePatternResolver.getResource(TEST_FILE_URL)
    exact.inputStream.use { input ->
        log.info { "File content: ${input.bufferedReader().readText()}" }
    }
    resourcePatternResolver.getResources(CONFIG_PATTERN).forEach { resource ->
        resource.inputStream.use { input ->
            log.info { "Config match: ${resource.filename}, bytes=${input.available()}" }
        }
    }
}
```

`inputStream.available()`는 예제 로그의 간단한 관찰값으로만 사용하며,
콘텐츠 길이 계약은 test의 `contentLength()`로 검증한다. `readContent()` helper는
기존처럼 `use`를 내부에서 수행해도 된다.

- [x] **Step 3: sample fixture RED를 GREEN으로 확인한다**

Run:

```bash
./gradlew :aws-s3-spring-cloud:test --tests '*SpringCloudAwsS3Test' --no-build-cache --no-daemon --max-workers=1 --console=plain
```

Expected: 기존 회귀와 new deterministic wildcard test가 모두 PASS하고,
resolver는 exact key에 대해 `ListObjectsV2`를 호출하지 않는다.

- [x] **Step 4: implementation commit을 만든다**

```bash
git add aws/s3-spring-cloud/src/main/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Sample.kt \
  aws/s3-spring-cloud/src/test/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Test.kt \
  aws/s3-spring-cloud/src/main/resources/application.yml
git commit -m "Issue #871 S3 pattern resolver 소비자 예제를 연결한다" \
  -m "기존 exact 흐름을 보존하면서 qualifier 기반 wildcard 읽기와 transfer 경계를 추가한다." \
  -m "Constraint: Floci local-first와 Spring Cloud AWS S3Template 소유권을 유지한다." \
  -m "Rejected: 전체 bluetape4k S3 자동 구성 비활성화와 S3TransferAutoConfiguration 유지(transfer.enabled 토글만 사용)는 resolver 비활성화 또는 converter 충돌을 남기므로 채택하지 않았다." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: wildcard write와 cross-bucket pattern을 추가하지 않는다." \
  -m "Tested: targeted SpringCloudAwsS3Test PASS." \
  -m "Not-tested: pagination 규모 fixture와 문서 검증은 후속 task다."
```

## Task 3: Floci pagination·metadata·parser 경계 테스트

**File:** `aws/s3-spring-cloud/src/test/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Test.kt`

- [x] **Step 1: 1,001개 object fixture를 만드는 RED 테스트를 작성한다**

테스트는 unique bucket을 만들고 `config/pagination-0000.yml`부터
`config/pagination-1000.yml`까지 1,001개의 zero/short-byte object와 고정
fixture를 저장한다. AWS `ListObjectsV2` 기본 최대 1,000개를 넘겨 SDK paginator가
두 page 이상을 소비하도록 한다.

```kotlin
private const val PAGINATION_FIXTURE_COUNT = 1_001

@Test
fun `consumes Floci pagination sorts matches and closes returned streams`() {
    val bucket = "issue-871-pattern-${Base58.randomString(10).lowercase()}"
    val generatedKeys = (0 until PAGINATION_FIXTURE_COUNT).map { index ->
        "config/pagination-${index.toString().padStart(4, '0')}.yml"
    }
    val fixedKeys = listOf(
        "config/application.yml",
        "config/nested/application.yml",
        "config/z.yml",
        "config/readme.txt",
        "other/application.yml",
    )
    val allKeys = generatedKeys + fixedKeys

    try {
        s3Client.createBucket { it.bucket(bucket) }
        allKeys.forEach { key ->
            s3Client.putObject(
                { it.bucket(bucket).key(key).contentType("text/yaml") },
                RequestBody.fromString("key=$key\n"),
            )
        }

        val exact = resourcePatternResolver.getResources(
            "s3://$bucket/config/application.yml",
        ).single()
        exact.exists().shouldBeTrue()
        exact.contentLength() shouldBeEqualTo "key=config/application.yml\n".toByteArray().size.toLong()
        (exact.lastModified() > 0).shouldBeTrue()
        (exact is S3Resource).shouldBeTrue()
        exact.inputStream.use { it.bufferedReader().readText() } shouldBeEqualTo
            "key=config/application.yml\n"

        val matches = resourcePatternResolver.getResources("s3://$bucket/config/**/*.yml")
        val matchKeys = matches.map { (it as S3Resource).location.key }
        matchKeys shouldBeEqualTo matchKeys.sorted()
        matchKeys.size shouldBeEqualTo PAGINATION_FIXTURE_COUNT + 3

        resourcePatternResolver.getResources("s3://$bucket/config/**/*.json")
            .size shouldBeEqualTo 0
    } finally {
        allKeys.chunked(1_000).forEach { chunk ->
            s3Client.deleteObjects { request ->
                request.bucket(bucket)
                request.delete { delete ->
                    delete.objects(chunk.map { key ->
                        ObjectIdentifier.builder().key(key).build()
                    })
                }
            }
        }
        runCatching { s3Client.deleteBucket { it.bucket(bucket) } }
    }
}
```

Imports are explicit: `Base58`, `RequestBody`, `ObjectIdentifier`, `S3Resource`,
and the existing Bluetape assertions. The
`lastModified` check uses the existing `(value > 0).shouldBeTrue()` assertion and
does not introduce a test-only helper.

- [x] **Step 2: run the test and inspect the first failure**

```bash
./gradlew :aws-s3-spring-cloud:test --tests '*consumes Floci pagination sorts matches and closes returned streams' --no-build-cache --no-daemon --max-workers=1 --console=plain
```

Expected RED before fixture implementation: missing `RequestBody`/fixture code or
resolver bean failure. After compilation is corrected, the test must fail only on
the new behavior assertion, never because cleanup leaked the bucket.

- [x] **Step 3: add parser boundary assertions**

In the same test class, add a separate test with the existing injected resolver:

```kotlin
@Test
fun `rejects unsupported S3 patterns before any network access`() {
    assertFailsWith<IllegalArgumentException> {
        resourcePatternResolver.getResources("s3://*/config/**/*.yml")
    }
    assertFailsWith<IllegalArgumentException> {
        resourcePatternResolver.getResources("s3://spring-cloud-aws-sample-bucket1/*.yml")
    }
    assertFailsWith<IllegalArgumentException> {
        resourcePatternResolver.getResources(
            "s3://spring-cloud-aws-sample-bucket1/config/**/*.yml,s3://other/config/**/*.yml",
        )
    }
}
```

`assertFailsWith`는 `io.bluetape4k.assertions.assertFailsWith`를 사용한다.
각 parser failure는 provider 조회 전에 발생해야 하므로 Floci endpoint 요청이
추가되지 않는 것이 성공 조건이다.

- [x] **Step 4: pagination·metadata·empty/parser test를 GREEN으로 확인한다**

```bash
./gradlew :aws-s3-spring-cloud:cleanTest :aws-s3-spring-cloud:test --no-build-cache --no-daemon --max-workers=1 --console=plain
```

Expected: 기존 test와 신규 test 전체 PASS, `config/**/*.yml` 결과 1,004개,
`config/**/*.json` 결과 0개, exact resource의 `exists/contentLength/lastModified`
및 `use` 기반 stream read PASS. 실패 시 generated key 개수와 cleanup
chunk(최대 1,000)를 먼저 확인한다.

## Task 4: README와 등록 계약 동기화

**Files:**

- Modify: `aws/s3-spring-cloud/README.md`
- Modify: `aws/s3-spring-cloud/README.ko.md`
- Modify: `docs/coverage-matrix.md`
- Modify: `scripts/smoke-validate.sh`
- Modify: `docs/ecosystem-reuse-train.json`

- [x] **Step 1: 두 README에 동등한 pattern 섹션을 작성한다**

각 README의 feature/usage section에 다음 의미를 같은 순서로 추가한다.

```kotlin
class ConfigReader(
    @Qualifier("s3ResourcePatternResolver")
    private val resources: ResourcePatternResolver,
) {
    fun yamlFiles(): Array<Resource> =
        resources.getResources("s3://my-bucket/config/**/*.yml")
}
```

문서에는 반드시 다음을 명시한다.

- `s3://bucket/key` exact는 고정 qualifier의 `ResourcePatternResolver`로 읽고,
  기존 `ResourceLoader` 호출은 Spring Cloud AWS 회귀 경로로 유지한다.
- wildcard는 literal bucket과 비어 있지 않은 prefix가 필요하며 `*`, `?`, `**`
  만 지원한다.
- 모든 paginator page를 소비한 결과는 sorted/deduplicated resource array다.
- match가 없으면 빈 배열이며 per-key GET/HEAD를 수행하지 않는다.
- `Resource`는 read-only이고 `inputStream.use { ... }`로 caller가 stream을 닫는다.
- wildcard bucket, cross-bucket glob, `s3://bucket/*.yml` root/empty prefix,
  write/output stream은 지원하지 않는다.
- `spring.autoconfigure.exclude`의
  `io.bluetape4k.aws.spring.s3.S3TransferAutoConfiguration`는 Spring Cloud AWS
  `s3ObjectConverter` 이름 충돌 방지용이며 S3 Resource auto-configuration은
  켜져 있다.
- Floci/Testcontainers만 기본 경로에 사용하며 real AWS credential은 필요 없다.

기존 `as WritableResource` 예제는 `Resource`로 바꾸고 모든 input stream을
`use`로 감싼다. `README.md`와 `README.ko.md`의 코드/API/명령은 동일하게
유지하고 설명만 언어별로 번역한다.

- [x] **Step 2: coverage matrix를 실제 gap에 맞게 갱신한다**

AWS S3 row의 current coverage에 exact qualifier resolver와 single-bucket wildcard
resolver, Floci pagination/ordering을 기록하고, multipart upload와 writable
resource는 남은 gap으로 유지한다. 마지막 column에 `#871`을 추가한다. 기존
`bluetape4k-aws` row는 coroutine wrapper gap을 실제 상태대로 유지한다.

- [x] **Step 3: stale-check에 좁은 Issue #871 guard를 추가한다**

`stale-check` case의 AppConfig guard 다음에 다음 변수를 검사한다.

```bash
echo ""
echo "=== AWS S3 Resource pattern example guard ==="
s3_pattern_main="aws/s3-spring-cloud/src/main/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Sample.kt"
s3_pattern_test="aws/s3-spring-cloud/src/test/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Test.kt"
s3_pattern_config="aws/s3-spring-cloud/src/main/resources/application.yml"
s3_pattern_readme="aws/s3-spring-cloud/README.md"
s3_pattern_readme_ko="aws/s3-spring-cloud/README.ko.md"
s3_pattern_lesson="docs/lessons/2026-09-01-issue-871-s3-resource-pattern-resolver.md"
if contains_disabled_bluetape_s3_switch "$s3_pattern_config"; then
  echo "ERROR: AWS S3 Resource pattern example still disables the global bluetape4k S3 auto-configuration."
  exit 1
fi
if contains_pattern 's3ResourcePatternResolver' "$s3_pattern_main" "$s3_pattern_test" "$s3_pattern_readme" "$s3_pattern_readme_ko" && \
   contains_pattern 'config/\*\*/\*\.yml' "$s3_pattern_main" "$s3_pattern_test" "$s3_pattern_readme" "$s3_pattern_readme_ko" && \
   contains_pattern 'autoconfigure:' "$s3_pattern_config" && \
   contains_pattern 'S3TransferAutoConfiguration' "$s3_pattern_config" && \
   contains_pattern 'PAGINATION_FIXTURE_COUNT' "$s3_pattern_test" && \
   [ -f "$s3_pattern_lesson" ]; then
  echo "AWS S3 Resource pattern example and lesson are registered."
else
  echo "ERROR: AWS S3 Resource pattern example contract is missing or stale."
  exit 1
fi
```

The guard must not accept the old global `bluetape4k.aws.s3.enabled: false` as a
substitute for excluding `S3TransferAutoConfiguration`.

- [x] **Step 4: ecosystem reuse child scope를 등록한다**

`docs/ecosystem-reuse-train.json`의 `follow_up_scopes` 또는 현재 child scope
목록에 다음 JSON object를 추가한다. JSON formatter로 trailing comma와 key order를
검증한다.

```json
{
  "scope_id": "issue-871-aws-s3-resource-pattern",
  "scope_kind": "child",
  "parent_track": "P0",
  "expected_head_ref": "feat/issue-871-s3-resource-pattern-resolver",
  "expected_base_ref": "develop",
  "base_ref_policy": "repository-base-after-parent-merge",
  "oid_policy": "rebase-aware",
  "head_oid": null,
  "base_oid": null,
  "issue_numbers": [871],
  "allowed_paths": [
    "aws/s3-spring-cloud/**",
    "docs/coverage-matrix.md",
    "docs/ecosystem-reuse-train.json",
    "docs/lessons/2026-09-01-issue-871-s3-resource-pattern-resolver.md",
    "docs/review/2026-09-01-issue-871-s3-resource-pattern-resolver-review.md",
    "docs/superpowers/plans/2026-09-01-issue-871-s3-resource-pattern-plan.md",
    "docs/superpowers/specs/2026-09-01-issue-871-s3-resource-pattern-design.md",
    "scripts/smoke-validate.sh"
  ],
  "review_artifact": "docs/review/2026-09-01-issue-871-s3-resource-pattern-resolver-review.md"
}
```

`allowed_paths`는 실제 변경 파일과 일치해야 하고, `.github/workflows/Examples.yml`
은 기존 등록이므로 추가하지 않는다.

- [ ] **Step 5: docs/registration commit을 만든다**

```bash
git add aws/s3-spring-cloud/README.md aws/s3-spring-cloud/README.ko.md \
  docs/coverage-matrix.md scripts/smoke-validate.sh docs/ecosystem-reuse-train.json
git commit -m "Issue #871 S3 pattern resolver 문서·검증 등록을 동기화한다" \
  -m "영문·한국어 README와 matrix, stale guard, ecosystem scope가 같은 read-only contract를 추적하게 한다." \
  -m "Constraint: 기존 Examples workflow 등록과 BOM-only dependency 경계를 유지한다." \
  -m "Rejected: 이미 등록된 workflow에 중복 job을 추가하지 않았다." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: README의 exact/pattern 제약을 변경할 때 stale guard도 함께 갱신한다." \
  -m "Tested: README parity/link checks와 stale-check 예정." \
  -m "Not-tested: 전체 AWS smoke는 후속 검증 단계다."
```

## Task 5: lesson/review artifact와 최종 검증

**Files:**

- Create: `docs/lessons/2026-09-01-issue-871-s3-resource-pattern-resolver.md`
- Create: `docs/review/2026-09-01-issue-871-s3-resource-pattern-resolver-review.md`

- [x] **Step 1: fresh validation을 실행한다**

순서와 기대 결과는 다음과 같다.

```bash
./gradlew :aws-s3-spring-cloud:cleanTest :aws-s3-spring-cloud:test --no-build-cache --no-daemon --max-workers=1 --console=plain
./gradlew :aws-s3-spring-cloud:build --no-build-cache --no-daemon --max-workers=1 --console=plain
bash scripts/smoke-validate.sh aws
bash scripts/smoke-validate.sh stale-check
./gradlew projects --console=plain
node scripts/validate-readme-parity.mjs aws/s3-spring-cloud
git diff --check
```

Expected: module test/build PASS, AWS smoke와 stale-check PASS, project graph에
`:aws-s3-spring-cloud`가 한 번만 나타남, README parity failure 0, diff-check
출력 없음. `./gradlew detekt`는 변경 Kotlin 전체의 static analysis로 추가 실행한다.

Dependency authority evidence:

```bash
./gradlew :aws-s3-spring-cloud:dependencyInsight \
  --dependency io.github.bluetape4k:bluetape4k-aws \
  --configuration testRuntimeClasspath --console=plain
```

Expected: `io.github.bluetape4k:bluetape4k-dependencies:2.0.0-SNAPSHOT` is
selected and supplies the versionless Bluetape4k aliases (the AWS module may
report its own 버전 메타데이터), with no individual bluetape4k BOM or
explicit module version in the module/catalog.

- [x] **Step 2: lesson에 실행 증거를 기록한다**

lesson은 Korean으로 작성하고 다음을 포함한다.

- Issue/upstream Issue #463·PR #538 link와 적용한 consumer boundary
- changed files와 `S3TransferAutoConfiguration` 제외 이유
- exact/pattern/pagination count/empty/metadata/stream/parser test 결과
- module/build/smoke/stale/parity/diff-check/detekt 결과
- real AWS credential 미사용과 Floci cleanup 방식
- 남은 multipart/writable/cross-bucket gap
- `audit-korean-terms.mjs` 결과와 재발 방지 규칙

- [x] **Step 3: 독립 7-Tier review artifact를 작성한다**

review table은 Code/API, Tests/Regression, Security/Trust, Performance/Stability,
Docs/UX, Build/Operations, Scope/Ownership 각 lens에 P0/P1/P2 수와 근거를
기록한다. `P0=0, P1=0`이 아니면 PR 생성 전에 수정한다. review는 upstream
resolver의 pagination/parser/client ownership을 재구현하지 않았는지 확인한다.

- [ ] **Step 4: docs/review commit을 만든다**

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/lessons/2026-09-01-issue-871-s3-resource-pattern-resolver.md \
  docs/review/2026-09-01-issue-871-s3-resource-pattern-resolver-review.md
git add docs/lessons/2026-09-01-issue-871-s3-resource-pattern-resolver.md \
  docs/review/2026-09-01-issue-871-s3-resource-pattern-resolver-review.md
git commit -m "Issue #871 S3 pattern resolver 검증 교훈을 기록한다" \
  -m "Floci pagination과 Spring Resource read-only 경계를 재현한 검증 증거와 재사용 교훈을 남긴다." \
  -m "Constraint: 실제 AWS credential 없이 CI와 local smoke를 반복할 수 있어야 한다." \
  -m "Rejected: upstream 내부 테스트를 consumer 성공 증거로 대체하지 않았다." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Directive: 다음 S3 예제도 exact/pattern ownership과 stream close를 명시한다." \
  -m "Tested: full validation commands and Korean terminology audit." \
  -m "Not-tested: hosted CI is checked after push."
```

## Task 6: PR·CI·승인·merge·sync/cleanup

- [ ] **Step 1: final local diff와 workflow receipt를 확인한다**

변경 파일이 계획의 허용 경로뿐인지 확인하고, `git status --short`가 clean인지
확인한다. workflow `check-result`/`component-evidence`에는 `design`, `tests`,
`docs`, `pr` 각 fresh command 결과를 기록하고 completion-check에서
`complete:true`를 얻는다.

- [ ] **Step 2: branch를 push하고 Korean PR을 만든다**

```bash
git push -u origin feat/issue-871-s3-resource-pattern-resolver
gh pr create --base develop --head feat/issue-871-s3-resource-pattern-resolver \
  --title "[2.0.0] Issue #871 S3 Resource pattern resolver 소비자 예제를 추가한다" \
  --body-file /tmp/issue-871-pr.md
```

PR body는 Korean으로 작성하고 `Closes #871`, 변경 요약, test/smoke/stale
증거, `## DoD Status`, real AWS 제외와 upstream ownership을 포함한다. PR metadata는
Issue와 같은 milestone `2.0.0`, assignee `debop`, required labels를 유지한다.

- [ ] **Step 3: exact head의 hosted CI와 review를 재확인한다**

`gh pr view --json headRefOid,baseRefName,statusCheckRollup,reviews,reviewDecision,mergeable`
와 checks/logs를 다시 읽는다. `Required checks: X/Y; N/A:N; Blocked:0` 형식의
merge-ready 보고서를 만들고, 실패한 check만 원인에 맞게 수정한다.

- [ ] **Step 4: fresh approval 후 exact head만 merge한다**

사용자의 새 `승인`이 현재 PR head SHA에 묶였음을 확인한 뒤에만
`gh pr merge --squash --match-head-commit <exact-head> --delete-branch`를
실행한다. auto-merge는 사용하지 않는다.

- [ ] **Step 5: merge 뒤 root develop 동기화와 좁은 cleanup을 증명한다**

root worktree에서 `git fetch origin`, `git switch develop`,
`git merge --ff-only origin/develop`를 실행하고 `git rev-list --left-right
--count develop...origin/develop`가 `0 0`인지 확인한다. merged feature worktree와
branch만 제거하고, dirty/active unrelated worktree는 보존한다. 마지막으로
merged PR, issue closed/milestone, root parity, preserved worktree 목록을
workflow `local-hygiene` evidence로 기록하고 completion-check를 다시 실행한다.

## Rollback boundary

- 테스트/문서 단계에서 실패하면 해당 commit을 revert하거나 수정 commit을 추가한다.
- `bluetape4k.aws.s3.enabled=false`를 복구하면 resolver가 사라지므로 rollback
  시에도 transfer-only 설정과 exact read-only test의 원인을 함께 검토한다.
- merge 전에는 feature branch/worktree만 되돌릴 수 있고, root `develop`과
  unrelated dirty worktree는 건드리지 않는다.
- 이미 merge된 remote branch나 issue metadata를 되돌리는 destructive action은
  별도 명시 권한 없이는 실행하지 않는다.
