# 예제 공통 Testcontainers 지원 통합 설계 검토

- 검토 대상: `docs/superpowers/specs/2026-08-08-shared-testcontainer-support-design.md`
- 검토 기준: Type-A Step 2-R six-lens contract, 현재 저장소 소스와 repo-local `AGENTS.md`
- 검토일: 2026-08-08
- 범위: `shared` Redis helper 이동, 영향 Gradle 의존성, 테스트/문서, 조건부 `bluetape4k-projects` 이슈

## 관점별 결과

| Priority | Lens | Evidence | Required edit | Rerun lane |
|---|---|---|---|---|
| P2 | Performance | Redis singleton과 `PropertyExportingServer` 맵 등록은 테스트 설정 경로이며 production hot path가 아니다. `NettyConfig` 차이를 보존하는 범위도 명시되어 있다. | 별도 benchmark는 N/A로 기록하고 `:shared:test` 및 영향 모듈 테스트의 startup/timeout 결과만 수집한다. | performance |
| P2 | Stability/Ops | 기존 `RedisServer.Launcher.redis`와 Gradle TestMutex를 유지하면 container 재사용/직렬 실행 계약이 보존된다. | RED/GREEN 후 Redis 영향 테스트를 한 번에 하나씩 실행하고 shutdown 잔류를 확인한다. | stability |
| P2 | Security | 새 helper는 host/port/url만 노출하며 credential/token을 만들지 않는다. 이슈 생성은 live 중복 검사 이후 조건부다. | 이슈 본문과 로그에 실제 URL 자격 증명을 포함하지 않고, 공개 KDoc 예시는 로컬 주소만 사용한다. | security |
| P2 | Operator/Ops | 새 모듈이 아니므로 settings/CI/Nightly 등록 변경은 발생하지 않는다. README와 rollback 경계가 설계에 포함되어 있다. | 최종 DoD에 module-registration N/A 근거와 기본 worktree dirty 이미지 보존 결과를 명시한다. | ops |
| P2 | Developer/API | 공개 `shared` object는 모듈 경계를 넘으므로 한국어 KDoc과 호출 예시가 필요하다. 설계에 이를 반영했다. | 구현 시 object/function KDoc, import 영향, `compileOnly` 소비 모듈 compile을 함께 검증한다. | developer/api |
| P2 | User/Caller | 기존 네 호출부는 동일한 한 줄 등록 API를 유지하고 import만 이동한다. `shared`가 repository-internal이라는 한계도 README에 기록해야 한다. | English/Korean README에 Redis helper 사용 범위와 외부 published dependency가 아님을 동일하게 기록한다. | user/caller |

## Main-session integration verdict

- 중복된 P0/P1은 없다.
- Netty, raw HTTP client 호출, 도메인별 PostgreSQL 설정은 의도적 차이 또는 별도 계약이 있어 이번 통합에서 제외한다.
- 설계가 정의한 테스트 순서(RED → 최소 구현 → `:shared:test` → Redis 영향 테스트 → 정적 검사)가 구현 순서와 일치한다.
- 새 모듈/발행 artifact가 없으므로 module registration, BOM, Kover artifact 변경은 concrete N/A로 기록한다.
- 라이브러리 이슈는 현재 `PropertyExportingServer` 계약을 재확인하고 open/all 이슈 중복 검색을 통과한 경우에만 생성한다.

## Lane execution note

Native review lane 세 개(performance, stability, security)는 90초 이상 bounded wait 후 응답하지 않아 main-session lens review로 대체했다. capability mismatch나 모델 대체는 없었고, 위 표에 여섯 관점을 모두 통합했다.

**Verdict: PASS (P0=0, P1=0; P2=6 deferred with explicit validation/DoD actions).**
