# Ainsoft RAG Spring Boot Starter

`ainsoft-rag-spring-boot-starter`는 다른 Spring Boot 애플리케이션이 바로 가져다 쓸 수 있는 RAG starter입니다.

## What It Gives You

- `RagEngine` 자동 구성
- `llm.*` provider 설정 해석
- chunker, embedding, summarizer, reranker, stats cache 빈 구성
- tenant + ACL 기반 검색 필터
- `/rag-admin` 및 `/api/rag/admin`

## Copy-Paste Start

```kotlin
dependencies {
    implementation("com.ainsoft.rag:ainsoft-rag-spring-boot-starter:0.1.0")
}
```

설정 예시는 [`docs/consumer-application.yml`](/Users/ygpark2/pjt/ainsoft/rag/ainsoft-rag-spring-boot-starter/docs/consumer-application.yml) 를 보세요.

## Example Links

- 예제 앱: [`examples/spring-boot-demo`](/Users/ygpark2/pjt/ainsoft/rag/ainsoft-rag-spring-boot-starter/examples/spring-boot-demo)
- 소비자용 YAML: [`docs/consumer-application.yml`](/Users/ygpark2/pjt/ainsoft/rag/ainsoft-rag-spring-boot-starter/docs/consumer-application.yml)

## Notes

- starter는 라이브러리로 배포되는 진입점입니다.
- 실제 실행 코드는 `examples/spring-boot-demo`에 있습니다.
- 소비자 앱에서는 자기 프로젝트의 `src/main/resources/application.yml`에 설정을 넣으면 됩니다.
