# Issue #861 Sudachi 비교 예제 계획·구현 검토

## 검토 범위와 판정

- 대상: 승인된 설계 `docs/superpowers/specs/2026-09-03-issue-861-sudachi-japanese-comparison-design.md`와 구현 계획 `docs/superpowers/plans/2026-09-03-issue-861-sudachi-japanese-comparison-plan.md`
- 계획 hash: `a2b459e5ad7e57c3dd1ce1d4a5a9c89ec09ff0acb38b90b1b8c374f3c962f0c9`
- 설계 hash: `9d48c72cb48d91abfdcb989e1934b44284c5b0c66a386317ad21d596f45d7ccb`
- 구현 commit: `4ccc7f12` (Sudachi 비교 API·Gradle preparation·단위/통합 테스트)
- 검토일: 2026-09-03
- 최종 판정: **P0=0, P1=0. 구현·로컬 검증 완료; PR delivery gate 대기**

초기 독립 검토에서 발견된 P1은 구현 전에 계획과 설계에 반영했다. 남은
P2/P3는 성능 주장 없이 dictionary 비용과 local/manual 경계를 문서화하거나,
운영 검증 명령으로 고정했다. 승인 후 구현은 계획된 파일 범위에서 완료했고,
아래 로컬 검증 결과를 fresh evidence로 반영했다. hosted CI와 exact-head
merge 승인은 별도 delivery gate다.

## 여섯 관점 검토

| 관점 | 근거와 초기 finding | 현재 처분 | 잔여 우선순위 |
| --- | --- | --- | --- |
| 성능 | `issue861-plan-performance`가 dictionary session을 corpus마다 열 수 있는 계획과 C mode 재토큰화를 지적했다. | `runJapaneseBackendComparisons()`가 한 dictionary/tokenizer session을 세 corpus에 재사용하고, C morpheme 목록 하나로 surface/POS를 함께 만든다. 정확도·latency benchmark 주장은 추가하지 않는다. | P0/P1 없음. 준비 비용은 72 MB archive/217 MB extracted dictionary와 local/manual 경계로 문서화. 통합 2개 PASS. |
| 안정성 | `issue861-plan-stability`가 `sudachiTest` 입력 fingerprint와 final dictionary 직접 쓰기 위험을 지적했다. | `inputs.file`/relative path sensitivity, `.part`와 `CREATE_NEW`, bounded copy, size+SHA-256, atomic move, 실패 cleanup, stale partial 검사, test-mutex, zero-test guard를 구현했다. 기본 test는 preparation과 분리한다. | P0/P1 없음. 전체 58개 및 offline UP-TO-DATE/복구 검증 PASS. |
| 보안 | `issue861-plan-security`가 추출물 digest 부재와 symlink/path traversal 위험을 지적했다. | archive/extracted SHA-256과 크기를 모두 고정하고, 승인 entry 하나만 추출하며, `NOFOLLOW_LINKS`, parent/tree 재검사, symlink 거부, HTTPS host allowlist를 사용한다. 예외·report에는 절대 경로와 dictionary 내용이 없다. | P0/P1 없음. timeout/redirect 횟수·Location·2xx 검증과 malformed property 비노출 테스트 PASS. |
| 운영 | `issue861_plan_ops`가 workflow/smoke 누락, zero-test, portable size 검증, connection lifecycle 및 clean directory semantics를 검토했다. 후속 검토에서 dependency ownership, network timeout, directory 생성, archive output, session reuse, configuration-cache를 추가로 지적했다. | `Examples.yml` path/smoke/artifact와 `scripts/smoke-validate.sh`를 반영했다. 외부 Sudachi는 workshop catalog `0.8.0`으로 고정하고 Bluetape 모듈만 root BOM으로 관리한다. 30초 connect/120초 read timeout, 최대 5 redirect와 모든 connection disconnect, 없는 ancestor 허용·기존 symlink 거부·생성 후 재검사를 구현했다. archive는 build-only cache output, dictionary는 build-only test input으로 구분하고 configuration-cache problems fail/reuse를 검증했다. integration fixture는 `PER_CLASS`/`BeforeAll` 한 번의 batch 호출을 사용한다. | P0/P1 없음. 217 MB archive 경계와 hosted 미실행을 유지. |
| API/개발자 | `issue861-plan-api`가 BOM constraint와 local alias의 경계, `HttpsURLConnection` lifecycle, extracted digest/report field, malformed property path, batch API, POS mapping 의미, metadata를 검토했다. | `implementation(libs.sudachi)` 외부 dependency만 local pin하고 Bluetape API/version은 BOM에 맡긴다. report에 archive/extracted digest를 모두 보존하고 malformed path만 `UNAVAILABLE`로 변환하며, valid path 이후 tokenizer 예외는 숨기지 않는다. batch/singular helper, `PosMappingStatus` 의미, `libs.sudachi` coordinate를 구현했다. | P0/P1 없음. targeted 5개와 Sudachi API 통합 2개 PASS. |
| 사용자/호출자 | main lane이 README 사용성, 모듈 기준 build 경로, offline 기본 명령, 오류 안내, misuse 경계를 재검토했다. | batch helper를 기본 사용으로 안내하고, 모듈 기준 경로를 명시하며, property 부재 시 `UNAVAILABLE`과 preparation 명령을 반환한다. English/Korean README의 동일 섹션·명령·hash·license·migration 주장을 반영했다. 정확도·latency 우위는 주장하지 않는다. | P0/P1 없음. README parity failures=0, 한국어 용어 findings=0. |

## 구현 및 로컬 검증 결과

| 영역 | fresh evidence |
| --- | --- |
| source/API | `JapaneseBackendComparisonExamples.kt`의 batch/single helper, Kuromoji·Sudachi observation, A/B/C fixture, unavailable 경계를 구현하고 compile/build 성공 |
| Gradle preparation | `prepareSudachiDictionary`가 공식 archive/license/entry/크기·SHA-256을 검증하고 atomic output을 생성; configuration-cache fail/reuse 성공 |
| tests | 기본 targeted 5개 PASS, 모듈 전체 58개 PASS, dictionary-backed `sudachiTest` 2개 PASS |
| recovery/security | offline UP-TO-DATE, 같은 크기 1-byte 변조 후 digest 불일치 재실행 및 원본 복구, partial/symlink·redirect/timeout 경계 코드 검토 |
| docs/CI | English/Korean README parity 0, 한국어 용어 audit 0, workflow YAML parse·shell syntax·diff check PASS, dictionary binary 미추적 |
| static analysis | `:kotlin-text-processing:detekt`는 project에 등록되지 않은 task라 N/A; `tasks --all`에서 미등록을 확인하고 build/compile 검증으로 대체 |

## 구현 완료 및 delivery 조건

1. 구현·로컬 검증은 완료했고, 새 P0/P1은 없다.
2. PR을 생성한 뒤 hosted CI, PR body DoD, live review/mergeability를 exact
   head에서 검증한다.
3. 사용자의 fresh `승인` 없이는 merge하지 않는다. merge 후에는 root
   `develop` sync와 승인된 feature worktree 정리를 수행한다.
