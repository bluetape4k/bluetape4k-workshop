# Issue #861 Sudachi JVM 일본어 tokenizer 비교 설계

## 문제와 목표

`kotlin/text-processing`은 현재 `JapaneseProcessor`를 사용한 Kuromoji
토큰화와 다국어 검색을 보여 준다. 중앙 dependencies catalog에 alias가
기록되어 있지만 published BOM constraint에는 없는 외부
`com.worksap.nlp:sudachi:0.8.0`을 같은 workshop에 연결하면,
학습자가 동일 입력에서 Kuromoji와 Sudachi JVM의 token surface, POS, 분할
모드 차이를 직접 관찰할 수 있다.

이 설계의 목표는 기존 Kuromoji 예제를 유지하면서 Sudachi JVM을 별도
비교 예제로 추가하는 것이다. 결과는 정확도나 latency 순위를 정하지 않고,
backend 선택 또는 migration 전에 확인해야 할 관찰값을 같은 report shape으로
제공한다.

## 현재 근거와 기준 정보

| 구분 | 확인 결과 |
| --- | --- |
| 대상 이슈 | [bluetape4k-workshop #861](https://github.com/bluetape4k/bluetape4k-workshop/issues/861), live 상태 `OPEN`, milestone `2.0.0` |
| 현재 checkout | `develop`과 `origin/develop`의 기준 commit은 `19c542caf66b342220a4b2de8a6557f93ac50ff5`이다. |
| 현재 BOM | `gradle/libs.versions.toml`의 `bluetape4k-dependencies-version = "2.0.0"`; workshop 소비자 예제는 정식 배포된 stable BOM을 기준으로 하며 snapshot 개발선을 사용하지 않는다. |
| 중앙 catalog/BOM 상태 | 중앙 catalog에는 `sudachi = "0.8.0"` alias가 있으나 현재 published `bluetape4k-dependencies` BOM의 `api(...)` constraint에는 포함되지 않음. workshop은 외부 artifact를 local catalog `0.8.0`으로 고정하고 Bluetape 모듈만 root BOM으로 관리 |
| 기존 예제 | `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/MultilingualSearchIndex.kt`의 `JapaneseProcessor` 경로와 관련 fixture |
| upstream 설계 | [`bluetape4k-text` Issue #284](https://github.com/bluetape4k/bluetape4k-text/issues/284), [`PR #286`](https://github.com/bluetape4k/bluetape4k-text/pull/286)의 dictionary-backed Sudachi 예제 |
| 공식 사전 | [`SudachiDict v20260428`](https://github.com/WorksApplications/SudachiDict/releases/tag/v20260428)의 core archive |

공식 core archive의 고정값은 URL
`https://github.com/WorksApplications/SudachiDict/releases/download/v20260428/sudachi-dictionary-20260428-core.zip`,
archive 크기 `72,238,136` bytes, SHA-256
`40c8ffc095283f07aa06cae922e7b8147bf2919ec8830567b0b3f7a7efa3239f`,
추출할 `system_core.dic` 크기 `217,374,303` bytes, SHA-256
`6c1d5adc8a2389875713056e7b39bbcd0073d6122ffd509866e1d3a196f8608e`이다.
이 값은 현재 릴리스에서 archive를 직접 추출해 다시 확인했으며, binary
자체는 저장소에 넣지 않는다.

## 선택한 접근

### A안: 명시적 사전 경계를 가진 optional integration (채택)

새 `JapaneseBackendComparisonExamples`는 항상 기존 Kuromoji 결과를 만들고,
`bluetape4k.sudachi.system-dictionary` system property가
`user.dir/build/sudachi-dictionary/v20260428/system_core.dic` 정규화 경로와
일치하는, `NOFOLLOW_LINKS` 기준 regular file이며 고정 extracted SHA-256을
만족할 때만 Sudachi를 실행한다. property가 없거나 경로·symlink·digest가
유효하지 않으면 예외로 기본 test를 깨뜨리지 않고 candidate observation을
`BackendExecution.UNAVAILABLE`과 절대 경로 없는 실행 안내 메시지로
반환한다.

기본 `test` task는 dictionary archive 다운로드·추출 없이 실행할 수 있는
단위 경로를 검증한다. Gradle dependency cache가 이미 준비된 환경에서는
`--offline`로도 실행할 수 있다. 별도 `sudachiTest` task는
`prepareSudachiDictionary`에 의존하고 동일 test class의
`sudachi-integration` tag만 실행하며, 준비된 `system_core.dic` 경로를
property로 주입한다. 따라서 두 실행 경로가 명령과 비용에서 명확히
분리된다.

### 제외한 대안

- **B안: 모든 `test`가 사전 준비에 의존** — 실제 실행은 단순하지만 72 MB
  archive 다운로드와 217 MB 추출을 오프라인 기본 경로에 강제하므로
  deterministic workshop test 계약을 깨뜨린다.
- **C안: 축소 fixture 또는 mock dictionary** — 빠르지만 실제
  `DictionaryFactory`와 공식 Sudachi dictionary 실행을 증명하지 못한다.

## 구성 요소와 계약

### 비교 report

새 파일은
`kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/tokenizer/JapaneseBackendComparisonExamples.kt`
로 둔다. 기존 `search` 패키지의 색인 책임과 분리하고, workshop 예제이므로
외부 공개 API가 아닌 `internal` 값과 함수를 사용한다.

- `runJapaneseBackendComparison(text)`는 승인된 corpus에 대해서만 실행한다.
  기본 입력은 `選挙管理委員会`이며, 허용 corpus는
  `選挙管理委員会`, `東京都へ行く`, `外国人参政権` 세 문장으로 고정한다.
- `JapaneseBackendComparisonReport`는 `input`, `current`(Kuromoji),
  `candidate`(Sudachi)를 가진다.
- 각 backend observation은 backend/dictionary/license/runtime footprint,
  Gradle dependency, dictionary version·archive SHA-256·extracted SHA-256·size,
  `BackendExecution`, POS mapping 상태, token 목록, `statusMessage: String?`를
  보존한다. `statusMessage`는 candidate가 `UNAVAILABLE`일 때 원인과
  `prepareSudachiDictionary`/property 안내를 담고, live 실행에서는 `null`이다.
- Kuromoji는 기존 `JapaneseProcessor.tokenize` 결과를 사용하고,
  Sudachi는 `DictionaryFactory`로 연 dictionary의
  `Tokenizer.SplitMode.A/B/C` 결과를 수집한다.
- 두 API의 POS 체계가 다르므로 각 token의 첫 번째 broad POS field만
  `partOfSpeech`로 연결한다. 이는 의미가 같은 POS라고 주장하는 매핑이
  아니라 observation을 위한 최소 공통 필드다.
- Sudachi A/B/C에는 `surface` 목록을 기록하고, C mode token에는 위 POS
  field를 기록한다. `renderJapaneseBackendComparison`은 이 값과
  migration 전에 surface/POS 불일치를 검토해야 한다는 경계를 출력한다.
- 여러 승인 corpus를 한 번에 검증하는 내부 runner는 하나의 열린 Sudachi
  dictionary/tokenizer session을 재사용하고, session 종료 시 dictionary를
  닫는다. 단일 입력 helper도 같은 observation 계약을 사용한다.
- candidate가 `UNAVAILABLE`이면 metadata와 `statusMessage` 안내는 남기되
  token 및 split 목록은 비워 둔다. 준비된 경로에서만 이 목록이 실제
  dictionary 결과로 채워진다.

### Dictionary preparation

`kotlin/text-processing/build.gradle.kts`에 `prepareSudachiDictionary`를
추가한다.

1. 출력 디렉터리를
   `build/sudachi-dictionary/v20260428`로 고정한다. 첫 실행에서는 없는
   ancestor와 leaf 디렉터리를 만들 수 있지만, 이미 존재하는 각 경로가
   symlink이면 즉시 거부하고 생성 후에도 전체 트리를 다시 확인한다.
2. archive를 HTTPS redirect allowlist와 bounded `.part` 스트림으로
   다운로드하고 archive 크기와 SHA-256을 검증한다. 검증 전에는 최종
   archive로 승격하지 않는다.
3. ZIP 안에 `LEGAL`, `LICENSE-2.0.txt`,
   `sudachi-dictionary-20260428/system_core.dic`가 있는지 확인한다.
4. 승인된 단일 entry만 `system_core.dic.part`로 bounded 추출하고 entry와
   결과 파일의 크기·SHA-256을 다시 검증한 뒤 같은 파일시스템 안에서
   `system_core.dic`로 원자적으로 승격한다. 임의 ZIP 경로를 추출하지
   않는다.
5. 출력 디렉터리, `.part`, archive, dictionary 경로의 symlink를
   `NOFOLLOW_LINKS` 기준으로 거부한다. 준비된 최종 dictionary test input은
   `build/sudachi-dictionary/v20260428/system_core.dic` 하나로 제한하고,
   검증된 archive는 별도의 build-only cache output으로만 보존한다.
6. 실패하면 task를 실패시키고 partial output을 정리해 유효하지 않은
   dictionary를 테스트에 전달하지 않는다. 다음 실행은 검증에 실패한
   archive를 재다운로드할 수 있어야 한다.

archive는 동일한 build 디렉터리에서 offline 재실행에 사용할 수 있는
build-only cache output으로 보존하고, `system_core.dic`는 `sudachiTest`가
읽는 build-only test input으로 취급한다. 두 파일 모두 Git에 추가하지 않으며,
tracked source에는 dictionary binary를 포함하지 않는다.

다운로드 helper는 connect timeout 30초와 read timeout 120초를 설정하고,
각 응답이 2xx인지 확인한다. redirect는 최대 5회까지 `Location`을 요구하며
허용된 HTTPS host로만 이동하고, 중간 connection을 포함한 모든
`HttpsURLConnection`을 `disconnect()`한다. timeout·상태 코드·redirect 제한을
넘으면 bounded byte 검사 전에 task를 실패시켜 네트워크 hang을 허용하지 않는다.

실행 task는 다음과 같이 분리한다.

```text
./gradlew :kotlin-text-processing:test
./gradlew :kotlin-text-processing:sudachiTest
```

첫 명령은 dictionary archive 다운로드와 추출에 의존하지 않는다. dependency
cache가 준비된 뒤에는 `./gradlew --offline
:kotlin-text-processing:test`로도 확인한다. 두 번째 명령만
`prepareSudachiDictionary`를 먼저 실행하고
`bluetape4k.sudachi.system-dictionary`를 주입한다.

### Dependency와 Gradle 경계

`gradle/libs.versions.toml`에는 외부 artifact인 `com.worksap.nlp:sudachi`의
`0.8.0` version과 `libs.sudachi` alias를 추가하고, module의
`build.gradle.kts`에는 `implementation(libs.sudachi)`를 선언한다. 이는
`io.github.bluetape4k.*` 모듈의 version을 pinning하는 예외가 아니며, root의
`platform(libs.bluetape4k.dependencies)`는 Bluetape 모듈 버전을 계속
결정한다. 별도 text BOM import는 하지 않는다. 중앙 catalog/BOM에 Sudachi
constraint를 추가하는 작업은 dependencies repository의 별도 변경으로
분리한다.

기본 `test`는 `sudachi-integration` tag를 제외하고, `sudachiTest`는 해당
tag만 포함한다. 이는 현재 graph/operations 예제의 `integrationTest` 패턴과
같은 Gradle test-classpath 재사용 경계를 따른다.

## 실패 모드와 복구

| 상황 | 관찰 가능한 동작 | 복구/재실행 |
| --- | --- | --- |
| system property 없음 | Kuromoji report는 반환되고 candidate는 `UNAVAILABLE`과 `prepareSudachiDictionary` 안내를 가진다 | 기본 `test`를 계속 실행하거나 preparation task 후 `sudachiTest` 재실행 |
| dictionary 경로가 준비된 build 출력과 일치하지 않거나 regular file이 아님 | Sudachi 실행을 시작하지 않고 절대 경로를 노출하지 않는 명확한 오류를 반환한다 | 출력 경로를 확인하고 task를 다시 실행 |
| archive 크기/SHA-256 불일치 | preparation task가 추출 전에 실패한다 | `.part` 다운로드를 새로 받아 검증 |
| ZIP license/entry/크기 또는 추출 SHA-256 불일치 | preparation task가 실패하고 invalid dictionary를 사용하지 않는다 | 공식 release URL과 고정값을 다시 확인 |
| output 경로가 symlink이거나 bounded stream이 예상 크기를 초과 | preparation task가 즉시 실패하고 partial 파일을 정리한다 | symlink/partial을 제거한 뒤 공식 release에서 재실행 |
| Sudachi API의 POS/surface가 Kuromoji와 다름 | report가 차이를 그대로 기록하며 우위 주장을 하지 않는다 | migration 전에 corpus·split mode·POS 차이를 별도로 검토 |
| 네트워크가 차단된 오프라인 환경 | dependency cache가 준비되어 있으면 `test`는 계속 결정적으로 통과하고 `sudachiTest`만 preparation 실패를 보고한다 | 사전을 준비할 수 있는 환경에서 `sudachiTest` 실행 |

## 호환성과 migration 경계

- 기존 `MultilingualSearchIndex`, `JapaneseProcessor` API와 fixture는 변경하지
  않는다. Sudachi 예제는 새 `tokenizer` package의 observation-only 내부
  계약으로 한정한다.
- Kuromoji를 제거하거나 tokenizer를 중립 API로 통합하지 않는다. 실제
  서비스 migration은 애플리케이션이 사용하는 dictionary, POS 체계,
  split mode, 검색 fixture를 별도로 재검토해야 한다.
- dictionary binary는 `build/` 아래에만 저장하므로 Git diff와 source
  archive에는 포함되지 않는다. version/archive hash/extracted hash/size를
  변경할 때는 공식 release 확인, task 검증, README 명령을 함께 갱신한다.
- workshop catalog에 외부 `sudachi` alias가 없는 catalog ref라면 Gradle
  resolution이 실패해야 하며, 승인되지 않은 임의 version을 pinning하는
  fallback은 두지 않는다.

## 검증 전략

TDD 순서로 다음 behavior를 잠근다.

1. 사전 property가 없는 기본 경로가 Kuromoji 결과와
   `UNAVAILABLE` candidate, 안내 메시지를 결정적으로 반환하는지 검증한다.
2. 준비된 property가 있을 때 `DictionaryFactory`를 실제로 열고 세 split
   mode surface와 C-mode POS를 반환하는 integration test를 추가한다.
3. 세 승인 corpus 각각에서 기존 Kuromoji fixture와 Sudachi 관찰값을
   검증하고, 허용되지 않은 입력은 `IllegalArgumentException`으로 거부한다.
4. report rendering에 backend, license, dependency, hash, migration 경계가
   포함되는지 검증한다.
5. 기존 `kotlin/text-processing` 전체 test를 다시 실행해 회귀를 확인한다.

검증 명령은 순서대로 다음과 같다.

```text
./gradlew :kotlin-text-processing:test --no-build-cache --no-daemon
./gradlew :kotlin-text-processing:sudachiTest --no-build-cache --no-daemon
./gradlew :kotlin-text-processing:test --configuration-cache --configuration-cache-problems=fail --no-daemon
./gradlew :kotlin-text-processing:build --no-daemon
git diff --check
```

`sudachiTest`는 외부 다운로드와 217 MB 추출을 포함하므로 다른
Testcontainers/native/real-IO 검사와 병렬 실행하지 않는다. 정확도·latency
benchmark는 이 이슈의 범위가 아니며 별도 근거가 없으면 추가하지 않는다.

## 수용 기준과 DoD

- workshop catalog에 명시한 외부 `com.worksap.nlp:sudachi:0.8.0` alias를
  사용하고, Bluetape 모듈 version은 중앙 BOM으로 계속 관리한다.
- 기존 Kuromoji API와 fixture가 유지되고 전체 text-processing test가 통과한다.
- 기본/offline `test`는 dictionary archive 다운로드·추출 없이 결정적으로
  동작하고,
  `sudachiTest`는 preparation task 후 두 backend를 실제 실행한다.
- 승인된 세 corpus의 token surface, A/B/C surface, C-mode broad POS가
  observation report와 test에 남는다.
- archive URL·크기·SHA-256·ZIP license/entry·extracted size·extracted
  SHA-256을 검증하며, archive와 dictionary는 build-only output/input으로만
  보존하고 binary는 commit하지 않는다.
- `README.md`와 `README.ko.md`가 실행 명령, 준비 방법, license,
  migration 검토 경계를 같은 의미로 설명한다.
- 해당 Gradle 검증과 `git diff --check`가 통과한다.

새 module을 추가하지 않으므로 `settings.gradle.kts`, module validation
matrix, Kover aggregation 변경은 범위에 없다. 다만 기존
`kotlin-text-processing` source/test 변경을 hosted 기본 회귀에서 누락하지
않도록 `.github/workflows/Examples.yml`의 path filter·smoke test·artifact와
`scripts/smoke-validate.sh all-smoke`에는 기본 `:kotlin-text-processing:test`를
연결한다. 217 MB dictionary가 필요한 `sudachiTest`는 비용과 외부 archive
경계를 이유로 local/manual-only로 둔다. 이는 기존 module의 source/test/docs와
필요한 CI wiring만 수정한다는 구체적인 scope 근거로 기록한다.
