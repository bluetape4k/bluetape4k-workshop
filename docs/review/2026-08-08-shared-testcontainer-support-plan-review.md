# 예제 공통 Testcontainers 지원 계획 리뷰

- 리뷰 대상: docs/superpowers/plans/2026-08-08-shared-testcontainer-support.md
- 기준 설계: docs/superpowers/specs/2026-08-08-shared-testcontainer-support-design.md
- 리뷰 기준: bluetape-full-feature Step 3-R, step-3r-plan-review.md, review-perspectives.md
- 리뷰 시점: 2026-08-08
- 범위: 계획의 순서·증거·테스트·문서·hazard·rollback 및 라이브 이슈 게이트
- 실행 제한: Testcontainers, Gradle, GitHub CLI는 계획 리뷰 중 실행하지 않음

## 독립 관점 결과

네이티브 세 lane(performance, stability, security)은 bounded wait 후 응답이 없어 중단했다. full-feature 대체 절차에 따라 아래 세 관점과 나머지 세 관점을 최신 계획에 대해 main session에서 독립적으로 수행했다. 계획 또는 저장소를 수정한 lane은 없다.

| Priority | Lens | Evidence | Required edit / disposition | Rerun lane |
|---|---|---|---|---|
| P2 | performance | RedisTestSupport는 테스트 시작 시 세 supplier를 등록하는 helper이고 production hot path가 아니다. 계획에 benchmark 명령은 없다. | benchmark는 N/A로 명시한다. 작업 3·5의 순차 영향 테스트와 dependency graph가 충분한 증거이며, 새 allocation/stress benchmark를 추가하지 않는다. | main integration |
| P2 | stability | RedisServer.Launcher.redis와 TestMutexService를 보존하고 Testcontainers 명령을 직렬화한다. 새 retry/cancellation 경로는 없다. | lifecycle/startup 실패, rollback, backend 준비 실패를 작업 3·5에 기록한다. retry/cancellation 테스트는 동작 비해당으로 유지한다. | main integration |
| P2 | security | helper 값은 로컬 Testcontainers 서버에서 읽고 외부 입력을 받지 않는다. issue body와 label은 정적이며 비밀값을 포함하지 않는다. | 추가 보안 구현은 불필요하다. live 게이트에서 source/issue/label을 재확인하고 이슈 본문을 즉시 view한다. | main integration |
| P2 | operator/Ops | 새 모듈·release·workflow 변경이 없으며 단계별 rollback과 dirty asset 보존이 계획되어 있다. | settings/CI/validation matrix/stale-check/Kover N/A를 작업 5에 명시한 현재 계획을 유지한다. | main integration |
| P2 | developer/API | 작업 1→2→3→4→5→6→7 순서가 컴파일 의존성과 외부 side effect를 분리한다. 공개 API, compileOnly, 소비 모듈 testImplementation, exact keys가 파일 단위로 지정됐다. | 추가 편집 없음. 작업 6에 source 검색·4개 issue 검색·label 목록·issue view 명령을 보강했다. | main integration |
| P2 | user/caller | 네 호출부 import 전환, local helper 삭제, English/Korean README parity, 한국어 KDoc과 GitHub 문구가 계획되어 있다. 호환 alias를 만들지 않는 migration 경계도 명시됐다. | 추가 편집 없음. README 예시와 exact property key 검증을 작업 4·7에서 확인한다. | main integration |

## Step 3-R 필수 체크

| Check | Verdict | Evidence |
|---|---|---|
| 모든 설계 요구·DoD가 concrete task에 매핑됨 | PASS | 계획 수용 기준 추적표 및 작업 1–7 |
| 현재 코드 기준 실행 가능한 순서 | PASS | RED는 새 symbol 부재, GREEN 후 소비 모듈 전환, issue는 테스트 후 실행 |
| 후속 artifact 선행 의존성 없음 | PASS | source/test→shared API→consumer→docs→validation→live issue |
| 성공·실패·edge·lifecycle·concurrency·backend 경로 | PASS | exact key contract, expected RED/failure rollback, Launcher/TestMutex, 직렬 Testcontainers, Redis backend |
| 구체적인 검증 명령 | PASS | focused test, compile/test, detekt, dependencies, projects, diff check, gh source/issue/label/view |
| README locale 문서 범위 | PASS | shared/README.md와 shared/README.ko.md 작업 4 |
| 한국어 KDoc/GitHub 공개 문구 | PASS | 작업 2 KDoc, 작업 6 issue body, 작업 7 언어 정책 |
| module/workflow/catalog/Kover hazard | PASS/N/A | 새 모듈 없음; 작업 5에 settings·workflow·matrix·stale-check·Kover N/A 기록 |
| Spring auto-configuration/Exposed/coroutine 조건 | N/A | auto-configuration, Exposed, coroutine 동작을 변경하지 않음 |
| Testcontainers 안정성·resource cleanup | PASS | Launcher 생명주기 보존, 명령 직렬화, startup 실패와 rollback |
| 중복 추출 결정 | PASS | 설계 대안 A 선택, Netty/PostgreSQL/Ktor 제외 근거 |
| rollback/compatibility/migration | PASS | 작업 2·3·6·7 rollback 및 exact key 보존 |

## Main-session integration verdict

- 중복된 P2 관찰을 통합했고 P0=0, P1=0이다.
- 계획의 live issue 단계는 구현·검증 이후이며, 중복/유효성/label 확인 실패 시 이슈를 만들지 않는 조건부 side effect로 제한된다.
- issue source search와 label 목록 명령을 계획에 추가하고 plan commit eb137169에 반영했다.
- 구현 전 compile command를 실제 Gradle project tree로 확인하는 과정에서 계획의 축약 경로 :spring-data:redis-examples가 ambiguous임을 발견했다. 명령을 :spring-data-redis-examples로 고쳐 plan commit 09302c43에 반영했으며, 이는 source/API 결함이 아닌 계획 명령 오기였다.
- spec 목표 1–5, 비목표, failure mode, README parity, dirty asset 보존이 모두 작업과 추적표에 연결됐다.
- 별도 사용자 결정이 남아 있지 않다. PR·merge·release는 사용자 승인 범위 밖이며 최종 DoD에서 N/A로 보고한다.
- 위 계획 명령 수정 후 main-session integration을 재실행해 P0=0, P1=0을 유지했다.

**Step 3-R DoD: PASS**

다음 의존 단계는 Step 4 TDD 구현이다.
