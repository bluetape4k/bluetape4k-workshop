# bluetape4k-workshop 생태계 코드 패턴 디자인

날짜: 2026-07-04
저장소: `bluetape4k-workshop`
분기: `feat/workshop-ecosystem-code-patterns`

## 문제

`bluetape4k-workshop`은 소비자 예제 저장소입니다. 그 예는 다음과 같습니다
bluetape4k 생태계로 일반적인 백엔드 문제를 해결하도록 사용자에게 교육
원시 JDK, 원시 타사 API 또는 임시 로컬이 아닌 라이브러리를 먼저 사용합니다.
bluetape4k 도우미가 이미 존재하는 경우 도우미.

최근 정리 PR에서는 이미 여러 가지 광범위한 클래스를 해결했습니다.

- PR #379 원시 UUID 문자열 생성을 제거하고 누락된 직렬화를 추가했습니다.
  선택된 예의 메타데이터.
- PR #389 잔여 생태계 패턴 표류를 줄이고 후속 조치를 취했습니다.
  유효성 검사, 경계 차단 및 Null 어설션 테스트에 대한 문제입니다.
- PR #393 많은 원시 `require(...)` 검사를 bluetape4k 검증으로 리팩터링했습니다.
  도우미.
- 이슈 #390, #391, #392 및 상위 에픽 #380이 종료되었으므로 이 작업은 반드시 완료되어야 합니다.
  완료된 변경 사항을 복제하지 마십시오.

남은 작업은 모듈별 검토와 대상 리팩터링 통과입니다.
예시 품질을 우선시합니다. 터치된 각 모듈은 bluetape4k를 시연해야 합니다.
유효성 검사, 검증문, coroutine/test 도우미, 로깅, Testcontainers
예시 경계에 맞는 런처 및 기타 생태계 API.

## 현재 증거

- `repo-status` 사양 작성 전: 분기
  `feat/workshop-ecosystem-code-patterns`, 업스트림 `origin/develop`, 깨끗함
  이 사양 파일이 생성되기 전의 작업 트리입니다.
- `./gradlew projects --console=plain`: `BUILD SUCCESSFUL in 11s`; 등기
  Gradle 프로젝트가 표시됩니다.
- 등록된 프로젝트 인벤토리: `:shared`를 포함한 100개 Gradle 프로젝트.
- 기본 빌드: `./gradlew build --max-workers=1 --console=plain`이(가) 전달되었습니다.
  `BUILD SUCCESSFUL in 2m 44s`.
- GitHub 상태: 시작 시 미해결 이슈 및 미해결 PR이 없습니다.
- 마일스톤 상태: `backlog`이(가) 열려 있고 새 PR 메타데이터에 사용할 수 있습니다.
- GNO 이전 워크숍 생태계 패턴 작업에서 발견된 증거: 이슈 #223 및
  coverage/validation 매트릭스 문서.
- 광범위한 Kotlin 패턴 스캔을 통해 등록된 프로젝트 100개 중 62개를 찾았습니다.
  `Thread.sleep`, 원시 `require`, `checkNotNull`와 같은 하나의 후보 패턴,
  boolean/size 어설션 형태, 테스트 `!!`, `runBlocking`, `runCatching` 또는
  `synchronized`.

초기 스캔의 고밀도 후보 모듈:

| 프로젝트 | 주요 후보자 강습 |
|---|---|
| `:okio-examples` | 원시 유효성 검사, 샘플 어설션 스타일, 차단 예제 |
| `:redis-redisson-examples` | 잠자기 및 타이밍 지향 tests/examples |
| `:image-processing-advanced-workflow` | 원시 검증 및 약한 검증문 형태 |
| `:virtualthreads-rules` | sleep/synchronization 교수의도 검토가 필요한 사례 |
| `:image-processing-ocr-api` | sensitive/public 오류 계약을 사용한 원시 유효성 검사 |
| `:leader-leader-election` | sleep/blocking 수명주기 예 |
| `:kotlin-text-processing` | 원시 검증 |
| `:leader-leader-zookeeper` | 차단 브리지 및 수명주기 예 |
| `:leader-tenant-scheduler` | 원시 검증 |
| `:messaging-kafka-outbox-fallback` | 어설션 형태 및 검증 |
| `:spring-boot-cache-caffeine`, `:spring-boot-cache-redis` | 요청 경로 차단 대기 시간 시뮬레이션 |
| `:gatling-virtualthread-simulation` | 부하 시뮬레이션 차단 동작 |
| `:spring-data-*`, `:spring-boot-*`, `:redis-*` | 혼합 어설션, 차단 및 수명 주기 후보 |

## 목표

1. 7-Tier 프레임으로 등록된 모든 Gradle 프로젝트를 검토합니다.
2. 구체적이고 안전한 생태계 재사용 개선을 통해 모듈만 패치합니다.
3. 변경된 Gradle 프로젝트별로 별도의 PR을 생성합니다.
4. 패치가 필요하지 않은 모듈에 대한 무작동 검토 증거를 보존합니다.
5. 집중적인 검토, 검증 및 CI을 위해 PR을 충분히 작게 유지하십시오.
6. 모든 PR 본문을 `## DoD Status`으로 끝내고 실제 본문을 확인합니다.
   `gh pr view --json body`.

## 논골

- 이미 종료된 광범위한 정리 이슈 #390, #391, #392 또는 #380를 다시 열지 마십시오.
- 모든 `Thread.sleep`, `runBlocking`, `check` 또는 `check`을 기계적으로 제거하지 마십시오.
  `runCatching` 의도적으로 차단 동작을 시연하는 경우,
  가상 스레드 동작, 내부 불변성 또는 실패 시뮬레이션.
- 명시적으로 요구되거나 승인되지 않는 한 새 종속성을 생성하지 마십시오.
- 모듈 등록 또는 CI 증거에
  진짜 갭.
- PR을 자동으로 병합하지 마세요.

## 접근 옵션

### 옵션 A: 하나의 저장소 전체 PR

실행하기는 간단하지만 검토하기에는 너무 광범위합니다. 관련 없는 혼합 위험이 있음
모듈 동작, CI 오류를 격리하기 어렵게 만들고 모듈별 숨기기
가르치는 의도.

거부되었습니다.

### 옵션 B: 등록된 Gradle 프로젝트당 하나의 PR

이는 사용자가 요청한 모듈별 형태와 일치합니다. 각 PR에는
명확한 모듈 소유자, 대상 검증, 모듈별 7계층 검토 아티팩트,
CI/debug 표면이 좁습니다. 빈 모듈은 무작동 검토 항목으로 기록됩니다.
빈 PR을 만드는 대신.

선택된.

### 옵션 C: 도메인 디렉터리당 하나의 PR

이렇게 하면 PR 개수가 줄어들지만 관련 없는 하위 모듈은 함께 그룹화됩니다. 예를 들어
`spring-data` 또는 `spring-boot` 런타임 종속성이 다른 예 및
테스트 프로필. 여러 모듈에 필요한 경우 대체 수단으로만 유용합니다.
동일한 공유 수정.

대체만 가능합니다.

## 선택한 디자인

단계별 모듈별 워크플로를 실행합니다.

1. `settings.gradle.kts`에서 등록된 프로젝트 인벤토리를 구축합니다.
2. 프로젝트별로 반복 가능한 생태계 패턴 스캔을 실행합니다.
3. 다음과 같은 모듈부터 시작하여 밀도와 위험에 따라 후보 프로젝트를 처리합니다.
   가장 강력한 실행 가능한 증거를 가지고 있습니다.
4. 각 후보 프로젝트에 대해 다음을 수행합니다.
   - 현재 source/tests/docs 검사;
   - 수정 사항을 설계하기 전에 기존 bluetape4k 생태계 도우미를 검색합니다.
   - 동작이 변경되면 먼저 테스트를 작성하거나 조정합니다.
   - 최소한의 코드 패턴 리팩터링을 적용합니다.
   - 해당 프로젝트에 대해 compile/tests을 실행하세요.
   - 모듈 범위의 7계층 검토를 실행하고 `docs/review/...`을 저장합니다.
   - 작업에서 재사용 가능한 미래 가드가 공개되면 짧은 교훈을 추가합니다.
   - 해당 프로젝트에 대해서만 PR을 커밋하고 엽니다.
5. 안전한 패치가 없는 프로젝트의 경우 추적된 무작동 검토 매트릭스에 추가합니다.
   `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`에 있습니다.
   모듈 범위의 7계층 검토가 확인된 후에만 무작동 상태가 허용됩니다.
   P0/P1=0, 경합, 교착 상태, 누출, 취소에 대한 안정성 검토 포함
   수명주기, 경합 및 Testcontainers 위험.

## 모듈 분기 및 PR Runbook

조정 분기(`feat/workshop-ecosystem-code-patterns`)가 이를 소유합니다.
사양, 구현 계획, 검토 매트릭스 등이 있습니다. 각각 변경됨 Gradle
프로젝트는 자체 모듈 브랜치를 가지며 PR:

| 가지 종류 | 명명 | 내용 |
|---|---|---|
| 조정 | `feat/workshop-ecosystem-code-patterns` | 사양, 계획, 검토 매트릭스, 웨이브 현황 |
| 모듈 PR | `refactor/<project-slug>-ecosystem-patterns` | 한 Gradle 프로젝트의 source/test/docs 변경 사항과 review/lesson 아티팩트 |
| 공유 도우미 예외 | `refactor/shared-ecosystem-patterns` | 둘 이상의 모듈에 필요한 공유 도우미 변경 사항만 |

규칙:

- 더티 모듈이 아닌 현재 `origin/develop`에서 모듈 분기를 만듭니다.
  나뭇가지.
- 사용자가 명시적으로 명시하지 않는 한 한 번에 최대 3개의 활성 모듈 PR을 유지합니다.
  더 큰 배치를 요청합니다.
- 각 웨이브 전과 모든 PR 생성 전에 `origin/develop`을 새로 고치세요.
  PRs/issues 열기를 확인하고, 로컬 상태가 깨끗한지 확인하고, 공유된 항목이 없는지 확인합니다.
  도우미 변경으로 인해 이전 모듈 가정이 무효화되었습니다.
- 모듈 분기가 검증에 실패하거나 대체되면 해당 분기를 닫거나 표시하십시오.
  PR 대체됨, 로컬 증거 보존 및 대체 분기 생성
  현재 `origin/develop`부터.
- 사용자가 정리를 요청하지 않는 한 로컬 또는 원격 분기를 삭제하지 마십시오.
  안전성은 기본 브랜치 조상 또는 패치 동등성에 의해 입증됩니다.

무작동 매트릭스 스키마는 다음과 같습니다.

| 칼럼 | 의미 |
|---|---|
| 프로젝트 | Gradle 프로젝트 경로 |
| 디렉토리 | 소스 디렉토리 |
| 후보 패턴 | 검사 적중 또는 위험 등급 검토 |
| 처분 | `patched`, `no-op`, `follow-up` 또는 `blocked` |
| 생태계 재사용 증거 | Helper/API 채택, 이미 존재 또는 이유가 있는 거부 |
| Stability/security 평결 | P0/P1 상태 및 근거 |
| 검증 증거 | 명령, 소스 행 또는 PR 숫자 |
| Reviewer/date | 소유자 및 날짜 검토 |

## 생태계 재사용 허용 기준

변경된 모든 프로젝트는 다음 점검에 대한 증거를 기록해야 합니다.

- 호출자 입력 검증은 의미 체계가 있을 때 bluetape4k `require*` 도우미를 사용합니다.
  성냥.
- 테스트는 터치된 어설션에서 `bluetape4k-assertions` 어설션 형태를 사용합니다.
- 코루틴, Flow 및 비동기 예제는 취소 의미론을 보존하고 취소를 방지합니다.
  코드가 의도적으로 가르치지 않는 한 일시 중지 경로 주변의 `runCatching`
  경계.
- 동시성 또는 스트레스 테스트에서는 `bluetape4k-junit5` 도우미를 사용합니다.
  (`MultithreadingTester`, `SuspendedJobTester` 또는
  `StructuredTaskScopeTester`) 그 조력자들이 위험에 처했을 때.
- Testcontainers 지원 예제는 다음과 같은 경우 bluetape4k 실행기 싱글톤을 사용합니다.
  생태계는 인프라에 대한 실행 프로그램을 제공합니다.
- 로깅에서는 런타임 `println` 대신 bluetape4k 로깅 패턴을 사용합니다.
  생산 경로.
- 불투명 문자열 식별자는 다음과 같은 bluetape4k ID/string 도우미를 사용합니다.
  `Base58.randomString(...)` 고유 문자열 의미가 필요한 경우.
- 기존 bluetape4k 코루틴, 수명 주기, date/time, 수집 및 지원
  도우미는 원시 JDK 또는 일반 타사 유틸리티보다 선호됩니다.
  예제의 목적에 맞게.
- README영향을 주는 변경 사항 업데이트 `README.md` 및 기존의 모든 지역화된 업데이트
  README 등 `README.ko.md`을 함께 사용합니다.
- 공개 KDoc/API 문서는 영어로 작성되었습니다.
- PR 본문에는 다음과 같은 이름을 지정하는 짧은 "이것이 가르치는 내용" 섹션이 포함되어 있습니다.
  모듈 변경으로 입증된 bluetape4k 생태계 패턴.
- 공개 README/KDoc/example 이름은 현재 소스에 대해 grep 검사됩니다.
  문서가 현재로 청구되기 전에.

## 차단, 수면 및 교육 의도 분류

모든 `Thread.sleep`, `runBlocking`, `runCatching`, lock, `close`/cleanup 및
일시 중지 루프 일치는 패치 전에 분류됩니다.

| 수업 | 기본 동작 |
|---|---|
| 의도적인 교육 시뮬레이션 | 모듈이 차단, 대기 시간, 가상 스레드 또는 오류 시뮬레이션을 명시적으로 가르치는 경우에만 유지하십시오. 검토 증거에 근거 기록 |
| 요청 경로 또는 핫 경로 동작 | bluetape4k coroutine/lifecycle 도우미, 비차단 API 또는 문서화된 시뮬레이터 경계를 선호하세요 |
| 깨지기 쉬운 async/test 잠깐만요 | 적절한 경우 Awaitility, `untilAsserted`, `untilSuspending` 또는 bluetape4k junit5 테스터로 교체 |
| 동시성 또는 취소 스트레스 | 도우미가 맞는 경우 `MultithreadingTester`, `SuspendedJobTester` 또는 `StructuredTaskScopeTester`를 사용하세요. |
| 수명주기 정리 | 명시적으로 안전하지 않은 한 독립적인 정리, 취소 다시 발생 및 일시 중지 없음 `runCatching`을 보장합니다 |
| README/KDoc 스니펫 | 프로덕션 코드 지침이 아닌 문서 전용 조각인 경우 간단한 `println` 예제를 유지하세요 |

정지 취소, 브릿지 차단, 잠자기, 잠금, 수명 주기 관련 편집
close/cleanup 및 Testcontainers 발사기는 안정성에 영향을 미치는 것으로 간주됩니다.
리뷰 아티팩트가 달리 입증되지 않는 한.

## 보안 승인 기준

보안에 민감한 모듈은 다음 검사에 대한 증거를 보존하거나 추가해야 합니다.

- 공개 응답, 경고, 로그, 저장된 요약 및 문서는 그대로 반영되지 않습니다.
  예외 메시지, 스택 추적, 기본 OCR/tessdata 경로, 업로드된 콘텐츠,
  JWT/Bearer 토큰, 인증 값, 멱등성 키, 액세스 키,
  비밀 키, 세션 토큰, 비밀번호, 자격 증명 또는 원시 요청 본문.
- OCR 기본 실패와 같은 기존의 비반향 공개 오류 계약
  만졌을 때 테스트를 거쳐야 합니다.
- 로그 표준화는 민감한 값 유출을 보존하거나 도입해서는 안 됩니다.
- SQL/NoSQL 호출은 다음과 같은 경우 호출자 입력을 쿼리 문자열로 삽입하지 않습니다.
  구조화된 API 또는 바인드 매개변수가 존재합니다.
- 역직렬화 예제에서는 `Any`에 대한 광범위한 다형성 기본 입력을 방지합니다.
  학습자 지향 또는 생산형 경로. 테스트 전용 예제는 다음을 문서화해야 합니다.
  경계를 설정하고 package/type-constrained 유효성 검사기를 선호합니다.
- 토큰, 자격 증명, 비밀 경로 또는 원시 기본 오류의 공개 유출은 P1입니다.
  노출된 신뢰할 수 없는 경로가 없는 안전하지 않은 역직렬화 기본값은 다음과 같습니다.
  테스트로만 입증되고 문서화되지 않은 경우에는 최소한 P2입니다.

## 리뷰 프레임

변경된 각 프로젝트는 모듈 범위의 7계층 검토를 받습니다.

1. 보안: 검증, 주입, 비밀, 안전하지 않은 기본값.
2. Ops/SRE 안정성: 수명 주기, 정리, 로깅, 진단.
3. 구조적 영향: 모듈 경계, 종속성, 등록.
4. Kotlin 코드 품질: 관용구, bluetape4k-code-patterns, 공개 문서.
5. Tests/types/silent 실패: 검증문, 실패 적용 범위, 거짓 긍정.
6. Performance/stability: 차단, 휴면, 경합, 취소.
7. Documentation/release/evidence: README 충격, PR 몸체, CI, DoD.

P0/P1 결과 블록 PR 생성. P2/P3 결과는 로컬 및
저렴하거나 후속 근거로 기록됩니다.

## PR 메타데이터

생성된 모든 PR에 대해:

- Title/body은(는) 영어입니다.
- 담당자는 `debop`입니다.
- 보다 정확한 실시간 마일스톤이 나타나지 않는 한 마일스톤은 `backlog`입니다.
- 라벨은 터치된 영역을 반영합니다(예: `refactoring`).
  `area:governance`, `area:async-reactive`, `area:data-access`,
  `area:spring-boot` 또는 `area:architecture-extension`.
- 마지막 Markdown `##` 제목은 `## DoD Status`입니다.
- 라이브 PR 본문은 `gh pr view <number> --json body`으로 확인됩니다.
- 라이브 메타데이터는 다음을 통해 확인됩니다.
  `gh pr view <number> --json headRefName,baseRefName,assignees,labels,milestone,body,statusCheckRollup`.
- `gh pr checks <number>` 또는 `statusCheckRollup`이(가) 검토됩니다. GitHub 경로인 경우
  필터는 모듈 테스트를 건너뛰고 로컬 대상 테스트는 필수로 기록됩니다.
  CI이 모듈을 다루고 있다고 가정하는 대신 증거를 테스트하세요.

## 검증 전략

변경된 각 프로젝트에 대해 다음을 수행합니다.

- 먼저 대상 컴파일을 실행하십시오.
  `./gradlew :<project>:compileKotlin :<project>:compileTestKotlin --max-workers=1 --warning-mode all --console=plain`.
- 타겟 테스트 실행:
  `./gradlew :<project>:test --max-workers=1 --warning-mode all --console=plain`.
- Testcontainers 지원 또는 캐시에 민감한 경우 `cleanTest --no-build-cache` 사용
  모듈.
- `git diff --check`를 실행하세요.
- 워크플로우 YAML이 변경되는 경우에만 `actionlint`을 실행하십시오.
- 최대 3개의 모듈 PR이 고정된 후에만 전체 리포지토리 빌드를 실행합니다.
  공유 코드 변경 후 또는 최종 종료 전.
- Testcontainers 지원 Gradle 프로세스를 에이전트 전체에서 병렬로 실행하지 마십시오.
  작업 트리, 웨이브 또는 별도의 Gradle JVM. 하나의 결합된 Gradle 호출 사용
  또는 명시적인 순차적 명령.
- 중단된 Testcontainers 실행 후 라벨이 붙은 검사
  `org.testcontainers=true` 재실행 전 잔여물; 다음 경우에만 리소스를 제거하세요.
  정리가 명시적으로 요청되었거나 확실히 안전합니다.
- 관찰 가능성, 가상 스레드, AWS 로깅 또는 추적 예제를 보려면 다음을 확인합니다.
  지연 로깅, 민감한 진단 출력 없음, 제한된 측정항목 레이블
  카디널리티, 관련이 있는 경우 trace/span 전파 및 적절한
  `scripts/smoke-validate.sh` 스크립트가 터치된 모듈을 덮을 때 그룹입니다.
- 벤치마크, Gatling 또는 성능 데모 모듈의 경우 의도적인 내용을 유지합니다.
  로드 시뮬레이션, 임시 벤치마크 하네스를 도입하지 않고 기록
  README/test/smoke 행동 변화에 대한 증거.

## 위험 및 완화

| 위험 | 완화 |
|---|---|
| 기계적 정리로 인해 교육 예제가 잘못 변경됨 | 의도별로 각 일치 항목을 검토하고 문서화된 blocking/virtual-thread 데모 보존 |
| 100개의 PR은 소음을 발생시킵니다 | 변경된 프로젝트에 대해서만 PR을 생성하세요. 최대 3개의 활성 모듈 PR을 유지합니다. 무작동 리뷰는 매트릭스로 이동 |
| Testcontainers 벗겨짐 | 컨테이너 지원 Gradle 작업을 순차적으로 실행하고 오래된 상태에서 실패를 숨길 수 있는 경우 `cleanTest --no-build-cache` 사용 |
| 공유 도우미 편집은 많은 모듈에 영향을 미칩니다 | 모듈-로컬 PR을 선호합니다. 필요한 경우에만 공유 도우미 변경 사항을 자신의 PR으로 분할 |
| 이미 닫힌 정리 중복 | 광범위한 클래스를 파일링하거나 패치하기 전에 PR #379/#389/#393 및 이슈 #390/#391/#392/#380를 확인하세요 |
| 실패했거나 대체된 모듈 PR | 증거를 보존하고 close/supersede PR를 현재 `origin/develop`에서 다시 만들고 rollback/supersede 이유를 문서화하세요 |

## 정지 조건

등록된 100개의 프로젝트가 모두 다음 중 하나로 분류되면 중지합니다.

- 로컬 검증을 통과한 PR 모듈로 패치되고 표시됩니다.
  P0/P1=0 증거 검토 및 실시간 PR 본문 확인; 또는
- 무작동 검토 대상:
  `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`으로
  문서화된 이유, P0/P1=0 stability/security 판정, 코드 패치 없음.

PR을 자동으로 병합하지 마세요.
