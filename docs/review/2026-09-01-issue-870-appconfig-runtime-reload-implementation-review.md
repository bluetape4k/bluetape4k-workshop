# Issue #870 AppConfig ConfigData·runtime reload 구현 검토

- 검토일: 2026-09-01
- 저장소: `bluetape4k-workshop`
- 대상: `feat/issue-870-appconfig-runtime-reload`
- 기준 HEAD: `bd50f80590dae198d9d3fcc10966ed131b672b46` 및 해당 HEAD 위의
  현재 작업 diff
- 이슈: [#870](https://github.com/bluetape4k/bluetape4k-workshop/issues/870)

## 검토 범위

`aws/settings-boundary`에 Spring Boot 4 `aws-app-config:` ConfigData import와
선택적 runtime reload 예제를 추가한 구현을 검토했다. root
`bluetape4k-dependencies` BOM을 version authority로 유지하면서 AWS SDK
`appconfigdata` versionless alias, bootstrap/runtime timeout, endpoint trust
guard, loopback fake, bilingual README와 저장소 validation guard를 함께
확인했다. upstream lifecycle 내부 구현은 복제하지 않고 consumer 경계만
검증한다.

## 독립 관점 결과

| 관점 | 판정 | 근거 |
| --- | --- | --- |
| 코드/API | PASS | `SettingsBoundarySpringApplication`이 bootstrap 단계에 `appconfigdata` 전용 10초/5초 timeout을 등록하고, 조건부 runtime customizer도 같은 service만 처리한다. 기존 override header는 `toBuilder()`로 보존하며 잘못된 builder는 조용히 무시하지 않고 명시적으로 실패한다. |
| 테스트 | PASS | `AppConfigDataSpringIntegrationTest` 13개가 실제 `SpringApplication`·ConfigData·SDK fake를 사용한다. 초기값/첫 갱신, caller별 binding, properties/JSON/prefix, optional/fail-fast, method/path, endpoint, redaction, timeout, close를 확인한다. |
| 보안 | PASS | fake는 `127.0.0.1:0`과 synthetic `StaticCredentialsProvider`만 사용한다. token ordinal·auth-present marker만 저장하고 raw token, `Authorization`, payload, credential은 저장/출력하지 않는다. pre-ConfigData endpoint guard는 region별 AWS HTTPS host와 literal loopback만 허용한다. |
| 성능/안정성 | PASS | refresh interval은 upstream의 15초 minimum을 따르고, in-flight 요청은 8초 지연 fake에서 context close 상한 6초를 검증한다. close 후 active request 0, executor 종료, 1초 quiescence 동안 request count 불변과 double close를 확인한다. 정상 단일 source의 최대 동시 요청은 1이다. |
| 문서/운영 | PASS | 양쪽 README, coverage matrix, workflow comment, stale-check, lesson이 같은 import·opt-in·caller·timeout·IAM·비용 경계를 설명한다. 실제 AWS와 credential은 기본 경로에서 제외된다. |
| 의존성/빌드 | PASS | `bluetape4k-dependencies:2.0.0-SNAPSHOT`을 유일한 Bluetape authority로 유지하고, `appconfigdata`는 AWS SDK BOM의 versionless alias다. AWS Spring Boot 좌표가 현재 `1.0.0-SNAPSHOT`으로 해석될 수 있다는 사실을 문서화했다. |

## 이전 finding과 해소 내용

| 등급 | finding | 해소 증거 |
| --- | --- | --- |
| P1 | `@Value` probe가 모든 context에 자동 적용되어 default/optional context를 깨뜨릴 수 있음 | probe를 `@TestConfiguration`으로 분리하고 성공적인 main integration에만 명시적으로 추가했다. helper context에는 credential configuration만 넣어 default/optional context가 실제 계약대로 시작한다. |
| P2 | AppConfig region보다 global region을 먼저 읽을 위험 | endpoint guard가 `bluetape4k.aws.app-config.region`을 우선하고 전용 precedence test가 이를 통과한다. |
| P2 | fake가 URI prefix collision 또는 잘못된 method를 수용할 위험 | handler가 정확한 path/method를 검사하고 `/configuration-other`, `/configurationsessions-other`, wrong method을 404/405로 확인한다. |
| P2 | SDK builder cast 실패를 무시하거나 기존 override를 덮어쓸 위험 | `AwsClientBuilder` cast를 명시적 오류로 만들고 `overrideConfiguration().toBuilder()`로 기존 header를 보존하는 production timeout test를 추가했다. |
| P2 | in-flight close가 짧은 delay 때문에 실제 취소를 증명하지 못함 | 두 번째 payload에 8초 지연과 delayed-start latch를 두고, close 직전 request count를 캡처한 뒤 6초 상한·active 0·executor 종료·quiescence를 검증한다. |
| P2 | endpoint guard의 외부 host/URI 오류 경계가 약함 | IMDS, 임의 host, region 불일치, 잘못된 port/path/userinfo와 malformed URI를 거부하고 오류에 원문 URI를 echo하지 않는 test를 추가했다. |
| P2 | lesson/validation 수치와 실제 suite가 불일치 | lesson을 13개 AppConfig test, 약 40.7초 suite, test별 30초 timeout으로 갱신하고 stale-check에 AppConfig contract를 등록했다. |

## 남은 위험

현재 구현에서 P0/P1/P2는 확인되지 않았다. 다음은 upstream 또는 배포 조합의
P3 범위이며 이번 consumer에서 재구현하지 않는다.

- upstream transport failure retry는 full-jitter 지연 상한이 있어도 횟수는
  무제한이다. 운영자는 timeout, shutdown과 네트워크 비용을 함께 관리해야 한다.
- upstream refreshable source worker pool은 8개로 제한된다. 대량 source의
  freshness/queue 지연은 이 consumer의 계약으로 주장하지 않는다.
- application/profile ConfigData에서 유래한 endpoint override는 pre-ConfigData
  guard 이후에 로드되므로 배포 allow-list가 별도로 필요하다.
- 실제 AWS signing/IAM과 upstream lifecycle의 duplicate scheduler, atomic
  replacement, empty/malformed last-good, 새 session retry는 upstream PR
  [#537](https://github.com/bluetape4k/bluetape4k-aws/pull/537)과 다음 named
  tests의 소유다.

## 구현 검토 결론

**CLEAR — P0=0, P1=0, P2=0.** 현재 diff는 PR 전 commit 및 exact-head CI
검증으로 진행할 수 있다. 단, 실제 AWS 호출과 upstream 내부 lifecycle을 이
consumer test의 성공 증거로 오인하지 않으며, merge는 최신 head에 대한 별도
승인 게이트를 따른다.
