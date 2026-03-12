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
includeBuild("../ainsoft-rag-engine")
includeBuild("../ainsoft-rag-spring-boot-autoconfigure")

rootProject.name = "ainsoft-rag-spring-boot-starter"
