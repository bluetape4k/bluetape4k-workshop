# graph-knowledge-graph

A workshop example demonstrating knowledge graph construction and traversal using
the [bluetape4k-graph](https://github.com/bluetape4k/bluetape4k-graph) library.

> **Related issue:** [bluetape4k-workshop #11](https://github.com/bluetape4k/bluetape4k-workshop/issues/11)

## Overview

This module models a **technology knowledge graph** covering programming languages,
frameworks, libraries, runtime platforms, and the documents that reference them.

It shows how to:

- Model heterogeneous entity types with `VertexLabel` and `EdgeLabel` schema objects
- Record which documents mention which entities (`MENTIONS` edges with confidence scores)
- Express semantic relationships between entities (`RELATED_TO` edges with typed relations)
- Classify entities under vocabulary concepts (`IS_A` edges)
- Traverse the graph at configurable hop depth
- Infer association paths between distant entities with a bounded depth/count limit
- Run the same service logic against multiple graph backends (TinkerGraph, Neo4j, Memgraph)

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   graph-knowledge-graph                  │
│                                                          │
│  ┌─────────────┐    ┌─────────────────────────────────┐ │
│  │   Schema    │    │           Services               │ │
│  │─────────────│    │─────────────────────────────────│ │
│  │ EntityLabel │    │ KnowledgeGraphService (blocking) │ │
│  │ ConceptLabel│    │ KnowledgeGraphSuspendService     │ │
│  │ DocumentLbl │    │           (coroutine)            │ │
│  │ MentionsLbl │    └─────────────────────────────────┘ │
│  │ RelatedToLbl│              │                          │
│  │ IsALabel    │    ┌─────────▼─────────────────────┐   │
│  └─────────────┘    │      GraphOperations           │   │
│                     │  (bluetape4k-graph-core)       │   │
│                     └────────────────────────────────┘   │
│                                  │                        │
│          ┌───────────────────────┼───────────────────┐   │
│          ▼                       ▼                    ▼   │
│   TinkerGraph               Neo4j               Memgraph  │
│   (in-memory)          (Testcontainer)      (Testcontainer)│
└─────────────────────────────────────────────────────────┘
```

## Domain Model

The seed graph uses a **technology domain** scenario:

```
Documents ──MENTIONS──► Entities ──RELATED_TO──► Entities
                              │
                           IS_A ▼
                           Concepts
```

### Vertex types

| Label    | Key property | Example values |
|----------|-------------|----------------|
| Entity   | entityId    | Kotlin, Spring, JVM, Coroutines |
| Concept  | conceptId   | Programming Language, Framework, Library, Platform |
| Document | documentId  | "Kotlin in Action", "Spring Boot Reference" |

### Edge types

| Label      | Direction | Properties | Meaning |
|------------|-----------|------------|---------|
| MENTIONS   | Doc → Entity | confidence (0–100) | document mentions entity |
| RELATED_TO | Entity → Entity | relationType | semantic relationship |
| IS_A       | Entity → Concept | — | entity is classified under concept |

### Seed topology

```
doc-kotlin-guide  ──MENTIONS──► entity-kotlin   (confidence=95)
doc-kotlin-guide  ──MENTIONS──► entity-jvm       (confidence=80)
doc-spring-guide  ──MENTIONS──► entity-spring    (confidence=98)
doc-spring-guide  ──MENTIONS──► entity-kotlin    (confidence=75)

entity-kotlin     ──has-feature──►    entity-coroutines
entity-coroutines ──integrates-with──► entity-spring
entity-spring     ──runs-on──►        entity-jvm
entity-kotlin     ──runs-on──►        entity-jvm

entity-kotlin     ──IS_A──► concept-language
entity-spring     ──IS_A──► concept-framework
entity-coroutines ──IS_A──► concept-library
entity-jvm        ──IS_A──► concept-platform
```

## Core Features

### Entity + concept + document management

```kotlin
val service = KnowledgeGraphService(ops, "knowledge_graph")
service.initialize()

val paper = service.addDocument("doc-1", "Graph API Guide", "docs")
val kotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
val language = service.addConcept("concept-language", "Programming Language", "software")

service.mention(paper.id, kotlin.id, confidence = 95)
service.classify(kotlin.id, language.id)
```

### Traversal

```kotlin
// Which entities does a document mention?
val mentioned = service.findMentionedEntities(paper.id)

// Which entities are related to Kotlin (up to 2 hops)?
val related = service.findRelatedEntities(kotlin.id, depth = 2)

// What concept is Kotlin classified under?
val concepts = service.findConceptsForEntity(kotlin.id)

// What are the relationship paths from Kotlin to Spring?
val paths = service.inferRelationshipPaths(kotlin.id, spring.id, maxDepth = 3, maxPaths = 5)
```

### Coroutine variant

```kotlin
val service = KnowledgeGraphSuspendService(ops, "knowledge_graph")
service.initialize()

val mentioned: Flow<GraphVertex> = service.findMentionedEntities(paper.id)
val related: Flow<GraphVertex> = service.findRelatedEntities(kotlin.id, depth = 2)
val paths: Flow<GraphPath> = service.inferRelationshipPaths(kotlin.id, spring.id)
```

## Supported Backends

| Backend     | Class                        | Docker required | Task        |
|-------------|------------------------------|-----------------|-------------|
| TinkerGraph | `TinkerGraphOperations`      | No              | `test`      |
| Neo4j       | `Neo4jGraphOperations`       | Yes             | `integrationTest` |
| Memgraph    | `MemgraphGraphOperations`    | Yes             | `integrationTest` |

## Running Tests

```bash
# Default tests — TinkerGraph only (no Docker required)
./gradlew :graph-knowledge-graph:test

# Integration tests — requires Docker (Neo4j + Memgraph)
./gradlew :graph-knowledge-graph:integrationTest
```

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)
    compileOnly(libs.bluetape4k.graph.neo4j)
    compileOnly(libs.bluetape4k.graph.memgraph)
}
```

## See Also

- [bluetape4k-graph](https://github.com/bluetape4k/bluetape4k-graph) — graph library source
- [graph-social-network](../social-network/README.md) — social network example
- [graph-abuser-detection](../abuser-detection/README.md) — fraud/abuse detection example
- [bluetape4k-workshop #11](https://github.com/bluetape4k/bluetape4k-workshop/issues/11) — tracking issue
