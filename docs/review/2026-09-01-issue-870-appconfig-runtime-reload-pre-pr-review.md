# Issue #870 AppConfig ConfigData·runtime reload pre-PR 검토

- 검토일: 2026-09-01
- 이슈: [#870](https://github.com/bluetape4k/bluetape4k-workshop/issues/870)
- live 이슈 상태: `OPEN`, milestone `2.0.0`, assignee `debop`
- 브랜치: `feat/issue-870-appconfig-runtime-reload`
- merge 전제: 최신 PR head의 CI와 review를 다시 읽고 새 `승인`을 받은 뒤에만
  merge한다.

## six-lens 체크리스트

| Lens | P0 | P1 | P2 | 결과 |
| --- | ---: | ---: | ---: | --- |
| 코드/API 경계 | 0 | 0 | 0 | `ConfigData` bootstrap과 runtime customizer가 `appconfigdata`만 처리하고 기존 SDK override를 보존한다. |
| 테스트/회귀 | 0 | 0 | 0 | 13개 AppConfig integration test와 기존 모듈 테스트를 모두 실행했다. caller rebinding 차이와 실패/redaction 경계를 assertion으로 고정했다. |
| 보안/신뢰 경계 | 0 | 0 | 0 | synthetic credential, loopback ephemeral fake, exact method/path, regional HTTPS allow-list, prefix isolation을 확인했다. |
| 성능/안정성 | 0 | 0 | 0 | 15초 poll, 8초 in-flight delay, 6초 close upper bound, executor termination, request quiescence와 idempotent close를 확인했다. |
| 문서/사용성 | 0 | 0 | 0 | `README.md`/`README.ko.md` parity, profile opt-in, optional/fail-fast, IAM/비용, `Environment`·`@Value`·`@ConfigurationProperties` 계약을 확인했다. |
| 빌드/운영 | 0 | 0 | 0 | root BOM 단일 authority, versionless SDK alias, workflow comment, stale-check, coverage matrix와 lesson을 갱신했다. |

**현재 게이트: READY FOR COMMIT/PR — P0=0, P1=0, P2=0.**

## 요구사항 추적

- ConfigData URI는 `application#profile#environment` 순서와
  `format=properties|json`, `prefix=appconfig`를 사용한다.
- 기본 `application.yml`은 `enabled=false`이고, `appconfig` profile에서만
  import를 명시한다. 실제 AWS credential/endpoint를 저장소나 기본 smoke에
  넣지 않는다.
- `refresh-interval`은 명시적 opt-in이며 upstream 15초 minimum을 따른다.
  reload 뒤 `Environment#getProperty`만 최신 atomic map을 보며
  `@Value`/`@ConfigurationProperties`는 자동 rebind하지 않는다.
- production timeout은 API 10초/attempt 5초이고, 지연 fake의 500ms timeout은
  test-only다. endpoint guard의 HTTP 예외는 literal `127.0.0.1` fake로
  제한한다.
- empty/malformed last-good, transport 후 새 session, duplicate scheduler,
  atomic property replacement은 upstream named lifecycle/property-source test를
  링크해 추적하며 consumer에서 내부 구현을 복제하지 않는다.

## fresh verification evidence

| 명령 | 결과 |
| --- | --- |
| `./gradlew :aws-settings-boundary:test --tests '*AppConfigDataSpringIntegrationTest' --no-build-cache --no-daemon --console=plain` | 13 tests, `SUCCESS: Executed 13 tests in 40.7s`, `BUILD SUCCESSFUL` |
| `./gradlew :aws-settings-boundary:build --no-build-cache --no-daemon --console=plain` | 기존 9개 + AppConfig 13개 = 22 tests, `BUILD SUCCESSFUL` |
| `./gradlew detekt --no-daemon --console=plain` | `BUILD SUCCESSFUL` |
| `bash scripts/smoke-validate.sh stale-check` | active modules 131, required modules/tenant/leader/AWS AppConfig guards PASS, broken image links 없음 |
| `node scripts/validate-readme-parity.mjs aws/settings-boundary` | `{"failures":0}` |
| `node scripts/validate-readme-language.mjs` | `{"offenders":0,"totalHits":0}` |
| Korean terminology audit | 5 files, findings 0 |
| `git diff --check` | PASS |

실패한 stale Gradle result-store 출력은 이후 `clean` 실행에서 재현되지 않았으며,
최종 targeted/module 실행은 모두 성공했다. test report와 fake 구현에는 raw
token, `Authorization`, payload 또는 synthetic credential을 기록하지 않는다.

## PR 전 확인할 live 항목

1. 변경 내용을 Lore trailers가 있는 한국어 commit으로 기록한다.
2. `feat/issue-870-appconfig-runtime-reload`를 push하고, 제목은
   `[2.0.0] Issue #870 settings-boundary에 AppConfig ConfigData·runtime reload 예제를 추가한다`로
   고정한다.
3. PR body에 `Closes #870`, 위 명령의 fresh evidence, upstream ownership,
   default-off/real AWS opt-in과 `## DoD Status`를 기록한다.
4. PR의 base/head/body, milestone `2.0.0`, labels와 assignee, CI/review 상태를
   live API로 재확인한다.
5. 최신 exact head의 CI와 review thread를 다시 읽고 사용자의 새 `승인`을 받은
   후에만 merge한다. merge 뒤 root `develop` fast-forward, module/stale/parity
   재검증, 현재 feature worktree/branch만 삭제하고 unrelated dirty worktree는
   보존한다.

## 최종 판정

**READY — 구현 및 pre-PR 검토 통과.** 아직 commit/PR/CI/merge는 이 문서 작성
시점의 후속 게이트다. upstream retry 횟수 무제한, 8-worker source cap,
application/profile endpoint 배포 allow-list는 잔여 P3로 추적한다.
