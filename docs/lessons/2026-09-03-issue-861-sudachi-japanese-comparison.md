# Issue #861 Sudachi 일본어 tokenizer 비교 예제 교훈

## 배경

기존 `kotlin/text-processing`은 `JapaneseProcessor`를 사용하는 Kuromoji 기반
검색 예제만 제공했다. Issue #861의 목표는 같은 consumer 모듈에서 공식
Sudachi JVM tokenizer를 실제로 비교하고, dictionary 준비 비용과 실행 경계를
기본 회귀 테스트와 분리하는 것이었다. 비교 corpus는
`選挙管理委員会`, `東京都へ行く`, `外国人参政権` 세 문장으로 고정했다.

upstream `bluetape4k-text`의 tokenizer-safety example에서
`DictionaryFactory`/`PathAnchor`/`Tokenizer.SplitMode` 사용법을 확인했지만,
workshop에는 consumer 관점의 metadata와 안전한 dictionary preparation을
별도로 두었다.

## 결정

- 외부 artifact `com.worksap.nlp:sudachi:0.8.0`만 version catalog에 명시하고,
  Bluetape 모듈은 기존 `bluetape4k-dependencies` BOM 경계를 유지했다.
- `runJapaneseBackendComparisons()`는 세 입력을 하나의 dictionary/tokenizer
  session에서 처리한다. Kuromoji와 Sudachi의 surface/broad POS observation을
  같은 report shape으로 기록하고, Sudachi는 A/B/C split surface를 함께 남긴다.
  이는 정확도나 latency 우위가 아닌 마이그레이션 비교 자료다.
- 기본 `test`는 archive를 다운로드하지 않고 property가 없거나 준비된 출력과
  일치하지 않으면 Sudachi candidate를 `UNAVAILABLE`로 반환한다.
  `sudachiTest`만 `prepareSudachiDictionary`를 선행해 실제 dictionary-backed
  통합 테스트를 실행한다.
- 공식 `SudachiDict v20260428 core` archive는 URL, 72,238,136-byte 크기,
  archive SHA-256
  `40c8ffc095283f07aa06cae922e7b8147bf2919ec8830567b0b3f7a7efa3239f`, ZIP의
  `LEGAL`/`LICENSE-2.0.txt`/고정 dictionary entry, 추출된
  `system_core.dic` 217,374,303-byte 크기와 SHA-256
  `6c1d5adc8a2389875713056e7b39bbcd0073d6122ffd509866e1d3a196f8608e`를
  검증한다. 산출물은 모듈 `build/sudachi-dictionary/v20260428` 아래에만
  두고 commit하지 않는다.
- preparation은 HTTPS host allowlist, 직접 redirect, timeout, bounded
  streaming, `CREATE_NEW` `.part`, atomic move, symlink 거부, 실패 시 partial
  cleanup을 사용한다. configuration cache가 이 custom task action을
  직렬화할 수 있도록 task class와 up-to-date spec을 script closure 밖에
  두었다.

## 검증

| 검증 | 결과 |
| --- | --- |
| `./gradlew :kotlin-text-processing:test --tests '*JapaneseBackendComparisonExamplesTest'` | 5개 PASS: 기본 UNAVAILABLE, corpus 순서, 입력 경계, malformed property path 비노출, renderer metadata |
| `./gradlew :kotlin-text-processing:test --no-build-cache --no-daemon --console=plain` | 전체 58개 PASS; dictionary download 없음 |
| `./gradlew :kotlin-text-processing:prepareSudachiDictionary --configuration-cache --configuration-cache-problems=fail` | 공식 archive 다운로드·추출 및 `BUILD SUCCESSFUL` |
| `./gradlew :kotlin-text-processing:prepareSudachiDictionary --offline --configuration-cache --configuration-cache-problems=fail` | 검증된 출력에서 `UP-TO-DATE`, offline 재실행 성공 |
| 같은 크기 `system_core.dic` 1-byte 변조 후 위 offline preparation | task 재실행 및 archive에서 원본 digest 복구 |
| archive 검증값 | 72,238,136 bytes, SHA-256 `40c8ffc095283f07aa06cae922e7b8147bf2919ec8830567b0b3f7a7efa3239f` |
| extracted dictionary 검증값 | 217,374,303 bytes, SHA-256 `6c1d5adc8a2389875713056e7b39bbcd0073d6122ffd509866e1d3a196f8608e` |
| `./gradlew :kotlin-text-processing:sudachiTest --offline --no-daemon --console=plain` | 2개 PASS: 세 corpus observation과 공식 A/B/C split fixture |
| `./gradlew :kotlin-text-processing:build --no-daemon --console=plain` | `BUILD SUCCESSFUL` |
| `./gradlew :kotlin-text-processing:detekt --no-daemon --console=plain` | N/A: `:kotlin-text-processing`에 `detekt` task가 없어 Gradle이 명시적으로 거부함; `tasks --all`로도 미등록 확인 |
| `./gradlew detekt --no-daemon --console=plain` | 저장소 집계 detekt `BUILD SUCCESSFUL` |
| `dependencyInsight --dependency com.worksap.nlp:sudachi --configuration runtimeClasspath` | `com.worksap.nlp:sudachi:0.8.0` 선택 확인 |
| `test --dry-run` / `sudachiTest --dry-run` | 기본 test에는 preparation 없음; `sudachiTest`는 preparation 선행 확인 |
| `git diff --check`, `bash -n scripts/smoke-validate.sh`, workflow YAML parse | 모두 PASS; dictionary binary 미추적 |
| README parity 및 한국어 용어 audit | `validate-readme-parity.mjs` failures=0, `audit-korean-terms.mjs` findings=0 |

## Miss와 다음 guard

network가 차단되고 Maven cache도 없는 환경에서는 외부 Sudachi artifact 해석이
별도 전제다. 모듈에는 `detekt` task가 등록되어 있지 않아 해당 정적 분석은
N/A로 남겼으며, build/compile과 targeted runtime 검증으로 대체했다. dictionary archive 자체는 기본 CI smoke에 넣지 않았고,
`sudachiTest`는 217 MB extraction 비용이 있는 local/manual-only 경계로
유지한다. 이후 dictionary URL, archive hash/size, extracted hash/size, ZIP
entry 또는 fixture를 바꿀 때는 설계·구현 task·두 README·integration test·이
lesson을 함께 갱신하고, `build/` binary를 저장소에 추가하지 않는다.
