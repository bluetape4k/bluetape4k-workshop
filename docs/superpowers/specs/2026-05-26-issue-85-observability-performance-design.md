# Issue #85 — Observability/Performance 고급 완성 설계

**날짜**: 2026-05-26
**지점**: `feat/issue-85-observability-performance`
**저장소**: `bluetape4k-workshop`
**작업 유형**: 유형 A — 전체 디자인

## 문맥

Issue #85은 워크샵에 고급 observability/performance 예시를 강화할 것을 요청합니다.

- 코루틴과 반응 경계를 넘나드는 trace/span 전파를 보여줍니다.
- Gatling/가상 스레드 로드 예시를 결정하고 문서화합니다.
- 유용한 경우 metrics/traces/screenshots 시퀀스 다이어그램을 포함합니다.
- 부하 테스트 전제 조건 및 중지 조건을 포함합니다.
- 행복한 경로에서 지원되는 `bluetape4k-*` API를 최대화합니다.
- `Used Bluetape4k features` 테이블과 before/after 근거를 문서화하세요.

PR #178은(는) 이미 `micrometer-observation`에 대한 여러 README 파일을 업데이트했습니다.
`micrometer-tracing-coroutines`, `gatling/virtualthread-simulation`,
`virtualthreads/spring-mvc-tomcat` 및 `virtualthreads/spring-webflux`, 그러나
문제는 계속 열려 있습니다. 현재 `origin/develop`에는 여전히 공백이 있습니다.
`observability/observability-advanced`: README에 필수 항목이 부족합니다.
`Used Bluetape4k features` 테이블과 before/after 설명, 로컬
`observed()` 도우미 starts/stops Micrometer 범위를 열지 않고 관찰
코루틴 디스패처 경계를 넘나듭니다. 이는 문서화된 스팬 트리가 다음을 수행할 수 있음을 의미합니다.
부모-자식 범위가 아닌 독립적인 관찰이 됩니다.

CodeGraph은(는) 이 저장소에 대해 초기화되지 않았으므로 구조적 영향 검토
현재 소스 검사와 대상 Gradle 검증을 사용합니다.

## 범위

기본 구현 범위:

- `observability/observability-advanced/src/main/kotlin/.../observation/ObservationSupport.kt`
- `observability/observability-advanced/src/test/kotlin/.../service/UserServiceTest.kt`
- `observability/observability-advanced/README.md`
- `observability/observability-advanced/README.ko.md`
- `docs/lessons/2026-05-26-issue-85-observability-performance.md`

검토 전용 회귀 범위:

- `observability/micrometer-observation/README.md`
- `observability/micrometer-tracing-coroutines/README.md`
- `gatling/virtualthread-simulation/README.md`
- `virtualthreads/spring-mvc-tomcat/README.md`
- `virtualthreads/spring-webflux/README.md`

## 설계

### D1. 코루틴 인식 관찰 래퍼

현재 로컬 `observed()` 구현을 다음과 같은 도우미로 바꿉니다.

1. bluetape4k 검증으로 `name`을 검증합니다.
2. Micrometer 관찰을 생성하고 시작합니다.
3. 코루틴 `ThreadContextElement`을 통해 관찰 범위를 엽니다.
4. `withContext(scopeElement)` 내부에서 정지 블록을 실행합니다.
5. 오류를 기록하기 전에 `CancellationException`을 다시 발생시킵니다.
6. 취소되지 않은 오류를 기록합니다.
7. 항상 `finally`에서 관찰을 중지합니다.

이는 업스트림에 대한 기존 해결 방법을 유지합니다.
`withObservationContextSuspending` 복원 중 행복 경로 중지 문제
`withContext(Dispatchers.IO)` 전체에 걸쳐 Micrometer 현재 관측 전파.

### D2. 부모-자식 범위 증명

`user.cache.get`, `user.db.find` 및
`user.cache.put` 모두 상위 관찰 컨텍스트로 `user.service.get`을 갖습니다.
캐시 미스 경로에 있습니다. 캐시 적중 DB 건너뛰기에 대한 기존 테스트 유지, 유출 없음
현재 관찰 및 의미론 중지.

### D3. README 완료

`observability-advanced`에 대한 영어 및 한국어 README를 다음과 같이 업데이트하세요.

- `Used Bluetape4k features` 테이블;
- 원시 프레임워크 접근 방식과 bluetape4k 지원 접근 방식 비교
- 구체적인 혜택 설명;
- coroutine/reactive 경계 전파 참고 사항;
- 연기 및 부하 명령, 전제 조건 및 정지 조건;
- Gatling/virtual-thread 모듈이 유지된다는 설명
  지원되는 `bluetape4k-virtualthread`, 로깅 및 Testcontainers 경로를 학습합니다.

새로 생성된 다이어그램 자산은 계획되지 않습니다. 기존 PNG 아키텍처 자산은
유지되며 기존 README 다이어그램만 참조됩니다.

## 수락 기준

| 기준 | 필수 증거 |
|---|---|
| `observed()`은 코루틴 디스패처 경계를 넘어 현재 관찰 내용을 전파합니다 | 새로운 부모-자식 범위 테스트 통과 |
| 취소는 안전합니다 | 코드 경로에 기존 `CancellationException` 동작이 유지됩니다. 테스트 레지스트리에 유출된 현재 관찰 내용이 없습니다 |
| `observability-advanced` 이슈 #85 Bluetape4k 우선 README 요구 사항 충족 | README/README.ko 기능 테이블, before/after, 이점, smoke/load 명령 포함 |
| 보유된 Gatling/virtual-thread 모듈이 문서화되었습니다 | README 텍스트 이름 유지 모듈 및 중지 조건 명령 |
| 관련되지 않은 모듈이 변경되지 않았습니다 | `git diff --name-status` 범위가 지정된 파일로 제한됨 |
| 타겟 인증 통과 | `./gradlew :observability-advanced:test` |

## 위험

- `TestObservationRegistry` 상위 검증문은 Micrometer 테스트 API에 따라 달라집니다.
  완화 방법: 로컬 1.16.5에서 `hasParentObservationContextSatisfying`을 사용하세요.
  jar는 `javap`으로 확인되었습니다.
- Redis 지원 테스트는 Testcontainers을 사용하며 순차적으로 실행되어야 합니다. 완화:
  하나의 타겟 Gradle 호출만 가능합니다.
- CodeGraph를 사용할 수 없습니다. 완화: 툴링 격차를 기록하고 다음 사항에 의존합니다.
  대상 소스 검사와 compile/test 증거.

## 2-R단계 검토 노트

Claude 코드 CLI 검토 스타일 프롬프트는 이 항목에서 빈 아티팩트를 반복적으로 반환했습니다.
Codex 앱 세션, `claude -p 'Return exactly OK'`이(가) 성공했지만. 사용할 수 있는
Advisor 아티팩트는 다음과 같습니다.

- `.omx/artifacts/claude-issue-85-spec-plan-blockers-20260526055825.md`

정규화된 결과:

| 우선순위 | 찾기 | 결정 |
|---|---|---|
| P1 | 코루틴 컨텍스트 요소가 올바르게 복원되지 않으면 범위가 누출되거나 전파되지 않을 수 있습니다. | 수락됨; 구현에서는 `ThreadContextElement`을 사용하고 `updateThreadContext`에서 반환된 범위를 닫습니다. |
| P1 | `CancellationException`를 삼키면 안 됩니다. | 수락됨; 구현은 `observation.error(e)` 전에 다시 발생합니다. |
| P2 | 하위 코루틴은 컨텍스트 요소를 상속해야 합니다. | 수락됨; 범위는 `withContext(...)`과 함께 설치됩니다. 부모-자식 행동은 `TestObservationRegistry`에 의해 확인됩니다. |

최신 2-R 단계: 계획된 구현 및 테스트 후 P0 = 0, P1 = 0
증거.
