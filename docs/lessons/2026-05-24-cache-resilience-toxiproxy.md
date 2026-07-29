# Lessons: ToxiproxyServer를 사용한 cache-resilience module

**Date**: 2026-05-24  
**Module**: `spring-boot/cache-resilience`  
**PR**: #187

## 근본 원인 / 배경

Issue #84는 "Redis failure paths + CircuitBreaker fallback"을 포함한 Spring Boot
Advanced 예제를 요청했다. 목표는 Redis를 mocking하지 않고 실제 network failure로
구동되는 전체 CB state machine(CLOSED→OPEN→HALF-OPEN→CLOSED)을 보여주는 것이었다.

## 주요 결정

### 1. chaos injection에 ToxiproxyServer 사용

`bluetape4k-testcontainers`는 Shopify Toxiproxy container를 감싼
`ToxiproxyServer`를 제공한다. proxy는 테스트의 Lettuce client와 Docker 내부
Redis server 사이에 위치한다.

```
Lettuce → toxiproxy (host:proxyPort) → redis (docker-network:6379)
```

모든 container는 공유 `Network.newNetwork()`에 참여하므로 Redis는 Docker alias
`redis`로 접근할 수 있다.

### 2. `limitData(0)` vs `timeout(1ms)` — one-shot 함정

**첫 시도**: `proxy.toxics().limitData("cut", ToxicDirection.DOWNSTREAM, 0)`
**문제**: `limitData`는 **one-shot toxic**이다. 한 번 발동한 뒤(0 byte 통과 후)
자동 제거된다. 첫 connection drop 이후 Lettuce가 reconnect하면 toxic은 이미
사라져 있어 이후 호출이 성공한다. CB는 충분한 failure를 누적하지 못했다.

**수정**: `proxy.toxics().timeout("drop-connections", ToxicDirection.UPSTREAM, 1)`을
사용한다. 이는 새 connection을 1ms 뒤마다 닫는 persistent toxic이다. CB는 모든
reconnect 시도에서 반복 failure를 관측한다.

### 3. Lettuce command timeout은 명시적으로 설정해야 한다

Lettuce의 기본 command timeout은 **60초**다. 4 × 60초 = 4분을 기다리므로 테스트가
느렸고, 결국 `limitData`가 one-shot이어서 잘못된 CB state가 만들어졌다.

`timeout(1ms)` toxic으로 전환한 뒤에도 Lettuce의 60초 commandTimeout이 상한으로
작동해 각 호출이 약 3초씩 걸렸다. 짧은 `commandTimeout`을 설정한다.

```kotlin
val clientConfig = LettuceClientConfiguration.builder()
    .commandTimeout(Duration.ofSeconds(3))
    .build()
factory = LettuceConnectionFactory(connectionConfig, clientConfig)
```

결과적으로 failure-injection 구간은 4 × 3초 = 12초가 되었고, 전체 테스트 시간은
41초가 되었다.

### 4. 이 toxiproxy-java version에는 `Proxy.setEnabled(false)`가 없다

`proxy.setEnabled(false)`를 사용하려 하자 compile error가 발생했다. 이 method는
`org.testcontainers:testcontainers-toxiproxy:2.0.5`에 포함된
`eu.rekawek.toxiproxy:toxiproxy-java` version에서 사용할 수 없다. 대신 toxics
API(`timeout`, `bandwidth`, `limitData`)를 사용한다.

### 5. `ToxiproxyServer.withNetwork()`는 `ToxiproxyServer`가 아니라 `ToxiproxyContainer`를 반환한다

builder chaining(`ToxiproxyServer().withNetwork(network)`)은 parent type을 반환한다.
`also {}`를 사용한다.

```kotlin
toxiproxyServer = ToxiproxyServer().also {
    it.withNetwork(network)
}
```

### 6. 2.x의 `testcontainers-toxiproxy` module name

testcontainers 2.x에서 module은 `org.testcontainers:toxiproxy`에서
`org.testcontainers:testcontainers-toxiproxy`로 이름이 바뀌었다. catalog가 아니라
명시적으로 선언한다.

```kotlin
testImplementation("org.testcontainers:testcontainers-toxiproxy") {
    version { require(libs.versions.testcontainers.get()) }
}
```

## 결과 / 검증

- 4개 테스트가 41초 안에 통과:
  - happy path(CB CLOSED, Redis read)
  - failure injection(timeout toxic → CB OPEN → Caffeine fallback)
  - recovery(remove toxic → CB HALF-OPEN → CLOSED)
  - cache miss(null returned)
- Architecture diagram(SVG + PNG) 커밋
- Used Bluetape4k Features 표가 포함된 README.md + README.ko.md

## 향후 지침

- Toxiproxy toxics를 사용할 때 CB failure injection test에는 one-shot toxic
  (`limitData`)보다 **persistent** toxic(`timeout`, `bandwidth`)을 우선한다.
- 테스트에서 사용하는 Lettuce client에는 항상 짧은 `commandTimeout`을 설정한다.
  기본 60초는 chaos test를 매우 느리게 만든다.
- `bluetape4k-testcontainers`가 이를 `compileOnly`로 선언하므로 `ToxiproxyServer`
  사용 시 `testcontainers-toxiproxy`(2.x 이름)를 명시적 test dependency로 추가해야 한다.
- enable/disable method를 쓰기 전에 `toxiproxy-java` API version을 확인하고, 가능하면
  toxics API를 사용한다.
