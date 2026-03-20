# Spring Boot Demo

`spring-boot-demo`는 `ainsoft-rag-spring-boot-starter` 사용 예제입니다.

Boot 4 기준 테스트는 `MockMvcBuilders.webAppContextSetup(...)` 방식으로 웹 컨텍스트를 직접 구성해 검증합니다.

## Run

```bash
./gradlew :spring-boot-demo:bootRun
```

앱을 실행한 뒤 브라우저에서 `http://localhost:8080`으로 접속하면 SvelteKit 기반 데모 UI를 사용할 수 있습니다.
기본 설정에서는 애플리케이션 시작 시 샘플 문서 3개를 자동으로 색인합니다.
starter에 포함된 공통 관리자 UI도 함께 열리며 `http://localhost:8080/rag-admin`에서 접근할 수 있습니다.

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
- `GET /api/rag/provider-health`
- `POST /api/rag/admin/ingest`
- `POST /api/rag/admin/ingest-file`
- `POST /api/rag/admin/search`
- `POST /api/rag/admin/diagnose-search`
- `GET /api/rag/admin/stats`
- `GET /api/rag/admin/provider-health`

실제 설정 예시는 `src/main/resources/application.yml`에 있습니다.

## Frontend

프론트엔드 소스는 [frontend](/Users/ygpark2/pjt/ainsoft/rag/ainsoft-rag-spring-boot-starter/spring-boot-demo/frontend) 아래에 있습니다.

```bash
cd spring-boot-demo/frontend
npm install
npm run dev
```

Gradle `bootRun`, `bootJar`, `processResources`는 프론트엔드를 자동으로 빌드한 뒤 Spring Boot 정적 리소스로 포함합니다.
백엔드 작업만 빠르게 검증할 때는 `-PskipFrontendBuild=true` 로 프론트 재빌드를 생략할 수 있습니다.

LLM 답변 생성이나 query rewrite/summarizer 연결이 필요하면 `src/main/resources/application.yml`의 `llm.*` 섹션을 참고하세요.

## E2E

Playwright 스모크 테스트:

```bash
cd ../ainsoft-rag-engine
./gradlew publishPublicModulesToMavenLocal

cd ../ainsoft-rag-spring-boot-autoconfigure
./gradlew publishToMavenLocal

cd ../ainsoft-rag-spring-boot-starter/spring-boot-demo/frontend
npm install
npm run test:e2e:install
npm run test:e2e
```

`MAVEN_REPO_LOCAL` 환경 변수를 주면 Playwright가 띄우는 Spring Boot 서버도 해당 로컬 Maven 저장소를 사용합니다.
E2E는 기본적으로 `http://127.0.0.1:18080` 포트를 사용합니다.
테스트 서버는 Lucene lock 충돌을 피하기 위해 `spring-boot-demo/build/e2e-rag-index` 경로를 별도로 사용합니다.
