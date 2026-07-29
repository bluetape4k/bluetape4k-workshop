# Issue #324 - JaVers 승인 워크플로 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use the bluetape4k workflow,
> 코드 패턴, 블로그 및 다이어그램 기술. 단계에서는 체크박스(`- [ ]`) 구문을 사용합니다.
> 추적을 위해.

**목표:** 다음을 가르치는 JaVers 승인 워크플로 워크샵 모듈을 구축합니다.
사전 커밋 diff 검토, approve/reject 결정 및 승인된 감사 조회.

**아키텍처:** 모듈은 Exposed/H2을 현재 상태 및 결정으로 유지합니다.
JaVers 인 메모리 스냅샷은 승인된 집계만 나타냅니다.
역사. 이 서비스는 지속성 이전의 현재 집계와 제안된 집계를 비교합니다.
그런 다음 승인된 제안만 커밋합니다.

**기술 스택:** Kotlin, Exposed JDBC, H2, `bluetape4k-javers-core`,
`bluetape4k-assertions`, JaVers `Diff` / `ValueChange`, CairoSVG 다이어그램
렌더링, GitHub 작업 예제 워크플로.

---

### 작업 1: 모듈 뼈대 및 빨간색 테스트

**파일:**
- 생성: `exposed/javers-approval-workflow/build.gradle.kts`
- 생성: `exposed/javers-approval-workflow/src/test/resources/junit-platform.properties`
- 생성: `exposed/javers-approval-workflow/src/test/resources/logback-test.xml`
- 생성: `exposed/javers-approval-workflow/src/test/kotlin/io/bluetape4k/workshop/exposed/javers/approval/ProductPolicyApprovalServiceTest.kt`

- [ ] 루트 BOM 확인 별칭을 사용하여 빌드 스크립트를 추가합니다.
  `libs.bluetape4k.javers.core`, `libs.exposed.core`, `libs.exposed.jdbc`,
  `libs.h2.v2`, `libs.bluetape4k.assertions`, `libs.exposed.jdbc.tests`.
- [ ] 다음에 대한 테스트를 먼저 추가합니다.
  - 제안된 스칼라 및 중첩된 값-객체 차이점;
  - 현재 행 및 JaVers 기록 업데이트를 승인합니다.
  - 현재 행과 JaVers 기록을 변경하지 않고 그대로 두는 것을 거부합니다.
  - 감사 조회는 승인된 스냅샷만 반환합니다.
- [ ] 달리다:
  `./gradlew :exposed-javers-approval-workflow:test --console=plain --max-workers=1`
- [ ] 예상되는 빨간색 결과: 승인에 대한 해결되지 않은 생산 기호
  워크플로 수업.

### 작업 2: 도메인, 테이블 및 서비스

**파일:**
- 생성: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/model/ProductPolicy.kt`
- 생성: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/model/ProductPolicyTable.kt`
- 생성: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/model/PolicyProposalTable.kt`
- 생성: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/service/ProductPolicyApprovalService.kt`

- [ ] 직렬화 가능한 불변 도메인 값을 구현합니다.
  `ProductPolicy`, `PricingPolicy`, `PolicyStatus`, `ProposalStatus`,
  `PolicyProposal` 및 `ChangedField`.
- [ ] 승인된 현재 정책 및 제안에 대한 Exposed 테이블을 구현합니다.
  결정. 예제를 유지하려면 proposed/current 스냅샷을 JSON 텍스트로 저장하세요.
  명시적이고 종속성이 적습니다.
- [ ] `publishInitial`을 사용하여 `ProductPolicyApprovalService`을 구현합니다.
  `submitProposal`, `approveProposal`, `rejectProposal`, `findProposal`,
  `findCurrentPolicy` 및 `getHistory`.
- [ ] 호출자 입력 및 Exposed v1에 bluetape4k 검증 도우미 사용
  `eq`, `deleteWhere` 및 `upsert`에 대한 최상위 가져오기.
- [ ] 달리다:
  `./gradlew :exposed-javers-approval-workflow:test --console=plain --max-workers=1 --rerun-tasks`
- [ ] 예상 결과: 테스트가 통과되었습니다.

### 작업 3: 학습자 문서

**파일:**
- 생성: `exposed/javers-approval-workflow/README.md`
- 생성: `exposed/javers-approval-workflow/README.ko.md`
- 수정: `README.md`
- 수정: `README.ko.md`

- [ ] 언어 전환, 개요,
  아키텍처, 워크플로, 코드 조각 및 "추가 전용 감사와 승인"
  워크플로' 비교.
- [ ] 데이터 액세스 아래의 두 루트 README 파일에 모듈 행을 추가합니다.
- [ ] 영어와 국문 산문으로 다이어그램 라벨을 자연스럽게 유지하세요.
- [ ] 존재하는 경우 README 유효성 검사기를 실행합니다.
  `ls scripts/*readme*` 그리고 적용 가능한 `node scripts/...` 명령.

### 작업 4: 다이어그램

**파일:**
- 생성: `docs/images/readme-diagrams/exposed-javers-approval-workflow-architecture-01.svg`
- 생성: `docs/images/readme-diagrams/exposed-javers-approval-workflow-architecture-01.png`
- 생성: `docs/images/readme-diagrams/exposed-javers-approval-workflow-sequence-01.svg`
- 생성: `docs/images/readme-diagrams/exposed-javers-approval-workflow-sequence-01.png`

- [ ] 현재 모범 사례 아키텍처 및 시퀀스 참조 PNG 공개
  그리기 전에 증거 장부에 경로를 기록하십시오.
- [ ] 다음을 사용하여 아키텍처 다이어그램을 정적 ownership/dependency 뷰로 그립니다.
  레이어 지우기, 일관된 카드 정렬, official/catalog 데이터베이스 아이콘 사용
  해당되는 경우에만 해당되며 커넥터 스타일이 다른 경우에는 범례가 표시됩니다.
- [ ] 확립된 모범 사례 시퀀스에서 시퀀스 다이어그램 그리기
  계열: 줄 위에 번호가 매겨진 레이블, 투명한 `alt` 본문, 지점별
  차분한 색상, 일치하는 화살촉 색상, 충분한 행 높이.
- [ ] 다음을 사용하여 각 SVG을 렌더링합니다.
  `~/.local/bin/cairosvg <svg> -o <png> -s 2`
- [ ] `node scripts/validate-readme-diagram-qa.mjs`을 실행하고 관련
  `bluetape4k-diagram/references/*.py` 커넥터가 많은 자산을 감사합니다.
- [ ] 터치된 모든 PNG을 전체 크기로 열고 connector/card/text을 거부합니다.
  둥근 모서리, 화살촉, 팔레트 또는 시퀀스 스타일 결함.

### 작업 5: 워크플로 등록 및 최종 확인

**파일:**
- 수정: `.github/workflows/Examples.yml`
- 수정: `scripts/smoke-validate.sh`

- [ ] 푸시 및 PR 경로 필터에 `exposed/javers-approval-workflow/**`을 추가합니다.
- [ ] H2/default 연기 차선에 `:exposed-javers-approval-workflow:test` 추가
  연기 결과 아티팩트.
- [ ] `scripts/smoke-validate.sh` no-container/data-access에 모듈을 추가합니다.
  stale-check 예상 프로젝트 수를 93에서 94로 확인하고 증가시킵니다.
- [ ] 달리다:
  - `./gradlew projects --console=plain`
  - `./scripts/smoke-validate.sh stale-check`
  - `actionlint .github/workflows/Examples.yml`
  - `git diff --check`
- [ ] Lore 프로토콜로 커밋, `debop`에 할당된 PR 생성, 미러 문제
  #324 milestone/labels, 라이브 PR 본문이 `## DoD Status`으로 끝나는지 확인합니다.
