# Issue #915 안정 BOM 소비자 전환 구현 검토

- 검토일: 2026-09-02
- 저장소: `bluetape4k-workshop`
- 이슈: [#915](https://github.com/bluetape4k/bluetape4k-workshop/issues/915)
- 브랜치: `chore/issue-915-dependencies-2.0.0`
- 기준 base: `develop`

## 검토 범위

`bluetape4k-dependencies` 정식 `2.0.0`을 workshop consumer의 단일 Bluetape
version authority로 승격하는 유지보수 변경을 검토했다. catalog alias, root
dependency-management/BOM import, snapshot repository 경계, 현재 독자에게
노출되는 README·AGENTS·KDoc, module registration과 validation contract를
확인했다. 역사적 spec·plan·review·lesson 문서의 당시 버전 표기는 범위에서
보존한다.

## 독립 관점 결과

| 관점 | 판정 | 근거 |
| --- | --- | --- |
| 의존성/API 경계 | PASS | `gradle/libs.versions.toml`은 `io.github.bluetape4k:bluetape4k-dependencies:2.0.0` alias만 고정하고 child module alias는 versionless로 유지한다. |
| 저장소·빌드 | PASS | 안정 릴리스에서 불필요한 `SonatypeSnapshots` repository와 snapshot-only cache comment를 제거했다. root `dependencyManagement`와 `implementation(platform(rootLibs.bluetape4k.dependencies))` 경계는 유지한다. |
| 문서·사용성 | PASS | 변경한 README 쌍, root README, AGENTS, Kotlin KDoc의 현재 버전 설명을 2.0.0 해석 결과에 맞추고 historical evidence는 수정하지 않았다. |
| 회귀·안정성 | PASS | 대표 AWS·graph·image·leader test와 7개 모듈 compile이 통과했다. 첫 grouped shutdown assertion 일시 실패는 단독 2회와 grouped 재실행에서 재현되지 않았고, 범위 밖 코드는 수정하지 않았다. |
| 운영·CI | WATCH | local ecosystem checker의 기존 manifest receipt 오류를 확인했고, 이 PR 변경 경로를 전용 follow-up scope와 fresh coordinator receipt로 명시한다. hosted gate는 exact head에서 재확인한다. |
| 성능·보안 | N/A | API/production behavior, credential, runtime algorithm은 변경하지 않는다. 전체 container-backed 성능 주장은 하지 않는다. |

P0=0, P1=0이다. 전체 README parity 도구의 미변경 optimization 3개 실패와
기존 inventory receipt 오류는 이번 diff에서 유발하지 않은 baseline debt로
분리했다.

## 검증 증거

| 명령 | 결과 |
| --- | --- |
| `:aws-storage-abstraction:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-dependencies --configuration testRuntimeClasspath` | `2.0.0` 선택, `BUILD SUCCESSFUL` |
| 대표 7개 모듈 `compileKotlin --rerun-tasks` | `BUILD SUCCESSFUL in 22s` |
| AWS·graph·image·leader 대표 test group 재실행 | `BUILD SUCCESSFUL in 43s` |
| isolated AppConfig shutdown test 2회 | 모두 `BUILD SUCCESSFUL` |
| `./gradlew detekt --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 108개 actionable task |
| 변경 README 10개 경로 `validate-readme-parity` | 모두 PASS |
| non-historical stale version audit | `2.0.0-SNAPSHOT`·BOM `1.4.0`·`1.7.0` 0건 |
| `python3 .github/scripts/check-ecosystem-reuse.py` | 기존 manifest receipt 오류; checker/inventory/manifest scope 변경 전 baseline |
| `git diff --check` | PASS |

## 결론

이 변경은 consumer BOM 승격과 현재 문서·저장소 경계 정렬에 한정되며 개별
Bluetape module pin이나 별도 BOM을 도입하지 않는다. 전용 ecosystem scope를
추가한 뒤 exact `--pr-scope` checker와 hosted CI를 통과하면 PR 병합 검토가
가능하다. merge는 최신 exact head에 대한 별도 사용자 승인을 요구한다.
