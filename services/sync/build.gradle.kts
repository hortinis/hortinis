import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.util.zip.ZipFile

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

val integrationTestSourceSet = sourceSets.create("integrationTest")
integrationTestSourceSet.compileClasspath += sourceSets.main.get().output
integrationTestSourceSet.runtimeClasspath += sourceSets.main.get().output

val topologyTestSourceSet = sourceSets.create("topologyTest")
topologyTestSourceSet.compileClasspath += sourceSets.main.get().output
topologyTestSourceSet.runtimeClasspath += sourceSets.main.get().output
sourceSets.test.get().compileClasspath += topologyTestSourceSet.output
sourceSets.test.get().runtimeClasspath += topologyTestSourceSet.output
configurations[topologyTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.implementation.get(),
)
configurations[topologyTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.runtimeOnly.get(),
)

tasks.register<JavaExec>("topologyTestRun") {
    description = "Runs the real application with disposable synthetic scope authority."
    group = "verification"
    dependsOn(topologyTestSourceSet.classesTaskName)
    classpath = topologyTestSourceSet.runtimeClasspath
    mainClass.set("com.hortinis.topology.TopologyTestApplication")
}

val verifyProductionTopologyIsolation =
    tasks.register("verifyProductionTopologyIsolation") {
        description = "Checks that production runtime and executable archive exclude topology fixtures."
        group = "verification"
        val archive = tasks.named<BootJar>("bootJar")
        val plainArchive = tasks.named<Jar>("jar")
        dependsOn(archive, plainArchive, topologyTestSourceSet.classesTaskName)
        doLast {
            val testPaths = topologyTestSourceSet.output.files
            require(
                sourceSets.main
                    .get()
                    .runtimeClasspath.files
                    .none { it in testPaths },
            ) {
                "Production runtime includes topology-test output."
            }
            val archives =
                listOf(
                    archive
                        .get()
                        .archiveFile
                        .get()
                        .asFile,
                    plainArchive
                        .get()
                        .archiveFile
                        .get()
                        .asFile,
                )
            for (file in archives) {
                ZipFile(file).use { zip ->
                    require(
                        zip.entries().asSequence().none {
                            it.name.contains("com/hortinis/topology/") ||
                                it.name.contains("TopologyAccessResolver")
                        },
                    ) { "Production archive includes synthetic authority." }
                }
            }
        }
    }

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
    dependsOn("checkstyleTopologyTest", "pmdTopologyTest", verifyProductionTopologyIsolation)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
