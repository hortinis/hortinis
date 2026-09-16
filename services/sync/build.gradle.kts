plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

val integrationTestSourceSet = sourceSets.create("integrationTest")
integrationTestSourceSet.compileClasspath += sourceSets.main.get().output
integrationTestSourceSet.runtimeClasspath += sourceSets.main.get().output

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add("integrationTestImplementation", "org.springframework.boot:spring-boot-testcontainers")
    add("integrationTestImplementation", "org.testcontainers:testcontainers-junit-jupiter")
    add("integrationTestImplementation", "org.testcontainers:testcontainers-postgresql")
}

sourceSets {
    test {
        resources.srcDir(rootProject.file("contracts/sync/fixtures"))
    }
}

configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs PostgreSQL integration tests against Testcontainers."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        shouldRunAfter(tasks.test)
        useJUnitPlatform()
    }

tasks.named("check") {
    dependsOn(integrationTest)
    dependsOn("checkstyleIntegrationTest", "pmdIntegrationTest")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
