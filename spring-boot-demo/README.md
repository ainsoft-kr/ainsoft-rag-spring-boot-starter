# Spring Boot Demo

`spring-boot-demo`는 `ainsoft-rag-spring-boot-starter` 사용 예제입니다.

## Run

```bash
./gradlew :spring-boot-demo:bootRun
```

## Dependency

예제는 starter를 통해 Ainsoft RAG를 참조합니다.

```kotlin
implementation("com.ainsoft.rag:ainsoft-rag-spring-boot-starter:0.1.0")
implementation("com.ainsoft.rag:parsers-api:0.1.0")
```

## Endpoints

- `POST /api/rag/ingest`
- `POST /api/rag/ingest-file`
- `POST /api/rag/search`
- `POST /api/rag/diagnose-search`
- `GET /api/rag/stats`

실제 설정 예시는 `src/main/resources/application.yml`에 있습니다.
