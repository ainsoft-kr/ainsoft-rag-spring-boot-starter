import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.plugins.signing.SigningExtension
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

fun Project.propertyOrEnv(propertyName: String, envName: String): String? =
    (findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }

plugins {
    `java-library`
    id("maven-publish")
    id("signing")
    id("org.jetbrains.dokka") version "1.9.20"
    kotlin("jvm") version "2.3.0" apply false
    id("org.springframework.boot") version "3.3.2" apply false
    kotlin("plugin.spring") version "2.3.0" apply false
}

group = (findProperty("projectGroup") as String?) ?: "com.ainsoft.rag"
version = (findProperty("projectVersion") as String?) ?: "0.1.0-SNAPSHOT"

val engineVersion = (findProperty("engineVersion") as String?) ?: version.toString()
val autoconfigureVersion = (findProperty("autoconfigureVersion") as String?) ?: version.toString()
val springBootVersion = "3.3.2"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    api("com.ainsoft.rag:ainsoft-rag-spring-boot-autoconfigure:$autoconfigureVersion")
    api("org.springframework.boot:spring-boot-starter:$springBootVersion")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val placeholderDir = layout.buildDirectory.dir("generated/publication-placeholders")

val preparePublicationPlaceholders = tasks.register<Sync>("preparePublicationPlaceholders") {
    into(placeholderDir)
    from(layout.projectDirectory.file("PUBLIC_ARTIFACT_NOTICE.txt"))
}

val publicSourcesJar = tasks.register<Jar>("publicSourcesJar") {
    group = "build"
    description = "Build the placeholder sources archive required for public publishing."
    dependsOn(preparePublicationPlaceholders)
    archiveClassifier.set("sources")
    from(placeholderDir)
    include("PUBLIC_ARTIFACT_NOTICE.txt")
}

val publicJavadocJar = tasks.register<Jar>("publicJavadocJar") {
    group = "build"
    description = "Build the placeholder javadoc archive required for public publishing."
    dependsOn(preparePublicationPlaceholders)
    archiveClassifier.set("javadoc")
    from(placeholderDir)
    include("PUBLIC_ARTIFACT_NOTICE.txt")
}

publishing {
    repositories {
        maven {
            name = "central"
            val releaseUrl = propertyOrEnv(
                "publicMavenReleaseUrl",
                "PUBLIC_MAVEN_RELEASE_URL"
            ) ?: "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            val snapshotUrl = propertyOrEnv(
                "publicMavenSnapshotUrl",
                "PUBLIC_MAVEN_SNAPSHOT_URL"
            ) ?: "https://central.sonatype.com/repository/maven-snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotUrl else releaseUrl)
            credentials {
                username = propertyOrEnv("centralPortalUsername", "CENTRAL_PORTAL_USERNAME")
                    ?: propertyOrEnv("ossrhUsername", "OSSRH_USERNAME")
                password = propertyOrEnv("centralPortalPassword", "CENTRAL_PORTAL_PASSWORD")
                    ?: propertyOrEnv("ossrhPassword", "OSSRH_PASSWORD")
            }
        }

        val githubRepository = propertyOrEnv("githubPackagesRepository", "GITHUB_REPOSITORY")
        if (!githubRepository.isNullOrBlank()) {
            maven {
                name = "github"
                url = uri("https://maven.pkg.github.com/$githubRepository")
                credentials {
                    username = propertyOrEnv("githubPackagesUsername", "GITHUB_ACTOR")
                    password = propertyOrEnv("githubPackagesToken", "GITHUB_TOKEN")
                }
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(publicSourcesJar)
            artifact(publicJavadocJar)
            pom {
                name.set((findProperty("pomName") as String?) ?: project.name)
                description.set((findProperty("pomDescription") as String?) ?: project.name)
                url.set((findProperty("pomUrl") as String?) ?: "https://github.com/ainsoft/rag")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("ainsoft")
                        name.set("Ainsoft")
                        email.set("dev@ainsoft.com")
                    }
                }
                scm {
                    url.set((findProperty("pomScmUrl") as String?) ?: "https://github.com/ainsoft/rag")
                    connection.set(
                        (findProperty("pomScmConnection") as String?)
                            ?: "scm:git:https://github.com/ainsoft/rag.git"
                    )
                    developerConnection.set(
                        (findProperty("pomScmDeveloperConnection") as String?)
                            ?: "scm:git:ssh://git@github.com/ainsoft/rag.git"
                    )
                }
            }
        }
    }
}

signing {
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String?
    if (!signingKey.isNullOrBlank() && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenLocal()
        mavenCentral()
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jetbrains.dokka")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }

        tasks.named("dokkaHtml").configure {
            group = "documentation"
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val dokkaProjects = subprojects.filter { project ->
    project.buildFile.exists() && project.file("src/main").exists()
}

tasks.named("dokkaHtml").configure {
    group = "documentation"
}

tasks.register<Sync>("docs") {
    group = "documentation"
    description = "Generate Dokka HTML documentation for starter modules."
    val dokkaTasks = dokkaProjects
        .filter { it.pluginManager.hasPlugin("org.jetbrains.dokka") }
        .map { "${it.path}:dokkaHtml" }
    dependsOn(dokkaTasks)
    into(layout.buildDirectory.dir("docs/dokka"))
    dokkaProjects
        .filter { it.pluginManager.hasPlugin("org.jetbrains.dokka") }
        .forEach { project ->
        from(project.layout.buildDirectory.dir("dokka/html")) {
            into(project.name)
        }
    }
}

tasks.register("publishPublicModule") {
    group = "publishing"
    description = "Publish the starter module to the configured Maven Central repository."
    dependsOn("publish")
}

tasks.register("publishSnapshotToGitHubPackages") {
    group = "publishing"
    description = "Publish the snapshot build to GitHub Packages."
    val githubRepository = propertyOrEnv("githubPackagesRepository", "GITHUB_REPOSITORY")
    if (!githubRepository.isNullOrBlank()) {
        dependsOn("publishMavenPublicationToGithubRepository")
    }
    doFirst {
        check(!githubRepository.isNullOrBlank()) {
            "githubPackagesRepository or GITHUB_REPOSITORY must be set to publish snapshots to GitHub Packages"
        }
    }
}

tasks.register("uploadPublicReleaseToCentralPortal") {
    group = "publishing"
    description = "Upload the current release staging repository to the Maven Central Portal."
    onlyIf { !version.toString().endsWith("SNAPSHOT") }
    doLast {
        val namespace = propertyOrEnv("centralNamespace", "CENTRAL_NAMESPACE")
            ?: error("centralNamespace or CENTRAL_NAMESPACE is required for Central Portal uploads")
        val username = propertyOrEnv("centralPortalUsername", "CENTRAL_PORTAL_USERNAME")
            ?: propertyOrEnv("ossrhUsername", "OSSRH_USERNAME")
            ?: error("centralPortalUsername or CENTRAL_PORTAL_USERNAME is required")
        val password = propertyOrEnv("centralPortalPassword", "CENTRAL_PORTAL_PASSWORD")
            ?: propertyOrEnv("ossrhPassword", "OSSRH_PASSWORD")
            ?: error("centralPortalPassword or CENTRAL_PORTAL_PASSWORD is required")
        val publishingType = propertyOrEnv("centralPublishingType", "CENTRAL_PUBLISHING_TYPE")
            ?: "automatic"
        val encodedToken = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
        val requestUrl = URI(
            "https",
            "ossrh-staging-api.central.sonatype.com",
            "/manual/upload/defaultRepository/$namespace",
            "publishing_type=$publishingType",
            null
        ).toURL()
        val connection = (requestUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $encodedToken")
            setRequestProperty("Content-Length", "0")
        }
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                ?: "no response body"
            error("Central Portal upload failed with HTTP $responseCode: $errorBody")
        }
    }
}

tasks.register("publishPublicRelease") {
    group = "publishing"
    description = "Publish the release build and transfer it to Maven Central Portal."
    dependsOn("publishPublicModule")
    if (!version.toString().endsWith("SNAPSHOT")) {
        finalizedBy("uploadPublicReleaseToCentralPortal")
    }
}
