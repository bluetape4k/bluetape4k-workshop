# Issue #555 Kafka 브로커 경로 복구 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` for task-by-task execution. Steps use checkbox syntax for tracking.

**목표:** 실제 Kafka 브로커 TCP 경로 중단이 미터 발신 편지함 행을 보존하고 경로가 복원된 후 전달을 복구한다는 야간 Testcontainers 증거를 추가합니다.

**아키텍처:** Toxiproxy 컨테이너는 Kafka 사용자 정의 리스너 앞에 있습니다. Kafka 광고 리스너는 프록시 매핑 포트를 다시 가리키므로 호스트-JVM Spring Kafka 클라이언트는 메타데이터 새로 고침 후에도 프록시 경로에 남아 있습니다. 기존 장애 스위치는 결정론적 테스트 경계선으로 남아 있습니다.

**기술 스택:** Kotlin, Spring Boot, Kafka 클라이언트, Testcontainers Kafka 2.0.5, Testcontainers Toxiproxy 2.0.5, PostgreSQL Testcontainers, JUnit 5, Awaitility.

---

### 작업 1: 프록시 종속성 및 고정 장치 토폴로지 선언

**파일:**

- 수정: `gradle/libs.versions.toml`
- 수정: `commerce/usage-billing-microservices-composition-tests/build.gradle.kts`
- 수정: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/io/bluetape4k/workshop/commerce/usagebilling/composition/fixture/UsageBillingMicroserviceFixture.kt`

- [ ] 기존 Testcontainers BOM에 의해 확인된 버전이 없는 `org.testcontainers:testcontainers-toxiproxy` 별칭을 추가합니다.
- [ ] 컴포지션 테스트 런타임에 별칭을 추가합니다.
- [ ] 공유 `Network`을 시작하고, Toxiproxy를 시작하고, `kafka:19092 -> proxy:8666`을 생성하고, 광고된 공급업체가 프록시 host/mapped 포트를 반환하는 사용자 정의 리스너로 Kafka를 등록하는 옵트인 프록시 모드를 추가합니다.
- [ ] 모든 Spring 컨텍스트에 프록시 모드에서만 프록시 부트스트랩 엔드포인트를 제공합니다. 그렇지 않으면 `kafka.bootstrapServers`을 유지하세요.
- [ ] 컨텍스트를 먼저 닫은 다음 컨테이너를 닫고 네트워크를 닫습니다. 안전한 청소 경로에서 두 가지 독성 방향을 모두 제거하십시오.

### 과제 2: 구현 전 실패를 입증하고 복구

**파일:**

- 생성: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/io/bluetape4k/workshop/commerce/usagebilling/composition/BrokerPathRecoveryIntegrationTest.kt`

- [ ] 브로커 경로 모드에서 고정 장치를 생성하고, 가격을 활성화하고, 프록시를 인하하고, 미터 아웃박스 작업을 게시하고, `retryWait == 1`에 하나의 백로그 행을 추가하는 태그된 통합 테스트를 작성합니다.
- [ ] 프록시 동작이 존재하기 전에 정확한 테스트를 실행하십시오. 예상되는 결과는 브로커 경로 고정 장치 API가 존재하지 않기 때문에 컴파일 실패입니다.
- [ ] 테스트에 필요한 고정 장치 API만 구현합니다(프록시로 생성, 경로 절단, 경로 복원).
- [ ] 정확한 테스트를 다시 실행하세요. 컷하는 동안 재시도 상태를 관찰하고, 두 가지 유해 요소를 모두 제거하고, 동일한 행을 재시도하고, 복구 후 동일한 사용 가격 증거를 기다려야 합니다.

### 작업 3: 야간 전용 동작 등록 및 문서화

**파일:**

- 수정: `.github/workflows/nightly.yml`
- 수정: `commerce/usage-billing-microservices-composition-tests/README.md`
- 수정: `commerce/usage-billing-microservices-composition-tests/README.ko.md`

- [ ] `integration`을 제외하고 `test`을 유지합니다. 현재 야간 `integrationTest` 호출을 유지하고 `BrokerPathRecoveryIntegrationTest`에 대한 대상 결과 파일 어설션을 추가합니다.
- [ ] 두 개의 보완적인 실패 레인, proxy/advertised-listener 불변 및 명시적인 단일 브로커 비클레임을 문서화합니다.
- [ ] 상응하는 한국어 문서를 추가합니다.

### 작업 4: 변경된 동작 및 저장소 가드 확인

- [ ] `cleanIntegrationTest --no-build-cache`을 사용하여 완전히 새로운 통합 테스트를 실행하세요.
- [ ] 전체 구성 `integrationTest`, 해당 `test` 및 `koverXmlReport`를 순차적으로 실행합니다.
- [ ] `detektTest`, README 유효성 검사기, `actionlint`, `git diff --check` 및 좁은 야간 YAML/result-path 검토를 실행합니다.
- [ ] diff에 프록시 모드의 직접 브로커 부트스트랩 속성이 없고 문서에서 이 클러스터 장애 조치를 호출하지 않는지 확인합니다.

## 위험과 회복

| 위험 | 신호 | 완화/재실행 지점 |
| --- | --- | --- |
| 메타데이터가 프록시를 우회함 | cut은 `RETRY_WAIT`을 산출하지 않습니다 | 테스트를 재시도하기 전에 Kafka 사용자 정의 광고 수신기 공급자 및 프록시 부트스트랩 속성을 확인하십시오. |
| 테스트 간 독성 청소 누출 | 나중에 통합 테스트를 게시할 수 없습니다 | `finally`에서 upstream/downstream 독성 물질을 제거합니다. 테스트마다 새로운 픽스쳐 생성 |
| 호스트 포트는 CI에서 다릅니다 | 컨텍스트를 시작하거나 연결할 수 없습니다. | `toxiproxy.host` 및 `getMappedPort`에서만 엔드포인트를 파생하며 고정된 호스트 포트는 사용하지 않음 |
| 테스트 타이밍이 결함을 가리다 | 컷 중에 게시 성공 | 복원 전에 재시도 상태와 백로그를 검증문합니다. 대신에 더 긴 대기 시간을 사용하지 마십시오 |
