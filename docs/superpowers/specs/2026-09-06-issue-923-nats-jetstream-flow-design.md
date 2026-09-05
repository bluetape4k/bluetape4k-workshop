# Issue #923 NATS JetStream Consumer Flow 설계

## 목표

`messaging/nats-jetstream-flow` 독립 예제를 추가해 dependencies 2.0.0의 공개
`bluetape4k-nats` API를 실제 JetStream 서버에서 검증한다. Pull
`ConsumerContext.consumeAsFlow`와 push `JetStream.consumeAsFlow`를 cold Flow로
사용하고, 수동 `ack`/`nak`/`term`, redelivery, bounded buffering, 취소 시 자원
정리, pending queue drop의 fail-fast 계약을 소비자 관점에서 보여준다.

## 근거와 범위

- GNO와 live GitHub의 workshop Issue #923, `bluetape4k-projects` Issue #1350과
  merged PR #1476, stable 2.0.0 provider source를 기준으로 한다.
- 예제는 `bluetape4k-dependencies:2.0.0` BOM과 versionless
  `bluetape4k-nats`, `bluetape4k-testcontainers` alias만 사용한다.
- 외부 계정이나 credential 없이 Testcontainers `NatsServer`의 JetStream 모드에서
  실행한다.

## 소비자 경계

- `JetStreamFlowWorkshop`은 stream 생성, publish, pull/push Flow 조립만 담당하고
  `Connection`, consumer context, Flow lifecycle의 소유권을 숨기지 않는다.
- Flow adapter는 자동 승인하지 않는다. caller가 처리 성공 시 `ack()`, 재시도 가능한
  실패 시 `nak()`, poison message 시 `term()`을 선택한다.
- `capacity`와 push pending message/byte limit을 유한하게 설정한다. 실제 pending drop은
  `NatsConsumerFlowException`으로 관찰하며 성공으로 축소하지 않는다.
- 같은 Flow instance의 동시 collect는 provider가 거부한다. 순차 collect는 새
  subscription/iterable consumer를 만들고 취소 시 adapter 소유 자원만 정리한다.
- 예제는 raw payload, credential, connection URL을 로그나 예외에 새로 추가하지 않는다.

## 테스트

- Pull의 durable `ConsumerContext`는 caller가 먼저 만들지만 collect 전에는 `iterate()`와
  adapter-owned `IterableConsumer`가 생성되지 않는다. Push는 collect 전 subscription을 만들지 않는다.
- Pull은 순서대로 수집하고 caller `ack()` 후 `ConsumerInfo.numAckPending == 0`을 bounded polling한다.
- Pull의 최초 미승인 message는 `nak()` 또는 ack wait 뒤 재전달되고, 두 번째 delivery는
  `ack()`한다.
- `term()` 처리한 message는 bounded `withTimeoutOrNull { flow.first() }`로 후속 delivery가 없음을 직접 판정한다.
- Push는 유한 pending limit과 capacity로 순서를 유지하며 caller가 승인한다.
- 느린 collector와 작은 pending limit에서 실제 drop을 발생시켜
  `NatsConsumerFlowException.droppedMessages > 0`을 확인한다. Drop이 발생하지 않으면 명시적 timeout 뒤
  collector를 cancel/join하고 재현 실패로 종료한다.
- 수집 취소 뒤 동일 Flow를 순차 재수집해 adapter cleanup과 lifecycle 재사용을 검증한다.
- 모든 live collection은 per-test timeout 안에서 `take`/`first`로 종료하며, background collector는 성공과 실패
  모두 `finally`에서 `cancelAndJoin()`한다. Connection은 `use`로 닫고 무한 `collect`/`toList`를 사용하지 않는다.
- Cold/lifecycle 단위 회귀는 pull `iterate()`와 push `subscribe()`의 pre-collect 0회, cancel 뒤 새 handle 생성과
  이전 handle cleanup을 검증한다. Live ack/nak/term 회귀는 consumer info와 delivery count를 함께 확인한다.

## 의존성·운영

- 신규 module을 settings auto-registration, root README pair, coverage matrix,
  `Examples.yml`의 push/pull_request path, container task와 report artifact,
  `scripts/smoke-validate.sh` messaging group/required-module/stale structural guard에 등록한다.
- Stable `NatsServer.Launcher`의 `-js` JetStream fixture를 사용한다. 공유 container에서 test별 고유 stream,
  subject, durable 이름을 사용하고 Docker-backed test는 `--max-workers=1`로 직렬 실행한다.
- module README pair에 bounded memory 식, ack 결정표, cancellation ownership과 실행 명령을
  기록한다.

## 제외

- 실제 운영 NATS cluster, authentication/TLS, stream migration, cross-region mirror,
  exactly-once 처리, durable offset 외부 저장소, Spring integration은 포함하지 않는다.

## 완료 조건

- 설계·구현 리뷰 P0/P1 0건
- RED 회귀 후 clean module test, detekt, messaging/full/stale/ecosystem/README/actionlint/diff 검증 통과
- dependency insight에서 `bluetape4k-nats:2.0.0`과 stable BOM 확인
- PR exact-head hosted CI와 metadata 확인 후 다섯 PR 전체에 대한 최종 병합 승인 요청
