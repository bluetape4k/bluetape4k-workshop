# 워크샵 생태계 코드 패턴 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 등록된 모든 `bluetape4k-workshop` Gradle 프로젝트를 검토하고 bluetape4k 생태계 라이브러리 사용을 더 잘 보여줄 수 있는 모듈에 대한 별도의 PR을 생성합니다.

**아키텍처:** 사양, 계획 및 검토 매트릭스에 대해 하나의 조정 분기를 사용한 다음 현재 `origin/develop`에서 변경된 Gradle 프로젝트당 하나의 branch/PR를 만듭니다. 각 모듈 PR은 해당 모듈의 source/test/docs과 review/lesson 아티팩트만 소유합니다. 무작동 모듈은 P0/P1=0 증거로 내구성 있는 매트릭스에 기록됩니다.

**기술 스택:** Kotlin 2.4, Java 21, Spring Boot 4.0.6, Gradle, bluetape4k-종속성 BOM, bluetape4k 검증 도우미, bluetape4k-assertions, bluetape4k-junit5, bluetape4k 로깅, bluetape4k Testcontainers 런처 패턴, GitHub CLI.

---

## 파일 및 아티팩트

조정 분기 파일:

- 기존 사양: `docs/superpowers/specs/2026-07-04-workshop-ecosystem-code-patterns-design.md`
- 이 계획은: `docs/superpowers/plans/2026-07-04-workshop-ecosystem-code-patterns-plan.md`
- 생성: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

모듈별 PR 파일:

- 모듈 디렉토리만 수정하십시오(예: `:okio-examples`의 경우 `io/okio-examples/**`).
- 변경된 모듈당 하나의 리뷰 아티팩트를 추가합니다(예: `docs/review/2026-07-04-spring-boot-cache-caffeine-ecosystem-review.md`).
- 유용한 경우에만 하나의 강의를 추가하세요(예: `docs/lessons/2026-07-04-spring-boot-cache-caffeine-ecosystem-patterns.md`).
- 공개 예제 동작이나 모듈 관련 문서가 변경되면 `README.md`과 기존 지역화된 README 파일을 업데이트합니다.

## 파동 순서

한 번에 최대 3개의 활성 모듈 PR을 유지합니다.

| 웨이브 | 프로젝트 | 이유 |
|---|---|---|
| 1 | `:spring-boot-cache-caffeine`, `:spring-boot-cache-redis`, `:gatling-virtualthread-simulation` | Hot-path/request-path 또는 부하 시뮬레이션 차단 예 |
| 2 | `:okio-examples`, `:image-processing-advanced-workflow`, `:image-processing-ocr-api` | 고밀도 validation/docs/security 계약 |
| 3 | `:leader-leader-election`, `:leader-leader-zookeeper`, `:leader-tenant-scheduler` | 브릿지 차단, 스케줄러 수명주기, 검증 |
| 4 | `:redis-redisson-examples`, `:redis-distributed-lock`, `:redis-cluster-demo` | 타이밍, lock/lifecycle, Testcontainers/Redis 예시 |
| 5A | `:messaging-kafka-outbox-fallback`, `:messaging-kafka`, `:messaging-kafka-reply` | 메시징 유효성 검사, 수정, 어설션 형태 |
| 5B | `:messaging-transactional-outbox`에 행렬 순위가 매겨진 다음 두 개의 messaging/data 후보 | 활성 PR 개수를 3개 이하로 유지 |
| 6 | `:spring-data-*` 후보자 3명 이하 일괄 | 데이터 액세스 어설션, 역직렬화, Testcontainers 및 reactive/coroutine 계약 |
| 7 | `:spring-boot-*` 나머지 후보는 3개 이하 배치 | Web/cache/resilience/idempotency/security 예 |
| 8 | 매트릭스 순서로 나머지 등록된 모든 프로젝트, 3개 이하의 활성 PR | 100개 프로젝트가 분류될 때까지 patched/no-op 상태 확인 |

## 글로벌 처형 가드

- 매트릭스를 작업 대기열로 취급합니다. 배치에는 최대 3개의 활성이 있을 수 있습니다.
  모듈 PR 및 Testcontainers 지원 Gradle 명령은 항상 단일에서 실행됩니다.
  읽기 전용 스캔이나 비컨테이너 테스트가 실행되는 경우에도 직렬화된 소유자 레인
  평행한.
- Testcontainers 지원 Gradle 작업을 시작하기 전에 소유자를 기록하세요.
  매트릭스의 분기, 작업 트리 경로, 명령, 시작 시간 및 중지 시간입니다. 하다
  현재 작업이 완료될 때까지 다른 Testcontainers 지원 Gradle 작업을 시작하지 마세요.
  완료되었거나 정리 증거로 차단된 것으로 표시되었습니다.
- Gradle/Testcontainers 작업이 중단되거나 중단되면 영향을 받는 작업을 중지합니다.
  프로세스, 로컬 Docker/Testcontainers 잔여물 검사, 한 번만 다시 실행
  깨끗한 상태가 확인되고, 그렇지 않으면 모듈 `blocked`을
  로그 경로 및 이유.
- 모듈 분기를 푸시하기 전에 `origin/develop`을 새로 고치고
  가지는 여전히 관찰된 베이스에서 내려옵니다. 베이스가 이동한 경우 리베이스하거나
  현재 `origin/develop`에서 모듈 브랜치를 다시 만든 다음 컴파일을 다시 실행하세요.
  테스트, 검토 및 PR 신체 검사를 수행합니다.
- 실패했거나 대체된 모듈 PR은 증거를 유지합니다. 실패에 대해 언급하거나
  PR에 대한 대체 이유, 매트릭스 배치 업데이트,
  현재 `origin/develop`에서 분기를 교체하고 로컬 또는 삭제하지 마세요.
  별도로 청소를 요청하거나 안전이 입증되지 않은 경우 원격 지점.

## 작업 1: 조정 매트릭스 만들기

**파일:**
- 생성: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **1단계: 등록된 프로젝트 인벤토리 생성**

달리다:

```bash
node - <<'NODE'
const fs = require("fs")
const path = require("path")
const root = process.cwd()
const specs = [
  ["aws", false, true],
  ["examples", false, false],
  ["exposed", false, true],
  ["gateway", false, false],
  ["gatling", false, true],
  ["graalvm", false, false],
  ["graph", false, true],
  ["image-processing", false, true],
  ["io", false, false],
  ["json", false, false],
  ["kotlin", false, true],
  ["ktor", false, true],
  ["leader", false, true],
  ["messaging", false, true],
  ["observability", false, false],
  ["ratelimit", false, false],
  ["redis", false, true],
  ["spring-boot", false, true],
  ["spring-data", false, true],
  ["spring-modulith", false, true],
  ["spring-security/mvc", false, true],
  ["spring-security/webflux", false, true],
  ["vertx", false, true],
  ["virtualthreads", false, true],
]
const modules = [{ project: ":shared", dir: "shared" }]
for (const [base, withProjectName, withBaseDir] of specs) {
  const basePath = path.join(root, base)
  if (!fs.existsSync(basePath)) continue
  for (const entry of fs.readdirSync(basePath, { withFileTypes: true }).filter((it) => it.isDirectory()).map((it) => it.name).sort()) {
    const dir = path.join(base, entry)
    if (!fs.existsSync(path.join(root, dir, "build.gradle.kts"))) continue
    const baseDash = base.replace(/\//g, "-")
    const projectName = !withProjectName && !withBaseDir
      ? entry
      : withProjectName && !withBaseDir
        ? "bluetape4k-" + entry
        : withProjectName
          ? "bluetape4k-" + baseDash + "-" + entry
          : baseDash + "-" + entry
    modules.push({ project: ":" + projectName, dir })
  }
}
console.log("| Project | Directory | Candidate patterns | Disposition | Ecosystem reuse evidence | Stability/security verdict | Validation evidence | Reviewer/date |")
console.log("|---|---|---|---|---|---|---|---|")
for (const m of modules) {
  console.log(`| \`${m.project}\` | \`${m.dir}\` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |`)
}
NODE
```

예상: 100개의 프로젝트 행이 있는 Markdown 테이블.

- [ ] **2단계: 매트릭스 파일 만들기**

생성된 테이블을 다음 위치에 복사합니다.

```markdown
# Workshop Ecosystem Code Patterns Matrix

Date: 2026-07-04
Coordination branch: `feat/workshop-ecosystem-code-patterns`

This matrix tracks every registered Gradle project. A row reaches terminal
state only when disposition is `patched`, `no-op`, or `follow-up` with P0/P1=0
review evidence.

Paste the exact table emitted by Step 1 here, then keep every row updated until
the project reaches a terminal disposition.
```

- [ ] **3단계: 행렬 행 수 확인**

달리다:

```bash
grep -Ec '^\| `:' docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md
```

예상: `100`.

- [ ] **4단계: Gradle 프로젝트에 대한 매트릭스 확인**

달리다:

```bash
./gradlew -q projects --console=plain > /tmp/workshop-gradle-projects.txt
node - <<'NODE'
const fs = require("fs")
const matrix = fs.readFileSync("docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md", "utf8")
  .split(/\r?\n/)
  .map((line) => (line.match(/^\| `([^`]+)` \|/) || [])[1])
  .filter(Boolean)
  .sort()
const gradle = fs.readFileSync("/tmp/workshop-gradle-projects.txt", "utf8")
  .split(/\r?\n/)
  .map((line) => (line.match(/Project '(:[^']+)'/) || [])[1])
  .filter(Boolean)
  .sort()
const missing = gradle.filter((it) => !matrix.includes(it))
const extra = matrix.filter((it) => !gradle.includes(it))
if (missing.length || extra.length) {
  console.error(JSON.stringify({ missing, extra }, null, 2))
  process.exit(1)
}
console.log(`matrix matches Gradle projects: ${matrix.length}`)
NODE
```

예상: `matrix matches Gradle projects: 100`.

- [ ] **5단계: 계획이 포함된 매트릭스 커밋**

커밋하기 전이 아니라 3-R 단계 계획 검토를 통과한 후에 커밋하세요.

## 작업 2: 반복 가능한 후보 검색 실행

**파일:**
- 수정: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **1단계: Kotlin 생태계 패턴 스캔 실행**

달리다:

```bash
rg -n "Thread\\.sleep|\\brunBlocking\\s*\\(|runCatching\\s*\\{|\\brequire\\s*\\(|checkNotNull\\s*\\(|\\bcheck\\s*\\(|shouldBeEqualTo\\s+(true|false)|shouldBeEqualTo\\((true|false)\\)|\\.size\\s+shouldBeEqualTo|!!|@Synchronized|synchronized\\s*\\(" --glob '*.kt'
```

예상: 일치 항목은 모듈별로 검토됩니다. 일치는 자동으로 결함이 아닙니다.

- [ ] **2단계: 보안 중심 검사 실행**

달리다:

```bash
rg -n "Bearer|Authorization|accessKey|secretKey|sessionToken|password|credential|Idempotency-Key|token|stackTrace|printStackTrace|stackTraceToString|exception\\.message|\\$\\{[^}]*\\.message\\}|\\$ex|@ExceptionHandler|ProblemDetail|ErrorResponse|logger\\.(trace|debug|info|warn|error)\\([^\\n]*e\\)|message\\s*[=:]" --glob '*.kt' --glob '*.md'
```

예상: 누출 위험, 수정, 교육 의도 또는 테스트 전용 범위에 대해 일치 항목이 검토됩니다.

- [ ] **3단계: config/default-risk 보안 검사 실행**

달리다:

```bash
rg -n "password|secret|apiKey|accessKey|clientSecret|privateKey|management\\.endpoints\\.web\\.exposure\\.include\\s*=\\s*\\*|management\\.endpoints\\.web\\.exposure\\.include:\\s*['\"]?\\*|csrf\\.disable|permitAll|allowedOrigins\\(\"\\*\"\\)|allowed-origins:\\s*\\*|debug:\\s*true|trace:\\s*true|Authorization|Bearer" --glob '*.yml' --glob '*.yaml' --glob '*.properties' --glob '.env*' --glob '*.md'
```

예상: 각 적중은 안전한 기본값, local/test-only 예 또는
이슈 후보. 실제처럼 보이는 비밀, 폭넓은 액츄에이터 노출, 불안전한 CORS,
CSRF 비활성화 및 모든 허용 보안 예제에는 명시적인 교육 전용이 필요합니다.
건드린 부분에 대한 근거와 테스트.

- [ ] **4단계: 주입 및 역직렬화 검사 실행**

달리다:

```bash
rg -n "activateDefaultTyping|DefaultTyping|@JsonTypeInfo\\(|readValue<Any>|GenericJackson2JsonRedisSerializer|@Query\\(|createQuery\\(|nativeQuery|SELECT .*\\$|WHERE .*\\$|\\$\\{.*\\}" --glob '*.kt' --glob '*.md'
```

예상: structured/bind API 증거, 제한된 유형 유효성 검사기 증거,
또는 모든 관련 히트에 대해 문서화된 테스트 전용 경계입니다.

- [ ] **5단계: Testcontainers/direct-container 검사 실행**

달리다:

```bash
rg -n "GenericContainer\\(|DockerImageName|Testcontainers|Launcher\\." --glob '*.kt'
```

예상: 직접 컨테이너 사용은 가능한 경우 bluetape4k 실행기로 대체되거나 모듈별 예외로 기록됩니다.

- [ ] **6단계: 매트릭스 후보 업데이트**

등록된 각 프로젝트에 대해 `pending scan`을 다음과 같은 간결한 후보 클래스로 바꿉니다.

```text
raw validation; blocking simulation; weak assertions; sensitive logging; no candidate
```

## 작업 3: 웨이브 1 모듈 PR 처리

각 Wave 1 프로젝트에 대해 하나의 브랜치와 하나의 PR를 만듭니다.

| 프로젝트 | 디렉토리 | 지점 | 아티팩트 검토 | 테스트 명령 접두사 |
|---|---|---|---|---|
| `:spring-boot-cache-caffeine` | `spring-boot/cache-caffeine` | `refactor/spring-boot-cache-caffeine-ecosystem-patterns` | `docs/review/2026-07-04-spring-boot-cache-caffeine-ecosystem-review.md` | `./gradlew :spring-boot-cache-caffeine` |
| `:spring-boot-cache-redis` | `spring-boot/cache-redis` | `refactor/spring-boot-cache-redis-ecosystem-patterns` | `docs/review/2026-07-04-spring-boot-cache-redis-ecosystem-review.md` | `./gradlew :spring-boot-cache-redis` |
| `:gatling-virtualthread-simulation` | `gatling/virtualthread-simulation` | `refactor/gatling-virtualthread-simulation-ecosystem-patterns` | `docs/review/2026-07-04-gatling-virtualthread-simulation-ecosystem-review.md` | `./gradlew :gatling-virtualthread-simulation` |

- [ ] **1단계: 분기 상태 새로 고침**

기본 저장소 체크아웃에서 실행:

```bash
git fetch --prune origin develop
repo-status
gh pr list --state open --json number,title,headRefName,baseRefName,labels,milestone,assignees
worktree-list
```

예상: 현재 상태, 활성 PR 수 및 분기-작업 트리 매핑은 다음과 같습니다.
모듈 브랜치를 생성하기 전에 이해했습니다.

- [ ] **2단계: 모듈 분기 작업 트리 만들기**

각 Wave 1 행에 대해 해당 명령어를 실행합니다.

```bash
worktree-new refactor/spring-boot-cache-caffeine-ecosystem-patterns --base origin/develop
worktree-new refactor/spring-boot-cache-redis-ecosystem-patterns --base origin/develop
worktree-new refactor/gatling-virtualthread-simulation-ecosystem-patterns --base origin/develop
```

예상: 각 분기마다 하나의 작업 트리가 존재합니다. Testcontainers 지원을 실행하지 마세요.
Gradle개의 작업이 이러한 작업 트리에서 동시에 수행됩니다.

- [ ] **3단계: 모듈 검사**

모듈 작업 트리 내에서 실행합니다.

```bash
rg -n "Thread\\.sleep|\\brunBlocking\\s*\\(|runCatching\\s*\\{|\\brequire\\s*\\(|checkNotNull\\s*\\(|\\bcheck\\s*\\(|shouldBeEqualTo\\s+(true|false)|shouldBeEqualTo\\((true|false)\\)|\\.size\\s+shouldBeEqualTo|!!|@Synchronized|synchronized\\s*\\(" spring-boot/cache-caffeine --glob '*.kt'
rg -n "Bearer|Authorization|accessKey|secretKey|sessionToken|password|credential|Idempotency-Key|token|stackTrace|printStackTrace|exception\\.message|\\.message\\}" spring-boot/cache-caffeine --glob '*.kt' --glob '*.md'
rg -n "password|secret|apiKey|accessKey|clientSecret|privateKey|management\\.endpoints\\.web\\.exposure\\.include|csrf\\.disable|permitAll|allowedOrigins\\(\"\\*\"\\)|debug:\\s*true|trace:\\s*true" spring-boot/cache-caffeine --glob '*.yml' --glob '*.yaml' --glob '*.properties' --glob '.env*' --glob '*.md'
rg -n "activateDefaultTyping|DefaultTyping|@JsonTypeInfo\\(|readValue<Any>|GenericJackson2JsonRedisSerializer|@Query\\(|createQuery\\(|nativeQuery|SELECT .*\\$|WHERE .*\\$|\\$\\{.*\\}" spring-boot/cache-caffeine --glob '*.kt' --glob '*.md'
rg -n "GenericContainer\\(|DockerImageName|Testcontainers|Launcher\\." spring-boot/cache-caffeine --glob '*.kt'
```

`spring-boot/cache-redis` 및 `gatling/virtualthread-simulation`을 반복합니다.
예상: 후보 목록은 모듈별로 다릅니다.

- [ ] **4단계: 편집하기 전에 bluetape4k 생태계 도우미 검색**

후보 클래스를 기반으로 타겟 검색을 실행합니다.

```bash
BLUETAPE4K_WORKSPACE=/Users/debop/work/bluetape4k
rg -n "requireNotBlank|requireNotNull|requireNotEmpty|requirePositiveNumber|requireInRange|Base58|KLogging|KLoggingChannel|MultithreadingTester|SuspendedJobTester|StructuredTaskScopeTester|untilAsserted|untilSuspending|Launcher\\." \
  spring-boot/cache-caffeine \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-projects" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-exposed" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-aws" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-image" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-javers" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-leader" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-text" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-graph" \
  --glob '*.kt'
```

활성 모듈 디렉터리에 대해 반복합니다. 예상: 각 raw/JDK/third-party
후보자는 adopt/borrow/skip 결정을 내렸습니다.

- [ ] **5단계: 동작이 변경되면 먼저 테스트를 작성하거나 조정하세요**

어설션 전용 리팩터링의 경우 기존 테스트를 특성화 증명으로 사용하세요.
동작 변경을 위해서는 먼저 집중 테스트를 추가하거나 조정한 후 실행하세요.
구현. 터치된 발신자 입력 확인, 공개 오류 계약 또는
보안에 민감한 경계에는 잘못된 입력에 대한 부정적인 테스트가 필요하며
수정 또는 의미론이 변경되지 않았음을 증명하는 명시적인 검토 증거입니다.

예제 명령:

```bash
./gradlew :spring-boot-cache-caffeine:test --tests "io.bluetape4k.workshop.cache.caffeine.*" --max-workers=1 --warning-mode all --console=plain
```

새로운 동작 테스트에 예상됨: 구현 전에 예상되는 이유로 실패합니다.

- [ ] **6단계: 최소 생태계 리팩터링 적용**

의미 체계가 일치하는 경우에만 이러한 변환을 사용하십시오.

```kotlin
name.requireNotBlank("name")
items.requireNotEmpty("items")
count.requirePositiveNumber("count")
actual.shouldBeTrue()
collection shouldHaveSize expectedSize
private companion object : KLogging()
```

수업 자체가 개선되지 않는 한 교수 의도를 차단하는 예를 변경하지 마십시오.

- [ ] **7단계: 문서, 공개 API 및 학습자 영향 분류**

모든 공개 예제, 컨트롤러, 구성 클래스, README에 대해
스니펫 또는 공개 API:

- 학습자가 볼 수 있는 경우 `README.md`과 기존 현지화된 README 파일을 업데이트하세요.
  행동 또는 사용법 변경;
- 공개 API 지침이 변경되면 영어 KDoc을 추가하거나 업데이트합니다.
- 검증문하기 전에 현재 소스에 대해 grep-check README/KDoc/example 이름
  그들은 현재이다;
- 상태 지원 시나리오, unsupported/non-goal 시나리오, 마이그레이션
  raw/JDK/third-party 패턴을 bluetape4k 도우미에 추가하고 왜 raw 또는
  차단 패턴이 남아 있습니다.
- README/KDoc/examples에 실제 토큰, 비밀번호가 포함되어 있지 않은지 확인합니다.
  인증 헤더, 원시 요청 본문, 기본 경로 또는 복사하여 붙여넣기 가능
  생산 비밀. Local/test 데모 자격 증명에 local/test-only 라벨을 붙여야 합니다.

- [ ] **8단계: 대상 컴파일, 테스트, 성능 및 작업 검사 실행**

달리다:

```bash
./gradlew :spring-boot-cache-caffeine:compileKotlin :spring-boot-cache-caffeine:compileTestKotlin --max-workers=1 --warning-mode all --console=plain
./gradlew :spring-boot-cache-caffeine:test --max-workers=1 --warning-mode all --console=plain
git diff --check
```

활성 Wave 1 프로젝트 경로를 사용하여 반복합니다. Testcontainers 지원 모듈의 경우,
사용:

```bash
./gradlew :spring-boot-cache-redis:cleanTest :spring-boot-cache-redis:test --no-build-cache --max-workers=1 --warning-mode all --console=plain
```

예상: 검토 전에 명령이 통과됩니다.

PR 생성 전에 Wave 1 성능 증거를 추가합니다.

- `:spring-boot-cache-caffeine`: 첫 번째 적중 대 캐시 적중 동작을 기록합니다.
  요청 경로 차단 분류, 할당 위험(`N/A`, `unchanged` 또는
  `changed with rationale/evidence`) 및 concurrency/cache-stability 증거
  기존 테스트 또는 집중 연기 테스트를 통해
- `:spring-boot-cache-redis`: 첫 번째 조회 기록, 캐시된 조회, 제거
  행동, Redis 실행 가능한 경우 명령 수 증거 또는 문서화된
  명령 계산이 불가능할 때 이유와 source/test 증거.
- `:gatling-virtualthread-simulation`: 로컬 Gatling/smoke 시뮬레이션 실행
  실용적이거나 보존된 동작으로 건너뛰는 것을 명시적으로 정당화합니다.
  source/test 증거.

PR 생성 전에 Ops/SRE 증거를 추가합니다.

- startup/readiness 또는 해당하는 경우 액츄에이터 상태;
- log/diagnostic 수정 및 측정항목 라벨 카디널리티;
- tracing/observation 모듈이 관찰 결과를 내보내는 관련성;
- `scripts/smoke-validate.sh` 모듈이 다루어졌을 때 증거를 그룹화하거나
  명시적인 "포함되지 않음"에 대한 근거.

- [ ] **9단계: 모듈 범위의 7계층 검토 실행**

다음을 사용하여 Wave 1 테이블에서 행별 검토 아티팩트를 생성합니다.

```markdown
# :spring-boot-cache-caffeine Ecosystem Code Patterns Review

Date: 2026-07-04
Scope: `:spring-boot-cache-caffeine` / `spring-boot/cache-caffeine`

## Findings

| Tier | P0 | P1 | P2/P3 | Evidence |
|---|---:|---:|---|---|
| Security | 0 | 0 | ... | ... |
| Ops/SRE | 0 | 0 | ... | ... |
| Structural impact | 0 | 0 | ... | ... |
| Kotlin code quality | 0 | 0 | ... | ... |
| Tests/types/silent failure | 0 | 0 | ... | ... |
| Performance/stability | 0 | 0 | ... | ... |
| Documentation/release/evidence | 0 | 0 | ... | ... |

## Ecosystem Reuse Evidence

- Adopted:
- Preserved teaching-intent exceptions:
- Rejected alternatives:

## Security Evidence

- Auth/authz:
- Sensitive data/logs/errors:
- Injection:
- Deserialization:
- Config safe defaults:
- README/example secrets:
- Tests or source lines:

## Performance Evidence

- Hot path/blocking:
- Allocation risk:
- Contention/concurrency helper evidence:
- DB/cache/Redis command count:
- Benchmark/load/stress evidence:
- Validation command/result:

## Ops Evidence

- Startup/readiness/health:
- Logs/diagnostics/redaction:
- Metrics/tracing/cardinality:
- Smoke validation:

Final verdict: PASS, P0/P1=0.
```

예상: P0/P1=0. 그렇지 않은 경우 영향을 받은 검증을 수정하고 다시 실행하십시오.

- [ ] **10단계: 필요할 때 강의 추가**

모듈이 재사용 가능한 미래 가드를 보여줄 때만 레슨을 생성하세요:

```markdown
# Lessons Learned - spring-boot-cache-caffeine ecosystem patterns (2026-07-04)

## L1: Prefer cache helper APIs in request-path examples

### Problem
The module had a request-path pattern that did not teach the preferred
bluetape4k cache or validation helper.

### Lesson
Future cache examples should search bluetape4k helper APIs before keeping raw
JDK or framework calls in learner-facing paths.

### Evidence
Record the changed file, targeted Gradle command, and PR number.
```

- [ ] **11단계: 커밋**

달리다:

```bash
git status --short
git add spring-boot/cache-caffeine docs/review/2026-07-04-spring-boot-cache-caffeine-ecosystem-review.md
# Only when Step 10 created a lesson:
git add docs/lessons/2026-07-04-spring-boot-cache-caffeine-ecosystem-patterns.md
git diff --cached --check
git commit -m "refactor: align spring-boot-cache-caffeine ecosystem patterns" \
  -m "Use bluetape4k ecosystem helpers in the example module so the workshop teaches the preferred library patterns." \
  -m "Constraint: one PR per changed Gradle project" \
  -m "Rejected: broad repository-wide cleanup | too hard to review and validate per module" \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Directive: preserve intentional teaching examples when raw blocking demonstrates the lesson" \
  -m "Tested: ./gradlew :spring-boot-cache-caffeine:compileKotlin :spring-boot-cache-caffeine:compileTestKotlin; ./gradlew :spring-boot-cache-caffeine:test; git diff --check" \
  -m "Not-tested: unrelated modules"
```

활성 Wave 1 모듈의 디렉터리, 검토 아티팩트, 분기 이름을 사용하여 반복합니다.
그리고 테스트 명령. 예상: 하나의 모듈 범위 커밋.

- [ ] **12단계: PR 이전 신선도 및 메타데이터 게이트**

달리다:

```bash
git fetch --prune origin develop
git merge-base --is-ancestor origin/develop HEAD || {
  echo "origin/develop moved; rebase or recreate the branch and rerun validation"
  exit 1
}
gh label list --json name
gh api repos/bluetape4k/bluetape4k-workshop/milestones --paginate --jq '.[] | select(.state == "open") | {number,title}'
```

예상: 분기는 여전히 현재 `origin/develop`을 기반으로 합니다. 중요한 단계
`backlog` 및 모듈별 라벨을 사용할 수 있습니다. `refactoring`과 함께 사용하세요.
`area:spring-boot`, `area:data-access` 등의 모듈별 영역 레이블 또는
`area:async-reactive` 라벨이 존재하는 경우. 대체 근거를 기록합니다.
PR 정확한 라벨이 존재하지 않는 경우 본문과 행렬.

- [ ] **13단계: PR 본문 생성 및 로컬 유효성 검사**

다음을 사용하여 `/tmp/spring-boot-cache-caffeine-pr-body.md`를 만듭니다.

```markdown
## Summary

## What This Teaches

- bluetape4k API/pattern:
- Before/after caller behavior:
- Misuse boundary:
- Unsupported/non-production scope:

## Work Done

## Validation

## Review Notes

## DoD Status

- [ ] README/KDoc impact classified, and localized README parity handled when applicable.
- [ ] Tests and compile commands passed locally.
- [ ] Performance/security/Ops evidence recorded in the module review artifact.
- [ ] 7-Tier review is PASS with P0/P1=0.
- [ ] PR metadata and live body verified.
- [ ] CI/check state recorded, including skipped checks and local substitutes.
```

PR 생성 전에 최종 제목을 확인합니다.

```bash
node - <<'NODE'
const fs = require("fs")
const body = fs.readFileSync("/tmp/spring-boot-cache-caffeine-pr-body.md", "utf8")
const headings = body.split(/\r?\n/).filter((line) => line.startsWith("## "))
if (headings.at(-1) !== "## DoD Status") {
  console.error(`Final heading is ${headings.at(-1)}`)
  process.exit(1)
}
for (const section of ["## Summary", "## What This Teaches", "## Work Done", "## Validation", "## Review Notes", "## DoD Status"]) {
  if (!headings.includes(section)) {
    console.error(`Missing ${section}`)
    process.exit(1)
  }
}
NODE
```

- [ ] **14단계: PR 만들기**

PR 푸시 및 생성:

```bash
git push -u origin refactor/spring-boot-cache-caffeine-ecosystem-patterns
gh pr create \
  --title "refactor: align spring-boot-cache-caffeine ecosystem patterns" \
  --body-file /tmp/spring-boot-cache-caffeine-pr-body.md \
  --assignee debop \
  --milestone backlog \
  --label refactoring \
  --label area:spring-boot
```

PR 본문에는 다음이 포함되어야 합니다.

```markdown
## Summary

## What This Teaches

## Work Done

## Validation

## Review Notes

## DoD Status
```

`## DoD Status` 뒤에는 섹션이 나타날 수 없습니다.

- [ ] **15단계: 실시간 PR 본문, 메타데이터 및 검사 확인**

달리다:

```bash
PR_NUMBER=$(gh pr view --head refactor/spring-boot-cache-caffeine-ecosystem-patterns --json number -q .number)
gh pr view "$PR_NUMBER" --json headRefName,baseRefName,assignees,labels,milestone,body,statusCheckRollup,headRefOid
gh pr checks "$PR_NUMBER" --watch
```

예상: 담당자 `debop`, 마일스톤 `backlog`, 최종 본문 제목
`## DoD Status`, 로컬 검증 기록 및 각 확인 name/conclusion/URL
또는 PR DoD 및 매트릭스에 기록된 건너뛴 이유. 경로 필터링된 워크플로인 경우
건너뛰면 대체되는 대상 로컬 compile/test/smoke 증거를 기록합니다.
수표를 건너뛴 거죠. 생체 검증이 실패하면 다음을 사용하여 수리하십시오.
`gh pr edit "$PR_NUMBER" --body-file /tmp/spring-boot-cache-caffeine-pr-body.md`
검토를 요청하거나 보고하기 전에 DoD.

## 작업 4: 무작동 모듈 처리

**파일:**
- 수정: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **1단계: 무작동 후보 검사**

프로젝트 디렉터리에 대해 작업 3, 3단계의 세 가지 검색을 실행합니다.

- [ ] **2단계: 안정성 및 보안 검토**

확인하다:

```text
P0=0
P1=0
No unresolved race/deadlock/leak/cancellation/lifecycle/security risk
Teaching-intent exceptions are explicit
```

- [ ] **3단계: 매트릭스 업데이트**

세트:

```text
Disposition = no-op
Ecosystem reuse evidence = already uses helper OR teaching exception
Stability/security verdict = P0/P1=0 with reason
Validation evidence = source lines or scan command
```

## 작업 5: 파경계 검증

최대 3개의 모듈 PR 이후 또는 공유 도우미 변경 이후에 실행됩니다.

**파일:**
- 수정: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **1단계: 상태 새로고침**

달리다:

```bash
git fetch --prune origin develop
gh pr list --state open --json number,title,headRefName,baseRefName,labels,milestone,assignees
worktree-list
```

- [ ] **2단계: 조정 분기 또는 현재 동기화된 베이스에서 전체 빌드 실행**

달리다:

```bash
./gradlew build --max-workers=1 --console=plain
```

예상: `BUILD SUCCESSFUL`.

- [ ] **3단계: 웨이브 성능, 보안, 운영 증거 확인**

웨이브에서 성능에 민감한 각 PR에 대해 모듈 검토를 확인합니다.
아티팩트에는 hot-path/blocking 증거, 할당 위험, 동시성 또는
캐시 안정성 증거, 관련 있는 경우 DB/cache/Redis 명령 개수 증거,
그리고 benchmark/load/stress 증거 또는 건너뛰기 근거. 모든 보안을 확인하세요
Ops 증거 하위 섹션은 소스 라인, 테스트 명령, PR으로 채워집니다.
URL을 확인하거나 해당되지 않는 명시적인 근거를 확인합니다.

- [ ] **4단계: 매트릭스에서 웨이브 상태 업데이트**

PR 번호 기록, 헤드 SHA, 로컬 유효성 검사, CI 상태, 건너뛰기 근거 확인,
작업 트리 경로, Testcontainers 직렬 실행 로그 및 나머지 모듈.

## 작업 6: 최종 종료

**파일:**
- 수정: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **1단계: 모든 프로젝트가 터미널인지 확인**

달리다:

```bash
grep -E "blocked|pending scan|pending" docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md
```

예상: 차단되거나 보류 중인 행이 없습니다.

- [ ] **2단계: 패치가 적용되었으며 무작동 행 증거 확인**

매트릭스에 대해 행 감사를 실행합니다.

- 모든 `patched` 행에는 PR 번호, 라이브 헤드 SHA, 모듈 검토 아티팩트가 포함됩니다.
  로컬 유효성 검사 명령, 7-Tier P0/P1=0, 확인된 최종 `## DoD Status` 및
  최신 CI/check 상태;
- 모든 `no-op` 행에는 source/scan 증거, 생태계 재사용 또는 교육이 포함됩니다.
  예외 근거 및 stability/security P0/P1=0;
- 모든 `follow-up` 행은 지속적인 GitHub 이슈 또는 기록된 후속 조치에 연결됩니다.
  이론적 해석.

- [ ] **3단계: 최종 저장소 빌드 실행**

달리다:

```bash
./gradlew build --max-workers=1 --console=plain
```

예상: `BUILD SUCCESSFUL`.

- [ ] **4단계: 최종 검토**

매트릭스에 대한 최종 7계층 통합 검토를 실행하고 PR 세트를 엽니다. P0/P1은(는) 0이어야 합니다.

- [ ] **5단계: 비파괴 최종 작업 목록**

달리다:

```bash
worktree-list
git branch --format='%(refname:short) %(upstream:short)'
docker ps --filter label=org.testcontainers=true --format '{{.ID}} {{.Image}} {{.Status}} {{.Names}}'
```

예상: 활성 worktrees/branches 및 Testcontainers 잔류물이 보고됩니다.
별도로 정리하지 않는 한 분기, 작업 트리 또는 컨테이너를 삭제하지 마십시오.
요청되었거나 안전이 입증되었습니다.

- [ ] **6단계: 보고 DoD**

보고서:

```markdown
| Step | Status | Evidence |
|---|---|---|
| Registered projects classified | PASS | 100/100 matrix rows terminal |
| Module PRs | PASS | PR list |
| No-op modules | PASS | Matrix rows |
| Local validation | PASS | Final build |
| 7-Tier final review | PASS | P0/P1=0 |
| Release impact | PASS | No merge/no release performed; README/KDoc and CHANGELOG need/no-need rationale recorded |
```
