# #890 VersionedDictionary 런타임 reload·rollback 구현 계획

## 순서

1. `origin/develop` exact head 위에 격리 worktree를 만들고 Issue #890, GNO,
   upstream 1.0.0 API, BOM 2.0.0과 두 대상 모듈 baseline을 확인한다.
2. application-owned immutable store, whole-index swap, moderation request 단일
   snapshot, bounded history, safe metadata 경계를 설계와 review로 고정한다.
3. TDD red 단계에서 search와 moderation의 reload/rollback, stale/failure/history,
   concurrent reader, compatibility 테스트를 먼저 추가한다.
4. `VersionedMultilingualSearchIndex`와 `VersionedModerationDictionary`를 구현하고
   `TextModerationService` 및 Spring configuration에 `@Autowired` primary와 기존
   automaton secondary constructor 호환 경계로 연결한다. 두 module
   `build.gradle.kts`에는 versionless `bluetape4k-text-core` 직접 의존성을 추가한다.
5. root/module EN·KO README, coverage matrix, stale guard, ecosystem manifest,
   lesson을 갱신한다. 기존 Examples workflow가 두 모듈을 이미 포함하므로 중복
   matrix entry는 추가하지 않고 현재 경로 coverage를 검증한다.
6. targeted clean tests, detekt, README language/parity, stale-check, actionlint,
   ecosystem unit/scope 검사, dependency insight와 diff-check를 실행한다.
7. implementation six-lens review에서 P0/P1/P2를 닫고 Lore commit/push 후 한국어
   PR을 생성한다. exact-head hosted CI와 metadata를 검증한 뒤 다음 이슈로 이동한다.

## 파일 소유 범위

- `kotlin/text-processing/src/{main,test}/**`
- `spring-boot/text-moderation-api/src/{main,test}/**`
- 두 module `build.gradle.kts`
- 두 module `README.md`, `README.ko.md`
- root `README.md`, `README.ko.md`, `docs/coverage-matrix.md`,
  `docs/ecosystem-reuse-train.json`, `scripts/smoke-validate.sh`
- `docs/superpowers/{specs,plans}`, `docs/review`, `docs/lessons`

## 검증 명령

```bash
./gradlew :kotlin-text-processing:cleanTest :kotlin-text-processing:test \
  :spring-boot-text-moderation-api:cleanTest :spring-boot-text-moderation-api:test \
  --no-build-cache --rerun-tasks --max-workers=1
./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs kotlin/text-processing spring-boot/text-moderation-api
bash scripts/smoke-validate.sh stale-check
python3 .github/scripts/test_check_ecosystem_reuse.py -v
./gradlew :kotlin-text-processing:dependencyInsight \
  --dependency io.github.bluetape4k.text:tokenizer-core \
  --configuration testRuntimeClasspath
./gradlew :spring-boot-text-moderation-api:dependencyInsight \
  --dependency io.github.bluetape4k.text:tokenizer-core \
  --configuration testRuntimeClasspath
actionlint .github/workflows/Examples.yml
git diff --check
```

## 중단/복구

- BOM 2.0.0에서 `VersionedDictionary`가 resolve되지 않으면 대체 store를 만들지
  않고 dependency evidence와 upstream 상태를 확인한다.
- concurrent test가 mixed revision을 재현하면 snapshot 캡처 지점을 요청 시작으로
  이동하고 parse/mask 또는 index/search 사이 재조회가 없도록 수정한다.
- slow-loader barrier에서 current reader가 막히면 wrapper가 upstream loader overload를
  호출한 것이므로 candidate build와 `DictionarySnapshot` publish를 다시 분리한다.
- ecosystem scope가 문서/guard 경로를 누락하면 manifest와 stale rule을 같은
  branch에서 보완하고 PR 생성 전에 다시 검증한다.
