# Exposed README diagram refresh

## 배경

exposed root README는 source tree에 `javers-audit`, `mvc-jdbc`, `mvc-virtualthread`,
`webflux-r2dbc` 네 개의 runnable Exposed module이 있음에도 generic Graphviz-era
architecture를 설명하고 세 모듈만 나열하고 있었다.

## 결정

generic layered graph 대신 module-selection architecture diagram을 사용한다. source-backed
relationship이 직선으로 유지되도록 module마다 하나의 vertical column을 둔다. 관계는 module
entrypoint, transaction boundary, persistence runtime이다. root README의 domain section이
reader에게 ASCII arrow와 source file만으로 FK ownership을 추론하게 만들지 않도록 compact
ERD를 추가한다.

`exposed/mvc-jdbc`는 reader contract를 두 시각 자료로 나눈다. architecture diagram은
MVC controller, service transaction ownership, inherited repository, explicit order
repository, PostgreSQL table을 보여주는 static view로 유지한다. lock ordering,
`SELECT FOR UPDATE`, order-line insert, stock decrement, rollback path는 sequence
diagram에 둔다. column name, type, key, FK ownership은 reader-facing information이며
prose-only metadata가 아니므로 schema section에는 별도 ERD가 필요하다.

`exposed/mvc-virtualthread`는 generic CRUD가 아니라 virtual-thread boundary를 중심에
둔다. architecture diagram은 Tomcat, shared `ExecutorService`, repository
`VirtualFuture<T>` method, service-owned order transaction, exception unwrapping,
PostgreSQL table을 보여주어야 한다. `Future.get()`, `transaction(db)`, row locking,
rollback, exception unwrap behavior는 sequence diagram을 사용한다. 이 모듈은 MVC JDBC
audit column 없이 plain Exposed `Table` definition을 사용하므로 별도 ERD를 둔다.

`exposed/webflux-r2dbc`는 transaction ownership을 service layer에 명시한다. architecture
diagram은 suspend WebFlux controller, service-owned `suspendTransaction`, thin Flow
repository, R2DBC pool/database configuration, JDBC-only schema initialization,
PostgreSQL table을 분리해 보여주어야 한다. sequence diagram은 Reactor-style 또는
blocking-thread 이야기가 아니라 coroutine R2DBC transaction 내부의 Flow/repository call을
보여주어야 한다.

`exposed/javers-audit`는 architecture를 service-level audit boundary에 집중시킨다.
유용한 reader contract는 `ProductAuditService`가 JaVers snapshot을 먼저 쓰고, 그 다음
Exposed current row를 변경한다는 점이다. `diff`는 value-only로 유지되며 어느 store에도
write하지 않는다. relational storage가 JaVers history가 아니라 최신 `products` row만
담는다는 점을 명확히 할 때 single-table ERD도 여전히 유용하다.

## 검증

- `@Transactional`, `virtualFuture { transaction(db) }`, `suspendTransaction`, JaVers
  commit/upsert behavior를 source에서 확인했다.
- MVC JDBC, WebFlux R2DBC, JaVers audit product table의 Exposed table definition과 ERD
  column을 대조했다.
- diagram을 SVG에서 PNG로 CairoSVG 렌더링하고 시각 검사했다.
- English와 Korean 파일 모두에서 README image link를 확인했다.
- README가 `docs/images/readme-diagrams/*readme-*` diagram으로 전환된 뒤
  `exposed/mvc-jdbc` Graphviz `.dot`, `.plain`, `*-graphviz.*`, stale non-README
  architecture asset을 제거했다.
- endpoint가 target card edge로 들어가도록 `exposed/mvc-jdbc` architecture connector를
  조정했다. visual review에서 오른쪽과 아래 margin이 좁게 보인 뒤 sequence canvas를
  확장했다.
- branch-level README diagram은 같은 fixed-size arrowhead family로 다시 렌더링했다.
  기준은 `15px` primary, `13.5px` return/secondary, `12px` small schema link다.
- `exposed/mvc-virtualthread` Graphviz artifact를 제거하고, README image link를
  `docs/images/readme-diagrams/*readme-*`로 전환했으며, architecture, sequence, ERD PNG를
  CairoSVG로 렌더링해 contact sheet로 시각 검사했다.
- `exposed/webflux-r2dbc` Graphviz artifact를 제거하고, README image link를
  `docs/images/readme-diagrams/*readme-*`로 전환했으며, architecture, sequence, ERD PNG를
  CairoSVG로 렌더링해 contact sheet로 시각 검사했다.
- `exposed/javers-audit` stale non-README architecture asset을 제거하고,
  `ProductAuditService`와 `ProductTable`을 기준으로 architecture/sequence diagram을 다시
  그렸으며, single-table ERD를 추가했다. rendered contact sheet와 high-risk
  sequence/architecture PNG를 시각 검사했다.

## 향후 지침

root workshop README에서는 reader가 submodule을 선택하는 방법을 설명한다. root README가
특정 scenario 자체를 다루는 경우가 아니라면 submodule sequence diagram을 root visual로
재사용하지 않는다.
root module pass 이후 다른 top-level module로 이동하기 전에 direct child README file을
scan한다. child마다 runnable artifact와 Graphviz 잔재가 있다면 root diagram이 submodule
README refresh를 대체하지 않는다.
locking example에서는 lock/rollback behavior를 architecture box로 압축하지 않는다.
architecture diagram에는 repository ownership을 static하게 유지하고, transaction ordering은
transparent branch가 있는 sequence diagram으로 보여준다.
module schema에서는 README가 prose table만으로 FK ownership을 설명하고 있다면 ERD를
추가한다. relationship line은 column text corridor 밖에 둔다.
arrowhead size가 바뀌면 path endpoint를 수치와 시각 양쪽으로 다시 확인한다. 특히 marker
`refX` 또는 marker width가 변경된 뒤에는, line이 얼핏 그럴듯해 보여도 endpoint가 card 옆에
떨어질 수 있다.
persistence layer가 답답해 보이면 text를 줄이거나 label만 옮기지 말고 canvas와 layer/card
height를 함께 키운다. top/bottom margin을 판단할 때 layer title area도 실제 visual space로
계산한다.
architecture diagram에 call-order hint가 필요하면 route chip보다 card text와 sequence
diagram을 우선한다. chip text가 정확해도 connector line 위에 놓인 chip은 제거한다.
