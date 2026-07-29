# Issue #324 - JaVers 승인 작업 흐름 설계

## 문맥

Issue #324 제작 스타일에 대한 학습자 대상 워크숍 예시 추가 JaVers
작업 흐름. 기존 `exposed/javers-audit` 모듈은 추가 전용 감사를 가르칩니다.
역사, `exposed/javers-persistence-audit`은 Redis의 지원을 받는 JaVers를 가르치고 있습니다.
고집. 이 예제는 이 둘 사이에 있어야 합니다. 즉, 인메모리를 사용합니다.
검토 시간 비교 및 ​​승인된 감사 스냅샷의 경우 JaVers, Exposed/H2
현재 행과 검토 결정.

## 목표

`exposed/javers-approval-workflow`를 `:exposed-javers-approval-workflow`로 생성
시연하기 위해:

- 지속되기 전에 제안된 전체 개정안을 제출합니다.
- 현재 상태와 제안된 상태 사이의 JaVers 차이점을 생성합니다.
- 지속적인 결정 기록을 통해 제안을 승인하거나 거부합니다.
- 승인 후에만 제안된 집계를 커밋합니다.
- 거부된 제안과 별도로 승인된 JaVers 내역을 쿼리합니다.

## 논골

- Redis, Kafka 또는 다른 JaVers 저장소 백엔드를 추가하지 마세요. 그것은에 속한다
  이슈 #290.
- 웹 UI 또는 Spring 애플리케이션을 빌드하지 마십시오.
- 모듈을 일반 승인 프레임워크로 만들지 마십시오. 충분히 작게 유지하십시오.
  워크숍 독자들은 한 번에 이해할 수 있습니다.

## 도메인 모델

제품에 대해 충분히 구체적이므로 `ProductPolicy` 집계를 사용하세요.
불필요한 테이블을 추가하지 않고 계약, 정책 검토 시나리오를 작성합니다.

- `ProductPolicy`: JaVers 루트를 `@TypeName("ProductPolicy")`로 집계하고
  `@Id id`.
- `PricingPolicy`: `currency`, `amount` 및
  `approvalLimit`.
- `PolicyStatus`: `DRAFT`, `ACTIVE` 및 `RETIRED`에 대한 열거형입니다.
- `PolicyProposal`: current/proposed 집계와 함께 저장된 검토 요청
  스냅샷, 변경된 필드 요약, 상태, 검토자 및 이유.
- `ProposalStatus`: `PENDING`, `APPROVED` 및 `REJECTED`에 대한 열거형입니다.

Exposed 테이블은 현재 승인된 `ProductPolicy` 행을 저장합니다. 잠시
Exposed 테이블은 제안 결정을 저장하므로 거부된 변경 사항은
하지만 JaVers 집계 스냅샷은 되지 않습니다.

## 작업 흐름

1. `publishInitial(author, policy)`은(는) 첫 번째로 승인된 정책을 JaVers에 커밋합니다.
   현재 정책 행을 업데이트합니다.
2. `submitProposal(requester, proposedPolicy)`은 현재 행을 로드하고 비교합니다.
   `javers.compare`을 사용하여 현재 제안과 제안을 비교하고 보류 중인 제안을 저장합니다.
   변경된 필드 요약을 반환합니다.
3. `approveProposal(reviewer, proposalId, reason)`은 제안이 다음과 같은지 확인합니다.
   보류 중, 제안된 집계를 JaVers에 커밋하고 현재 행을 업데이트합니다.
   제안이 승인되었음을 표시합니다.
4. `rejectProposal(reviewer, proposalId, reason)`은 제안이 다음과 같은지 확인합니다.
   보류 중이며 제안된 집계를 커밋하지 않고 거부를 기록합니다.
5. `getHistory(policyId)`은 승인된 JaVers 스냅샷만 반환합니다.

## 문서 및 다이어그램

`README.md` 및 `README.ko.md` 모두 추가 전용과의 차이점을 설명해야 합니다.
감사 내역:

- 추가 전용 감사는 저장된 모든 집계 상태를 커밋합니다.
- 승인 워크플로는 먼저 커밋 전 차이를 계산합니다.
- 거부된 제안은 승인된 JaVers 스냅샷이 아닌 결정 기록입니다.
- 승인된 제안은 현재 상태와 JaVers 기록을 모두 업데이트합니다.

다이어그램:

- 아키텍처 다이어그램: 검토자, 승인 서비스, Exposed current/decision
  테이블 및 인메모리 JaVers 스냅샷.
- 시퀀스 다이어그램: 제안서 제출, 차이점 검토, approve/reject `alt` 브랜치,
  및 감사 조회.

다이어그램은 현재 `bluetape4k-diagram` 체크리스트를 통과하고 렌더링되어야 합니다.
SVG+PNG, 전체 크기 PNG 시력 검사 증거를 포함합니다.

## 등록

- `settings.gradle.kts`은 `includeModules`을 통해 모듈을 자동 포함합니다.
- 루트 `README.md` 및 `README.ko.md`은 데이터 액세스 행을 얻습니다.
- `.github/workflows/Examples.yml`은 경로 필터와 H2/default 연기를 얻습니다.
  적용 범위.
- `scripts/smoke-validate.sh`은 smoke/data-access 검사에서 모듈을 가져오고
  오래된 확인 프로젝트 수를 늘립니다.

## 확인

- `./gradlew :exposed-javers-approval-workflow:test --console=plain --max-workers=1 --rerun-tasks`
- `./gradlew projects --console=plain`
- `./scripts/smoke-validate.sh stale-check`
- `node scripts/validate-readme-diagram-qa.mjs`
- README language/parity `scripts/`에서 사용 가능한 유효성 검사기
- `actionlint .github/workflows/Examples.yml`
- `git diff --check`
