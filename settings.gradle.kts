pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

include("spring-boot-demo")
project(":spring-boot-demo").projectDir = file("examples/spring-boot-demo")

rootProject.name = "ainsoft-rag-spring-boot-starter"
