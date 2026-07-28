# Gateway README Diagram Refresh

## 배경

gateway root README는 실제 gateway system boundary를 설명하는 대신 generic workshop
slice를 설명하고 child module scenario image를 연결하고 있었다. legacy Graphviz artifact도
README image 옆에 남아 있었다.

## 결정

root `gateway` README를 세 runnable application의 runtime overview로 다룬다. public
gateway, downstream WebFlux service, Redis-backed Bucket4j support path만 보여준다.
controller와 module-specific detail은 child module README에 둔다.

## 결과

README는 이제 `application.yml`의 실제 port와 route prefix를 문서화한다. 항목은
`8080` gateway, `8081` customer service, `8082` order service,
`/customer-service/**`, `/order-service/**`, Swagger UI, optional `/echo` route다.

## 검증

- gateway, customer, order application resource와 controller를 읽었다.
- SVG diagram을 CairoSVG로 PNG로 렌더링했다.
- rendered PNG를 시각 검사하고 commit 전에 footer overlap을 수정했다.
- README image link, Graphviz reference, SVG XML, connector endpoint, `git diff --check`를
  확인했다.

## 향후 지침

parent README가 child module scenario diagram을 재사용하게 하지 않는다. parent diagram은
orchestration과 runtime boundary를 설명하고, child diagram은 service internal을 설명해야 한다.

`gateway/api-gateway`에서는 README claim을 오래된 Spring Cloud Gateway 예제가 아니라
`application.yml`에 묶어 둔다. 현재 route는 localhost target, `/customer-service/**`,
`/order-service/**`, `/v3/api-docs/**`, `/echo`를 사용한다. source가 바뀌지 않았다면
`lb://...` route를 문서화하지 않는다. sequence diagram은 root redirect branch를
service-route Bucket4j 및 rewrite branch와 분리해야 한다.
