# Issue 384 Stale Check Project Count Review

## 범위

- 이슈: #384 `Refresh stale-check Gradle project count baseline`.
- 작업 유형: build/governance validation cleanup.
- Diff 범위: `scripts/smoke-validate.sh`와 review/lesson artifact.
- Baseline local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 42s`.

## 결정

현재 `develop` project count는 stale issue-body snapshot인 `106`이 아니라 이미 `100`이다. 그러나 `stale-check`가 hard-coded `expected=100`을 사용했기 때문에 false-warning risk는 여전히 존재했다.

script는 이제 current Gradle project graph에서 default baseline을 도출하고, `EXPECTED_GRADLE_PROJECTS`가 명시적으로 제공된 경우에만 fixed value와 비교한다.

## 검증 근거

| Scope | Command | 근거 | 결과 |
|---|---|---|---|
| Repo | Baseline local build | `BUILD SUCCESSFUL in 1m 42s`; log `/tmp/issue384-baseline-build.log` | PASS |
| Repo | Post-work local build | `BUILD SUCCESSFUL in 2m 22s`; log `/tmp/issue384-full-build.log` | PASS |
| smoke script | Syntax | `bash -n scripts/smoke-validate.sh` | PASS |
| stale-check default | `./scripts/smoke-validate.sh stale-check` | `Active modules: 100 (expected: current Gradle project graph)`; stale ref 없음; broken image link 없음 | PASS |
| stale-check explicit baseline | `EXPECTED_GRADLE_PROJECTS=100 ./scripts/smoke-validate.sh stale-check` | `Active modules: 100 (expected: 100)`; stale ref 없음; broken image link 없음 | PASS |
| Repo | Whitespace | `git diff --check` | PASS |

## 7-Tier Review

| Tier | 판정 | 근거 |
|---|---|---|
| Security | PASS | Shell validation만 변경했다. credential, network, secret path는 변경되지 않았다. |
| Stability | PASS | module count가 의도적으로 바뀔 때 default path가 더 이상 false warning을 내지 않는다. |
| Performance | PASS | 기존 `./gradlew projects` query를 재사용하며 추가 scan은 없다. |
| Operator/Ops | PASS | `EXPECTED_GRADLE_PROJECTS`를 통한 optional fixed-baseline mode를 유지한다. |
| Developer/API | PASS | 기존 command name과 stale README/image check는 보존된다. |
| User/Reader | PASS | README 또는 public example behavior는 변경되지 않았다. |
| Evidence | PASS | baseline build, shell syntax, stale-check variant, diff check, post-work full build가 통과했다. |

## 발견사항

- P0/P1: 0.
- P2: 없음.
- P3: CI가 나중에 hard project-count drift gate를 필요로 한다면 local default에 hard-code하지 말고 해당 CI step에 `EXPECTED_GRADLE_PROJECTS`를 설정한다.
