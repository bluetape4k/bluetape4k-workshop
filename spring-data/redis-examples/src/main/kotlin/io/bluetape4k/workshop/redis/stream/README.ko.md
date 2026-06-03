# Spring Data Redis - Streams Examples

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Data Redis - Streams Examples**를 실행 가능한 Spring Data 영속성 워크숍 조각으로 다룹니다. 개발자가 가장 먼저 확인하는 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 아키텍처 다이어그램

![Spring Data Redis - Streams Examples Graphviz architecture diagram](../../../../../../../../../../docs/images/readme-diagrams/spring-data-redis-examples-src-main-kotlin-io-bluetape4k-workshop-redis-stream-readme-architecture-01.png)

이 모듈은 샘플 진입점 또는 테스트 픽스처, bluetape4k 확장 계층, 예제가 사용하는 런타임 의존성을 중심으로 구성됩니다. README와 코드를 비교할 때는 `io.bluetape4k.workshop.springdata` 패키지를 기준으로 삼습니다.

## 흐름 다이어그램

1. `Spring Data Redis - Streams Examples`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여 주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

[Redis Stream](https://redis.io/topics/streams-intro)은 Redis 5.0에서 도입된 새 데이터 타입으로, 로그 데이터 구조를 모델링합니다. Spring Data Redis는 명령형 API와 반응형 API 양쪽에서 _Redis Streams_를 지원합니다.

## 명령형 API

**기본 사용법**

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

## 반응형 API

**기본 사용법**

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
