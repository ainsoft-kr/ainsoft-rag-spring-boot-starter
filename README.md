# Ainsoft RAG Spring Boot Starter

`ainsoft-rag-spring-boot-starter`는 Ainsoft RAG 엔진을 Spring Boot 애플리케이션에서 바로 사용할 수 있게 해 주는 starter입니다.

이 starter는 다음 모듈을 전이 의존성으로 가져옵니다.

- `ainsoft-rag-spring-boot-autoconfigure`
- `core`
- `chunkers`
- `embeddings-api`
- `stats-cache-spi`
- `stats-cache-file`
- 필요 시 `reranker-onnx`

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
