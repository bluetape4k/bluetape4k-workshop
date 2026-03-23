# Spring Data Elasticsearch Example with Spring Boot 4 and Elasticsearch 8

## 아키텍처 다이어그램

```mermaid
classDiagram
    class Book {
        +String title
        +String authorName
        +Int publicationYear
        +String isbn
        +String? id
    }
    class BookService {
        <<interface>>
        +findAll() Flux~Book~
        +findByIsbn(isbn) Mono~Book~
        +save(book) Mono~Book~
        +update(id, request) Mono~Book~
        +deleteById(id) Mono~Void~
        +searchByAuthorAndTitle(author, title) Flux~Book~
    }
    class DefaultBookService {
        -BookRepository bookRepository
    }
    class BookRepository {
        <<interface>>
        +findByIsbn(isbn) Mono~Book~
        +searchByAuthorAndTitle(author, title) Flux~Book~
    }
    class BookController {
        +getBooks() Flux~Book~
        +getBookByIsbn(isbn) Mono~Book~
        +createBook(book) Mono~Book~
        +updateBook(id, request) Mono~Book~
        +deleteBook(id) Mono~Void~
    }
    class PublicationYearValidator {
        +isValid(year, context) Boolean
    }

    BookService <|.. DefaultBookService
    DefaultBookService --> BookRepository
    BookController --> BookService
    BookRepository --> Book
    Book --> PublicationYearValidator : 검증
```

```mermaid
sequenceDiagram
    participant 클라이언트 as HTTP 클라이언트
    participant 컨트롤러 as BookController
    participant 서비스 as DefaultBookService
    participant 저장소 as BookRepository
    participant ES as Elasticsearch

    클라이언트->>컨트롤러: POST /books
    컨트롤러->>서비스: save(book)
    서비스->>저장소: existsByIsbn(isbn)
    저장소->>ES: 중복 ISBN 확인
    ES-->>저장소: false
    서비스->>저장소: save(book)
    저장소->>ES: PUT /books/_doc/{id}
    ES-->>저장소: Book (id 생성)
    저장소-->>서비스: Mono~Book~
    서비스-->>컨트롤러: Book
    컨트롤러-->>클라이언트: 201 Created

    클라이언트->>컨트롤러: GET /books/search?author=&title=
    컨트롤러->>서비스: searchByAuthorAndTitle(author, title)
    서비스->>저장소: fuzzy search 쿼리
    저장소->>ES: multi_match + fuzzy
    ES-->>저장소: SearchHits
    저장소-->>클라이언트: Flux~Book~
```

## Introduction

This example demonstrates how to use Spring Data Elasticsearch to do simple CRUD operations.

You can find the tutorial about this example at this
link: [Getting started with Spring Data Elasticsearch](https://www.geekyhacker.com/getting-started-with-spring-data-elasticsearch/)

For this example, we created a Book controller that allows doing the following operations with Elasticsearch:

- Get the list of all books
- Create a book
- Update a book by Id
- Delete a book by Id
- Search for a book by ISBN
- Fuzzy search for books by author and title

## 참고

- [Spring Data Elasticsearch - Reference Documentation](https://docs.spring.io/spring-data/elasticsearch/docs/current/reference/html/)
- [Spring Data Elasticsearch NativeSearchQuery 사용법](https://juntcom.tistory.com/149)
