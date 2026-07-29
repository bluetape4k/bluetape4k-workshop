# Issue #322 - DDD 감사 워크숍 설계 주문

## 문맥

`bluetape4k-workshop` 마일스톤 `1.3.1`에 문제가 포함됨
[#322](https://github.com/bluetape4k/bluetape4k-workshop/issues/322),
다음을 결합한 학습자 대상 사례를 요구합니다.

- DDD 명령 방법을 집계합니다.
- Spring Modulith 도메인 이벤트 게시.
- JaVers 기록 및 비교 쿼리를 집계합니다.
- 도메인 이벤트, 모듈리스 이벤트, 아웃박스,
  JaVers 함께 감사합니다.

현재 저장소 증거에 따르면 해당 성분은 이미 별도의 존재로 존재합니다.
예:

- `spring-modulith/events-deep-dive`은 Spring 애플리케이션 이벤트를 가르치고,
  트랜잭션 리스너 및 모듈 경계.
- `spring-modulith/jpa-demo`은 다음을 사용하여 Spring Modulith 모듈 캡슐화를 가르칩니다.
  JPA.
- `exposed/javers-persistence-audit`은 Redis를 사용하여 JaVers 감사 내역을 가르칩니다.
  지속성 저장소 및 Exposed 현재 행 지속성.

누락된 학습 경계는 집계된 단일 서비스 흐름입니다.
명령이 상태를 변경하고, 감사 기록이 커밋되고, Spring Modulith 리스너가 있습니다.
출판 레지스트리를 통해 반응합니다.

## 현재 증거

- 실시간 이슈 #322가 열려 있고 `debop`에 할당되었으며 마일스톤에 첨부되었습니다.
  `1.3.1`.
- `settings.gradle.kts`은 `spring-modulith/` 아래의 하위 모듈을 자동 등록합니다.
  그래서 `spring-modulith/ddd-order-audit`은
  `:spring-modulith-ddd-order-audit`.
- `./gradlew :spring-modulith-events-deep-dive:test --console=plain --no-daemon`
  설계 작업 전 10번의 테스트를 거쳐 합격하였습니다.
- 공식 Spring Modulith 문서에 설명되어 있습니다.
  `@ApplicationModuleListener`은 트랜잭션 비동기 리스너로서
  이벤트 게시 레지스트리.
- 공식 JaVers 문서에서는 워크숍에서 `commit`을 사용할 수 있음을 확인합니다.
  감사 및 차이점 동작에 대한 `findSnapshots`, `findChanges` 및 `compare`.
- `bluetape4k-javers`은(는) 이미 재사용 가능한 DDD 도우미를 제공합니다.
  `AggregateRoot`, `DomainEvent`, `AggregateRepository` 및
  `SpringApplicationEventDomainEventPublisher`.

## 승인된 방향

새 `spring-modulith/ddd-order-audit` 모듈을 만듭니다.

사용자가 한 번의 변경으로 권장 방향을 승인했습니다: PostgreSQL 사용
H2 대신. 따라서 기본 테스트 경로는 다음을 사용합니다.
`bluetape4k-testcontainers` PostgreSQL 인프라이며 다음에서 실행되어야 합니다.
H2 연기 차선이 아닌 컨테이너 지원 차선입니다.

## 건축학

모듈은 주문 승인 워크플로를 모델링합니다.

1. `OrderCommandService`은 `PlaceOrder`과 같은 명령을 받습니다.
   `ApproveOrder`.
2. `Order` 집계는 명시적 명령 방법을 통해 불변성을 적용합니다.
3. `OrderRepository`은 PostgreSQL에 집계를 유지합니다.
4. 명령 서비스는 도메인 이벤트를 동일한 내부의 Spring Modulith에 전달합니다.
   트랜잭션이므로 게시 레지스트리 행이 주문 행과 함께 커밋됩니다.
5. `FulfillmentReservationHandler`은 `OrderApproved`를 처리합니다.
   `@ApplicationModuleListener` 트랜잭션이 커밋된 후.
6. `OrderAuditService`은 다음 후에만 집계 상태를 JaVers에 커밋합니다.
   선택한 JaVers 저장소가 아닐 때 성공적인 트랜잭션 커밋
   PostgreSQL에 트랜잭션 방식으로 결합됩니다.
7. 쿼리 코드는 집계에 대한 JaVers 스냅샷, 변경 사항 및 차이점을 읽습니다.

모듈은 PostgreSQL Testcontainers을 진실 데이터베이스로 사용합니다.
애플리케이션 상태 및 Spring Modulith 게시 행에 대한 것입니다. JaVers은(는)
구현 검색이 입증되지 않는 한 기본 인메모리 저장소
PostgreSQL 지원 JaVers 경로는 현재 BOM를 통해 이미 사용 가능하며
불필요한 설정을 추가하지 않습니다. 이를 통해 수업은 사건에 집중하고
실제 서비스 경로에 대해 트랜잭션 서비스 경로를 입증하는 동시에 경계를 감사합니다.
PostgreSQL 데이터베이스.

## 디자인 대안

### 옵션 A - PostgreSQL + 인메모리 JaVers + 모듈리스 게시

이것이 선택된 접근 방식입니다.

장점:

- PostgreSQL에 대한 서비스 트랜잭션 및 게시 행을 증명합니다.
- JaVers 스냅샷 및 차이점을 통해 감사 API를 쉽게 검사할 수 있도록 유지합니다.
- 로컬 DDD 프레임워크를 만드는 대신 `javers-ddd` 도우미를 재사용합니다.
- 단일 워크숍 모듈과 단일 학습자 여정에 적합합니다.

단점:

- JaVers 스냅샷 스토리지 자체는 기본적으로 PostgreSQL 지원되지 않습니다.
- 감사 쓰기는 커밋 이후에 수행되므로 결과적으로는
  옵션 B가 구현 중에 충분히 단순하다고 입증되지 않는 한 주문 거래.
- 컨테이너 지원 테스트는 더 무겁기 때문에 전체 레인에 등록해야 합니다.

### 옵션 B - PostgreSQL + PostgreSQL 지원 JaVers 저장소

장점:

- 응용 프로그램 행, 게시 행 및 감사를 위한 하나의 물리적 데이터베이스를 제공합니다.
  스냅샷.

단점:

- 더 많은 저장소 설정이 필요하며 수업을 JaVers으로 전환할 위험이 있습니다.
  지속성 배관.
- 기존 지속성별 감사 예시와 겹칩니다.
- 현재 BOM이 단순하고 안정적인 JaVers을 노출하는 경우에만 선택해야 합니다.
  DDD/Modulith에 방해가 되지 않는 SQL/Exposed 저장소 경로
  수업.

### 옵션 C - Redis 지원 JaVers(예: `exposed/javers-persistence-audit`)

장점:

- 기존 저장소 패턴으로 내구성 있는 JaVers 기록을 보여줍니다.

단점:

- Redis 감사 강의를 복제합니다.
- 더 많은 Testcontainers이 필요하고 매장 간 감사에 대한 모듈을 만듭니다.
  DDD + 모듈리스 통합 대신 실패 경계.

## 모듈 형태

예상 파일:

- `spring-modulith/ddd-order-audit/build.gradle.kts`
- `spring-modulith/ddd-order-audit/README.md`
- `spring-modulith/ddd-order-audit/README.ko.md`
- `spring-modulith/ddd-order-audit/src/main/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/`
- `spring-modulith/ddd-order-audit/src/test/kotlin/io/bluetape4k/workshop/spring/modulith/ddd/audit/`
- `spring-modulith/ddd-order-audit/src/test/resources/junit-platform.properties`
- `spring-modulith/ddd-order-audit/src/test/resources/logback-test.xml`
- README 다이어그램 SVG/PNG `docs/images/readme-diagrams/` 아래 자산

예상 패키지 접두사:

```text
io.bluetape4k.workshop.spring.modulith.ddd.audit
```

## 도메인 모델

도메인은 작게 유지되어야 합니다.

- `OrderId`, `CustomerId` 및 `Money` 값 개체입니다.
- `OrderStatus`: `PLACED`, `APPROVED`, `CANCELLED`.
- `OrderLine` 및 `Order` 집계.
- 명령: `PlaceOrderCommand`, `ApproveOrderCommand`.
- 이벤트: `OrderPlaced`, `OrderApproved`.

집계 규칙:

- 주문에는 라인이 하나 이상 있어야 합니다.
- 수량과 단가는 양수여야 합니다.
- `PLACED` 주문만 승인될 수 있습니다.
- 취소된 주문은 승인될 수 없습니다.
- 명령은 상태를 변경하는 대신 새로운 집계 인스턴스를 반환해야 합니다.
- 반복 또는 동시 승인은 낙관적 잠금을 통해 안전해야 하며,
  멱등성 명령 처리 또는 키를 사용하는 고유한 이행 예약
  `orderId`; 구현 계획은 하나를 선택해야 합니다.

지속 계약이 되는 모든 공개 도메인 유형에는 영어 KDoc이 포함되어야 합니다.
Value/data 클래스는 `Serializable`을 구현하고 `serialVersionUID`을 정의해야 합니다.

## 지속성과 트랜잭션 경계

구현에서는 애플리케이션 상태로 Spring Data JPA를 선호해야 합니다.
기존 Spring Modulith 예제는 이미 Spring Boot/JPA을 사용하고 있기 때문입니다.
path와 Spring Modulith의 이벤트 게시 레지스트리가 자연스럽게 통합됩니다.
봄 트랜잭션.

PostgreSQL Testcontainers은 기본 테스트에 필수입니다. 구현
다음과 같은 bluetape4k Testcontainers 도우미를 사용해야 합니다.
`PostgreSQLServer.Launcher.postgres`(현재 종속성에서 사용 가능한 경우)
경계. 정확한 도우미 이름이 다른 경우 구현 시 검사해야 합니다.
`bluetape4k-testcontainers` 선택한 도우미를 계획에 기록하고 DoD
증거. 원시 `GenericContainer`은(는) 허용되지 않습니다.

명령 트랜잭션은 주문 행을 유지하고 Spring을 등록해야 합니다.
모듈리스 게시 행은 원자 단위로 수행됩니다. 이후에만 도메인 이벤트 게시
트랜잭션 커밋은 충돌을 초래할 수 있으므로 이 강의에서는 충분하지 않습니다.
주문 행은 있지만 게시 행은 없는 창입니다. 경청자
실행은 `@ApplicationModuleListener`을 통해 커밋 후에도 유지됩니다.

테스트는 PostgreSQL 스키마 상태, Spring Modulith 게시 행,
이행 행, 리스너 실패 토글 및 인메모리 JaVers 상태
사례. Testcontainers은(는) 공유 런처를 사용할 수 있지만 테스트 데이터가 유출되어서는 안 됩니다.
테스트 방법 전반에 걸쳐.

## JaVers 경계

모듈은 `bluetape4k-javers` DDD 도우미를 적합한 위치에 재사용해야 합니다.

- 감사된 집계 계약의 경우 `AggregateRoot`입니다.
- 이벤트 메타데이터의 경우 `DomainEvent`입니다.
- `AggregateRepository` 저장 + JaVers 커밋 + 이벤트 게시인 경우에만 해당
  주문은 선택한 거래 경계와 호환됩니다.
- `SpringApplicationEventDomainEventPublisher` 이벤트를 전달할 수 있는 경우에만
  원자 출판 행 등록을 약화시키지 않는 스프링 모듈리스.

구현 시 해당 도우미를 워크숍 모듈에 복사해서는 안 됩니다.
종속성 해결은 현재를 통해 아티팩트를 사용할 수 없음을 증명합니다.
루트 BOM. 대체가 필요한 경우 사전에 계획에서 설명해야 합니다.
구현.

모듈이 인메모리 JaVers을 사용하는 경우 감사 커밋은 다음 이후에만 실행되어야 합니다.
PostgreSQL 트랜잭션 커밋 및 롤백 테스트가 실패했음을 입증해야 합니다.
명령은 오해의 소지가 있는 JaVers 스냅샷이나 차이점을 남기지 않습니다. 구현하는 경우
검색을 통해 간단한 PostgreSQL 지원 JaVers 저장소를 사용할 수 있음이 입증되었습니다.
대신 계획은 트랜잭션과 결합된 감사 경로를 선택하고 다음을 기록할 수 있습니다.
추가 설치 비용.

Audit/event 페이로드에는 안전한 샘플 데이터(합성 식별자,
상태, 라인 수량 및 금액. 이벤트에는 집계 식별자가 있어야 합니다.
전체 집계 본문이 아닌 최소한의 메타데이터입니다. 로그 및 예외 메시지는 다음과 같아야 합니다.
`orderId` 또는 출판 ID와 같은 안전한 상관 관계 필드를 사용하고 덤프해서는 안 됩니다.
직렬화된 이벤트 본문, 스냅샷 또는 JaVers diff.

## 스프링 계수 경계

모듈에는 최소한 두 개의 논리 모듈이 포함됩니다.

- `orders`: 집계, 명령 서비스, 감사 저장소, JaVers 쿼리 서비스.
- `fulfillment`: `OrderApproved`에 대한 `@ApplicationModuleListener`.

리스너는 게시 레지스트리 동작을 보여야 합니다.

- 성공적인 승인은 결국 이행 예약을 생성합니다.
- 리스너 실패로 인해 검사할 수 있는 incomplete/failed 게시가 남습니다.
- 게시에 실패한 경우 결정적 테스트 경로를 통해 다시 제출할 수 있습니다.
  현재 Spring Modulith API는 하나를 노출합니다.
- 커밋 전 롤백에는 주문 행, 게시 행, 이행 행이 남지 않습니다.
  리스너 부작용 또는 JaVers 스냅샷.

## 문서 및 다이어그램

README 파일은 학습자용이어야 합니다.

- `README.md` 영어로.
- `README.ko.md` 등가의 소스 내용을 자연스러운 한국어로 제공합니다.
- 제목 바로 아래에서 언어를 전환하세요.
- 유효성 검사 명령 및 PostgreSQL/Testcontainers 전제 조건.
- Testcontainers 자격 증명 및 JDBC URL은 임시 테스트 전용 값입니다. 아니요
  프로덕션 자격 증명은 README/config에 속하며 JDBC URL은
  비밀번호로 기록됩니다.
- 설명 테이블 비교:
  - 도메인 이벤트,
  - 스프링 모듈리스 출판물,
  - 거래 발신함,
  - JaVers 감사합니다.

다이어그램은 필수입니다.

- 아키텍처 다이어그램: 집계 명령, PostgreSQL, JaVers, 게시
  레지스트리, 이행 리스너.
- 시퀀스 다이어그램: place/approve 순서, 트랜잭션 내 게시 행
  등록, 트랜잭션 커밋, 커밋 후 리스너 실행, 감사 쿼리.

모든 다이어그램 자산은 생성된 전체 `bluetape4k-diagram` 체크리스트를 통과해야 합니다.
SVG/PNG 검증 및 전체 크기 PNG 육안 검사. 스크립트 PASS만으로는 충분하지 않습니다.
증거는 충분합니다.

## CI 및 등록

새 모듈에는 등록 업데이트가 필요합니다.

- 루트 `README.md` 및 `README.ko.md`.
- `AGENTS.md` 모듈 설명의 범위를 좁혀야 하는 경우 모듈 목록입니다.
- `.github/workflows/Examples.yml` 경로 필터.
- 컨테이너 지원 예제 워크플로 명령 및 아티팩트
- `scripts/smoke-validate.sh` container/full 차선.
- `scripts/smoke-validate.sh stale-check` 예상 프로젝트 수.
- 새 architecture/sequence SVG 이름이 다음과 같은 경우 다이어그램 유효성 검사기 허용 목록
  엄격한 검증자에 포함됩니다.

PostgreSQL Testcontainers이 기본 경로에 있으므로 테스트를 실행해야 합니다.
다른 컨테이너 지원 테스트와 순차적으로 수행됩니다.

## 테스트 계획

필수 테스트:

- 집계 불변은 잘못된 행과 잘못된 상태 전환을 거부합니다.
- 주문을 하면 집계가 유지되고 JaVers 스냅샷이 기록됩니다.
- 주문을 승인하면 두 번째 스냅샷이 기록되고 유용한 차이점이 표시됩니다.
- 승인은 `OrderApproved` 게시 행을 주문에 등록합니다.
  트랜잭션이 있는 경우 리스너 부작용은 트랜잭션 커밋 후에만 실행됩니다.
- 이행 수신기는 성공적인 게시에 대한 예약을 생성합니다.
- 시뮬레이션된 리스너 실패로 인해 incomplete/failed 모듈리스 출판물이 남음
  API에서 지원하는 경우 결정적 경로를 통해 다시 제출될 수 있습니다.
- 커밋 전 롤백은 게시 행 지속성, 이행 측면을 방지합니다.
  효과 및 리스너 실행.
- 명령 경로가 실패한 후 롤백하면 주문 행, 게시 행,
  이행 예약 또는 실패한 명령의 경우 JaVers snapshot/diff입니다.
- 반복 또는 동시 승인은 중복 이행을 생성하지 않습니다.
  예약 또는 중복 유효 승인.
- 제한된 승인 루프는 예상치 못한 게시 백로그가 없는지 확인하고
  고정된 시간 초과 내에서 리스너 폴링.

구현 예정인 검증 명령:

```bash
./gradlew :spring-modulith-ddd-order-audit:compileKotlin :spring-modulith-ddd-order-audit:compileTestKotlin --warning-mode all --console=plain
./gradlew :spring-modulith-ddd-order-audit:test --warning-mode all --console=plain --max-workers=1
./gradlew projects --console=plain
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh data-access-full
./scripts/smoke-validate.sh diagram-qa
actionlint .github/workflows/Examples.yml
git diff --check
```

## 위험 및 완화

| 위험 | 완화 |
|---|---|
| PostgreSQL Testcontainers 비용 증가 CI | 모듈을 컨테이너 지원 레인에 배치하고 테스트에 집중하세요. |
| JaVers 끈질긴 배관이 수업을 방해합니다 | 안정적인 PostgreSQL 지원 경로가 현재 BOM을 통해 단순하지 않은 한 커밋 후 인메모리 JaVers을 사용하세요. 최종 감사 일관성을 문서화합니다. |
| 이벤트 게시 시기가 불안정함 | 테스트에서 고정된 시간 제한 및 폴링 간격 값과 함께 형제 예제에서 이미 사용된 Spring 모듈리스 테스트 API 및 Awaitility 스타일의 제한된 어설션을 사용하세요. |
| 원시 컨테이너 설정이 bluetape4k 규칙을 우회합니다 | `bluetape4k-testcontainers` PostgreSQL 도우미를 사용하세요. DoD에 도우미를 문서화하세요. |
| 다이어그램 회귀는 이전 누락을 반복합니다 | 모든 다이어그램 좌표 변경 후 렌더링된 PNG 육안 검사를 필수 최종 증거로 처리합니다. |
| `exposed-workshop`을 사용한 범위 중복 | 이 모듈은 Exposed DDD 저장소 메커니즘이 아닌 bluetape4k-workshop의 Spring Modulith + JaVers 통합에 중점을 둡니다. |
| 감사 데이터로 인해 민감한 페이로드가 유출됨 | 샘플 페이로드를 종합적으로 유지하고, 최소한의 이벤트를 게시하고, 오류 진단에서 logs/diffs를 수정합니다. |

## 수락 기준

- 새로운 `:spring-modulith-ddd-order-audit` Gradle 모듈이 발견되었습니다.
- 기본 테스트에서는 bluetape4k 도우미를 통해 PostgreSQL Testcontainers을 사용합니다.
  하부 구조.
- 모듈은 루트 `bluetape4k-dependencies` BOM만 사용합니다. 새로운 bluetape4k 별칭은
  버전을 고정하지 마세요.
- 테스트에서는 집계 불변성, 이벤트 게시, 롤백 동작 및
  JaVers history/diff 쿼리입니다.
- 롤백 테스트에서는 주문 행, 게시 행, 이행 행 또는 JaVers이 없음을 증명합니다.
  snapshot/diff은 실패한 명령에 대해 남아 있습니다.
- 이벤트 게시 행 등록은 주문 행과 트랜잭션 방식으로 결합됩니다.
  PostgreSQL에서; 리스너 실행은 커밋 후에 수행됩니다.
- README.md 및 README.ko.md에는 다이어그램, 유효성 검사 명령 및
  domain-events/Modulith/outbox/JaVers 비교.
- CI/smoke 등록에는 올바른 컨테이너 기반의 새 모듈이 포함됩니다.
  레인.
- 다이어그램은 `bluetape4k-diagram` 체크리스트와 전체 크기 PNG 육안 검사를 통과합니다.
- PR 메타데이터 미러 이슈 #322 및 최종 PR 본문이 `## DoD Status`으로 끝납니다.
