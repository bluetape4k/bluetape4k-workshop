# Kinesis 코루틴 워크숍

[English](README.md) | 한국어

이 모듈은 credential 없이 실행되는 local 기본값으로 `bluetape4k` Kinesis
producer와 AWS SDK v2 `consumerFlow` 계약을 보여줍니다. 기존
`KinesisOperations` publish/ensure fake는 하나의 스트림과 샤드를 유지하고,
별도의 인메모리 async client가 discovery, ordering, backpressure, checkpoint,
lease를 연습할 수 있는 두 개의 결정적 샤드를 노출합니다. 두 local adapter 모두
credential을 해석하거나 네트워크에 접근하지 않습니다.

## 프로필과 실행 명령

| 프로필 | 기본 동작 | 명령 |
| --- | --- | --- |
| `local` (기본) | 결정적 `LocalKinesisOperations` publish/ensure와 두 샤드 async consumer; 세 record를 publish하고 consume | `./gradlew :aws-kinesis-coroutines:bootRun` |
| `real-aws` (명시적 opt-in) | upstream `KinesisCoroutinesTemplate`; 명시적으로 켜기 전에는 `run-demo=false` | `AWS_REGION=ap-northeast-2 ./gradlew :aws-kinesis-coroutines:bootRun --args='--spring.profiles.active=real-aws --kinesis.workshop.run-demo=true --kinesis.workshop.stream-name=kinesis-workshop-<unique>'` |

성공한 local demo 뒤 프로세스는 exit code `0`으로 종료됩니다.

local 명령은 다음과 같이 비밀값을 제거한 요약을 출력하고 종료해야 합니다.

```text
Kinesis demo completed: publishedCount=3, consumedCount=3, sequenceCount=3
```

local demo가 성공하면 프로세스는 exit code `0`으로 종료됩니다. 요약에는
개수와 sequence 메타데이터만 포함하며 payload, partition key, endpoint,
credential, 원시 exception message를 포함하지 않습니다.

## AWS 안전 경계

`real-aws`는 의도적으로 opt-in입니다. `AWS_REGION`과 표준 AWS credential
provider chain은 실행자가 직접 공급해야 합니다. 고유 스트림 이름을 사용하고
모든 명령을 확인하십시오. 이 모듈은 실제 Kinesis 스트림을 생성하고 쓸 수
있으며 스트림을 자동 삭제하지 않습니다. demo에 필요한 최소 권한은 다음과
같습니다.

```text
kinesis:CreateStream
kinesis:DescribeStream
kinesis:PutRecord
kinesis:GetShardIterator
kinesis:GetRecords
kinesis:ListShards
```

스트림에는 AWS 비용이 발생할 수 있습니다. 대상 확인 후 필요할 때 다음 명령으로
명시적으로 삭제하십시오.

```bash
aws kinesis delete-stream --stream-name "$KINESIS_WORKSHOP_STREAM_NAME"
```

선택적 endpoint override는 HTTP(S) loopback host와 고정된 local 서비스 이름
`localstack`, `kinesis`로 제한됩니다. URI user-info, link-local metadata host,
임의 private host는 거부합니다. access key, secret key, session token, payload를
source, YAML, log, 명령에 넣지 마십시오.

## 예제가 설명하는 내용

- `ensureStream`은 먼저 describe하고 `ResourceNotFound`일 때만 생성한 뒤,
  최대 30초 동안 250ms readiness 간격으로 `ACTIVE`를 확인합니다.
- `publish`는 크기가 제한된 JSON event를 직렬화하고 upstream request 경계에서
  partition key를 보존합니다.
- `consume`은 AWS SDK v2 `consumerFlow` 기반의 cold `Flow`를 반환합니다. 여러
  샤드를 동적으로 발견하고 샤드별 순서를 유지하며 `maxShardConcurrency`로
  활성 shard poller 수를 제한합니다. rendezvous handoff가 downstream
  collection을 backpressure 경계로 만듭니다. `consumerGroup`,
  `streamIdentity`, 호출자가 정한 `ownerId`가 상태 namespace와 fencing을
  구성합니다.
- `InMemoryKinesisCheckpointStore`와 `InMemoryKinesisLeaseStore`는 명시적인
  프로세스 전용 학습용 저장소입니다. downstream emit이 반환된 뒤에만
  checkpoint를 저장하며 stale lease counter는 현재 owner의 checkpoint를
  덮어쓰거나 lease를 해제할 수 없습니다. service tracking adapter도 caller가
  소유한 client를 닫지 않고 collector failure/cancellation 때 lease를 회수합니다.
- `take(3)`은 demo collection의 범위를 제한할 뿐 공유 client를 닫지 않습니다.
- iterator 만료와 throttling retry 예산은 upstream
  `KinesisCoroutinesTemplate`가 소유하며 이 모듈이 해당 계약을 재구현하지
  않습니다.
- metric은 `kinesis.workshop.publish`, `.consume`, `.retry`, `.failure`만
  허용하고 `backend`, `operation`, `outcome` tag만 사용합니다. health는 local에서
  `UP`, 준비되지 않은 real-AWS demo에서 `UNKNOWN`, terminal failure 뒤에는
  `DOWN`입니다.
- shutdown에는 10초 lifecycle 한도가 있습니다. app-owned demo job을 먼저
  끝내고 caller-owned collector는 passive하게 관찰한 뒤 owned AWS resource를
  정리합니다. registry는 caller-owned collector를 취소하지 않습니다.

각 local shard에 append한 record는 이 연습 범위에서 결정적 순서를 가집니다.
delivery는 at-least-once입니다. downstream effect 뒤 durable checkpoint가
commit되기 전에 재시작하면 record가 재생될 수 있습니다. Kinesis는 shard 또는
producer 사이의 global ordering을 보장하지 않으며, 이 모듈은 exactly-once
delivery를 주장하지 않습니다. durable checkpoint/lease 저장소, AWS provisioning,
Redis/DynamoDB adapter는 이 예제의 범위가 아닙니다.

## 테스트

AWS credential이나 Docker 없이 모듈 테스트를 실행할 수 있습니다.

```bash
./gradlew :aws-kinesis-coroutines:test --no-daemon --max-workers=1
```

테스트는 properties 경계, endpoint redaction, 결정적 fake, multi-shard discovery와
샤드별 순서, bounded concurrency, checkpoint-after-emit, lease fencing, collector
cleanup, stream readiness, cold Flow cancellation, public upstream template 계약,
profile별 bean 선택, runner 출력, metrics, health, passive shutdown registry를
검증합니다.
