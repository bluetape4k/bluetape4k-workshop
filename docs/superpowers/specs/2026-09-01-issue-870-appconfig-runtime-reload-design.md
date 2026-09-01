# Issue #870 AppConfig ConfigData·runtime reload 소비자 예제 설계

- Issue: [#870](https://github.com/bluetape4k/bluetape4k-workshop/issues/870)
- Branch: `feat/issue-870-appconfig-runtime-reload`
- 대상 독자: Spring Boot 애플리케이션에서 bluetape4k AWS 기능을 소비하는 개발자
- 문서 언어: 한국어 (코드·API 이름·명령은 원문 유지)

## 목표와 문제

`aws/settings-boundary`는 Secrets Manager와 Systems Manager Parameter Store를
provider-neutral 계약으로 감싸지만, Spring Boot 4 애플리케이션이
`bluetape4k-aws-spring-boot`의 신규 AWS AppConfig Data 기능을 사용하는 경로는
보여주지 않는다. Issue #870은 다음 두 소비자 흐름을 하나의 credential-isolated
기본 예제와 명시적 opt-in 통합 테스트로 고정한다.

1. `aws-app-config:application#profile#environment` ConfigData import로 초기
   설정을 `Environment`에 로드한다.
2. `refresh-interval`을 명시했을 때만 context 수명 동안 AppConfig Data의
   최신 token을 사용해 runtime reload를 수행한다. 빈 응답·decode/transport
오류에서는 마지막 정상 기준 데이터를 유지하고, context 종료 시 poller와
   client를 닫는다.

기본 `./gradlew :aws-settings-boundary:run`과 smoke/CI에는 AWS credential,
네트워크, provider 비용이 없어야 한다. 실제 AWS 호출은 별도 profile 또는
통합 테스트의 local HTTP fake에서만 발생한다.

## 근거 ledger

| 근거 | 확인한 계약 | 적용 결정 |
| --- | --- | --- |
| 현재 `aws/settings-boundary`의 `SettingsSource`·`SettingsResolver` | provider 오류 분류, full replacement, redaction, cancellation 보존 | 기존 경계를 삭제하지 않고 Spring Boot 소비자 예제를 같은 모듈에 추가 |
| `gradle/libs.versions.toml`의 `bluetape4k-dependencies` | `2.0.0-SNAPSHOT`이 Bluetape 버전 authority이고 AWS SDK v2는 `aws2-bom`이 관리 | AppConfig Data alias는 versionless로만 추가하고 개별 Bluetape BOM/버전은 추가하지 않음 |
| upstream `bluetape4k-aws` Issue [#458](https://github.com/bluetape4k/bluetape4k-aws/issues/458) | `StartConfigurationSession`·`GetLatestConfiguration`, IAM actions, token/payload 비노출 | fake는 초기 token과 다음 token을 반드시 검증하고 로그에는 값·token을 넣지 않음 |
| upstream `bluetape4k-aws` PR [#537](https://github.com/bluetape4k/bluetape4k-aws/pull/537) | Spring Boot ConfigData SPI, properties/YAML/JSON, optional/fail-fast, prefix, single scheduler, last-good reload | upstream 구현을 재구현하지 않고 소비자 설정·wire contract·lifecycle 관찰 예제로 연결 |
| [Spring Boot external config](https://docs.spring.io/spring-boot/reference/features/external-config.html) | `spring.config.import`와 `optional:`은 ConfigData resolver 경계에서 동작 | profile 설정은 import 문법과 optional/fail-fast 차이를 보여줌 |
| [AWS AppConfig retrieval](https://docs.aws.amazon.com/appconfig/latest/userguide/retrieving-feature-flags.html) | 최초 session token과 다음 poll token을 순서대로 사용하며 빈 configuration이 가능 | fake HTTP adapter가 token을 한 번씩 소비하고 빈 응답을 정상 no-op으로 처리 |
| [AWS AppConfig Data API](https://docs.aws.amazon.com/appconfig/2019-10-09/APIReference/API_appconfigdata_GetLatestConfiguration.html) | `GetLatestConfiguration` 응답의 next token·poll interval·payload | local server가 SDK v2의 request/response 경계를 검증하되 실제 AWS 호출은 하지 않음 |

## 범위

### 포함

- `aws/settings-boundary`를 Spring Boot 4 소비자 모듈로 확장한다.
- versionless `software.amazon.awssdk:appconfigdata` catalog alias와
  `bluetape4k-aws` Spring Boot starter를 root BOM 규칙에 맞게 연결한다.
- opt-in `application-appconfig.yml` profile에 ConfigData import, region,
  optional/fail-fast, prefix, reload 설정 예시를 둔다.
- JDK `HttpServer` 기반 credential-isolated fake AppConfig Data endpoint로 실제
  ConfigData resolver/loader와 runtime poller의 token, format, prefix,
  last-good, cancellation/close 경계를 검증한다.
- `Environment` 값은 runtime reload될 수 있지만 `@Value`와
  `@ConfigurationProperties` bean은 자동 rebinding되지 않는다는 계약을
  README와 테스트에서 명시한다.
- validation matrix, smoke workflow 설명/그룹, stale-check lesson, 한국어·영어
  README를 동기화한다.

### 제외

- Spring Cloud Context refresh event, 자동 bean rebinding, actuator refresh
  endpoint를 추가하지 않는다.
- real AWS account, IAM provisioning, hosted configuration 배포, 비용이 드는
  live smoke를 기본 경로에 넣지 않는다.
- upstream `AppConfigDataReloadLifecycle`·decoder·SDK adapter를 consumer에서
  복제하거나 internal API에 의존하지 않는다.
- 새로운 retry/cache abstraction 또는 secret payload를 담는 metric/log를
  만들지 않는다.

## 선택지와 권고

### A — upstream ConfigData 기능을 실제 소비자로 연결 (권고)

`bluetape4k-aws-spring-boot`와 AWS SDK v2 AppConfig Data client를 추가하고,
Spring Boot의 표준 `spring.config.import`로 초기 로딩을 수행한다. 테스트는
JDK local HTTP fake로 SDK wire contract와 context 수명주기를 검증한다.
upstream 기능을 그대로 검증하면서 기본 smoke는 credential-isolated로 유지할 수
있고, 이 모듈이 생산용 poller를 중복 구현하지 않는 것이 장점이다.

### B — 독립 poller를 consumer에 새로 구현

fake adapter와 `ScheduledExecutorService`를 직접 작성하면 테스트는 쉽지만
token 교체, backoff, last-good, close 계약을 upstream과 이중으로 소유하게 된다.
기능 개선 때 두 구현이 갈라질 위험이 있어 거부한다.

### C — Spring Cloud AWS AppConfig에 위임

별도 의존성과 lifecycle 모델을 가져오고 bluetape4k의 ConfigData·client
customizer 경계를 검증하지 못한다. Issue #870의 소비자 학습 목표와 맞지 않아
거부한다.

## 구조와 데이터 흐름

```text
application-appconfig.yml
  └─ spring.config.import=optional:aws-app-config:application#profile#environment?format=properties&prefix=appconfig
       └─ AppConfigDataLocationResolver
            └─ StartConfigurationSession (initial token)
                 └─ GetLatestConfiguration (payload + next token)
                      └─ AppConfigDataPropertySource → Spring Environment
                           └─ refresh-interval 명시 시 AppConfigReloadLifecycle
                                ├─ 단일 scheduler / source별 task
                                ├─ non-empty 정상 payload만 atomic replacement
                                ├─ empty/decode/transport 실패 → last-good 유지
                                └─ context close → task cancel → executor/client close
```

### 모듈 변경

- `aws/settings-boundary/build.gradle.kts`
  - 기존 `application` 진입점은 유지한다.
  - `kotlin.spring`·`spring.boot` plugin과 `springBoot.mainClass`를 추가한다.
  - `libs.bluetape4k.aws`, versionless `libs.aws2.appconfigdata.lib`,
    `spring-boot-autoconfigure` 및 Spring Boot test 의존성을 추가한다.
- `gradle/libs.versions.toml`
  - `aws2-appconfigdata-lib = { module = "software.amazon.awssdk:appconfigdata" }`
    를 AWS SDK v2 block에 추가한다. 버전은 `aws2-bom`에서만 결정한다.
- `SettingsBoundarySpringApplication.kt`
  - `@SpringBootApplication` 진입점을 추가한다. 기본 profile은 AppConfig import를
    활성화하지 않고 credential-isolated 안내만 출력한다.
  - `BootstrapRegistryInitializer`로 ConfigData bootstrap client에
    `appconfigdata` 전용 timeout customizer를 등록하고, context bean에는
    `AwsSyncClientCustomizer`를 등록한다. production 예시는 API 10초·attempt
    5초를 사용하며 credentials provider는 기본 AWS chain을 유지한다.
- `src/main/resources/application-appconfig.yml`
  - 사용자가 `--spring.profiles.active=appconfig`를 명시했을 때만 import한다.
  - URI는 `application#profile#environment`와 optional/prefix/format을 보여주고,
  `prefix=appconfig`를 고정해 원격 key namespace를 제한한다.
  - `refresh-interval`은 15초 이상인 명시적 opt-in 예시로 둔다.
- `src/main/resources/application.yml`
  - 기본 경로에서는 `bluetape4k.aws.app-config.enabled=false`를 명시해 SDK client와
    reload lifecycle bean도 만들지 않는다. AppConfig는 profile에서만 활성화한다.
- 기존 `SettingsBoundaryApplication`과 Secret/SSM 계약은 변경하지 않는다.

## 테스트 설계

1. **ConfigData 초기 로딩**: local `HttpServer`가
   `/configurationsessions`에 `InitialConfigurationToken`을 반환하고
   `/configuration`에 properties payload와 next token을 반환한다. 실제
   `SpringApplication`을 시작해 `Environment`에서 `appconfig.*` 값을 확인하고,
   application/profile/environment header와 token 순서를 기록한다.
2. **format·prefix·optional/fail-fast**: properties payload와 JSON payload를
   각각 prefix import로 읽고, missing endpoint에서 `optional:`은 시작하며
   required import와 `fail-fast=true`는 명시적 오류를 낸다.
3. **runtime reload**: refresh interval을 `15s`로 둔 단 하나의 context에서 fake가
   두 번째 payload를 반환하면 `Environment`의 값은 갱신되지만 시작 시 바인딩된
   `@ConfigurationProperties` probe의 값은 그대로임을 확인한다. 나머지 format,
   failure, token state 검증은 deterministic unit/HTTP contract test로 분리해 CI
   시간을 고정한다.
4. **last-good·token·lifecycle**: empty payload와 malformed payload의 last-good
   보존, transport 오류 뒤 새 session 시작, full-jitter 재시도는 upstream PR #537의
   lifecycle 테스트가 소유한다. 이 consumer 예제는 동일 source가 중복되어도
   poller를 하나만 만들고, context close 후 요청이 더 이상 발생하지 않으며
   executor/client가 닫히는지와 성공적인 첫 runtime 값을 확인한다. 지연 응답 fake는
   **테스트 전용** SDK `apiCallTimeout`/`apiCallAttemptTimeout`을 500ms 이하로
   설정한 client에서 bounded close를 검증한다. production customizer 예시는
   API 10초·attempt 5초처럼 네트워크 환경에 맞는 값으로 분리한다. fake는 loopback의 ephemeral port만 열고 synthetic
   credential만 사용하며, `Authorization`·token·payload 원문을 기록하지 않는다.
   consumer는 단일 source만 소유하며, 8 worker cap과 다중 source 경합은 upstream
   PR #537 테스트의 소유 계약으로 연결한다. 동일 source deduplication과
   property-source atomic replacement도 upstream의
   `AppConfigReloadLifecycleTest.one scheduler and one task per refreshable source update the latest values`
   및 `AppConfigDataPropertySourceTest.property names and values switch atomically`
   근거로 추적하며 consumer에서 내부 구현을 복제하지 않는다.
   bootstrap client의 close listener와 runtime bean의 Spring destroy method를
   각각 한 번씩 관찰하고, 동일 source의 delayed poll에서 최대 동시 요청 수가
   1인지 확인한다. partial map 비관측성은 위 upstream atomic replacement 테스트
   근거로 추적한다. AppConfig lifecycle은
   coroutine이 아닌 sync `SmartLifecycle`이므로 이 항목은 coroutine cancellation
   대신 in-flight call 중단/context close로 검증한다.
5. **기존 회귀**: 기존 `SettingsBoundaryTest` 9개를 유지하고, cancellation과
   redaction이 regression되지 않는지 함께 실행한다.

## 실패 모드와 대응

| 실패 모드 | 관찰 가능한 계약 | 대응 |
| --- | --- | --- |
| session/get 호출의 transport·credential 오류 | startup은 `optional`/`fail-fast` 정책에 따라 실패 또는 빈 source; runtime은 last-good 유지 | SDK API/attempt timeout을 명시하고, token/payload를 로그에 넣지 않으며 full-jitter 지연 상한 5분(재시도 횟수는 upstream 계약상 무제한)으로 새 session을 시작 |
| AWS가 빈 payload 또는 malformed payload 반환 | 값은 삭제·부분 병합되지 않고 이전 atomic map을 유지 | next token은 전진시키며 decode 오류는 경고만 남김 |
| context close 또는 sync SDK 호출 중단 | 새 schedule이 생성되지 않고 close가 idempotent | bootstrap client는 bootstrap close listener, runtime client는 Spring destroy method가 소유한다. API/attempt timeout과 task cancel → executor 종료 → client close 순서를 검증 |
| 동일 import가 여러 번 선언됨 | 하나의 context scheduler와 source별 하나의 task | source name deduplication을 테스트로 고정 |
| `@Value`/`@ConfigurationProperties`에 최신값을 기대함 | `Environment`만 최신값, 기존 bean은 초기 binding 유지 | README 표와 integration test에서 rebinding 범위를 명시 |
| 응답이 무기한 지연됨 | sync SDK worker와 context close가 함께 멈출 수 있음 | production `AwsSyncClientCustomizer`에는 API 10초·attempt 5초 등 환경에 맞는 timeout을 설정하고, 테스트에서는 500ms 이하의 별도 값을 사용해 지연 fake의 upper bound를 검증 |
| 신뢰할 수 없는 endpoint 또는 원격 key가 주입됨 | signed 요청·AppConfig payload가 외부 host로 전송되거나 `spring.*` 등 운영 설정이 덮어써질 수 있음 | endpoint는 운영에서 HTTPS·신뢰된 host만 허용하고, 테스트는 loopback·port 0·합성 credential만 사용한다. import에는 `prefix=appconfig`를 필수로 하며 보안·credential·endpoint key를 원격에 두지 않는다 |

## 수용 기준과 DoD

- [ ] Issue #870과 branch가 문서 metadata에 고정되고, upstream/공식 근거 링크가
  실제 설계 결정을 뒷받침한다.
- [ ] `aws-app-config:` ConfigData import가 실제 Spring Boot 소비자 테스트에서
  동작하고 AWS credential/network 없이 local fake로 재현된다.
- [ ] properties/JSON, prefix, optional/fail-fast, token 순서와 successful
  runtime reload, cancellation/close를 consumer 테스트로 확인한다. duplicate
  scheduler·atomic replacement·empty/malformed last-good 및
  transport-to-new-session 재시도는 upstream PR #537 lifecycle 테스트와
  연결된 근거로 추적한다.
- [ ] 테스트 endpoint는 loopback ephemeral port와 합성 credential만 사용하고,
  허용되지 않은 method/path를 negative contract로 거부한다. 소비자
  `EnvironmentPostProcessor`는 ConfigData보다 먼저 보이는 command-line·system·
  environment source의 운영 endpoint override에 region별 AWS AppConfig Data
  HTTPS host allowlist를 적용하고, HTTP는 loopback 테스트로 한정한다. effective
  endpoint는 AppConfig 전용 override를 먼저 사용하고 공통
  `bluetape4k.aws.endpoint-override`를 fallback으로 사용한다. AppConfig가
  명시적으로 비활성화되면 이 guard는 공통 AWS 예제의 endpoint를 차단하지 않는다.
  application/profile ConfigData endpoint는 배포 정책에 맡긴다. import의
  `prefix=appconfig`로 `spring.*` 등 민감한 unprefixed key가 top-level에
  노출되지 않음을 확인한다. `optional`/`fail-fast=false`는 비보안 feature
  flag에만 사용한다.
- [ ] 기본 `application.yml`은 `bluetape4k.aws.app-config.enabled=false`로 AppConfig
  client/poller를 만들지 않으며, 명시적 `appconfig` profile에서만 import한다.
- [ ] runtime client에는 `apiCallTimeout`/`apiCallAttemptTimeout` 경계가 있고,
  지연 응답 fake가 bounded close를 증명한다. 500ms 이하 값은 테스트 전용이며,
  production 예시는 API 10초·attempt 5초로 분리한다. upstream retry는 full-jitter
  최대 5분·무제한 횟수라는 사실을 문서화한다.
- [ ] runtime integration test는 context 시작·15초 poll·종료를 포함해 30초
  upper bound 안에 끝나며, 실패 시 fake 요청 수와 thread/server cleanup을 진단한다.
  consumer failure tests는 `refreshInterval=null`로 bounded startup 계약만
  검증하고, upstream lifecycle retry 시간은 중복 실행하지 않는다.
- [ ] ConfigData bootstrap initializer와 runtime application customizer가 모두
  `appconfigdata`에만 timeout을 적용한다. bootstrap close listener와 Spring
  destroy method의 소유 경계를 문서화하고, consumer test는 context close의
  요청 quiescence·executor 종료·idempotent close를 관찰하며 upstream holder
  테스트는 실제 client close once 계약을 근거로 제공한다. consumer 정상
  경로의 동시 요청 수는 1이며 partial map은 관찰되지 않는다. 실제 동일
  source overlap 방지는 upstream scheduler 테스트가 소유하므로 smoke 경로에
  세 번째 장기 poll을 추가하지 않는다.
- [ ] 두 README, validation matrix, workflow/stale-check 설명, lesson이 같은
  명령·설정·rebinding 계약을 설명한다.
- [ ] `git diff --check`, module test/build, stale-check, README locale parity,
  Kotlin/Detekt 검증이 통과한다.
- [ ] PR body에 테스트 증거와 `## DoD Status`를 기록하고, exact-head CI 통과 후
  새 `승인`을 받은 다음에만 merge한다. merge 뒤 `develop` 동기화와 feature
  worktree/branch 정리를 수행한다.

## SPW·한국어 품질 게이트

| 게이트 | 이번 산출물 적용 |
| --- | --- |
| SPW-01 목적·독자 | 상단 metadata와 목표 절에 기록 |
| SPW-02 근거·범위 | evidence ledger와 포함/제외 절에 기록 |
| SPW-03 실행 가능성 | 구조, 파일별 변경, 테스트 설계, 실패 대응을 구체화 |
| SPW-04 검증 가능성 | 수용 기준과 명령/기대 계약을 명시 |
| SPW-05 추적성 | Issue, upstream 이슈/PR, README·matrix·lesson 산출물을 연결 |

최종 문서 검토에서는 KO-01(문장 자연스러움), KO-02(용어 일관성),
KO-03(영문 API 보존), KO-04(표현 과잉 금지), KO-05(명령 정확성),
KO-06(독자 행동의 명확성), KO-07(영문 README와 의미 동등성)을 확인하고
`audit-korean-terms.mjs` 결과를 남긴다.
