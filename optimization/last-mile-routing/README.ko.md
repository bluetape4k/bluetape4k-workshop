# Last-Mile Routing

[English](README.md) | 한국어

이 Spring Boot 참조 애플리케이션은 고정 travel matrix를 사용하는 synthetic
픽업·배송 라우팅을 보여 줍니다. 작업, route proposal, carrier version, callback,
확정 정류장의 권위 상태는 PostgreSQL에 저장합니다. 기본 deterministic provider는
오프라인·provider-neutral이며 Timefold, OSRM, 지도 tile, GPS, 운송사 API에 연결하지
않습니다.

## 범위와 경계

- 픽업 선행, 차량 capacity, time window, required skill, started-stop pin을 hard
  constraint로 적용합니다.
- matrix miss와 provider outage는 bounded 명시적 실패이며 silent network fallback은
  없습니다.
- 정규화한 `RoutingProvider` seam으로 provider revision과 PostgreSQL
  job/carrier version을 분리합니다.
- callback inbox/outbox는 event key와 canonical payload digest를 사용해 중복·충돌·
  오래된 결과를 관측하고 raw payload는 기록하지 않습니다.
- browser console은 synthetic polyline, depot/stop marker, ETA, capacity, window,
  skill, unassigned reason, numeric score, revision diff, started pin만 투영합니다.
  주소, 고객 정보, secret, raw provider text는 렌더링하지 않습니다.

## 실행

demo는 PostgreSQL 설정을 명시해야 합니다. `application.yml`에는 credential이나
database 기본값을 넣지 않았습니다.

```bash
export LAST_MILE_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/last_mile'
export LAST_MILE_DATABASE_USERNAME='last_mile'
export LAST_MILE_DATABASE_PASSWORD='change-me-locally'
./gradlew :optimization-last-mile-routing:bootRun
open http://127.0.0.1:8080/last-mile-routing/
```

loopback API는 `GET /api/last-mile-routing/plans/{planId}`와 replan, approval,
정규화 provider callback, canonical event, driver reconnect `POST` endpoint를
제공합니다. 응답은 `ETag`와 revision을 사용하며 stale approval과 callback
digest conflict를 명시적 HTTP conflict로 반환합니다.

demo는 synthetic·loopback 범위이며 HTTP surface는 production 인증이나 CSRF
보호가 아닙니다. 실제 Timefold/OSRM credential, live GPS, geocoding, traffic,
carrier contract, tenant API, production migration은 의도적으로 범위에서 제외합니다.

## 검증

```bash
./gradlew :optimization-last-mile-routing:cleanTest \
  :optimization-last-mile-routing:test \
  --no-build-cache --max-workers=1
./gradlew :optimization-last-mile-routing:build --max-workers=1
./scripts/smoke-validate.sh optimization
```

이 모듈은 root build의 `bluetape4k-dependencies` BOM을 사용하며 개별 Bluetape
버전을 고정하지 않습니다. `:optimization-planning-contracts` 구현 클래스에도
의존하지 않습니다.
