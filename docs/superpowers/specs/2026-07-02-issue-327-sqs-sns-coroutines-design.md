# Issue 327 SQS/SNS 코루틴 메시징 디자인

## 문맥

Issue #327은 `bluetape4k-workshop`에 AWS queue/topic 메시징 예제를 추가합니다.
저장소에는 이미 Kafka, Kafka 보낼 편지함 대체 및 트랜잭션이 있습니다.
발신함의 예. 누락된 워크샵 계약은 AWS 기본 팬아웃 플러스입니다.
학습자가 AWS 자격 증명 없이 실행할 수 있는 대기열-소비자 경로입니다.

현재 소스 증거:

- `bluetape4k-workshop`은(는) 소비자 저장소이며 루트를 사용해야 합니다.
  `bluetape4k-dependencies`BOM만 가능합니다.
- `bluetape4k-aws`은 이 범위에 대해 Spring Boot 코루틴 API를 노출합니다.
  `io.bluetape4k.aws.spring.sns.SnsOperations`,
  `io.bluetape4k.aws.spring.sns.SnsPublishRequest`,
  `io.bluetape4k.aws.spring.sqs.SqsOperations` 그리고
  `io.bluetape4k.aws.spring.sqs.SqsReceivedMessage`.
- `bluetape4k-aws`도 `MicrometerSqsOperations`을 제공하지만 워크샵은
  publish/consume 결과에 대해 학습자가 볼 수 있는 비즈니스 지표가 여전히 필요합니다.
- 기존 `messaging/kafka-outbox-fallback` 테스트는 retry/dead-letter을 확인하고
  Micrometer 카운터; 이 모듈은 테스트 형태를 재사용하지 않고 재사용해야 합니다.
  내구성이 뛰어난 아웃박스 디자인을 복사합니다.

## 목표

매핑하는 로컬 우선 Spring Boot 예제로 `aws/sqs-sns-coroutines`을 생성합니다.
주문 알림:

1. SNS 게시 요청입니다.
2. 대기열 전달 알림에 대한 SQS 소비자 경계입니다.
3. Retry/dead-letter 핸들러 실패 분류.
4. Micrometer 결과 게시 및 사용을 위한 카운터 및 대기 시간 타이머.

기본 테스트는 AWS 자격 증명 없이 가짜 작업으로 실행되어야 합니다.

## 논골

- 이 모듈에서 Kafka 또는 트랜잭션 발신함 구현을 생성하지 마십시오.
- 기본 확인을 위해 LocalStack 또는 실제 AWS을 요구하지 않습니다.
- 루트 BOM 외부에 bluetape4k 모듈 버전을 고정하지 마십시오.
- 메시지, 로그, 지표 또는 데이터에 민감한 페이로드나 원시 비밀을 노출하지 마세요.
  README 예.

## 건축학

이 모듈은 작은 Spring Boot 서비스 계층을 사용합니다.

- `OrderNotificationMessagingService`은 게시 및 소비 사용 사례를 소유합니다.
- `SnsOperations`은 주제 게시 경계입니다.
- `SqsOperations`은 큐 receive/delete/change-visibility 경계입니다.
- `OrderNotificationHandler`은 학습자 소유의 애플리케이션 핸들러입니다.
- `OrderNotificationMetrics`은 안정적인 낮은 카디널리티 counters/timers를 기록합니다.
- `SqsSnsMessagingProperties`에는 ARN 주제, 대기열 URL, 폴링 제한,
  표시 시간 초과, 최대 수신 횟수 및 메시지 제목.

로컬 런타임은 인메모리 `SnsOperations` 및 `SqsOperations` Bean을 등록합니다.
응용 프로그램이 실제 응용 프로그램을 제공하지 않은 경우에만. 이렇게 하면 `bootRun`이 유지됩니다.
사용할 수 있으며 실제 AWS 유효성 검사 옵션은 그대로 유지됩니다.

## 데이터 계약

`OrderNotificationRequest` 필드:

- `orderId`: 비어 있지 않아야 합니다.
- `customerId`: 비어 있지 않아야 합니다.
- `eventType`: `ORDER_PLACED`, `PAYMENT_CAPTURED` 등의 열거형 또는
  `SHIPMENT_READY`.
- `message`: 공백이 아닌 학습자에게 안전한 메시지 텍스트가 필요합니다.
- `idempotencyKey`: 비어 있지 않아야 합니다.
- `correlationId`: 비어 있지 않아야 합니다.

`OrderNotificationEvent`은 SNS로 전송되고 SQS에서 소비되는 JSON 페이로드입니다.
동일한 ID 및 상관 필드와 `publishedAt`을 전달합니다.

## 실패 계약

- 검증 실패로 인해 bluetape4k를 통해 `IllegalArgumentException` 발생
  검증 도우미.
- `CancellationException`은(는) 항상 다시 발생합니다.
- SNS 게시 실패는 실패한 게시 보고서를 반환하고 실패 측정항목을 기록합니다.
- SQS 핸들러 성공은 메시지를 삭제하고 `acked`을 기록합니다.
- 최대 수신 횟수가 즉시 재시도를 요청하기 전에 처리기 오류가 발생했습니다.
  `changeVisibility(..., timeoutSeconds = 0)` 및 `retry`을 기록합니다.
- 수신 횟수가 이미 최대 수신 횟수에 도달한 메시지는 삭제됩니다.
  현지 작업장 계약의 경우 `dead-letter`으로 분류됩니다.

## 문서 계약

`README.md` 및 `README.ko.md`은(는) 다음을 설명해야 합니다.

- 로컬 우선 기본 및 가짜 클라이언트 경계.
- SNS 게시와 SQS 소비 책임.
- retry/dead-letter 분류와 지속 가능한 발신함 재생의 차이점
- Kafka, 트랜잭션 발신함 및 Kafka 발신함 대체와의 비교.
- 실제 AWS/LocalStack 유효성 검사는 선택 사항이며 기본 빌드 외부에 있습니다.

다이어그램:

- 아키텍처 다이어그램: AWS SNS/SQS 아이콘이 있는 정적 component/boundary 보기,
  레이어, 로컬 대 실제 AWS 경로에 대한 범례.
- 시퀀스 다이어그램: 게시 -> 팬아웃 -> 폴링 -> 핸들러 -> ack/retry/dead-letter
  모범 사례 시퀀스 스타일링을 사용합니다.
- 두 다이어그램 모두 현재 `$bluetape4k-diagram` 체크리스트를 통과해야 하며
  전체 크기 PNG 육안 검사.

## 수락 기준

- `:aws-sqs-sns-coroutines:test`은 게시 매핑, 소비 처리를 확인합니다.
  retry/failure 분류, 데드 레터 분류, metrics/tags,
  유효성 검사 및 취소 전파.
- `aws/README.md` 및 `aws/README.ko.md`은 모듈을 나열합니다.
- `README.md` 및 `README.ko.md`은 루트 AWS 인덱스에 모듈을 나열합니다.
- `.github/workflows/Examples.yml` 및 `scripts/smoke-validate.sh`을 실행합니다.
  비컨테이너 AWS/smoke 레인의 모듈.
- 다이어그램 QA 증거에는 XML 구문 분석, CairoSVG 렌더링, 커넥터 감사가 포함됩니다.
  marker/color 패리티, 시퀀스 스타일 감사 및 전체 크기 PNG 시각 검사.
- PR 메타데이터 미러 이슈 #327: 담당자 `debop`, 마일스톤 `1.3.1` 및
  이슈 라벨.
