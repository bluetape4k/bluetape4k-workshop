# Spring Data Elasticsearch

[English](README.md) | 한국어

이 모듈은 Testcontainers Elasticsearch OSS 서버를 사용하는 blocking Spring Data
Elasticsearch 예제입니다. 애플리케이션 시작 시 conference 문서를 적재하고, 같은
`conference-index`를 대상으로 repository derived query와 `ElasticsearchOperations`
criteria query를 검증합니다.

## 이 예제가 보여 주는 것

![Spring Data Elasticsearch architecture](../../docs/images/readme-diagrams/spring-data-elasticsearch-readme-architecture-01.png)

애플리케이션은 `ElasticsearchOssServer.Launcher.elasticsearchOssServer`를 시작하고,
container URL로 Spring Data client를 구성한 뒤
`ElasticsearchApplication.insertSampleData()`에서 5개의 `Conference` 문서를 저장합니다.
동기 repository는 startup data loading과 기본 검색에 사용되고, reactive repository와
reactive operations는 동일한 document model을 WebFlux 스타일 API와 비교할 수 있게 둔
구성입니다.

## Query 흐름

![Spring Data Elasticsearch query flow](../../docs/images/readme-diagrams/spring-data-elasticsearch-readme-sequence-01.png)

테스트는 세 가지 읽기 경로를 다룹니다.

| Reader path | API | 확인하는 내용 |
|---|---|---|
| Repository CRUD | `ConferenceRepository.findAll()`과 `findByName()` | Spring Data가 `Conference` 문서를 `conference-index`에 매핑한다. |
| Criteria query | `ElasticsearchOperations.search()` | keyword/date, geo-distance 조건을 `CriteriaQuery`로 표현한다. |
| Reactive repository | `ReactiveConferenceRepository` | derived query 결과를 Reactor `Flux`로 받고 테스트에서 Kotlin `Flow`로 소비한다. |

Operations 및 reactive repository 테스트는 현재 비활성화되어 있습니다. 이 stack에서 사용하는
Elasticsearch Java client가 Jackson 2 기반인 반면, 이 Spring Boot 4 예제 라인은 Jackson 3를
사용하기 때문입니다.

## Domain model

`Conference`는 `conference-index`에 다음 필드로 저장됩니다.

| Field | Mapping role |
|---|---|
| `id` | Spring Data document id |
| `name` | Conference title |
| `date` | date criteria에 사용하는 `FieldType.Date` string |
| `location` | nearby search에 사용하는 `GeoPoint` |
| `keywords` | repository 및 criteria filter에 사용하는 tag |

## 주요 파일

- `ElasticsearchApplication.kt`는 container-backed sample을 시작하고 5개의 conference를 저장합니다.
- `ElasticsearchClientConfig.kt`는 Spring Data client를 Testcontainers URL과 basic auth로 연결합니다.
- `ConferenceRepository.kt`는 blocking `ElasticsearchRepository` 사용을 보여 줍니다.
- `ReactiveConferenceRepository.kt`는 derived reactive search method를 보여 줍니다.
- `ElasticsearchOperationsTest.kt`와 `ReactiveElasticsearchOperationsTest.kt`는 같은 조건 검색을 criteria API로 표현합니다.

## 참고

- [Spring Data Elasticsearch Reference Documentation](https://docs.spring.io/spring-data/elasticsearch/reference/)
- [이 저장소의 reactive variant](../elasticsearch-webflux)
