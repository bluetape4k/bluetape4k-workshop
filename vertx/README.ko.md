# Vert.x Workshop

[English](README.md) | 한국어

이 디렉터리는 워크샵에서 사용하는 Vert.x 예제를 묶습니다. Coroutine verticle,
SQL client 사용법, WebClient request/response 예제 중 무엇을 먼저 볼지 고를
때 이 README에서 시작하면 됩니다.

## Module Map

![Vert.x workshop module map](../docs/images/readme-diagrams/vertx-readme-architecture-01.png)

## Modules

| Module | Reader question | Main focus |
|---|---|---|
| [`coroutines`](coroutines/README.ko.md) | Coroutine verticle로 HTTP service를 어떻게 실행하나? | `CoroutineVerticle`, routing, WebClient 호출, H2/JDBC rating 조회 |
| [`vertx-sqlclient`](vertx-sqlclient/README.ko.md) | Vert.x SQL client는 row와 template을 어떻게 매핑하나? | JDBC pool, SQL client templates, MySQL/PostgreSQL clients, data objects |
| [`vertx-webclient`](vertx-webclient/README.ko.md) | WebClient request/response API는 어떻게 동작하나? | request 생성, response decoding, coroutine-style client calls |

## Common Stack

- Vert.x core와 Kotlin coroutine bindings.
- `bluetape4k-vertx` helper APIs.
- Reactive/coroutine interop을 위한 `kotlinx-coroutines-reactor`.
- 실행 가능한 예제를 위한 JUnit 5와 Vert.x test support.

## References

- [Vert.x documentation](https://vertx.io/docs/)
- [Vert.x Kotlin coroutines](https://vertx.io/docs/vertx-lang-kotlin-coroutines/kotlin/)
