# Vert.x Kotlin Coroutines

[English](README.md) | 한국어

이 모듈은 Vert.x `CoroutineVerticle`로 작은 movie-rating HTTP service를
실행합니다. Vert.x event-loop 모델은 유지하면서, route handler는
`suspendHandler { }`로 suspend function처럼 작성합니다.

## 아키텍처

![Vert.x coroutine movie-rating architecture](../../docs/images/readme-diagrams/vertx-coroutines-readme-architecture-01.png)

## Endpoints

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/movie/:id` | H2-backed `MOVIE` table에서 movie title을 읽습니다 |
| `POST` | `/rateMovie/:id?getRating=N` | 해당 movie의 rating row를 추가합니다 |
| `GET` | `/getRating/:id` | `RATING` table에서 평균 rating을 반환합니다 |

## Coroutine Pattern

`MovieRatingVerticle`은 `CoroutineVerticle`을 확장하고, `start()`에서 H2 sample
data를 초기화한 뒤 `suspendHandler { }`로 route를 등록합니다. 각 route는
Vert.x JDBC future를 `coAwait()`로 기다리므로 query와 response 코드를 callback
nesting 없이 순차적으로 읽을 수 있습니다.

## Test Coverage

`MovieRatingVerticeTest`는 verticle을 한 번 deploy한 뒤 Vert.x WebClient로 movie
조회, rating 조회, rating 입력을 검증합니다.

## 테스트

```bash
./gradlew :vertx-coroutines:test
```

Service를 수동으로 실행하려면 IDE에서
`io.bluetape4k.workshop.movierating.main()`을 시작한 뒤
`http://localhost:8080/movie/starwars`를 엽니다.
