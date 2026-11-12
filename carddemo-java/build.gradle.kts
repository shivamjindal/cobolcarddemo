import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
}

group = "com.carddemo"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val legacyApp = rootDir.resolve("../app")

// Copybooks stay in app/cpy as the single source of truth; the loader reads them from the classpath.
sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated/legacy-resources"))
        }
    }
}

val copyCopybooks by tasks.registering(Copy::class) {
    from(legacyApp.resolve("cpy"))
    into(layout.buildDirectory.dir("generated/legacy-resources/copybooks"))
}

tasks.processResources {
    dependsOn(copyCopybooks)
}

val migration = "src/main/resources/db/migration/V1__vsam_tables.sql"

tasks.register<JavaExec>("generateDdl") {
    group = "carddemo"
    description = "Regenerates $migration from the copybooks in app/cpy."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.carddemo.vsam.DdlGenerator"
    args(file(migration).absolutePath)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("carddemo.legacy-app", legacyApp.absolutePath)
}
