# Issue #287 - 그래프 IO 파이프라인 워크샵 계획

**날짜**: 2026-06-28
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/287
**사양**: `docs/superpowers/specs/2026-06-28-issue-287-graph-io-pipeline-design.md`
**모듈**: `graph/io-pipeline` -> `:graph-io-pipeline`
**상태**: 3-R단계 검토 초안

---

## 1. 인코딩된 결정

- `settings.gradle.kts`은(는) 이미 `graph/*`를 자동 검색합니다. 설정 편집이 필요하지 않습니다.
- 모듈은 연기로부터 안전합니다: TinkerGraph 및 로컬 파일만 가능하며 Testcontainers은 불가능합니다.
- 저장소 종속성 권한은 `bluetape4k-dependencies`입니다. 개별 그래프 BOM은 추가되지 않습니다.
- 구현이 import `bluetape4k-graph-okio`되지 않는 한 이 문제에 대해서는 Okio가 documentation/reference-only입니다. 현재 계획에서는 이를 가져오지 않습니다.
- CSV 설비는 `_graphIoExternalId`을 의도적으로 보존하므로 학습자가 외부 ID가 백엔드 ID에 매핑되는 방식을 볼 수 있습니다.
- 테스트는 논리적 `code` 속성, 레이블, 개수, 보고서 상태 및 보고서 실패를 비교합니다. 백엔드 생성 ID 또는 전체 내보낸 페이로드 스냅샷을 비교하지 않습니다.
- 생성된 테스트 출력은 JUnit `@TempDir`에서만 활성화됩니다.

## 2. 추진과제

### T1 - 카탈로그 작성 및 비계 구축

- **파일**:
  - `gradle/libs.versions.toml`
  - `graph/io-pipeline/build.gradle.kts`
  - `graph/io-pipeline/src/test/resources/junit-platform.properties`
  - `graph/io-pipeline/src/test/resources/logback-test.xml`
- **행동**:
  - `bluetape4k-graph-io-core`, `bluetape4k-graph-io-csv`, `bluetape4k-graph-io-graphml` 및 `bluetape4k-graph-io-jackson3`에 대한 버전 없는 별칭을 추가합니다.
  - `bluetape4k-graph-okio`를 추가하지 마세요.
  - 벤치마크 플러그인, JMH, kotlinx-benchmark, stress/load 종속성 또는 압축이 많은 종속성을 추가하지 마세요.
  - 그래프 코어, TinkerPop, graph-io core/csv/graphml/jackson3, 로깅, `project(":shared")`, JUnit5, 어설션 및 MockK에 대한 모듈 종속성을 사용하는 경우에만 추가합니다.
  - 기존 그래프 모듈 규칙에 따라 JUnit/logback 테스트 리소스를 추가합니다.
- **DoD**:
  - `./gradlew :graph-io-pipeline:dependencies --configuration testRuntimeClasspath`이 해결되었습니다.
  - 로컬 그래프 BOM 또는 명시적인 bluetape4k 그래프 버전이 모듈에 표시되지 않습니다.
  - benchmark/stress 종속성이나 플러그인이 도입되지 않았습니다.

### T2 - 결정적 고정물

- **파일**:
  - `graph/io-pipeline/src/test/resources/graph-io-pipeline/vertices.csv`
  - `graph/io-pipeline/src/test/resources/graph-io-pipeline/edges.csv`
- **행동**:
  - ASCII ID, 레이블, `prop.code` 및 텍스트 속성을 사용하여 정점 3개와 방향성 가장자리 2개를 추가합니다.
  - 다음과 같은 정확한 행을 사용하세요.
    - 정점: `person-alice,Person,alice,Alice,learner`; `person-bob,Person,bob,Bob,reviewer`; `project-graphio,Project,graphio,Graph IO Pipeline,workshop`
    - 가장자리: `edge-alice-project,CONTRIBUTES_TO,person-alice,project-graphio,alice-project,author`; `edge-bob-project,REVIEWS,person-bob,project-graphio,bob-project,reviewer`
  - 테스트 및 내보내기 옵션에 사용되는 상수로 고정 장치 정점 레이블 `Person`, `Project` 및 가장자리 레이블 `CONTRIBUTES_TO`, `REVIEWS`을 정의합니다.
  - 각 조명기를 2 KB 아래로 유지하세요.
  - 스프레드시트 수식 접두사, 경로를 찾는 값, 비밀을 찾는 이름 및 제어 문자를 사용하지 마세요.
- **DoD**:
  - Fixture 헤더는 graph-io CSV 쌍을 이루는 파일 계약과 일치합니다.
  - 설비는 결정적이며 민감해 보이는 데이터를 포함하지 않습니다.

### T3 - 먼저 테스트 실패

- **파일**:
  - `graph/io-pipeline/src/test/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipelineTest.kt`
- **행동**:
  - 프로덕션 구현 전에 테스트를 추가합니다.
    - CSV 가져오기는 `COMPLETED`, 빈 실패, 꼭지점 3개, 모서리 2개를 반환합니다.
    - Jackson3 NDJSON export/import 왕복 반환 `COMPLETED`, 빈 실패, 보존된 개수, 10 KB 미만으로 출력됩니다.
    - GraphML export/import 왕복 반환 `COMPLETED`, 빈 실패, 보존된 개수, 10 KB 미만으로 출력됩니다.
    - 빈 정점 ID CSV는 `FAILED`을 반환합니다.
    - 누락된 가장자리 엔드포인트 CSV은 `FAILED`를 반환합니다.
    - `port`, `hyperedge`, 중첩 그래프 또는 페일클로즈 옵션이 있는 무방향 에지와 같은 지원되지 않는 GraphML은 `FAILED`를 반환합니다.
    - 내보내기 경로는 `@TempDir` 및 내보낸 파일 exist/non-empty 내에서 정규화됩니다.
  - 가져오기 및 왕복 가져오기가 성공할 때마다 공유 그래프 상태 어설션을 추가합니다.
    - 정확한 꼭지점 레이블과 가장자리 레이블;
    - 정확한 `code` 값;
    - `_graphIoExternalId` 첫 번째 CSV 가져오기 이후에만 정확한 고정 장치 값;
    - 꼭지점 3개와 모서리 2개;
    - 예상치 못한 고정물 속성 키가 없습니다.
  - Jackson3 및 GraphML 왕복 가져오기의 경우 레이블, 개수, `code` 및 에지 토폴로지를 지정합니다. `_graphIoExternalId`이 export/import 다음에 백엔드 파생될 수 있는 문서입니다.
  - Assert 내보내기 보고서에는 예상되는 형식인 `verticesWritten = 3`, `edgesWritten = 2`이 포함되어 있으며 건너뛴 정점이나 가장자리가 없습니다.
  - 내보낸 NDJSON/GraphML에는 의도적인 `_graphIoExternalId`을 포함하여 예상된 레이블과 고정 장치 속성 키만 포함되어 있다고 검증합니다.
  - 각 실패 테스트에 대해 새로운 `TinkerGraphOperations`을 사용하고 `status`, `failures` 크기, 심각도, 단계, 파일 역할, 메시지, created/skipped 개수 및 정확한 실패 후 그래프 상태를 확인합니다.
    - 빈 정점 ID: `0V/0E`;
    - `FAIL`이 있는 가장자리 엔드포인트이 누락됨: 가져온 유효한 정점은 남아 있을 수 있지만 가장자리는 `0E`이어야 합니다.
    - 실패 시 닫힘 옵션이 있는 지원되지 않는 GraphML: 가져오기 성공 가정이 없고 예상치 못한 그래프 변형이 없습니다.
  - `tempDir.resolve(relative).normalize()`을 확인하고, 정규화된 `tempDir` 외부의 경로를 거부하고, temp-dir 내용이 예상되는 `.ndjson` 및 `.graphml` 출력인지 확인하는 테스트 도우미를 추가합니다.
  - `=`, `+`, `-` 또는 `@`로 시작하는 셀이 없거나 경로 separators/control 문자를 포함하거나 token/key/password-like 이름을 사용하는 셀이 없다는 고정 장치 안전 어설션을 추가합니다.
  - bluetape4k 어설션과 JUnit `@TempDir`을 사용하세요.
  - 절전 모드, `@RepeatedTest`, stress/load 루프 및 반복되는 왕복 루프를 피하세요. 3-vertex/2-edge 조명기에 대해 형식당 정확히 한 번의 왕복을 실행합니다.
- **DoD**:
  - `GraphIoPipeline`이(가) 구현되지 않았기 때문에 초기 `./gradlew :graph-io-pipeline:test`이 실패합니다.

### T4 - GraphIoPipeline 구현

- **파일**:
  - `graph/io-pipeline/src/main/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipeline.kt`
- **행동**:
  - 영어 KDoc을 사용하여 공개 `GraphIoPipeline` 클래스를 구현합니다.
  - 경로 역할, 반환된 보고서 의미 체계, 호출자 `GraphIoStatus.COMPLETED`/`failures` 검사 및 GraphML 지원되지 않는 요소 정책(해당하는 경우)을 다루는 모든 공용 메서드에 영어 KDoc를 추가합니다.
  - 생성자는 `GraphOperations`을 허용합니다.
  - 행동 양식:
    - `importCsv(vertices: Path, edges: Path): GraphImportReport`
    - `exportJackson3NdJson(target: Path): GraphExportReport`
    - `importJackson3NdJson(source: Path): GraphImportReport`
    - `exportGraphMl(target: Path): GraphExportReport`
    - `importGraphMl(source: Path): GraphImportReport`
  - `GraphImportOptions(onDuplicateVertexId = FAIL, onMissingEdgeEndpoint = FAIL, preserveExternalIdProperty = "_graphIoExternalId")`를 사용하세요.
  - 페일클로즈 `GraphMlImportOptions(unsupportedElementPolicy = UnsupportedGraphMlElementPolicy.FAIL)`를 사용하세요.
  - 소스 경로가 존재하는지, 내보내기 대상이 디렉터리가 아닌 정규화된 경로인지 확인합니다.
  - API은 신뢰할 수 있는 출력 루트가 아닌 `Path`만 허용하므로 프로덕션 순회 보호를 검증하지 마십시오. 테스트 도우미와 README 예제는 임시 루트를 포함합니다.
  - Jackson3 및 GraphML 내보내기 모두에 `GraphExportOptions(vertexLabels = setOf("Person", "Project"), edgeLabels = setOf("CONTRIBUTES_TO", "REVIEWS"))`을 사용합니다.
- **DoD**:
  - `./gradlew :graph-io-pipeline:test` 통과.
  - `!!` 없음, `runBlocking` 없음, 더 이상 사용되지 않는 가져오기 없음.

### T5 - README, 한국어 README 및 루트 카탈로그

- **파일**:
  - `graph/io-pipeline/README.md`
  - `graph/io-pipeline/README.ko.md`
  - `README.md`
  - `README.ko.md`
- **행동**:
  - 언어 스위치를 추가합니다.
  - 아키텍처와 CSV -> TinkerGraph -> Jackson3/GraphML 흐름을 설명하세요.
  - `README.md` 및 `README.ko.md`에 동일한 섹션 세트를 추가합니다.
  - CSV, Jackson3 NDJSON, GraphML 및 Okio 지원 스트림에 대한 행이 포함된 어댑터 결정 테이블을 추가합니다.
  - 어댑터 테이블 열: 입력 형태, 최상의 사용 사례, 관련 클래스/`GraphIoFormat` 이름, 강점, limitations/unsupported 기능, 종속성 상태 및 사용하지 않는 경우.
  - 문서의 구체적인 class/format 이름, CSV 쌍으로 된 파일과 단일 스트림의 차이점, 파일 이름 확장자 자동 감지 없음, CSV+Okio 상위 수준 compression/encryption 도우미 제한, 스트림 소유권, 원자 쓰기 주의, GraphML `port`/지원되지 않는 요소 처리, `COMPLETED`/`PARTIAL`/`FAILED` 보고 의미 체계, 신뢰할 수 있는 로컬 GraphML 경고, 집중 테스트 명령 및 BOM 참고.
  - GraphML 및 NDJSON 가져오기는 엔드포인트나 새니타이저 업로드가 아닌 로컬 워크샵 예시라는 점을 경고하세요. 호출자는 graph-io를 가져오기 전에 신뢰할 수 없는 파일을 검증하고 샌드박스해야 합니다.
  - NDJSON/GraphML을 내보낸 문서에는 그래프 속성과 의도적인 `_graphIoExternalId`이 포함되어 있습니다.
  - 보고 실패가 진단 데이터이며 공개 엔드포인트에서 그대로 반환되어서는 안 된다는 문서입니다.
  - 다음을 위해 두 README 파일 모두에 컴파일 정렬 Kotlin 스니펫을 추가합니다.
    - CSV `GraphIoPipeline`, `TinkerGraphOperations`, `Path` 입력, `status == GraphIoStatus.COMPLETED` 및 `failures.isEmpty()`로 가져오기;
    - 동일한 보고서를 사용하는 Jackson3 NDJSON export/import;
    - GraphML export/import 동일한 보고서를 확인합니다.
  - 기존 그래프 예제는 도메인 순회 예제로 남아 있고 graph-io 파이프라인은 반복 가능한 고정 장치 import/export용임을 설명하는 "수동 TinkerGraph 시드에서 마이그레이션" 섹션을 추가합니다.
  - 누락된 경우 루트 그래프 domain/module 카탈로그 항목을 추가합니다.
- **DoD**:
  - 영어와 한국어 README는 축약되지 않고 소스와 동일합니다.
  - 수동 패리티 검사는 `README.md` 및 `README.ko.md`에서 일치하는 섹션 제목, 어댑터 테이블 행, 경고 설명선, 명령, 이미지 링크 및 Kotlin 스니펫 count/content을 확인합니다.
  - `node scripts/validate-readme-parity.mjs` 및 `node scripts/validate-readme-language.mjs`이 통과되었습니다.

### T6 - README 다이어그램

- **파일**:
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-architecture-01.svg`
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-architecture-01.png`
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-sequence-01.svg`
  - `docs/images/readme-diagrams/graph-io-pipeline-readme-sequence-01.png`
- **행동**:
  - 제목에 `Architects Daughter`을 사용하고 세부 텍스트에 `Comic Mono`를 사용하여 영어 라벨 SVG 자산을 만듭니다.
  - CairoSVG을 사용하여 PNG를 렌더링합니다.
  - 계속하기 전에 렌더링된 PNG을 검사하세요.
- **DoD**:
  - `node scripts/validate-readme-architecture-diagrams.mjs` 통과.
  - `node scripts/validate-sequence-diagrams.mjs` 통과.
  - 전체 크기 PNG 육안 검사 결과 겹치거나 읽을 수 없는 라벨이 없는 것으로 나타났습니다.

### T7 - 연기, 예제 및 등록 검증

- **파일**:
  - `scripts/smoke-validate.sh`
  - `.github/workflows/Examples.yml`
  - `.github/workflows/nightly.yml` 읽기 전용 증거
- **행동**:
  - `:graph-io-pipeline:test`을 `all-smoke`에 추가합니다.
  - 현재 관찰된 기준선 `79`에서 `80`까지 오래된 확인 예상 프로젝트 수를 업데이트합니다.
  - 예제 푸시 및 풀 요청 경로에 `graph/io-pipeline/**`를 추가합니다.
  - 기존 단일 H2/default 예제 smoke Gradle 명령에 `:graph-io-pipeline:test`을 추가합니다. 새로운 작업이나 컨테이너 차선을 추가하지 마십시오.
  - `.github/workflows/Examples.yml` `smoke-examples.timeout-minutes: 25`을 변경하지 않고 유지하세요.
  - 기존 `smoke-example-test-results` 업로드 블록 아래에 graph/io-pipeline 테스트 결과 아티팩트 경로를 추가합니다.
- **DoD**:
  - `./scripts/smoke-validate.sh all-smoke` 통과.
  - `./gradlew projects --console=plain | rg "Project ':graph-io-pipeline'"`은 자동 등록을 증명합니다.
  - `./scripts/smoke-validate.sh stale-check` 출력에는 `Active modules: 80 (expected: 80)`, `No stale refs found.` 및 `No broken image links found.`이 포함됩니다. 모든 `WARNING:`는 이 문제에 대한 실패입니다.
  - `actionlint .github/workflows/Examples.yml` 통과.
  - `test -z "$(rg -n "\\\\'" .github/workflows || true)"` 통과; 모든 일치는 실패 조건입니다.
  - 워크플로 차이점에 시간 초과 증가가 표시되지 않습니다.
  - `rg -n "smoke-validate.sh all-smoke" .github/workflows/nightly.yml`은 Nightly가 `all-smoke`을 통해 새 모듈에 도달했음을 증명합니다.

### T8 - 최종 검증 및 아티팩트 검토

- **파일**:
  - `docs/review/2026-06-28-issue-287-graph-io-pipeline-code-review.md`
  - `docs/lessons/2026-06-28-issue-287-graph-io-pipeline.md`
- **행동**:
  - 대상 모듈 테스트, README 유효성 검사기, 다이어그램 유효성 검사기, 워크플로 린트 및 `git diff --check`을 실행합니다.
  - 실행 후 소스, 빌드, 고정 장치, 연기 스크립트 또는 워크플로 파일이 변경되지 않는 한 T7 `all-smoke` 증거를 재사용하세요.
  - 증거와 함께 코드 검토 및 수업을 기록합니다.
  - 리뷰 또는 강의 아티팩트에 rollback/runbook 증거를 기록합니다.
    - runtime/data 마이그레이션 없음;
    - 롤백은 `graph/io-pipeline/`을 제거합니다.
    - 롤백은 사용되지 않은 graph-io 카탈로그 별칭을 제거합니다.
    - 롤백은 연기 스크립트 및 예제 워크플로 항목을 제거합니다.
    - 롤백은 오래된 검사 횟수를 복원합니다.
    - 롤백은 루트 README 항목과 다이어그램 자산을 제거합니다.
  - 기여자 진단 위치 기록:
    - `graph/io-pipeline/build/reports/tests/test/index.html`;
    - `graph/io-pipeline/build/test-results/test/*.xml`;
    - GitHub `smoke-example-test-results` 아티팩트 경로.
- **PR 준비 상태**:
  - `gh issue view 287 --json assignees,labels,milestone` 발행일 메타데이터를 확인합니다.
  - PR 본문에는 `Closes #287`이(가) 포함됩니다.
  - `gh pr view <pr> --json assignees,labels,milestone,body`은 완료를 보고하기 전에 양수인, 레이블, 마일스톤 및 본문 패리티를 증명합니다.
- **DoD**:
  - P0/P1 최종 검토 결과 결과는 0입니다.
  - 커밋은 Lore 프로토콜을 사용합니다.
  - PR은 이슈 담당자, 마일스톤 및 레이블을 반영합니다.

## 3. 검증 명령어 세트

```bash
./gradlew :graph-io-pipeline:test
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml
test -z "$(rg -n "\\\\'" .github/workflows || true)"
git diff --check
```

## 4. 위험

- `all-smoke`은 관련되지 않은 기존 오류를 노출할 수 있습니다. 그렇다면 집중 모듈 테스트를 다시 실행하고 증거와 함께 관련 없는 실패를 기록하십시오.
- GraphML 지원되지 않는 요소 처리는 데모 경로에 대해 페일클로즈되어야 하며, README는 더 광범위한 기본 동작을 설명합니다.
- 루트 README는(는) 현재 그래프 모듈을 노출하지 않을 수 있습니다. 광범위한 재작성 없이 보수적으로 두 로캘을 모두 업데이트합니다.
- 기존 다이어그램 유효성 검사기는 node/edge 클래스 규칙과 일치하지 않는 경우 새 자산을 거부할 수 있습니다. 로컬 유효성 검사기 모양에 다이어그램을 작성합니다.
