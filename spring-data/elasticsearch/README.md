# Spring Data Elasticsearch - Demo

## 아키텍처 다이어그램

```mermaid
classDiagram
    class Conference {
        +String name
        +String date
        +GeoPoint? location
        +MutableList~String~ keywords
        +String? id
    }
    class ConferenceRepository {
        <<interface>>
        +findByKeywordsContaining(keyword) List~Conference~
        +findByDateAfter(date) List~Conference~
        +findByLocationNear(point, distance) List~Conference~
    }
    class ReactiveConferenceRepository {
        <<interface>>
        +findByKeywordsContaining(keyword) Flux~Conference~
        +findByDateAfter(date) Flux~Conference~
    }
    class ElasticsearchClientConfig {
        +elasticsearchClient() ElasticsearchClient
        +reactiveElasticsearchClient() ReactiveElasticsearchClient
    }

    ConferenceRepository --> Conference : 동기 검색
    ReactiveConferenceRepository --> Conference : 리액티브 검색
```

```mermaid
sequenceDiagram
    participant 테스트 as 통합테스트
    participant 저장소 as ConferenceRepository
    participant ES as Elasticsearch

    테스트->>저장소: save(conference)
    저장소->>ES: PUT /conference-index/_doc/{id}
    ES-->>저장소: 인덱싱 완료

    테스트->>저장소: findByKeywordsContaining("Kotlin")
    저장소->>ES: GET /conference-index/_search\n{query: {term: {keywords: "Kotlin"}}}
    ES-->>저장소: SearchHits
    저장소-->>테스트: List~Conference~

    테스트->>저장소: findByLocationNear(geoPoint, distance)
    저장소->>ES: geo_distance 쿼리
    ES-->>저장소: 근처 컨퍼런스 목록
    저장소-->>테스트: List~Conference~
```

Spring Data Elasticsearch를 활용하는 동기(MVC) 방식 예제입니다.
Testcontainers로 Elasticsearch 컨테이너를 자동으로 구동하여 통합 테스트를 수행합니다.

## 주요 내용

- `@Document` 어노테이션을 이용한 Elasticsearch 문서 매핑
- `ElasticsearchRepository` 기반 CRUD 및 검색 메서드
- 커스텀 쿼리(`@Query`)와 Native Query 사용
- Spring MVC 컨트롤러를 통한 REST API 노출

## 참고

- [Spring Data Elasticsearch 공식 문서](https://docs.spring.io/spring-data/elasticsearch/reference/)
- WebFlux(Reactive) 방식은 [`spring-data/elasticsearch-webflux`](../elasticsearch-webflux) 모듈 참고
