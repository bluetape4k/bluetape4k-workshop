# Issue #522 고경합 profile 구현 교훈

## Context

Job Console과 Concert Ticket 예제는 각각 PostgreSQL authority, lease/fencing,
deduplication, recovery 경계를 이미 가지고 있었다. 하지만 일반 unit/integration test만으로는
burst, step, retry storm이 겹치거나 Redis 경로가 끊긴 상황에서 실제 production adapter가 같은
불변식을 지키는지 한 형식으로 비교하기 어려웠다.

#522는 새 generic benchmark module을 추가하지 않고 기존 모듈의 production boundary를 그대로
호출하는 Java 25 고경합 profile suite를 만들었다. `ci-correctness`는 정확성 게이트이고,
`local-reference`는 같은 schedule에서 얻은 환경별 관찰값이다. 이 관찰값으로 framework 순위나
운영 용량을 주장하지 않는다.

## Decision or Finding

### Redis 장애는 기존 connection과 새 connection을 모두 끊어야 한다

한 번 연결된 client socket만 끊으면 connection pool이 새 socket을 열어 proxy를 우회하거나,
반대로 신규 연결만 막으면 기존 keep-alive connection으로 계속 성공할 수 있다. Ticket profile은
Redis client가 실제로 사용하는 Toxiproxy endpoint를 주입하고 양방향 경로를 내려 기존 connection과
새 connection을 함께 실패시킨 뒤 같은 경로를 복구한다.

이 증거의 범위는 Redis TCP path 단절·복구다. Kafka leader election, PostgreSQL failover,
host 장애, multi-region 복구를 증명하지 않는다.

### Spring이 관리하는 HikariCP가 실행 경계다

Spring adapter와 Ticket profile은 애플리케이션과 같은 Spring bean 구성을 통해 HikariCP
`DataSource`를 사용한다. Test code에서 `Database.connect()`로 별도 connection 경로를 만들면 pool
상한, timeout, transaction wiring을 우회해 실제 adapter와 다른 결과가 나온다. Virtual thread
concurrency도 Hikari permit보다 먼저 database connection을 얻는 근거가 될 수 없으므로, profile은
설정된 admission wait와 pool capacity를 실제 bean에 전달한다.

### PostgreSQL fencing이 correctness authority다

Redis는 admission 또는 취소 알림을 가속하는 보조 경로다. Redis path가 끊겨도 lease, fencing
token, checkpoint, deduplication, inventory, payment reconciliation, receipt의 durable 결정은
PostgreSQL transaction에서 유지된다. Profile은 Redis 복구를 business fact 재생성으로 처리하지
않고 기존 PostgreSQL intent와 receipt가 수렴하는지 검증한다.

### 증거는 append/replace가 아닌 불변 artifact다

Coordinator는 run ID별 새 root를 만들고 manifest, journal, terminal report를 create-new 방식으로
기록한다. Terminal report를 나중에 교체하거나 일부 child를 빠뜨린 결과는 validator가 거부한다.
Upload manifest는 정렬된 allowlist와 SHA-256 digest를 포함하고 symlink, unknown file, secret/URI
pattern, workflow run/attempt 불일치를 fail closed로 처리한다.

성공 artifact뿐 아니라 validation 이전 실패도 고정된 상수형 summary만 업로드한다. 이 fallback은
raw exception, container log, connection URI를 포함하지 않는다.

### 실행 deadline과 cleanup reserve를 분리해야 한다

마지막 profile까지 workload budget을 모두 써버리면 container, process, network를 정리할 시간이
없다. Coordinator는 profile 시작 전에 남은 absolute deadline을 확인하고 run cleanup reserve를
별도로 보존한다. 각 child가 끝날 때 worker와 descendant process, Docker label resource,
topology cardinality가 모두 zero-live인지 확인한 뒤에만 다음 child를 시작한다.

Ryuk을 끈 hosted run도 이 parent-owned cleanup 증거를 통과해야 하므로, cleanup 성공은 외부
reaper가 우연히 정리했다는 뜻이 아니다.

### 공통 계약과 module-local adapter를 분리했다

Profile schema, deterministic schedule, report vocabulary, coordinator는 공통 계약을 사용한다. 실제
workload adapter와 invariant query는 Job Core, Spring, Ktor, Ticket 모듈에 남겼다. 이 방식은
module의 Spring bean, coroutine dispatcher, Exposed transaction, Redis namespace ownership을
그대로 사용하면서도 공통 report로 결과를 검증한다.

### 대형 Gradle 실행기는 `buildSrc`가 아니라 included build에 격리한다

초기 구현은 coordinator, process reaper, artifact validator를 `buildSrc`에 두었다. 이 코드는 단순한
몇 줄짜리 공용 helper가 아니라 독립적으로 테스트되는 실행기였고, `buildSrc` 변경은 모든 build
script classpath와 configuration을 불필요하게 무효화할 수 있었다.

`build-logic` included build에 root/profile plugin을 두고 필요한 project만 명시적으로 적용하도록
바꿨다. Task 이름과 report 계약은 유지하면서 registration 책임을 plugin에 모았고, 실제 module의
JVM/Hikari/Testcontainers 설정은 각 build script에 남겨 production adapter 특성을 보존했다.

### 공통 실행 경계는 문장만이 아니라 한 장의 architecture로 고정한다

네 구현이 같은 profile contract를 사용한다는 사실만 적으면 coordinator, child JVM, PostgreSQL
authority, Redis fault path, evidence/cleanup gate의 소유 관계를 빠르게 검토하기 어렵다. 공통
architecture diagram을 영문·국문 README 모두에 연결하고 validator가 image link를 강제하도록
했다. Validator는 PNG/SVG가 실제 regular non-empty file인지도 확인하며 PR의
`high-contention-contract` smoke gate에서 실행된다. Diagram은 Toxiproxy가 Redis path만 끊으며
PostgreSQL failover를 흉내 내지 않는다는 범위도 시각적으로 분리한다.

## Adopted and Rejected

채택한 방식은 root coordinator가 한 번에 하나의 격리된 child JVM을 실행하고, 각 모듈의
production adapter가 module-local authority를 검증한 terminal report를 생성하는 것이다.

다음 대안은 제외했다.

- 새 generic high-contention module에 repository와 service를 복제하는 방식: 실제 Spring/Hikari,
  coroutine, Exposed, Redis ownership 경계를 우회해 예제와 다른 시스템을 측정한다.
- 모든 profile이 singleton Toxiproxy/container topology를 공유하는 방식: 이전 profile의 toxic,
  connection, namespace가 다음 profile을 오염시키고 zero-live cleanup을 독립적으로 증명할 수 없다.
- `@Synchronized` 또는 큰 JVM lock으로 결과를 직렬화하는 방식: Java 25 virtual thread의 실제
  persistence contention을 숨기고 PostgreSQL fencing 검증을 대체한다.
- raw build directory 전체를 CI artifact로 업로드하는 방식: unknown file, secret, URI, symlink,
  교체된 report를 allowlist 밖에서 반출할 수 있다.
- `local-reference` throughput을 framework benchmark나 production capacity로 해석하는 방식:
  runner, Docker, host memory, pool 설정에 종속된 관찰값을 correctness와 혼동한다.

## Outcome

- `highContentionCi`와 `highContentionLocalReference` root task가 deterministic profile matrix를
  `--max-workers=1` 경계에서 순차 실행한다.
- Job Core, Spring, Ktor, Ticket은 같은 schedule/report 계약을 사용하되 각자의 production
  persistence와 runtime adapter를 유지한다.
- Toxiproxy Redis path 단절·복구, Spring-managed HikariCP, PostgreSQL fencing, child process와
  Docker cleanup을 machine evidence로 남긴다.
- Hosted artifact는 exact workflow run/attempt, digest allowlist, redaction, regular-file 검사를
  통과한 canonical file만 보존한다.
- 선택된 profile의 failure step, invariant ID, observation field를 report와 다시 대조하고,
  latency·duration·count는 non-negative typed number로, terminal disposition은 closed vocabulary로
  검증한다. 같은 임의 문자열로 여러 측정값을 채운 report는 거절한다.
- 영문/국문 runbook은 JDK 25, Docker, 4 GiB memory, command, report path, 증거 해석 한계를 같은
  내용으로 설명한다.
- 실행기 build logic은 `build-logic` included build로 격리하고, 공통 architecture diagram을 네
  예제의 영문·국문 README에서 함께 사용한다.

## Verification

재실행 가능한 문서 및 계약 검증 경계는 다음과 같다.

```bash
node scripts/validate-high-contention-readme.mjs
node scripts/validate-readme-language.mjs
./scripts/smoke-validate.sh high-contention-contract
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
```

전체 matrix는 새 고유 run ID로 실행해야 하며 생성된
`build/reports/high-contention/<run-id>/`는 source control에 추가하지 않는다.

## Future Guidance

- 경합 test는 throughput 숫자보다 authority winner, dedup receipt, fencing rejection, cleanup
  zero-live를 먼저 검증한다.
- Toxiproxy를 추가할 때 client가 advertise 또는 reconnect 이후에도 같은 proxy path를 사용하는지
  확인한다.
- Spring 예제의 database test는 production과 같은 Spring-managed HikariCP bean을 우선 사용한다.
- 새 implementation을 추가하면 module-local adapter, closed manifest, golden schedule, report
  validator, cleanup label을 같은 변경에서 갱신한다.
- Broker failover, PostgreSQL failover, host loss는 이 suite의 Redis path 증거를 재사용해
  주장하지 말고 별도의 topology와 acceptance criteria로 검증한다.
