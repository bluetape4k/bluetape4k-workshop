# Issue #869 설계 spec 통합 리뷰

## 구현 후 판정 보정 (2026-09-01)

이 문서는 계획 단계의 기록이다. 당시 제안한 external AspectJ CTW/Freefair
weaving은 구현 전후 source/artifact smoke에서 upstream
`LeaderElectionAspect.aspectOf()`의 `NoAspectBoundException` 및 no-arg
constructor `NoSuchMethodError`로 실행 불가함을 확인했다. 따라서 CTW 관련
acceptance와 실행 모델은 historical discovery로 격하하고, 최종 구현·검증·README의
`@EnableAspectJAutoProxy(proxyTargetClass = true)` + `spring.aop.auto=false`
Spring runtime proxy 경계를 우선한다.

## 검토 시점과 범위

- 검토일: 2026-08-31
- 대상: `docs/superpowers/specs/2026-08-31-issue-869-scheduled-policy-design.md`
- 기준: live Issue [#869](https://github.com/bluetape4k/bluetape4k-workshop/issues/869),
  `origin/develop`, 현재 `leader/tenant-scheduler` source/build/workflow,
  upstream `bluetape4k-leader`의 `leader-spring-boot` public API와 테스트
- 목적: 승인된 설계가 YAML policy binding, AspectJ CTW 실행 모델, Spring task
  lifecycle, 기본 profile 격리, 문서·운영·보안 경계를 구현 가능한 수준으로
  고정했는지 판단한다.
- 제외: 구현 코드, 구현 계획, PR/CI/merge. 이 단계의 판정은 사양서와 근거
  대조에만 적용한다.

## 독립 관점 결과

| 관점 | 초기 결과 | 최신 통합 결과 | 판정 |
| --- | --- | --- | --- |
| performance | P0=0, P1=0, P2=3, P3=1 | P0=0, P1=0, P2=0, P3=0 | PASS |
| stability | P0=0, P1=3, P2=3, P3=0 | P0=0, P1=0, P2=0, P3=0 | PASS (무응답 후 main fallback) |
| security | P0=0, P1=1, P2=4, P3=1 | P0=0, P1=0, P2=0, P3=0 | PASS |
| operator/Ops | P0=0, P1=2, P2=4, P3=2 | P0=0, P1=0, P2=0, P3=0 | PASS |
| developer/API | P0=0, P1=2, P2=2, P3=1 | P0=0, P1=0, P2=0, P3=0 | PASS |
| user/caller | P0=0, P1=1, P2=4, P3=1 | P0=0, P1=0, P2=0, P3=0 | PASS |

performance 관점은 수정본을 재검토해 P0/P1=0을 확인했다. stability 관점은
두 차례 bounded 요청 후 새 증거가 없어 무응답 lane으로 기록하고, 동일한
artifact·upstream source·local source를 main session이 재검토했다. lane 누락을
자동 PASS로 간주하지 않았으며, fallback 범위와 근거를 위 표에 명시했다.

## 발견 사항과 반영한 수정

| 우선순위 | 관점 | 근거 | 반영한 수정 또는 처분 |
| --- | --- | --- | --- |
| P1 | operator/Ops, developer/API | upstream `leader-spring-boot/README.md`와 `AdviceFireCountTest`는 Spring runtime proxy가 아닌 Freefair AspectJ compile-time weaving(CTW)을 사용하고, test sourceSet은 weave하지 않는다고 명시한다. | `@EnableAspectJAutoProxy`와 `AopUtils.isAopProxy` 성공 조건을 제거하고, Freefair post-compile-weaving plugin, main-source production fixture, `internalAutoProxyCreator` 부재, 실제 woven invocation을 acceptance로 고정했다. `ApplicationContextRunner`는 binding/조건 검증에만 사용한다. |
| P1 | stability, developer/API | `strict=true` upstream method-shape validator는 final fixture를 경고/실패 경계로 분류할 수 있다. CTW 자체의 final weaving 지원과 validator 조건을 혼동하면 profile startup이 깨진다. | fixture를 `open class`/`open fun`으로 고정하고, 이것이 runtime proxy를 켜기 위한 설정이 아니라 strict validator 경계를 통과하기 위한 형태임을 명시했다. |
| P1 | stability, developer/API | 자동설정 opt-in은 profile 이름이 아니라 `bluetape4k.leader.scheduling.enabled` property다. 실제 resource를 읽지 않은 inline-only 테스트는 profile wiring을 증명하지 못한다. | `ConfigDataApplicationContextInitializer`와 `scheduled-policy` active profile로 실제 `application-scheduled-policy.yml`을 읽고, property가 외부 override되면 다른 profile에서도 켜질 수 있다는 경계를 기록했다. |
| P1 | security, operator/Ops | `bluetape4k.leader.aop.enabled=false`는 factory/registry/aspect 조건을 제거할 수 있지만 `@EnableScheduling` task는 남을 수 있다. `spring.aop.auto=false`는 CTW와 다른 설정이다. | 두 opt-out의 서로 다른 결과를 context test와 README 계약으로 분리하고, 무잠금 task 가능성은 단일 프로세스 학습용 경고로 고정했다. `spring.aop.auto=false`에서도 CTW가 유지된다는 acceptance를 추가했다. |
| P1 | security | YAML의 `bean`/`name`은 trusted deployment configuration이며 raw tenant/customer 입력으로 취급하면 lock·metric·log에 PII가 유입될 수 있다. `FAIL_OPEN_RUN`은 backend 장애 때 중복 실행을 허용한다. | static bounded non-PII name, trusted bean/name, `allow-method-invocation=false`, lock-name `REDACT`, `SKIP` 기본값, `FAIL_OPEN_RUN`의 멱등 작업 전용 override를 명시했다. |
| P2 | performance | local elector는 본문 완료 뒤 `min-lease-time` floor를 기다릴 수 있어 production YAML의 5초 값으로 direct CTW smoke가 약 5초 동기 블로킹될 수 있다. | production profile은 `min-lease-time: 5s`를 유지하되 CTW smoke만 indexed property `bluetape4k.leader.scheduling.policies[0].min-lease-time=0s`로 override하고 `assertTimeout(Duration.ofSeconds(5))` 상한을 사용하도록 수정했다. |
| P2 | performance | bean을 직접 호출하면 Spring `ScheduledMethodRunnable` wrapper를 우회하므로 scheduler-level Observation을 증명할 수 없다. | acceptance를 woven leader aspect/local factory/leader-aspect observation으로 한정하고, Spring task 등록·trigger·close는 `ScheduledTaskHolder` lifecycle assertion으로 분리했다. scheduler-level Observation을 direct-call 성공 조건으로 주장하지 않는다. |
| P2 | operator/Ops | profile resource/main class/packaging은 일반 module test만으로 충분히 드러나지 않고, 새 selector/YAML/README는 stale drift 대상이다. | 별도 외부 process 단계 대신 `@SpringBootTest` profile startup/close를 계획하고, 구현 시 `scripts/smoke-validate.sh stale-check`에 YAML·configuration·exact selector·양쪽 README 명령을 검사하는 좁은 guard를 추가하도록 고정했다. |
| P2/P3 | performance, stability, user/caller | 60초 `initialDelay`, 5초 `min-lease-time`, `cancel(false)`의 pending/in-flight 차이, metadata cache와 README 실행 순서가 모호하면 예제 재현성이 떨어진다. | README에 초기화 후 최소 약 60초 대기, lease floor에 따른 추가 지연, `Ctrl-C`, bounded test command를 기록하고, pending task만 close 보장하며 in-flight 중단은 보장하지 않는다고 명시했다. upstream immutable registry/metadata cache를 그대로 소비한다는 비회귀 근거를 남겼다. |

## 통합 확인

- **범위와 호환성:** root `bluetape4k-dependencies` BOM을 통한 versionless
  `bluetape4k-leader-spring-boot`만 추가하고 개별 Bluetape version pinning이나
  별도 BOM을 만들지 않는다. 기존 reducer와 default profile은 변경하지 않는다.
- **실행 모델:** `io.freefair.aspectj.post-compile-weaving`이 main sourceSet을
  weave하고 upstream registry/BPP/aspect/local factory를 소비한다. workshop이
  scheduler, executor, registry, aspect를 복제하지 않는다.
- **검증 경계:** 실제 profile YAML binding과 task 수/close는 Spring context에서,
  woven leader invocation은 main-source fixture를 `@SpringBootTest` context bean에서
  직접 호출해 검증한다. 이 직접 호출은 Spring `ScheduledMethodRunnable` observation
  경로를 검증한다고 주장하지 않는다.
- **실패·안전:** 빈/오타/중복/overload/unmatched selector, malformed duration과
  plain policy의 lease semantic 오류는 startup fail-fast로 고정한다. explicit
  `@LeaderElection`, `@LeaderGroupElection`, `@LeaderScheduled`는 property보다
  우선하며 미사용 property semantic 검증과 annotation validator를 구분한다.
- **운영·문서:** 기본값은 background task가 없고, local factory는 외부 backend가
  없는 학습용이다. 양쪽 README, root README pair, `docs/coverage-matrix.md`,
  stale guard와 rollback 명령을 같은 사실로 갱신한다. 새 module registration이나
  diagram 변경은 현재 구조상 필요 없다는 N/A 근거를 남겼다.
- **후속 단계 경계:** profile configuration, YAML, tests, README, catalog/plugin,
  stale guard의 구현과 실제 Gradle/CI 증거는 구현 계획과 이후 gate의 책임이다.
  사양서가 이를 acceptance와 exact command로 고정했으므로 현재 단계에서 구현을
  시작하지 않는다.

## SPW writer gate

| 검사 | 결과 | 증거 |
| --- | --- | --- |
| SPW-01 독자·목적·출처·범위 | PASS | spec metadata, Issue #869, local/upstream source ledger, 포함·제외 범위 |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | 선택한 CTW 구조, rejected alternatives, failure modes, acceptance/DoD, six-lane finding/repair 표 |
| SPW-03 한국어 기술 문체·용어 | PASS | `audit-korean-terms.mjs` 결과 `findings=0`; API/path/command/URL/identifier는 원문 보존 |
| SPW-04 현재 소스·외부 계약 대조 | PASS | live Issue, `origin/develop`, catalog/BOM/workflow, upstream properties/registry/BPP/CTW/lifecycle source 대조 |
| SPW-05 read-back·Markdown·공백 | PASS | spec/review read-back, placeholder scan, code fence/table/link 확인, `git diff --check` |

## KO-01~KO-07 자연스러움 점검

- **KO-01 PASS:** Issue 번호, branch, package/path, API, duration, profile,
  실행 조건과 uncertainty(외부 property override, in-flight cancellation)를
  의미 변경 없이 보존했다.
- **KO-02 PASS:** “안전하다/성능이 좋다”와 같은 근거 없는 홍보 대신 task 수,
  startup failure, close 범위, timeout, trust boundary를 수용 기준으로 썼다.
- **KO-03 PASS:** 반복적인 번역투를 줄이고 문제→선택→실패→검증 순서로 문단과
  표를 정리했다.
- **KO-04 PASS:** `scheduled-policy`, CTW, selector, policy, task, registry,
  `FAIL_OPEN_RUN` 등 고정 용어를 문서 전체에서 같은 의미로 사용했다.
- **KO-05 PASS:** 과장된 비유나 홍보 표현 없이 예제의 학습용 범위와 unsupported
  distributed ownership을 직접 설명했다.
- **KO-06 PASS:** metadata, 본문, 표, fenced YAML/명령, 링크와 영어/한국어
  README 갱신 surface를 검토 범위에 포함했다. diagram은 변경하지 않는 근거를
  기록했다.
- **KO-07 PASS:** spec과 review 모두 contextual terminology audit를 실행하고
  findings=0을 확인했다.

## 통합 판정

초기 P1은 CTW 실행 모델, sourceSet weaving, strict fixture shape, 실제 profile
binding, AOP opt-out과 trust boundary 문제였다. 각 항목을 exact spec edit로
수정했고, performance의 min-lease blocking/직접 호출 observation P2도 test-only
override와 acceptance 축소로 처분했다. stability native lane은 bounded retry 후
main fallback으로 동일 근거를 독립 재확인했다.

**결론: PASS — 통합 결과 P0=0, P1=0, P2=0, P3=0.**

다음 gate는 이 문서를 바탕으로 implementation plan을 작성하고, plan 자체를
다시 여섯 관점과 main integration으로 검토하는 것이다. 구현·PR·CI·merge는 그
이후 단계이며, merge에는 현재 head에 대한 별도 fresh `승인`이 필요하다.
