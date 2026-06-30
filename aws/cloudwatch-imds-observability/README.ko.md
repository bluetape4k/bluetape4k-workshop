# CloudWatch + IMDS 관찰 가능성 워크숍

[English](README.md) | 한국어

이 예제는 AWS CloudWatch metrics, CloudWatch Logs, Micrometer meter publishing,
EC2 Instance Metadata Service(IMDS)를 안전한 관찰 가능성 경계로 다루는 방법을
보여줍니다. 기본 profile은 local-first입니다. 운영 경로와 같은 publish request를
만들지만, AWS client를 만들지 않고, 자격 증명도 요구하지 않으며, 요청이 명시적으로
metadata를 요구하지 않는 한 IMDS를 호출하지 않습니다.

## 아키텍처

![CloudWatch and IMDS observability architecture](../../docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.png)

서비스 계층이 telemetry 정책을 소유합니다. Micrometer counter와 timer를 기록하고,
CloudWatch `MetricDatum`, 민감한 값이 제거된 CloudWatch Logs `InputLogEvent`,
bluetape4k CloudWatch publishing 경계를 통한 meter snapshot을 만듭니다. Local
adapter bean은 AWS에 접속하지 않고 성공 SDK 응답을 반환합니다.

## 요청 흐름

![CloudWatch and IMDS observability sequence](../../docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.png)

핵심은 `alt metadata boundary`입니다. 일반 order telemetry 요청은 metadata 조회를
건너뜁니다. Metadata는 요청에 `includeMetadata=true`가 있거나
`bluetape4k.workshop.aws.observability.metadata.enabled=true` 설정이 켜진 경우에만
읽습니다.

## 학습 포인트

| 주제 | 워크숍 동작 |
| --- | --- |
| CloudWatch metric intent | `Outcome`, `Service`, `Source` dimension을 가진 `OrderTelemetryOutcome`을 publish합니다. |
| CloudWatch Logs intent | Log message를 작은 JSON 객체로 직렬화하고 민감한 필드는 redaction합니다. |
| Micrometer bridge | 서비스가 `workshop.aws.order.telemetry.requests`를 기록하고 해당 meter snapshot을 publish합니다. |
| Failure isolation | Metric, log, meter publishing 실패를 독립적으로 보고합니다. Log 실패가 metric 성공을 가리지 않습니다. |
| IMDS boundary | `instanceId`, `region`, `availabilityZone`만 읽으며, 반드시 명시적 opt-in 뒤에만 실행합니다. |
| Local safety | 기본 테스트와 smoke 실행에는 AWS 계정, AWS 자격 증명, 실제 IMDS endpoint가 필요하지 않습니다. |

## 로컬 실행

```bash
./gradlew :aws-cloudwatch-imds-observability:test
./gradlew :aws-cloudwatch-imds-observability:bootRun
```

로컬 telemetry 요청을 보냅니다.

```bash
curl -s http://localhost:8080/api/aws-observability/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId": "order-1001",
    "outcome": "SUCCESS",
    "message": "accepted token=secret-value",
    "includeMetadata": false
  }' | jq
```

예상되는 로컬 응답 형태는 다음과 같습니다.

```json
{
  "outcome": "SUCCESS",
  "metric": { "state": "PUBLISHED", "message": "" },
  "logs": { "state": "PUBLISHED", "message": "" },
  "meterSnapshot": { "state": "PUBLISHED", "message": "" },
  "metadata": { "state": "SKIPPED", "message": "metadata lookup disabled" }
}
```

Metadata를 명시적으로 요청합니다.

```bash
curl -s http://localhost:8080/api/aws-observability/orders \
  -H 'Content-Type: application/json' \
  -d '{ "eventId": "order-1002", "includeMetadata": true }' | jq
```

Local profile은 결정적인 demo metadata를 반환합니다.

```json
{
  "metadata": {
    "state": "PUBLISHED",
    "instanceId": "local-instance",
    "region": "local-region",
    "availabilityZone": "local-zone"
  }
}
```

Validation 실패는 호출자가 보낸 값을 그대로 노출하지 않도록 정규화합니다.

```bash
curl -s -i http://localhost:8080/api/aws-observability/orders \
  -H 'Content-Type: application/json' \
  -d '{ "eventId": "   " }'
```

```json
{ "error": "eventId must not be blank." }
```

## 설정

기본 `src/main/resources/application.yml`은 bluetape4k AWS auto-configuration을
꺼 둡니다. 그래서 샘플이 실수로 호스트의 region이나 자격 증명을 해석하지 않습니다.

```yaml
bluetape4k:
  aws:
    enabled: false
    cloudwatch:
      enabled: false
    cloudwatch-logs:
      enabled: false
    imds:
      enabled: false
  workshop:
    aws:
      observability:
        namespace: Bluetape4k/Workshop
        log-group-name: /bluetape4k/workshop/orders
        log-stream-name: local
        service-name: order-service
        source-name: workshop
        metadata:
          enabled: false
```

## 선택적 Real AWS Profile

실제 AWS 호출은 기본 경로 밖에 있습니다. 비용, cleanup, IAM 권한, region 선택을
이해한 수동 환경에서만 사용하세요.

```bash
export AWS_REGION=ap-northeast-2
export AWS_PROFILE=your-profile

./gradlew :aws-cloudwatch-imds-observability:bootRun \
  --args='--spring.profiles.active=real-aws \
  --bluetape4k.aws.enabled=true \
  --bluetape4k.aws.cloudwatch.enabled=true \
  --bluetape4k.aws.cloudwatch-logs.enabled=true'
```

IMDS는 metadata 접근이 기대되는 EC2 instance에서만 켭니다.

```bash
./gradlew :aws-cloudwatch-imds-observability:bootRun \
  --args='--spring.profiles.active=real-aws \
  --bluetape4k.aws.enabled=true \
  --bluetape4k.aws.imds.enabled=true \
  --bluetape4k.workshop.aws.observability.metadata.enabled=true'
```

이 모듈은 IMDS를 자동 credential source로 사용하지 않습니다. IMDS는 metadata로만
취급하며, 서비스는 security credential document path를 요청하지 않습니다.

## 테스트 범위

```bash
./gradlew :aws-cloudwatch-imds-observability:compileKotlin
./gradlew :aws-cloudwatch-imds-observability:compileTestKotlin
./gradlew :aws-cloudwatch-imds-observability:test
```

테스트는 publish intent, tag와 field mapping, redaction, 부분 실패 동작,
cancellation propagation, 명시적 IMDS opt-in 경계를 실제 AWS 자격 증명 없이
검증합니다.
