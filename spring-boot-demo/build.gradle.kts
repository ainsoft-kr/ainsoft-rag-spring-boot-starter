import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm")
    id("org.springframework.boot")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":"))
    implementation("com.ainsoft.rag:parsers-api:${rootProject.findProperty("engineVersion")}")
    implementation("org.springframework.boot:spring-boot-starter:4.0.4")
    implementation("org.springframework.boot:spring-boot-starter-web:4.0.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test:4.0.4")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure:4.0.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.named<BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = false
}

val frontendDir = layout.projectDirectory.dir("frontend")
val frontendBuildOutput = frontendDir.dir("build")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
val skipFrontendBuild = providers.gradleProperty("skipFrontendBuild")
    .map(String::toBoolean)
    .orElse(false)

val frontendNpmInstall = tasks.register<Exec>("frontendNpmInstall") {
    group = "frontend"
    description = "Install the SvelteKit frontend dependencies."
    workingDir(frontendDir.asFile)
    commandLine(npmExecutable, "ci")
    onlyIf { !skipFrontendBuild.get() }
    inputs.files(
        frontendDir.file("package.json"),
        frontendDir.file("package-lock.json")
    )
    outputs.dir(frontendDir.dir("node_modules"))
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    description = "Build the SvelteKit frontend for Spring Boot static resources."
    dependsOn(frontendNpmInstall)
    workingDir(frontendDir.asFile)
    commandLine(npmExecutable, "run", "build")
    onlyIf { !skipFrontendBuild.get() }
    inputs.dir(frontendDir.dir("src"))
    inputs.files(
        frontendDir.file("package.json"),
        frontendDir.file("package-lock.json"),
        frontendDir.file("svelte.config.js"),
        frontendDir.file("playwright.config.js"),
        frontendDir.file("vite.config.js"),
        frontendDir.file("jsconfig.json")
    )
    outputs.dir(frontendBuildOutput)
}

tasks.named<ProcessResources>("processResources") {
    if (!skipFrontendBuild.get()) {
        dependsOn(buildFrontend)
    }
    from(frontendBuildOutput) {
        into("static")
    }
}
