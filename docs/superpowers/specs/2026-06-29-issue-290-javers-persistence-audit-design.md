# Issue #290 - JaVers 지속성 감사 워크숍 설계

## 문맥

`exposed/javers-audit`는 가장 작은 Exposed + JaVers 경계를 다음과 같이 가르칩니다.
인메모리 JaVers 저장소. Issue #290에는 지속성 지원 감사 예제가 필요합니다.
현재 관계형 행과 행 사이의 작동 경계를 보여줍니다.
내구성 있는 JaVers 스냅샷 저장소.

## 소스 증거

- `exposed/javers-audit`은 현재 `ProductTable` 행만 Exposed에 저장합니다.
  변경 불가능한 `Product` 값을 인메모리 JaVers 저장소에 커밋합니다.
- 워크샵 카탈로그에는 이미 `bluetape4k-javers-persistence-redis`이(가) 노출되어 있습니다.
  및 `bluetape4k-javers-persistence-kafka` 별칭.
- `bluetape4k-javers/javers-persistence-redis`은 읽기 가능 제공
  `LettuceCdoSnapshotRepository` 및 `RedissonCdoSnapshotRepository`.
- `bluetape4k-javers/javers-persistence-kafka`은 의도적으로 쓰기 전용입니다.
  기록 읽기에는 `KafkaCdoSnapshotProjector`이 읽기 가능으로 필요합니다.
  Redis 같은 저장소.
- 워크샵 Redis 테스트에서는 이미 `RedisServer.Launcher.redis`을 사용하고 있으며
  `RedisServer.Launcher.RedissonLib`.

## 결정

확장하는 대신 `exposed/javers-persistence-audit`을 집중 모듈로 추가합니다.
`exposed/javers-audit`.

Redis/Redisson을 구현된 지속성 백엔드로 사용하세요.
하나의 학습자에서 지속성, 최신 스냅샷, 기록 및 차이점 쿼리를 커밋합니다.
길. Kafka을 Kafka 변형으로 문서화하세요.
저장소는 스냅샷을 게시하지만 기록 쿼리 자체에는 응답하지 않습니다.

## 모듈 계약

- Exposed는 현재 `orders` 행을 소유합니다.
- Redis-backed JaVers는 내구성 있는 감사 스냅샷을 소유하고 있습니다.
- `OrderAuditService.place()`는 INITIAL 스냅샷을 작성하기 전에 커밋합니다.
  현재 행.
- `OrderAuditService.markPaid()`은 이전에 UPDATE 스냅샷을 커밋합니다.
  유료 행을 구체화합니다.
- `OrderAuditService.delete()`은(는) 제거하기 전에 TERMINAL 스냅샷을 커밋합니다.
  현재 행.
- `OrderAuditService.getHistory()`은 README에 대해 가장 오래된 것부터 스냅샷을 반환합니다.
  가독성.
- `OrderAuditService.getLatestSnapshot()`은 최신 JaVers 스냅샷을 반환합니다.
  `null`.
- `OrderAuditService.diff()`은 변경 불가능한 두 순서 값을 비교합니다.
  Exposed 또는 Redis에 쓰고 있습니다.

## 실패 계약

JaVers 저장소가 스냅샷을 인코딩하거나 유지할 수 없는 경우 서비스는 다음을 수행해야 합니다.
감사되지 않은 쓰기를 자동으로 수락하는 대신 오류를 표면화합니다. 테스트 커버
감사 싱크 경계에서의 오류.

## 다이어그램 계약

두 개 이상의 README 다이어그램을 만듭니다.

- 아키텍처: Exposed 현재 행이 있는 정적 ownership/dependency 보기,
  Redis JaVers 스냅샷 저장소 및 Kafka write-only/projection 경계.
- 쓰기 순서 흐름: 돌연변이 보호, Redis 지원 commit/read 경로 및 싱크
  실패 행동.

모든 다이어그램은 `$bluetape4k-diagram`을 따라야 합니다: 영어 라벨, `Architects
딸` and `코믹 모노`, Redis/Kafka/database, CairoSVG 카탈로그 아이콘
SVG-PNG 렌더링, 도우미 감사, 밀착 인화 및 전체 크기 PNG 시각적 개체
점검.

## 범위를 벗어남

- `exposed/javers-audit` 다시 작성 중입니다.
- 첫 번째 반복에서 전체 Kafka 프로젝션 소비자를 구현합니다.
- 루트 BOM/catalog 외부에 새 종속성 버전을 추가합니다.
