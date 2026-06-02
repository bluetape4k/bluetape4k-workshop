# Spring Data Redis - Streams Examples

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Data Redis - Streams Examples** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Spring Data Redis - Streams Examples Graphviz architecture diagram](../../../../../../../../../../docs/images/readme-diagrams/spring-data-redis-examples-src-main-kotlin-io-bluetape4k-workshop-redis-stream-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.springdata` as the source of truth when comparing this README with the code.

## Flow Diagram

1. Prepare the local runtime required by `Spring Data Redis - Streams Examples`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

The [Redis Stream](https://redis.io/topics/streams-intro) is a new data type introduced with Redis 5.0 modelling log
data structure.
Spring Data Redis supports _Redis Streams_ via both the imperative and the reactive API.

## Imperative API

**Basic Usage**

```java

@Autowired
RedisTemplate template;

StringRecord record = StreamRecords.string(…)
        .withStreamKey("my-stream");
RecordId id = template.streamOps().add(record);

List<...>records=template.

streamOps().

read(count(2),from(id));
```

**ContinuousRead Read**

```java

@Autowired
RedisConnectionFactory factory;

StreamListener<String, MapRecord<…>listener=
        (msg)->{
        // ...
        };

StreamMessageListenerContainer container = StreamMessageListenerContainer.create(factory));

        container.

receive(StreamOffset.fromStart("my-stream"),listener);
```

## Reactive API

**Basic Usage**

```java

@Autowired
ReactiveRedisTemplate template;

StringRecord record = StreamRecords.string(…)
        .withStreamKey("my-stream");
Mono<RecordId> id = template.streamOps().add(record);

Flux<...>records=template.

streamOps().

read(count(2),from(id));
```

**ContinuousRead Read**

```java

@Autowired
ReactiveRedisConnectionFactory factory;

StreamReceiver receiver = StreamReceiver.create(factory));

        container.

receive(StreamOffset.fromStart("my-stream"))
        .

doOnNext((msg)->{
        // ...
        })
        .

subscribe();
```
