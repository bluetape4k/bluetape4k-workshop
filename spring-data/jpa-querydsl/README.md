# JPA and Querydsl

[한국어](README.ko.md) | English

This module demonstrates Spring Data JPA repositories with Querydsl custom search logic.
It models `Team` and `Member`, projects joined query results into DTOs, and compares
simple, deferred, and optimized count strategies for pageable search.

## What this example shows

![JPA Querydsl architecture](../../docs/images/readme-diagrams/spring-data-jpa-querydsl-readme-architecture-01.png)

`MemberRepository` combines Spring Data `JpaRepository`, `QuerydslPredicateExecutor`, and
`MemberRepositoryCustom`. The custom implementation uses `JPAQueryFactory` and generated
`QMember`/`QTeam` types to build dynamic predicates from `MemberSearchCondition`.

## Domain ERD

![JPA Querydsl ERD](../../docs/images/readme-diagrams/spring-data-jpa-querydsl-readme-erd-01.png)

`Member` has an optional lazy `ManyToOne` relationship to `Team`. `Team.members` is the
inverse collection mapped by `Member.team`, so `Team.addMember()` and
`Member.changeTeam()` keep the two sides in sync in the sample domain model.

## Querydsl paths

| Path | Method | What it demonstrates |
|---|---|---|
| Derived repository query | `findAllByName`, `findAllByTeam` | Standard Spring Data method-name queries over JPA entities. |
| Dynamic DTO search | `search(MemberSearchCondition)` | `leftJoin(member.team, team)`, nullable predicates, and constructor projection to `MemberTeamDto`. |
| Simple page | `searchPageSimple` | Separate content query and deprecated `fetchCount()` count query. |
| Deferred count page | `searchPageComplex` | `PageableExecutionUtils.getPage()` defers the count query when possible. |
| Optimized count page | `searchPageExtremeCountQuery` | Counts distinct member ids instead of fetching a full entity count. |

## Test runtime

The tests use `@DataJpaTest`, which starts an embedded H2 database. The JDBC URL is written
as `jdbc:mysql://localhost:3306/test;` so H2 runs in MySQL compatibility mode for this
slice. `InitMemberService` seeds `teamA`, `teamB`, and members before repository tests.

## Key files

- `Member.kt` and `Team.kt` define the JPA model and relationship helper methods.
- `MemberRepository.kt` composes Spring Data and custom Querydsl repository contracts.
- `MemberRepositoryImpl.kt` builds dynamic predicates, projections, sorting, and paging.
- `SpringRepositoryQuerydslSupport.kt` shows a reusable Querydsl support base for custom repositories.
- `QuerydslExamples.kt` contains focused Querydsl examples such as distinct, grouping, sorting, and projections.
