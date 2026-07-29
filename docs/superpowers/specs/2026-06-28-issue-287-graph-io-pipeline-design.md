# Issue #287 - 그래프 IO 파이프라인 워크샵 설계

**날짜**: 2026-06-28
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/287
**마일스톤**: 1.2.0
**상태**: 구현 계획 준비 완료

---

## 1. 목표

`bluetape4k-graph`를 가르치는 `graph/io-pipeline` 워크숍 모듈을 추가합니다.
`graph-io` import/export 결정론적, 컨테이너 없는 테스트를 통해 표면을 테스트합니다.

모듈은 학습자에게 다음 방법을 보여주어야 합니다.

- CSV vertex/edge 조명기에서 TinkerGraph로 작은 그래프를 가져옵니다;
- 가져온 그래프를 Jackson3 NDJSON 및 GraphML로 내보냅니다.
- 내보낸 파일을 새로운 TinkerGraph 인스턴스로 가져옵니다.
- 정점 수, 가장자리 수, 레이블 및 키 속성에 대한 왕복 불변성을 검증문합니다.
- CSV, GraphML, Jackson3 NDJSON 및 Okio 지원 어댑터 중에서 선택하세요.

## 2. 출처 증거

| 소스 | 증거 |
|--------|----------|
| GitHub 발행 #287 | 새로운 graph-io 워크샵 예제, 결정론적 고정 장치, 연기 경로용 외부 컨테이너 없음, README/README.ko 업데이트 및 개별 그래프 가져오기 없음 BOM이 필요합니다. |
| `settings.gradle.kts` | `includeModules("graph", false, true)`은 `graph/*` 모듈을 Gradle 프로젝트로 자동 등록합니다. `graph/io-pipeline`은 `:graph-io-pipeline`에 매핑됩니다. |
| 기존 그래프 예시 | `graph/social-network`, `graph/knowledge-graph`, `graph/recommendation`, `graph/abuser-detection`는 README 스타일, 그래프 패키지 형태, TinkerGraph-1차 연기 패턴을 제공합니다. |
| `bluetape4k-graph` 그래프-IO 사양 | CSV, Jackson3 NDJSON 및 GraphML는 `GraphOperations`에 대한 독립 형식 모듈입니다. 정확한 JVM 속성 유형 충실도는 형식에 따라 보장되지 않습니다. |
| `bluetape4k-graph` 테스트 | `CsvRoundTripTest`, `GraphMlRoundTripTest`, `Jackson3RoundTripTest` 및 교차 형식 테스트는 API 모양과 보고서 기반 검증문을 입증합니다. |
| 워크샵 레포 규칙 | 새로운 예제에는 README 로캘 패리티, 생성된 PNG/SVG 다이어그램, 유효성 검사 매트릭스 업데이트 및 모듈이 연기에 안전한 경우 CI/smoke 적용 범위가 필요합니다. |

## 3. 논골

- 기존 그래프 워크샵 모듈을 교체하거나 다시 작성하지 마십시오.
- Neo4j, Memgraph, PostgreSQL 또는 Testcontainers 지원 경로를 추가하지 마세요.
- 개별 `bluetape4k-graph` BOM를 가져오지 마십시오.
- 이 워크숍 모듈에서는 큰 그래프 IO 처리량을 벤치마킹하지 마세요.
- stress/load 테스트, kotlinx-benchmark/JMH 모듈, 압축이 많은 시나리오 또는 반복되는 왕복 루프를 추가하지 마세요.
- 이 첫 번째 모듈에서는 일시 중지, 가상 스레드, 취소 또는 마감일 예시를 추가하지 마세요.
- CSV, NDJSON 및 GraphML 전체에서 정확한 JVM 속성 값 클래스를 보장하지 마세요.

## 4. 옵션

### 옵션 A - 문서 전용 그래프-IO 가이드

`bluetape4k-graph` graph-io 모듈을 가리키는 README 구문을 추가합니다.

**거부됨**: #287은 결정적 고정 장치 및 왕복 테스트가 포함된 실행 가능한 예제를 명시적으로 요청합니다.

### 옵션 B - 연기 방지 TinkerGraph 파이프라인 모듈

작은 CSV 설비, import/export 서비스로 `graph/io-pipeline`을 생성합니다.
왕복 테스트, README/README.ko 및 README 다이어그램. TinkerGraph만 사용하고
컨테이너 없이 실행되는 형식 어댑터.

**채택됨**: 이는 문제를 직접적으로 만족시키며 작업장의 연기 테스트 모델에 적합합니다.

### 옵션 C - Okio/compression-heavy 파이프라인

Okio 가상 파일 시스템, 압축 싱크 및 원자적 쓰기를 중심으로 모듈을 구축합니다.

**지연**: Okio는 학습자 대상 어댑터 선택 테이블에 속하지만 첫 번째 예는 graph-io 형식 계약에 초점을 맞춰야 합니다. 압축 및 가짜 파일 시스템 시나리오는 필요한 경우 나중에 문제가 될 수 있습니다. README에서 Okio를 개념적으로만 설명하는 경우 Okio 종속성을 추가하지 마세요.

## 5. 제안 모듈

```
graph/io-pipeline/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipeline.kt
  src/test/kotlin/io/bluetape4k/workshop/graph/io/GraphIoPipelineTest.kt
  src/test/resources/graph-io-pipeline/vertices.csv
  src/test/resources/graph-io-pipeline/edges.csv

docs/images/readme-diagrams/
  graph-io-pipeline-readme-architecture-01.svg
  graph-io-pipeline-readme-architecture-01.png
  graph-io-pipeline-readme-sequence-01.svg
  graph-io-pipeline-readme-sequence-01.png
```

README 이미지 링크는 `../../docs/images/readme-diagrams/...`를 사용해야 합니다.

### 런타임 종속성

기존 저장소 규칙을 통해서만 워크샵 종속성 BOM을 사용하십시오.
누락된 경우 버전 없이 로컬 카탈로그 별칭을 추가합니다.

- `bluetape4k-graph-io-core`
- `bluetape4k-graph-io-csv`
- `bluetape4k-graph-io-graphml`
- `bluetape4k-graph-io-jackson3`
- `bluetape4k-graph-okio` 소스 코드가 Okio 어댑터 API를 가져오는 경우에만 해당

예제 모듈은 다음에 의존해야 합니다.

- `bluetape4k-graph-core`
- `bluetape4k-graph-tinkerpop`
- 선택한 `graph-io` 형식 모듈
- `bluetape4k-logging`
- `testImplementation(project(":shared"))`
- `testImplementation(libs.bluetape4k.junit5)`
- `testImplementation(libs.bluetape4k.assertions)`

### API 계약

`GraphIoPipeline`은(는) 영어 KDoc을 사용하는 소규모 공개 수업이어야 합니다.
학습자 대상 예시의 일부입니다. 파일을 소유하지 않으며 임시 파일을 생성하지 않습니다.
디렉토리; 호출자는 경로를 전달하고 테스트는 임시 파일 수명 주기를 소유합니다.

최소 API:

- `importCsv(vertices: Path, edges: Path): GraphImportReport`
- `exportJackson3NdJson(target: Path): GraphExportReport`
- `importJackson3NdJson(source: Path): GraphImportReport`
- `exportGraphMl(target: Path): GraphExportReport`
- `importGraphMl(source: Path): GraphImportReport`

경로 인수는 가져오기 또는 정규화를 위해 기존 입력 파일로 검증되어야 합니다.
내보낼 대상 파일. 이 메소드는 graph-io 보고서를 반환합니다. 발신자는 확인해야합니다
`GraphIoStatus.COMPLETED`이고 `failures`은 비어 있습니다.

### 데이터 세트

안정적인 외부 ID를 가진 결정적 CSV 고정 장치를 사용하십시오.

- 정점: 두 사람과 하나의 project/data 세트 노드;
- edge: contribution/review 사람과 프로젝트의 관계;
- 속성: 짧은 텍스트 값만, CSV 및 GraphML의 숫자 유형 모호성을 방지합니다.
- 고정물 크기: 3개의 정점, 2개의 가장자리, 2개 KB 아래의 CSV 파일, 10개 KB 아래의 NDJSON/GraphML 테스트 출력 생성;
- 전체 페이로드 스냅샷 비교가 없습니다.

CSV 헤더 및 예제 모양:

```csv
id,label,prop.code,prop.name,prop.kind
person-alice,Person,alice,Alice,learner
```

```csv
id,label,from,to,prop.code,prop.role
edge-alice-project,CONTRIBUTES_TO,person-alice,project-graphio,alice-project,author
```

가져오기 옵션:

- `onDuplicateVertexId = FAIL`
- `onMissingEdgeEndpoint = FAIL`
- `preserveExternalIdProperty = "_graphIoExternalId"`

테스트는 백엔드 생성 ID가 아닌 `code`과 같은 논리적 텍스트 키를 어설션해야 합니다.
또한 `_graphIoExternalId`이 의도적으로 보존되어 있으며
예기치 않은 설비 속성 키가 내보내졌습니다.

설비 안전 규칙:

- ID, 레이블, 속성 키 및 관계 코드는 ASCII 허용 목록 값을 사용합니다.
- 값은 민감하지 않은 합성 텍스트입니다.
- CSV 셀은 `=`, `+`, `-` 또는 `@`으로 시작할 수 없습니다.
- 경로 찾기 값, 제어 문자, token/key 이름 또는 너무 큰 문자열을 포함하지 마십시오.

## 6. README 및 다이어그램

`README.md` 및 `README.ko.md` 모두 다음을 포함해야 합니다.

- 제목 바로 아래에서 언어를 전환합니다.
- SVG 소스와 일치하는 아키텍처 다이어그램 PNG;
- SVG 소스가 일치하는 sequence/pipeline 다이어그램 PNG;
- CSV, GraphML, Jackson3 NDJSON 및 Okio 지원 스트림에 대한 어댑터 결정 테이블
- 구체적인 클래스 이름과 관련이 있는 경우 `GraphIoFormat` 이름;
- CSV 쌍을 이루는 파일과 단일 스트림 형식의 차이점;
- 파일 이름 확장자 자동 감지 기능이 없습니다.
- 모듈이 `bluetape4k-graph-okio`을 가져오지 않는 한 Okio는 참조 전용입니다.
- CSV+Okio 고급 compression/encryption 도우미 제한 사항;
- 스트림 소유권 및 원자 쓰기 주의사항
- GraphML `port` 및 지원되지 않는 요소 처리를 포함하여 지원되는 기능과 지원되지 않는 기능;
- `COMPLETED`, `PARTIAL` 및 `FAILED`에 대한 의미 체계를 보고합니다.
- CSV 가져오기, Jackson3 NDJSON export/import, GraphML export/import 및 `GraphIoStatus.COMPLETED` 검사를 위한 최소 Kotlin 스니펫;
- 짧은 마이그레이션 참고 사항: 기존 그래프 예제는 도메인 순회 예제로 남아 있습니다. 반복 가능한 고정 장치 import/export가 필요한 경우에만 graph-io 파이프라인을 사용하십시오.
- 집중 테스트 명령: `./gradlew :graph-io-pipeline:test`;
- bluetape4k 버전은 `bluetape4k-dependencies`에 의해 관리된다는 종속성 참고 사항입니다.

다이어그램 레이블은 영어로 유지되므로 두 README 파일에서 동일한 자산을 공유할 수 있습니다.
CairoSVG을 사용하여 커밋된 SVG에서 PNG를 생성하고 렌더링된 PNG를 검사합니다.
README 텍스트에는 GraphML 가져오기가 신뢰할 수 있는 local/exported용임을 명시해야 합니다.
워크샵 파일이며 임의 업로드 삭제 프로그램이 아닙니다.

## 7. CI 및 검증 매트릭스

모듈은 TinkerGraph 및 로컬 파일만 사용하기 때문에:

- `:graph-io-pipeline:test`을 `scripts/smoke-validate.sh all-smoke`에 추가;
- 예상되는 오래된 검사 Gradle 프로젝트 수를 `79`에서 `80`로 업데이트합니다.
- `.github/workflows/Examples.yml` 푸시 및 풀 요청 경로 필터에 `graph/io-pipeline/**`을 추가합니다.
- `Examples.yml` `Run H2/default examples` Gradle 명령에 `:graph-io-pipeline:test`을 추가합니다.
- 기존 `smoke-examples` 시간 초과를 변경하지 않고 유지합니다.
- 연기 예제 아티팩트 업로드에 `graph/io-pipeline/build/test-results/test/*.xml` 및 `graph/io-pipeline/build/reports/tests/test/`을 포함합니다.
- 그래프 예제 또는 프로젝트 구조를 열거하는 경우 `README.md` 및 `README.ko.md` 루트 module/domain 카탈로그 항목을 업데이트합니다.

Nightly는 이미 `scripts/smoke-validate.sh all-smoke`을 호출하므로 스크립트 업데이트가 기본 Nightly 통합 지점입니다.

## 8. 합격기준

- [ ] `graph/io-pipeline`은 기존 자동 모듈 규칙을 통해 `:graph-io-pipeline`로 등록됩니다.
- [ ] 모듈은 CSV 조명기를 TinkerGraph로 가져옵니다.
- [ ] 테스트는 Jackson3 NDJSON 및 GraphML로 내보낸 다음 새로운 TinkerGraph 인스턴스로 가져옵니다.
- [ ] 테스트에서는 vertex/edge 개수, 선택한 레이블 및 선택한 텍스트 속성을 확인합니다.
- [ ] 테스트는 최소한 빈 정점 ID에 대한 CSV import failure/report 계약을 검증문하고 에지 엔드포인트 실패 사례가 누락되었습니다.
- [ ] JUnit `@TempDir`에서만 쓰기 생성된 NDJSON/GraphML을 테스트하고 정규화된 경로가 임시 디렉토리 내에 남아 있는지 확인하고 내보낸 파일이 존재하며 비어 있지 않다고 확인합니다.
- [ ] GraphML 가져오기는 실패 시 닫힘 지원되지 않는 요소 처리를 사용하고 `COMPLETED`을 빈 `failures`로 검증문합니다.
- [ ] 테스트에서는 컨테이너, 절전, 스트레스, 반복 루프 및 벤치마크 동작을 방지합니다. 워크플로 시간 초과는 늘어나지 않습니다.
- [ ] `:graph-io-pipeline:test`에는 외부 컨테이너가 필요하지 않습니다.
- [ ] 저장소는 bluetape4k 버전에 대해 `bluetape4k-dependencies` BOM만 사용합니다.
- [ ] README/README.ko 어댑터, 장단점 및 집중적인 Gradle 테스트 명령을 설명합니다.
- [ ] README 다이어그램에는 SVG 소스, 렌더링된 PNG 및 시각적 QA 증거가 있습니다.
- [ ] CI/smoke 검증에는 `Examples.yml`, `smoke-validate.sh all-smoke`의 새로운 연기 방지 모듈과 오래된 검사 예상 개수가 포함됩니다.
- [ ] 기여자 유효성 검사에는 `./gradlew :graph-io-pipeline:test`, `./scripts/smoke-validate.sh all-smoke`, `./scripts/smoke-validate.sh stale-check`, README 유효성 검사기, 다이어그램 유효성 검사기 및 `git diff --check`이 포함됩니다.

## 9. 기여자 런북

문제가 완료되기 전에 다음을 실행하세요.

```bash
./gradlew :graph-io-pipeline:test
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
git diff --check
```

`.github/workflows/Examples.yml`이 변경되면 다음도 실행합니다.

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

## 10. 마이그레이션/롤백

Runtime/data 마이그레이션: 없음. 이것은 결정론적인 예제 전용 모듈입니다.
설비 및 생성된 README 자산.

롤백은 다음을 제거합니다:

- `graph/io-pipeline/`
- 롤백 후 사용되지 않는 graph-io 카탈로그 별칭
- 연기 스크립트 및 예제 워크플로의 `:graph-io-pipeline:test`
- 오래된 검사 횟수 변경
- 루트 README/README.ko 그래프 카탈로그 항목
- `graph-io-pipeline-readme-*` SVG/PNG 자산

## 11. 위험 및 완화

| 위험 | 완화 |
|------|------------|
| CSV/GraphML 속성 값은 텍스트 또는 매퍼별 숫자 유형으로 왕복됩니다. | 텍스트 고정 속성을 사용하고 JVM 숫자 클래스가 아닌 논리값을 비교하세요. |
| 그래프 백엔드 생성 ID는 고정 장치 외부 ID와 다릅니다. | 레이블, 개수 및 선택한 속성을 지정합니다. 에지 엔드포인트 재구성에만 graph-io 외부 ID 매핑을 사용하세요. |
| GraphML은 전체 사양보다 지원되는 하위 집합이 더 좁습니다. | 방향성 속성 그래프 기본 사항에 대한 고정 장치를 유지하고 하위 집합을 문서화합니다. |
| 새 모듈은 연기에 안전하지만 CI 도우미 목록에서는 생략되었습니다. | `smoke-validate.sh` 및 `Examples.yml`을 업데이트합니다. `./scripts/smoke-validate.sh stale-check`를 확인합니다. |
| 다이어그램 자산이 렌더링되지만 README에서 읽을 수 없게 됩니다. | 렌더링된 PNG를 검사하고 완료되기 전에 기존 다이어그램 유효성 검사기를 실행하세요. |
