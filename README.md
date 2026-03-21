# Ainsoft RAG Spring Boot Starter

`ainsoft-rag-spring-boot-starter`는 Ainsoft RAG 엔진을 Spring Boot 애플리케이션에서 바로 사용할 수 있게 해 주는 starter입니다.

이 starter는 "검색 엔진 라이브러리를 추가하는 의존성"이라기보다, Spring Boot 애플리케이션 안에 embedded RAG와 운영 콘솔을 빠르게 포함시키는 진입점입니다.

현재 이 모듈은 Spring Boot `4.0.4`와 JDK `24` 조합으로 검증합니다.

`llm.*` 공통 설정도 함께 사용할 수 있습니다. 새 설정이 있으면 이를 우선 사용하고, 없으면 기존 `rag.queryRewrite*`, `rag.summarizer*`, `rag.openAi*`로 fallback 합니다.

이 starter는 다음 모듈을 전이 의존성으로 가져옵니다.

- `ainsoft-rag-spring-boot-autoconfigure`
- `core`
- `chunkers`
- `embeddings-api`
- `stats-cache-spi`
- `stats-cache-file`
- 필요 시 `reranker-onnx`

## When To Use It

starter는 아래와 같은 경우에 가장 적합합니다.

- 내부 포털, 관리자 시스템, 사내 업무 도구에 검색을 내장할 때
- 외부 검색 인프라보다 애플리케이션 내부 통합을 우선할 때
- tenant/ACL 기반 문서 검색을 서비스 코드와 함께 운영할 때
- 운영 팀이 `/rag-admin`에서 ingest, diagnostics, index ops를 직접 다뤄야 할 때

## Maven Coordinate

Gradle:

```kotlin
implementation("com.ainsoft.rag:ainsoft-rag-spring-boot-starter:0.1.0")
```

Maven:

```xml
<dependency>
  <groupId>com.ainsoft.rag</groupId>
  <artifactId>ainsoft-rag-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Basic Usage

```yaml
rag:
  indexPath: ./rag-index
  embeddingProvider: hash
  chunkerType: basic
```

LLM API를 함께 쓰는 예시:

```yaml
llm:
  defaultProvider: openai
  providers:
    openai:
      kind: openai-compatible
      baseUrl: https://api.openai.com/v1
      apiKey: ${OPENAI_API_KEY}
      model: gpt-4o-mini
    gemini:
      kind: gemini
      baseUrl: https://generativelanguage.googleapis.com/v1beta/models
      apiKey: ${GEMINI_API_KEY}
      model: gemini-2.0-flash
    anthropic:
      kind: anthropic
      baseUrl: https://api.anthropic.com/v1
      apiKey: ${ANTHROPIC_API_KEY}
      model: claude-3-5-sonnet-latest
  queryRewrite:
    provider: openai
    model: gpt-4o-mini
  summarizer:
    provider: openai
    model: gpt-4o-mini
```

위 설정만으로도 다음 구성이 기본 활성화됩니다.

- Lucene 기반 local index
- heuristic embedding/hash provider
- 기본 chunking
- `RagEngine` 빈
- servlet 웹 애플리케이션인 경우 admin UI/API 자동 등록

```kotlin
import com.ainsoft.rag.api.RagEngine
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SampleController(
    private val ragEngine: RagEngine
) {
    @GetMapping("/health/rag")
    fun health(): Map<String, Any> = mapOf("docs" to ragEngine.stats().docs)
}
```

## Recommended Production-Oriented Configuration

운영형 애플리케이션에서는 아래와 같이 품질 제어와 운영 export를 같이 설정하는 편이 일반적입니다.

```yaml
rag:
  indexPath: ./rag-index
  embeddingProvider: openai
  openAiModel: text-embedding-3-small
llm:
  defaultProvider: openai
  providers:
    openai:
      kind: openai-compatible
      baseUrl: https://api.openai.com/v1
      apiKey: ${OPENAI_API_KEY}
      model: gpt-4o-mini
  queryRewrite:
    provider: openai
    model: gpt-4o-mini
  summarizer:
    provider: openai
    model: gpt-4o-mini
  chunkerType: sliding
  slidingWindowSize: 240
  slidingOverlap: 40
  contextualRetrievalEnabled: true
  rerankerEnabled: true
  rerankerType: heuristic
  rerankerTopN: 24
  correctiveRetrievalEnabled: true
  correctiveMinConfidence: 0.08
  queryRewriteEnabled: true
  queryRewriterType: heuristic
  hierarchicalSummariesEnabled: true
  statsCacheStoreType: file
  statsCacheFilePath: ./rag-index/stats-cache.json
  providerHealthAutoExportIntervalMillis: 10000
  providerHealthAutoExportWindowMillis: 60000
```

외부 provider를 쓰지 않는 온프레미스 환경에서는 `embeddingProvider: hash`, `rerankerType: heuristic`, `summarizerType: rule-based` 조합으로 시작한 뒤, 품질 요구가 높아질 때 provider 기반 설정을 추가하는 방식이 현실적입니다.

지원하는 LLM provider kind는 `openai-compatible`, `openai`, `anthropic`, `claude`, `gemini`, `google-gemini`, `vertex`, `vertex-ai`, `vertex-gemini`입니다.
`vertex*` 계열은 프로젝트/리전별 base URL을 명시해 주는 편이 안전합니다.

## Retrieval and Security Characteristics

starter를 통해 등록되는 엔진은 아래 특징을 그대로 가집니다.

- hybrid retrieval
- corrective retrieval
- optional query rewrite
- reranker
- hierarchical summaries
- tenant + ACL filter query

즉, 검색 결과 보안은 애플리케이션 컨트롤러 후처리가 아니라 엔진 query 구성 단계에서 반영됩니다.

## Admin UI

웹 애플리케이션에서 `spring-boot-starter-web`이 함께 있으면 starter가 공통 관리자 UI와 운영 API를 자동 등록합니다.

- UI: `/rag-admin`
- API: `/api/rag/admin`

설정 예시:

```yaml
rag:
  admin:
    enabled: true
    basePath: /rag-admin
    apiBasePath: /api/rag/admin
    defaultRecentProviderWindowMillis: 60000
```

관리 화면에서 제공하는 기본 기능:

- 텍스트/파일 ingest
- 웹사이트 crawl ingest
- 검색
- 검색 진단
- 인덱스 통계
- provider health 조회

실제 운영 관점에서는 아래 화면들이 함께 제공됩니다.

- documents browser / source preview
- provider history
- search audit
- job history
- tenants & index operations
- config
- access & security
- bulk operations

관리 화면을 끄려면 아래처럼 설정합니다.

```yaml
rag:
  admin:
    enabled: false
```

보안이 필요한 경우 feature-role 기반 정책을 함께 설정할 수 있습니다.

```yaml
rag:
  admin:
    enabled: true
    security:
      enabled: true
      tokenHeaderName: X-Rag-Admin-Token
      tokens:
        admin-token: ADMIN
        ops-token: OPS
        audit-token: AUDITOR
```

## Minimal Search Endpoint Example

애플리케이션에서 보통은 `tenantId`와 `principals`를 명시해 검색을 호출합니다.

```kotlin
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.api.SearchRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class SearchController(
    private val ragEngine: RagEngine
) {
    @GetMapping("/api/search")
    fun search(@RequestParam q: String): Any =
        ragEngine.search(
            SearchRequest(
                tenantId = "tenant-admin",
                principals = listOf("user:1", "group:ops"),
                query = q,
                topK = 5
            )
        )
}
```

이 방식은 tenant와 ACL principal을 함께 전달하는 Ainsoft RAG의 기본 사용 패턴을 보여줍니다.

## Demo

예제 애플리케이션은 [spring-boot-demo](/Users/ygpark2/pjt/ainsoft/rag/ainsoft-rag-spring-boot-starter/spring-boot-demo) 에 있습니다.

실행:

```bash
./gradlew :spring-boot-demo:bootRun
```

## Build

```bash
./gradlew build
```

## Docs

Kotlin API 문서는 Dokka로 생성합니다.

```bash
./gradlew docs
```

생성 결과:

- starter 멀티모듈: `build/docs/dokka/<module>/index.html`

## Local Maven Flow

로컬 개발에서는 composite build 대신 `mavenLocal()` 소비를 사용합니다. 순서는 아래와 같습니다.

```bash
cd ../ainsoft-rag-engine
./gradlew publishPublicModulesToMavenLocal

cd ../ainsoft-rag-spring-boot-autoconfigure
./gradlew publishToMavenLocal

cd ../ainsoft-rag-spring-boot-starter
./gradlew build
```

버전을 바꾸면 세 프로젝트의 `engineVersion`, `autoconfigureVersion`, `projectVersion`을 동일하게 맞춰야 합니다.

## Publishing

snapshot:

```bash
./gradlew publishPublicModule
```

release:

```bash
./gradlew publishPublicRelease
```

`sources.jar`와 `javadoc.jar`는 Maven Central 요구사항 충족용 placeholder archive로 배포됩니다.

필요한 로컬 설정 예시는 `gradle.properties.template`에 있습니다.

GitHub Actions secrets:

- `MAVEN_CENTRAL_NAMESPACE`
- `MAVEN_PORTAL_USERNAME`
- `MAVEN_PORTAL_PASSWORD`
- `MAVEN_CENTRAL_PUBLISHING_TYPE`
- `MAVEN_SIGNING_KEY`
- `MAVEN_SIGNING_PASSWORD`

로컬 `~/.gradle/gradle.properties` 또는 CI secret으로 들어가야 하는 핵심 키:

- `centralPortalUsername`
- `centralPortalPassword`
- `centralPublishingType`
- `signingKey`
- `signingPassword`
