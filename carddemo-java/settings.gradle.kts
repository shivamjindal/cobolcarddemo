pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // The Spring Boot plugin marker is only on the Gradle Plugin Portal; resolving the module
    // directly lets builds run where only Maven Central is reachable.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "org.springframework.boot") {
                useModule("org.springframework.boot:spring-boot-gradle-plugin:${requested.version}")
            }
        }
    }
}

rootProject.name = "carddemo-java"
