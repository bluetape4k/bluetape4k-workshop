# aws-eventbridge-scheduler 생태계 리뷰

날짜: 2026-07-05
모듈: `:aws-eventbridge-scheduler`
브랜치: `refactor/aws-eventbridge-scheduler-ecosystem-patterns`

## 범위

- EventBridge Scheduler 예제를 Kotlin style, 로컬 AWS 경계, Scheduler request shape, Spring wiring coverage, bluetape4k 생태계 재사용 기준으로 검토했다.
- class spacing과 Serializable model formatting을 정규화했다.
- 로컬 EventBridge publisher와 Scheduler bean을 검증하는 Spring Boot wiring smoke test를 추가했다.
- 로컬 one-time Scheduler request가 AWS-ready `at(yyyy-MM-ddTHH:mm:ss)` 문법과 명시적 `UTC` timezone metadata를 사용하도록 갱신했다.
- 원래 feature issue가 이미 닫혀 있으므로 이 PR은 `Closes #326`가 아니라 최대 `Refs #326`만 사용해야 한다.

## 7-Tier 리뷰

| Tier | 결과 | 근거 |
|---|---|---|
| 1. API와 동작 | PASS | EventBridge publish, Scheduler skip/failure, cancellation, validation test는 계속 커버된다. |
| 2. Kotlin style | PASS | class spacing과 Serializable model layout은 repo style을 따른다. |
| 3. 생태계 재사용 | PASS | 기존 bluetape4k validation, assertion, `runSuspendIO`, 로컬 boundary adapter를 유지했다. |
| 4. Spring wiring | PASS | 새 `EventBridgeSchedulerApplicationTest`가 로컬 publisher, scheduler, service, properties wiring을 검증한다. |
| 5. AWS 경계 안전성 | PASS | 로컬 adapter는 credential-free 상태를 유지하며, Scheduler expression 문법은 AWS 공식 문서와 대조했다. |
| 6. 문서/release 준비도 | PASS | README 동작과 diagram은 변경 없으며, stale-check에서 stale README ref나 깨진 image link가 없었다. |
| 7. 회귀 위험 | PASS | targeted compile/test와 AWS smoke가 통과했고 P0/P1 리뷰 finding은 0이다. |

## 검증

- `repo-test-summary -- ./gradlew :aws-eventbridge-scheduler:compileKotlin :aws-eventbridge-scheduler:compileTestKotlin :aws-eventbridge-scheduler:cleanTest :aws-eventbridge-scheduler:test --no-build-cache --warning-mode all --console=plain --max-workers=1`: PASS, 6개 test 실행, build successful in 5s.
- `repo-test-summary -- ./scripts/smoke-validate.sh aws`: PASS, build successful in 14s.
- `repo-test-summary -- ./scripts/smoke-validate.sh stale-check`: PASS, active module 101개, stale README ref 없음, 깨진 image link 없음.
- `git diff --check`: PASS.
- AWS 공식 문서: EventBridge Scheduler one-time 문법은 `at(yyyy-mm-ddThh:mm:ss)` 이고 timezone은 별도로 설정한다.
- 위험 pattern scan: `aws/eventbridge-scheduler/src`에는 `!!`, `lateinit`, raw JUnit assertion, `assertThrows`, 이전 `companion object:`/`class X:` spacing이 남아 있지 않다.

## 판정

P0/P1 finding: 0.

PR 준비 완료.
