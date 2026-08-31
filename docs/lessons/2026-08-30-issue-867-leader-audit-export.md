# Issue #867 leader audit export 경계

## Context

`bluetape4k-leader` 2.0.0-SNAPSHOT은 leader history와 lifecycle event를
외부 exporter로 전달할 수 있는 공개 API를 추가했다. 기존 `leader/job-safety-lab`은
Redis leader acquire/release와 PostgreSQL fencing을 실제로 실행하지만, 이 lifecycle을
운영자가 관찰할 bounded audit report나 전송 경계가 없었다.

## Decision or Finding

- 실제 Redis elector를 `ListeningLeaderElector`로 감싸 lifecycle publisher를 유지하고,
  history recorder는 `ExportingLeaderHistorySink`에 연결했다.
- `MEMORY`를 기본 transport로 선택해 startup 시 외부 DNS, socket, credential을 사용하지
  않게 했다. serialized payload는 별도 bounded store에 기록해 report에서만 관찰한다.
- wire encoder는 upstream sanitizer 결과를 다시 필요한 필드만 가진 JSON으로 투영한다.
  lock/node/slot/leader/token/customer/tenant 식별자와 raw exception message는 payload와
  report에 넣지 않는다.
- `HTTPS`는 exact host allow-list와 `Authorization`만 허용하는 명시적 opt-in이다.
  HTTP, user-info, query/fragment, localhost, IP literal은 fail closed 한다. DNS rebinding과
  private-address egress는 resolver/proxy/network policy의 운영 책임으로 남긴다.
- `ACCEPTED`는 delivery success가 아니라 bounded queue admission이다. queue full drop,
  retry, terminal failure, cancellation은 upstream snapshot과 고정 meter 이름으로 노출한다.
- PostgreSQL history와 outbox는 계속 authoritative 상태를 소유한다. audit export는
  best-effort 관찰 기능이며 exactly-once history나 external delivery receipt가 아니다.
- Spring resource의 implicit destroy를 끄고 `JobSafetyAuditShutdownCoordinator` 하나가
  subscription → exporter → HTTP client → scheduler → executor → coroutine scope 순서로
  하나의 monotonic deadline 아래 종료한다.

## Outcome

operator 역할만 `/api/job-safety/audit`를 조회할 수 있다. 기본 report는 endpoint와
credential 없이 bounded recent JSON, retained byte budget, exporter snapshot, low-cardinality
meter catalog를 제공한다. 실제 Redis lifecycle 통합 테스트는 audit event가 생성되는 동안
기존 PostgreSQL resource fence와 summary가 변하지 않음을 함께 확인한다.

## Verification

- `JobSafetyAuditPropertiesTest`: MEMORY 기본값, HTTPS trust/allow-list, header redaction,
  Long 산술 및 aggregate byte budget.
- `JobSafetyAuditPayloadEncoderTest`: payload 크기와 raw 식별자/exception 비노출.
- `JobSafetyAuditExporterTest`: queue full, retry, terminal status, close/cancellation.
- `JobSafetyAuditReportServiceTest`: malformed payload drop, bounded report와 meter catalog.
- `JobSafetyAuditShutdownCoordinatorTest`: idempotent close, FIFO resource order, queue 제거,
  bounded timeout.
- `JobSafetyEndToEndIntegrationTest`: 실제 Redis acquire/release lifecycle export와
  PostgreSQL 권위 유지.
- `JobSafetyContextRestartIntegrationTest`: Spring context restart에서 resource lifecycle.

## Future Guidance

새 transport를 추가할 때도 외부 네트워크를 기본값으로 만들지 말고, payload schema와
retained memory를 먼저 bounded하게 고정한다. admission과 delivery를 같은 성공 상태로
표현하지 말며, endpoint/credential/식별자를 report·metric·log에 다시 넣지 않는다. 운영
HTTPS 배포는 exact allow-list 외에도 DNS rebinding과 private-network egress를 제어하는
resolver 또는 egress proxy를 함께 검토해야 한다. 다음 leader library 변경에서는 upstream
public API와 이 workshop adapter의 책임 경계를 다시 교차 검증한다.
