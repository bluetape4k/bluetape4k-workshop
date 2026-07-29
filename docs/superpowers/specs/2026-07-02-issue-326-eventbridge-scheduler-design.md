# Issue 326 EventBridge 스케줄러 디자인

## 문맥

Issue #326은 `bluetape4k-workshop`에 대한 로컬 우선 AWS 기본 워크플로 예제를 추가합니다.
워크숍에는 이미 신청 이벤트, Kafka, 거래 발신함 및 AWS이 있습니다.
storage/observability 예이지만 EventBridge을 선택해야 하는 시기는 표시되지 않습니다.
스케줄러 스타일의 지연된 워크플로 경계.

현재 소스 증거:

- `bluetape4k-aws` `develop` 소스는 EventBridge를 지원하지만
  `bluetape4k-dependencies 1.3.1`은 `bluetape4k-aws-spring-boot:0.4.0`을 해결합니다.
  그 아티팩트는 아직 EventBridge Spring 패키지를 노출하지 않습니다.
- `bluetape4k-aws` 스케줄러 래퍼 지원은 여전히 ​​개별적으로 추적됩니다.
  `bluetape4k-aws`호 #310이므로 이 워크숍이 완료되었음을 의미해서는 안 됩니다.
  bluetape4k 스케줄러 통합.
- `bluetape4k-workshop`은 루트에서 `bluetape4k-dependencies`을 가져옵니다. 새로운
  워크샵 모듈은 bluetape4k 버전을 고정하면 안 됩니다.

## 목표

`aws/eventbridge-scheduler`을 학습자 대상 Spring Boot 예시로 만듭니다.
주문 워크플로 요청을 다음에 매핑합니다.

1. EventBridge `PutEventsRequestEntry`.
2. 로컬 스케줄러 스타일 요청 경계.
3. 멱등성 키와 상관관계 ID를 보존하는 보고서
   외부 경계.

기본 테스트는 실제 AWS 자격 증명 없이 실행되어야 합니다.

## 논골

- `bluetape4k-aws` 이슈 #310 전에 실제 AWS 스케줄러 지원을 구현하지 마세요.
  래퍼를 제공합니다.
- 이 모듈 내에 Kafka 보낼 편지함 구현을 추가하지 마세요.
- 기본 확인을 위해 LocalStack 또는 실제 AWS을 요구하지 않습니다.
- 새로운 bluetape4k 버전 핀을 추가하지 마십시오.

## 건축학

모듈은 작은 Spring Boot 서비스를 사용합니다:

- `OrderWorkflowService`은 사용 사례를 소유합니다.
- `EventBridgePublisher`은 AWS SDK v2에 대한 작업장-로컬 경계입니다.
  `PutEventsRequestEntry` 예제에서는 기다리지 않고 봉투를 학습할 수 있습니다.
  최신 bluetape4k-aws 아티팩트를 확인합니다.
- `WorkflowScheduler`은 스케줄러를 모델링하는 작업장-로컬 경계입니다.
  계약을 요청하고 테스트를 위한 로컬 캡처 구현을 통해 지원됩니다.
  및 기본 런타임.
- `OrderWorkflowProperties`은 EventBridge source/detail type/event 버스를 보유하고 있으며
  스케줄러 group/target 기본값입니다.

이 예에서는 EventBridge 라우팅과 스케줄러 지연을 의도적으로 분리합니다.
실행. EventBridge은 도메인 이벤트 봉투를 게시합니다. 스케줄러는
지연된 콜백 인텐트. 둘 다 동일한 멱등성 키와 상관 관계 ID를 사용하므로
학습자는 중복 제거 및 추적에 대해 추론할 수 있습니다.

## 데이터 계약

`OrderWorkflowRequest` 필드:

- `orderId`: 비어 있지 않아야 합니다.
- `customerId`: 비어 있지 않아야 합니다.
- `workflow`: `PAYMENT_REMINDER` 또는 `FULFILLMENT_CHECK`와 같은 열거형입니다.
- `scheduledAt`: 미래 또는 명시적 타임스탬프가 필요합니다. 테스트에서는 고정 사용
  `Instant` 값.
- `idempotencyKey`: 비어 있지 않아야 합니다.
- `correlationId`: 비어 있지 않아야 합니다.
- `reason`: 트리밍 후 공백이 아닌 선택 사항입니다.

`EventBridgeWorkflowEnvelope`에는 소스, 세부 유형, 이벤트 버스 이름, JSON이 포함됩니다.
세부 정보, 멱등성 키, 상관 관계 ID 및 이벤트 시간.

`SchedulerWorkflowRequest`에는 스케줄 이름, 그룹 이름, 대상 ARN,
일정 표현, 페이로드 JSON, 유연한 시간 창 모드, 멱등성 키,
및 상관 관계 ID.

## 실패 계약

- 검증 실패로 인해 bluetape4k를 통해 `IllegalArgumentException` 발생
  검증 도우미.
- `CancellationException`은(는) 항상 다시 발생합니다.
- EventBridge 실패는 EventBridge 쪽을 `FAILED`으로 표시하고 스케줄러를 건너뜁니다.
  라우팅 이벤트가 수락되지 않은 워크플로를 예약하지 않도록 예약합니다.
- 스케줄러 실패는 EventBridge를 `PUBLISHED`로 유지하고 스케줄러를 다음으로 표시합니다.
  `FAILED`; 보고서에는 실패 메시지가 포함됩니다.

## 문서 계약

`README.md` 및 `README.ko.md`은(는) 다음을 설명해야 합니다.

- 로컬 우선 기본값입니다.
- EventBridge 대 스케줄러 책임 분할.
- 로컬 애플리케이션 이벤트, Kafka outbox, EventBridge 등과 비교
  스케줄러.
- Scheduler 래퍼 지원에 대한 경고와 함께 나중에 실제 AWS을 선택하는 방법
  은(는) 아직 이 모듈의 일부가 아닙니다.

다이어그램:

- 아키텍처 다이어그램: 정적 component/boundary 보기.
- 시퀀스 다이어그램: 요청 -> EventBridge 게시 -> 스케줄러 요청
  failure/skip 브랜치.
- 두 다이어그램 모두 현재 `$bluetape4k-diagram` 체크리스트를 통과해야 하며
  전체 크기 PNG 육안 검사.

## 수락 기준

- `:aws-eventbridge-scheduler:test`은 봉투 매핑, 일정 요청을 확인합니다.
  매핑, idempotency/correlation 전파, 검증, 실패 처리,
  취소 전파.
- `settings.gradle.kts` 자동 검색을 통해 새 모듈이 포함됩니다.
- `aws/README.md` 및 `aws/README.ko.md`은 모듈을 나열합니다.
- `README.md` 및 `README.ko.md`은 다음과 같은 경우 루트 인덱스에 모듈을 포함합니다.
  repo 인덱스에는 이미 AWS 예제가 나열되어 있습니다.
- `.github/workflows/Examples.yml` 및 `scripts/smoke-validate.sh`을 실행합니다.
  비컨테이너 AWS/smoke 레인의 모듈.
- 다이어그램 유효성 검사기에는 새로운 아키텍처 및 시퀀스 자산이 포함되어 있습니다.
- PR 메타데이터 미러 이슈 #326: 담당자 `debop`, 마일스톤 `1.3.1` 및
  관련 라벨.
