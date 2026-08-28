# #741 Bedrock Converse/ConverseStream consumer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** AWS Kotlin Bedrock의 model-neutral \`Converse\`와 cold \`ConverseStream\` Flow를 credential-free 테스트로 설명하는 \`aws/bedrock-converse\` consumer module을 추가한다.

**Architecture:** \`BedrockPrompt\`가 입력을 보유하고 \`BedrockConverseService\`가 매 호출마다 \`BedrockRuntimeClient\`를 factory에서 받아 \`useSafe\` 범위로 사용한다. non-streaming은 텍스트를 반환하고 streaming은 collection 시 client를 만들고 닫는 \`Flow<String>\`을 반환한다. 기본 실행은 local 안내만 제공하고 real AWS 호출은 명시적 \`real-aws\` 모드에서만 허용한다.

**Tech Stack:** Kotlin 2.4, Java 25, AWS Kotlin SDK \`bedrockruntime\`, \`bluetape4k-aws-kotlin\` 0.5.0, kotlinx.coroutines Flow, JUnit 5, MockK, \`bluetape4k-assertions\`, Gradle application plugin.

---

### Task 1: 모듈 골격과 catalog alias 추가

**Files:**
- Create: \`aws/bedrock-converse/build.gradle.kts\`
- Create: \`aws/bedrock-converse/src/main/kotlin/io/bluetape4k/workshop/aws/bedrock/BedrockConverseApplication.kt\`
- Create: \`aws/bedrock-converse/src/main/resources/logback.xml\`
- Create: \`aws/bedrock-converse/src/test/resources/junit-platform.properties\`
- Create: \`aws/bedrock-converse/src/test/resources/logback-test.xml\`
- Modify: \`gradle/libs.versions.toml: AWS Kotlin aliases\`

- [ ] **Step 1: Write the failing module registration test**

\`\`\`bash
./gradlew :aws-bedrock-converse:tasks --all --console=plain
\`\`\`

Expected: FAIL because the project and \`aws-kotlin-bedrock-runtime\` alias do not yet exist.

- [ ] **Step 2: Add the module and versionless-by-consumer alias**

Add this catalog entry next to \`aws-kotlin-dynamodb\`:

\`\`\`toml
aws-kotlin-bedrock-runtime = { module = "aws.sdk.kotlin:bedrockruntime", version.ref = "aws-kotlin" }
\`\`\`

Create \`build.gradle.kts\` with \`application\`, \`libs.aws.kotlin.bedrock.runtime\`, \`libs.bluetape4k.aws.kotlin\`, \`libs.bluetape4k.core\`, \`libs.bluetape4k.coroutines\`, \`libs.bluetape4k.logging\`, \`libs.kotlinx.coroutines.core.lib\`, and the standard assertions/JUnit5/coroutines-test/MockK test dependencies. Keep \`configurations.testImplementation.extendsFrom(compileOnly, runtimeOnly)\` consistent with the AWS modules.

Create a \`main()\` that prints only a credential-free usage hint through \`KLogging\`; it must not construct a client or resolve credentials unless \`-Dbluetape4k.aws.bedrock.mode=real-aws\` is explicitly set.

- [ ] **Step 3: Run the registration proof**

Run:

\`\`\`bash
./gradlew :aws-bedrock-converse:tasks --all --console=plain
./gradlew projects --console=plain
\`\`\`

Expected: both commands succeed and list \`:aws-bedrock-converse\`.

- [ ] **Step 4: Commit the module skeleton**

\`\`\`bash
git add aws/bedrock-converse gradle/libs.versions.toml
git commit -m "AWS Bedrock consumer 경계를 검증 가능한 모듈로 시작한다"
\`\`\`

Constraint: AWS Kotlin SDK has no workshop BOM, so its existing \`aws-kotlin\` catalog compatibility line remains the version authority. Rejected: adding a second AWS BOM for Kotlin SDK.

### Task 2: Bedrock prompt/service contract (TDD)

**Files:**
- Create: \`aws/bedrock-converse/src/main/kotlin/io/bluetape4k/workshop/aws/bedrock/BedrockPrompt.kt\`
- Create: \`aws/bedrock-converse/src/main/kotlin/io/bluetape4k/workshop/aws/bedrock/BedrockConverseService.kt\`
- Create: \`aws/bedrock-converse/src/test/kotlin/io/bluetape4k/workshop/aws/bedrock/BedrockConverseServiceTest.kt\`

- [ ] **Step 1: Write RED tests for request mapping and lazy streaming**

The test must use a MockK \`BedrockRuntimeClient\`, \`coEvery\` for \`converse\` and \`converseStream\`, \`ConverseResponse\`/\`ConverseStreamResponse\` builders, and \`bluetape4k-assertions\`. Cover:

\`\`\`kotlin
@Test
fun \`converse maps model and prompt and returns text\`() = runTest { /* verify request fields */ }

@Test
fun \`stream is cold and invokes native stream once per collection\`() = runTest { /* zero before collection, two after two collections */ }

@Test
fun \`stream cancellation closes the client and preserves cancellation\`() = runTest { /* awaitCancellation in response stream, cancelAndJoin, verify close once */ }

@Test
fun \`native failure identity reaches caller\`() = runTest { /* assertFailsWith and shouldBeSameInstanceAs */ }
\`\`\`

Use two model ids (\`anthropic.claude-3-haiku\` and \`amazon.nova-lite\`) in separate parameterized or explicit tests to prove model-neutral mapping. Do not use \`!!\`, raw JUnit assertions, or generic Boolean/null equality matchers.

- [ ] **Step 2: Run the targeted test and confirm the expected RED state**

\`\`\`bash
./gradlew :aws-bedrock-converse:test --tests '*BedrockConverseServiceTest' --no-build-cache --console=plain
\`\`\`

Expected: compilation/test failure because \`BedrockPrompt\` and \`BedrockConverseService\` are not defined.

- [ ] **Step 3: Implement the smallest service**

Use this contract:

\`\`\`kotlin
data class BedrockPrompt(val modelId: String, val prompt: String) : Serializable

class BedrockConverseService(
    private val clientFactory: () -> BedrockRuntimeClient,
) {
    suspend fun converse(prompt: BedrockPrompt): String
    fun stream(prompt: BedrockPrompt): Flow<String>
}
\`\`\`

Validate \`modelId\` and \`prompt\` with \`requireNotBlank\`. Build messages with \`userMessageOf\`, call \`client.converse\` and \`client.converseStreamFlow(...).textDeltaFlow()\`, wrap each operation in \`clientFactory().useSafe\`, and rethrow cancellation/native failures unchanged. Keep logs free of prompt/response/credential values.

- [ ] **Step 4: Run the targeted test and confirm GREEN**

\`\`\`bash
./gradlew :aws-bedrock-converse:test --tests '*BedrockConverseServiceTest' --no-build-cache --console=plain
\`\`\`

Expected: all service tests PASS, including zero-before-collection, two invocations for two collections, ordered deltas, cancellation cleanup, and exception identity.

- [ ] **Step 5: Commit the service contract**

\`\`\`bash
git add aws/bedrock-converse/src/main aws/bedrock-converse/src/test
git commit -m "Bedrock Converse 요청과 cold Flow 수명주기를 고정한다"
\`\`\`

### Task 3: 실행 설정과 bilingual README

**Files:**
- Create: \`aws/bedrock-converse/README.md\`
- Create: \`aws/bedrock-converse/README.ko.md\`
- Modify: \`aws/README.md\`
- Modify: \`aws/README.ko.md\`

- [ ] **Step 1: Add the runnable configuration contract**

Document \`bluetape4k.aws.bedrock.mode=local\` as the default and \`real-aws\` as the only mode that calls AWS. Show \`bedrockRuntimeClientOf\` with region/endpoint/credentials injection and require HTTPS or literal loopback HTTP as defined upstream. Include non-streaming and streaming snippets, cancellation scope, and the explicit non-guarantees (no retry, replay, buffering, exactly-once, or provider-specific guarantee).

- [ ] **Step 2: Keep English/Korean locale parity**

Both module READMEs must contain the same module purpose, dependency snippet, test command, local/real mode table, API snippets, lifecycle warning, and source links. Add the module to the AWS root README module guide, run table, and coverage table in each locale without adding a diagram.

- [ ] **Step 3: Run documentation checks**

\`\`\`bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs aws/bedrock-converse/README.ko.md aws/README.ko.md
\`\`\`

Expected: no whitespace errors and \`findings=0\`.

### Task 4: CI/smoke/coverage registration

**Files:**
- Modify: \`.github/workflows/Examples.yml\` (path filters, AWS smoke/full tasks, summary needs/artifacts where applicable)
- Modify: \`scripts/smoke-validate.sh\` (AWS group and all-smoke task list)
- Modify: \`docs/coverage-matrix.md\`

- [ ] **Step 1: Add failing registration assertions**

Run the existing stale/coverage checks and verify they do not yet include \`:aws-bedrock-converse:test\`:

\`\`\`bash
./scripts/smoke-validate.sh stale-check
rg -n "aws-bedrock-converse" .github/workflows/Examples.yml scripts/smoke-validate.sh docs/coverage-matrix.md
\`\`\`

Expected: the new module is absent from the registration surfaces.

- [ ] **Step 2: Add anchored registration entries**

Add \`aws/bedrock-converse/**\` to the AWS path filters, \`:aws-bedrock-converse:test\` to the AWS smoke group, and the same credential-free test to the appropriate full/examples group. Preserve existing job \`needs\`, Kover/report artifact behavior, and do not add live AWS credentials.

- [ ] **Step 3: Verify all registration surfaces**

\`\`\`bash
./scripts/smoke-validate.sh stale-check
./gradlew :aws-bedrock-converse:test --console=plain
rg -n "aws-bedrock-converse|Bedrock Converse" .github/workflows/Examples.yml scripts/smoke-validate.sh docs/coverage-matrix.md aws/README.md aws/README.ko.md
\`\`\`

Expected: stale-check PASS, module tests PASS, and every required surface contains the exact project/task name.

- [ ] **Step 4: Commit registration and docs**

\`\`\`bash
git add .github/workflows/Examples.yml scripts/smoke-validate.sh docs/coverage-matrix.md aws/README.md aws/README.ko.md aws/bedrock-converse/README.md aws/bedrock-converse/README.ko.md
git commit -m "Bedrock consumer를 AWS 검증 경로와 문서에 등록한다"
\`\`\`

### Task 5: Final verification, lesson, and stacked PR publication

**Files:**
- Create: \`docs/lessons/2026-08-28-issue-741-bedrock-converse.md\`
- Review: all #741 changed files and exact diff from \`origin/chore/ecosystem-reuse-manifest-transition-1\`

- [ ] **Step 1: Run proportional validation**

\`\`\`bash
./gradlew :aws-bedrock-converse:test --no-build-cache --console=plain
./gradlew :aws-bedrock-converse:build --no-build-cache --console=plain
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
git diff --check
\`\`\`

Expected: all commands PASS; no live AWS call is required or attempted.

- [ ] **Step 2: Write and validate the Korean lesson**

Record the evidence, rejected emulator/SPI alternatives, credential boundary, lifecycle contract, any surprises, and the future guard (smoke/full registration plus fake cancellation test). Run the Korean terminology audit and read the final Markdown back in context.

- [ ] **Step 3: Commit the lesson with Lore trailers**

\`\`\`bash
git add docs/lessons/2026-08-28-issue-741-bedrock-converse.md
git commit -m "Bedrock consumer의 credential-free 수명주기 계약을 남긴다"
\`\`\`

The commit body must include \`Constraint\`, \`Rejected\`, \`Confidence\`, \`Scope-risk\`, \`Directive\`, \`Tested\`, and \`Not-tested\` trailers.

- [ ] **Step 4: Publish the stacked PR**

Push \`feat/aws-bedrock-converse-741\` and create a Korean PR whose base is \`chore/ecosystem-reuse-manifest-transition-1\` and whose head is the exact pushed SHA. Set assignee \`debop\`, milestone \`1.4.0\`, labels \`enhancement\`, \`difficulty:expert\`, \`area:async-reactive\`, \`area:architecture-extension\`, and \`area:security\`. Link \`Closes #741\`. End the body with \`## DoD Status\` and \`Final status: PENDING — fresh exact-head approval and final rebase merge remain; Epic #792 stays OPEN.\`

- [ ] **Step 5: Live-read PR metadata and stop at merge gate**

\`\`\`bash
gh pr view <new-pr> --json number,url,baseRefName,headRefName,headRefOid,state,mergeStateStatus,assignees,milestone,labels,statusCheckRollup,body
\`\`\`

Expected: metadata, exact base/head, Korean body, required checks, and PENDING status match. Do not enable auto-merge and do not merge this PR before the later whole-train approval.
