# exposed/javers-approval-workflow

[English](README.md) | 한국어

이 모듈은 JaVers를 승인 전 검토 흐름에 붙이는 예제입니다. 변경된 aggregate를
바로 commit하지 않고, 현재 `ProductPolicy`와 제안된 revision을 먼저 비교한 뒤
review decision을 저장하고, 승인된 제안만 current row와 JaVers audit history에
반영합니다.

도메인 변경을 적용하기 전에 reviewer가 어떤 필드가 바뀌는지 먼저 확인해야 할 때
사용할 수 있는 가장 작은 워크플로우입니다.

![exposed/javers-approval-workflow architecture diagram](../../docs/images/readme-diagrams/exposed-javers-approval-workflow-readme-architecture-01.png)

## 런타임 흐름

![exposed/javers-approval-workflow sequence diagram](../../docs/images/readme-diagrams/exposed-javers-approval-workflow-readme-sequence-01.png)

## 이 모듈에서 확인할 내용

| Operation | 소스 기준 동작 |
|---|---|
| `publishInitial(author, policy)` | 첫 approved `ProductPolicy` snapshot을 JaVers에 commit하고 Exposed current row를 upsert |
| `submitProposal(requester, proposed)` | current row를 읽고 current/proposed를 JaVers로 비교한 뒤 changed-field summary와 함께 pending proposal 저장 |
| `approveProposal(reviewer, proposalId, reason)` | proposed aggregate를 JaVers에 commit하고 current row를 갱신한 뒤 proposal을 approved로 표시 |
| `rejectProposal(reviewer, proposalId, reason)` | rejection reason만 기록하고 current row와 JaVers snapshot은 변경하지 않음 |
| `getHistory(policyId, limit = 100)` | `1..100` query limit을 pushdown하고 approved JaVers snapshot을 newest-first로 반환 |

## Approval Workflow와 Append-Only Audit의 차이

| Audit 스타일 | JaVers commit 시점 | 거절된 변경의 위치 |
|---|---|---|
| Append-only audit (`exposed/javers-audit`) | save 작업마다 aggregate state를 commit | 별도 모델 없음. 이미 저장된 state가 audit history |
| Approval workflow (이 모듈) | 최초 publish와 approved proposal만 aggregate state commit | `PolicyProposalTable`의 durable decision이며 JaVers snapshot이 아님 |
| Redis-backed audit (`exposed/javers-persistence-audit`) | Redis-backed JaVers repository에 commit 저장 | Approval gate가 아니라 persistence concern |

## Bounded History 계약

`getHistory(policyId, limit)`은 `1..100`을 허용하고 한 인자 JVM overload는 100을 사용합니다.
JaVers가 snapshot을 반환하기 전에 `QueryBuilder.limit`을 적용하며 materialize된 목록을 다시
정렬하거나 잘라내지 않습니다. Approved snapshot은 2.0.0 consumer 계약에 맞춰
newest-first로 반환합니다. 이는 이전 oldest-first 예제에서의 behavioral migration입니다.

Rejected proposal은 계속 snapshot을 추가하지 않습니다. 빈 history는 unknown policy와
approved commit이 없는 policy를 구분하지 않으므로 존재 확인 수단으로 사용하면 안 됩니다.
`CdoSnapshot`은 policy field를 포함하므로 HTTP/API 경계로 노출하는 호출자가 authorization과 redaction을
추가해야 합니다.

## Domain Schema

`ProductPolicyTable`은 현재 승인된 aggregate state를 저장합니다. `PolicyProposalTable`은
review decision, proposed fields, reader-facing diff summary를 저장합니다. 이 예제의
핵심은 외부 audit persistence가 아니라 승인 순서이므로 JaVers repository는 in-memory로 둡니다.

| Table | 책임 |
|---|---|
| `product_policies` | 현재 approved `ProductPolicy` row |
| `policy_proposals` | Pending/approved/rejected review decision과 proposed state |
| JaVers in-memory repository | `getHistory(policyId)`로 조회하는 approved aggregate snapshot |

## 사용 예

```kotlin
val javers = JaversBuilder.javers().build()
val service = ProductPolicyApprovalService(javers)

val current = ProductPolicy(
    id = 100L,
    title = "Standard Support Policy",
    status = PolicyStatus.ACTIVE,
    pricing = PricingPolicy("USD", BigDecimal("99.00"), BigDecimal("500.00")),
    owner = "platform-team",
)

service.publishInitial("alice", current)

val proposed = current.copy(
    title = "Enterprise Support Policy",
    pricing = current.pricing.copy(amount = BigDecimal("129.00")),
)

val proposal = service.submitProposal("bob", proposed)
proposal.changedFields.map { it.path } // ["pricing.amount", "title"]

service.approveProposal("carol", proposal.id, "Pricing reviewed")
val approvedHistory = service.getHistory(100L, limit = 10)
```

## 테스트

```bash
./gradlew :exposed-javers-approval-workflow:test
```

테스트는 scalar 및 nested value-object diff, bounded newest-first history, JVM overload,
invalid limit, 승인 시 current row와 JaVers history 갱신, 거절 시 둘 다 변경되지 않는 동작을
검증합니다.
