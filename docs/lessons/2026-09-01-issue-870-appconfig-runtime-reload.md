# Issue #870 AppConfig ConfigData·runtime reload lesson

## 결정

기존 `aws/settings-boundary` consumer에 upstream bluetape4k AWS Spring Boot의
`aws-app-config:` ConfigData 경계를 추가했다. 대안 A(Spring Boot 표준
ConfigData resolver/loader와 upstream context lifecycle 재사용)를 선택하고,
upstream 내부 lifecycle 코드를 이 저장소에 복사하지 않았다. root
`bluetape4k-dependencies` BOM을 유일한 bluetape4k version authority로 유지하고
AWS SDK v2 `appconfigdata`만 versionless catalog alias로 추가했다.

초기 payload는 `Environment`에 `prefix=appconfig`으로 들어오고,
`refresh-interval`을 명시한 경우에만 context 수명 동안 최신 map으로 원자적
교체된다. `@Value`와 `@ConfigurationProperties` bean은 자동 rebind하지
않는다. Spring Cloud Context refresh/rebinding은 의도적으로 범위에서
제외했다.

## 안전 경계

- 기본 `application.yml`은 AppConfig를 끄며 기본 실행, smoke, CI에는 credential과
  원격 호출이 없다.
- 테스트는 `StaticCredentialsProvider`의 synthetic 값과 `127.0.0.1:0` fake만
  사용한다. credential 파일, 환경 credential 변수, IMDS, 실제 AWS endpoint를
  사용하지 않는다.
- production AppConfig SDK client에는 API timeout 10초와 attempt timeout
  5초를 적용한다. 500ms timeout은 direct SDK delayed loopback 테스트 전용이다.
- endpoint guard는 ConfigData보다 먼저 보이는 command-line·system·environment
  source의 값을 client 생성 전에 검사한다. AppConfig 전용 endpoint를 먼저,
  공통 `bluetape4k.aws.endpoint-override`를 fallback으로 적용하고, AppConfig가
  활성화된 경우에만 region별 AWS AppConfig Data HTTPS host를 허용한다. HTTP는
  literal loopback fake로 제한하며, AppConfig가 비활성화된 경우 공통 endpoint는
  다른 AWS 예제의 설정으로 남긴다. application/profile ConfigData endpoint는
  배포 정책으로 별도 제한한다.
- `prefix=appconfig`으로 원격 `spring.*`·`management.*` key의 top-level 주입을
  차단한다. endpoint override는 신뢰할 수 있는 HTTPS host와 배포 allow-list를
  통해서만 명시해야 한다.
- 실제 AWS 실행에는 `appconfig:StartConfigurationSession`과
  `appconfig:GetLatestConfiguration` 권한이 필요하며 polling traffic과 비용을
  고려해야 한다.

## upstream 재사용 범위

다음 failure/lifecycle 계약은 upstream PR [#537](https://github.com/bluetape4k/bluetape4k-aws/pull/537)의
내부 테스트가 소유한다.

- `AppConfigReloadLifecycleTest.one scheduler and one task per refreshable source update the latest values`
- `AppConfigReloadLifecycleTest.empty response retains values while advancing the token`
- `AppConfigReloadLifecycleTest.decode failure retains map while advancing response token`
- `AppConfigReloadLifecycleTest.transport failure discards session and retries with a new session`
- `AppConfigDataPropertySourceTest.property names and values switch atomically`
- `AwsConfigDataBootstrapBridgeTest.initialized-only holder does not create or close unused client`

consumer 테스트는 위 내부 구현을 복제하지 않고 초기 ConfigData 로드, 첫 runtime
갱신, JSON/prefix, optional/fail-fast, method/path 및 endpoint 경계, 기본 profile
비활성, synthetic auth marker, delayed timeout, redaction, 정상 경로의 단일 source
순차성, in-flight context/fake 종료를 검증한다. duplicate scheduler와 진짜
overlap 방지는 위 upstream lifecycle 테스트가 소유한다.

## 검증과 수정 기록

초기 RED에서는 `SettingsBoundarySpringApplication` 미구현으로
`compileTestKotlin`이 실패했다. 구현 후 runtime 갱신이 일어나지 않은 첫 GREEN
시도에서는 `SpringApplicationBuilder.properties`가 default property라서
`application.yml`의 `bluetape4k.aws.app-config.enabled=false`에 우선하지 않는
문제를 확인했다. 테스트 실행 시 `--bluetape4k.aws.app-config.enabled=true`를
command-line property로 전달해 이 경계를 명시했다.

이후 fake request body에서 identifier만 파싱하고 Authorization·token·payload
본문은 보관하지 않도록 했다. AppConfig API의 session 요청과 latest 요청 모두
인증 marker를 기록하되 token은 ordinal만 기록한다. context를 두 번 닫고 fake
server를 `finally`에서 중지한 뒤 active handler 0, executor terminated,
1초 quiescence 동안 request count 불변을 확인한다.

Issue #963의 Nightly smoke에서는 모든 Spring 통합 테스트에 direct SDK timeout
검증용 `500ms`를 적용한 탓에 loopback 404 응답이 지연되면 optional 처리 전에
`ApiCallTimeoutException`이 발생했다. 일반 Spring 통합 client는 production과
같은 API timeout 10초와 attempt timeout 5초를 사용하고, `500ms`는 1.2초 지연을
검증하는 direct SDK 테스트에서만 명시한다. optional 404 fake에 750ms bounded
delay를 둔 회귀 테스트로 두 timeout 용도가 다시 섞이지 않도록 고정한다.

성능 검토에서 in-flight poll 종료가 실제 취소 경계를 통과하는지 확인하도록
8초 지연 응답을 추가하고, close 직전에 request count를 캡처하도록 보강했다.
endpoint guard는 IMDS·임의 외부 host·region 불일치 host와 공통 endpoint
fallback 우회를 거부하는 negative 테스트로 고정했다. AppConfig 비활성화 시
guard가 동작하지 않는 조건도 함께 확인한다.

2.1.0-SNAPSHOT consumer CI에서 같은 종료 테스트가 간헐적으로
`POST /configurationsessions` 1회와 `GET /configuration` 2회 뒤, 종료 경계에서
동일한 `GET /configuration?configuration_token=synthetic-token-2`를 한 번 더
받는 3→4 요청 수 race를 확인했다. upstream lifecycle 구현이나 일반 통합
client 설정을 바꾸지 않고, 이 테스트의 목적을 lifecycle 종료 취소 경계로
한정했다. 따라서 해당 fake 요청만 lifecycle의 5초 종료 대기보다 긴 API/attempt
timeout(각 30초)을 사용하고, 중단된 SDK 호출의 재시도는
`DefaultRetryStrategy.doNotRetry()`로 비활성화했다. 기존 일반 통합 client의
10초/5초 timeout과 direct SDK 500ms timeout은 유지한다. 이 격리는 종료 계약과
SDK timeout/retry 계약이 서로의 결과를 바꾸지 않도록 하는 회귀 규칙이다.

같은 consumer lane의 Container CI는 2.0.0 mock image tag와 2.1.0-SNAPSHOT
consumer가 요구하는 mock API 소스가 어긋나지 않도록 `bluetape4k-projects`
`develop`의 live commit `23f60647ef53503a3dcbb9a7ac331abb83401db9`를 exact
checkout ref로 고정했다. Nightly의 안정 release tag(`2.0.0`) 계약은 별도
workflow와 lesson에서 유지한다.

첫 hosted CI에서는 consumer test의 legacy JUnit assertion import가 assertion
governance에 걸려 `shouldBeTrue`로 치환했다. 다음 실행에서는 변경 경로가 기존
`epic-792-train-promotion` scope를 선택해 `expected_head_ref`가
`feat/issue-870-appconfig-runtime-reload`와 불일치했다. 이번 PR의 20개 경로만
포괄하는 `issue-870-aws-appconfig-runtime-reload` stacked-parent-head scope를
manifest에 추가하고, 기존 receipt를 재사용하지 않은 새
`coordinator_scope_receipt`를 발행했다. scope canonical JSON SHA-256은
`6a3192f4ca72c3f6773861f95a7f6e535d7d0765c40158005aec2c0b8b0e16e8`이며, trusted
manifest 기반 exact `--pr-scope` checker와 ecosystem checker 106개 테스트가
통과했다.

실행한 핵심 명령:

```bash
./gradlew :aws-settings-boundary:compileTestKotlin --no-daemon --console=plain
./gradlew :aws-settings-boundary:test --tests '*AppConfigDataSpringIntegrationTest' \
  --no-build-cache --no-daemon --console=plain
```

AppConfig consumer 통합 테스트 16개가 통과했으며 두 runtime 경계 테스트를
포함한 suite는 약 40.6초에 30초 개별 테스트 제한을 지킨다. 전체 모듈의
기존 9개 테스트를 포함해 25개 테스트가 통과했다. 이 비용은 upstream
lifecycle을 복제하지 않고 실제 15초 poll과 in-flight 종료를 검증하는 CI 비용으로
수용한다. 전체 모듈 테스트,
`build`, `detekt`, stale-check, README parity/diff-check는 PR 직전에 다시
실행한다.

## 남은 위험

upstream lifecycle의 transport failure 재시도는 delay당 최대 5분의 full-jitter
backoff를 사용하지만 횟수 제한은 없다. 이 consumer는 이를 재구현하거나
완화하지 않고 upstream 테스트와 운영 timeout/종료 계약을 문서화한다. 실제
AWS endpoint allow-list와 IAM 정책은 consumer guard와 배포 조합에서 함께
관리하며, 예제는 실제 AWS credential을 자동으로 활성화하지 않는다.
