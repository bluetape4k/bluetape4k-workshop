# Issue #870 AppConfig ConfigData·runtime reload 설계 6-lens 검토

- 검토 대상: `docs/superpowers/specs/2026-09-01-issue-870-appconfig-runtime-reload-design.md`
- 검토일: 2026-09-01
- 근거: 현재 `aws/settings-boundary`, `gradle/libs.versions.toml`, upstream
  `bluetape4k-aws`의 AppConfig 구현·테스트, Spring Boot/AWS 공식 문서
- 판정 기준: `review-perspectives.md`의 P0~P3 및 Type A 설계 게이트

## 독립 관점 결과

| 관점 | 결과 | 적용 또는 N/A |
| --- | --- | --- |
| Performance | PASS (P0=0, P1=0, P2=0, P3=0) | 15초 최소 poll을 단 하나의 30초 bounded integration으로 제한하고, test-only 500ms timeout과 production 10초/5초 timeout을 분리했다. 단일 consumer source와 upstream의 8-worker cap 소유 경계를 명시했다. |
| Stability | PASS (P0=0, P1=0, P2=0, P3=0) | `BootstrapRegistryInitializer`와 application `AwsSyncClientCustomizer`의 timeout 경로, sync `SmartLifecycle` 종료, bootstrap/runtime client 소유권, delayed poll의 non-overlap·atomic replacement·close를 설계에 추가했다. |
| Security | PASS (P0=0, P1=0, P2=0, P3=0; 구현 이관 3건) | 운영 sample에는 endpoint override를 노출하지 않고 trusted HTTPS 경계를 문서화했다. 테스트 구현에서 `StaticCredentialsProvider`, token 순번/해시, 정확한 method/path 및 raw header/payload 비저장을 고정한다. |
| Operator/Ops | PASS | 기본 profile `enabled=false`, explicit profile opt-in, bounded timeout/backoff, full-jitter 5분 상한·무제한 retry라는 upstream 사실, IAM/비용/rollback 주의를 수용 기준과 README에 연결했다. |
| Developer/API | PASS | 기존 provider-neutral API를 유지하면서 Spring Boot ConfigData 표준 URI와 versionless AWS SDK alias를 추가한다. upstream internal API 복제는 하지 않는다. |
| User/caller | PASS | `prefix=appconfig`, properties/JSON, optional/fail-fast, `Environment`와 bean rebinding 차이를 영어·한국어 README에서 동일하게 설명한다. |

## 통합 판정과 결정

초기 검토에서 확인된 세 가지 P1은 다음과 같이 해소했다.

1. sync SDK 호출이 무기한 block되지 않도록 bootstrap과 runtime customizer의
   timeout 등록 경로 및 delayed fake 검증을 설계에 넣었다.
2. 기본 profile에서 AppConfig client/poller가 만들어지지 않도록
   `bluetape4k.aws.app-config.enabled=false`를 명시하고 negative test를 요구했다.
3. runtime 테스트를 단일 15초 poll context와 30초 upper bound로 제한하고,
   다중 source worker cap은 upstream PR #537 소유 계약으로 분리했다.

보안 관점의 구현 이관 항목은 설계 결함이 아니라 테스트 harness가 지켜야 할
구체 계약이다. 구현 시 다음 세 항목이 충족되지 않으면 코드 게이트를 닫지
않는다.

- SDK 기본 credential chain 대신 bootstrap/runtime 모두 synthetic
  `StaticCredentialsProvider`를 주입한다.
- 실제 token 원문과 `Authorization`·payload를 저장/출력하지 않고 순번 또는
  one-way digest만 assertion에 사용한다.
- loopback ephemeral fake는 허용된 `POST /configurationsessions`와
  `GET /configuration`만 처리하고 나머지 method/path는 거부한다.

`Testcontainers`, Exposed, DB/cache/queue, JMH benchmark는 이번 consumer
ConfigData 경계에 없어 N/A이다. upstream이 이미 decoder 제한·worker cap·backoff
동작을 소유하므로 동일 구현을 consumer에서 복제하지 않는다.

## Gate 결과

**PASS — P0=0, P1=0.** 다음 게이트는 이 문서의 구현 이관 보안 계약과 계획의
정확한 테스트 순서를 확인한 뒤 TDD 구현을 시작한다.
