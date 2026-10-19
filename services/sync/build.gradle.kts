import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

val passwordCorpusRevision = "c205c36a445bff37f8e58a9ec829105cd4975c58"
val passwordCorpusSourceSha256 = "c95989fecfae9c91aa9f5c730344d5cabd6335bb094c9c60aba16bf1742a72fe"
val passwordCorpusArtifactSha256 = "2d136a42a3d7d9d4165dec0cf0fabc3e84cee4865ba290a50c9cfcec4b980870"
val passwordCorpusLicenseSha256 = "3dbdc93d5f8829de0941744841730a09c106d0732e5ae0e98ca1d77be7ded66c"
val passwordCorpusSourceLineCount = 5_189_454
val passwordCorpusEligibleLineCount = 141_033
val passwordCorpusEntryCount = 10_000
val passwordCorpusSourceUrl =
    "https://raw.githubusercontent.com/danielmiessler/SecLists/$passwordCorpusRevision/" +
        "Passwords/Common-Credentials/xato-net-10-million-passwords.txt"
val passwordCorpusOutput =
    layout.buildDirectory.file("generated/password-corpus/main/authentication/password-blocklist.sha256")
val passwordCorpusResources = layout.buildDirectory.dir("generated/password-corpus/main")
val passwordCorpusLicense = layout.projectDirectory.file("src/main/resources/authentication/LICENSE.txt")

fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file.toPath()).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

val generatePasswordCorpus =
    tasks.register("generatePasswordCorpus") {
        description = "Fetches and verifies the pinned password reference corpus for the application."
        group = "build"
        inputs.properties(
            mapOf(
                "sourceUrl" to passwordCorpusSourceUrl,
                "sourceSha256" to passwordCorpusSourceSha256,
                "artifactSha256" to passwordCorpusArtifactSha256,
                "licenseSha256" to passwordCorpusLicenseSha256,
                "sourceLineCount" to passwordCorpusSourceLineCount,
                "eligibleLineCount" to passwordCorpusEligibleLineCount,
                "entryCount" to passwordCorpusEntryCount,
            ),
        )
        inputs.file(passwordCorpusLicense)
        outputs.file(passwordCorpusOutput)

        doLast {
            val cacheFile =
                gradle.gradleUserHomeDir.resolve(
                    "caches/hortinis/password-corpus/$passwordCorpusRevision/xato-passwords.txt",
                )
            val licenseFile = passwordCorpusLicense.asFile
            require(sha256(licenseFile) == passwordCorpusLicenseSha256) {
                "Pinned password corpus license is missing or has changed."
            }

            if (!cacheFile.isFile || sha256(cacheFile) != passwordCorpusSourceSha256) {
                cacheFile.parentFile.mkdirs()
                val cacheTemporaryFile =
                    Files.createTempFile(cacheFile.parentFile.toPath(), "xato-passwords-", ".part").toFile()
                try {
                    val connection = URI(passwordCorpusSourceUrl).toURL().openConnection()
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 60_000
                    connection.getInputStream().use { input ->
                        cacheTemporaryFile.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    require(sha256(cacheTemporaryFile) == passwordCorpusSourceSha256) {
                        "Downloaded password reference source does not match its pinned checksum."
                    }
                    Files.move(
                        cacheTemporaryFile.toPath(),
                        cacheFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    cacheTemporaryFile.delete()
                }
            }

            val eligiblePasswords = LinkedHashSet<String>()
            var sourceLines = 0
            var eligibleLines = 0
            Files.newBufferedReader(cacheFile.toPath(), StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val password = reader.readLine() ?: break
                    sourceLines++
                    val scalarCount = password.codePointCount(0, password.length)
                    val utf8Length = password.toByteArray(StandardCharsets.UTF_8).size
                    if (scalarCount in 15..128 && utf8Length <= 1024) {
                        eligibleLines++
                        if (eligiblePasswords.size < passwordCorpusEntryCount) {
                            eligiblePasswords.add(password)
                        }
                    }
                }
            }
            require(sourceLines == passwordCorpusSourceLineCount) {
                "Pinned password reference source has an unexpected line count."
            }
            require(eligibleLines == passwordCorpusEligibleLineCount) {
                "Pinned password reference source has an unexpected eligible-entry count."
            }
            require(eligiblePasswords.size == passwordCorpusEntryCount) {
                "Pinned password reference source does not contain enough eligible entries."
            }

            val artifact =
                eligiblePasswords
                    .map { sha256(it.toByteArray(StandardCharsets.UTF_8)) }
                    .sorted()
                    .joinToString(separator = "", postfix = "") { "$it\n" }
                    .toByteArray(StandardCharsets.US_ASCII)
            require(sha256(artifact) == passwordCorpusArtifactSha256) {
                "Generated password reference data does not match its pinned checksum."
            }
            val outputFile = passwordCorpusOutput.get().asFile
            outputFile.parentFile.mkdirs()
            outputFile.writeBytes(artifact)
        }
    }

sourceSets.main {
    resources.srcDir(passwordCorpusResources)
}

tasks.named("processResources") {
    dependsOn(generatePasswordCorpus)
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
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("com.password4j:password4j:1.8.4")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
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
        exclude("**/R5BrowserIntegrationTest*")
        description = "Runs PostgreSQL integration tests against Testcontainers."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        shouldRunAfter(tasks.test)
        systemProperty("hortinis.operator.test.classpath", integrationTestSourceSet.runtimeClasspath.asPath)
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

val authenticationBrowserTest =
    tasks.register<Test>("authenticationBrowserTest") {
        description = "Runs the isolated Angular/native Spring/PostgreSQL replacement browser fixture."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        include("**/R5BrowserIntegrationTest.class")
        inputs.file(rootProject.file("apps/web/e2e/r5-browser.mjs"))
        inputs.dir(rootProject.file("apps/web/dist/web/browser"))
        systemProperty("hortinis.repository.root", rootProject.projectDir.absolutePath)
        useJUnitPlatform()
    }

val verifyAuthenticationReplacementIsolation =
    tasks.register("verifyAuthenticationReplacementIsolation") {
        group = "verification"
        val archives = listOf(tasks.named<BootJar>("bootJar"), tasks.named<Jar>("jar"))
        dependsOn(archives)
        doLast {
            require(
                sourceSets.main
                    .get()
                    .runtimeClasspath.files
                    .none { it in integrationTestSourceSet.output.files },
            ) {
                "Production runtime includes authentication test assembly."
            }
            val retired =
                listOf(
                    "BoundWebAuthnOperations",
                    "AuthenticationCredential",
                    "AuthenticationOperatorTickets",
                    "AuthenticationAdmissionCapacity",
                    "OperatorTicketFiles",
                    "com/hortinis/r2/",
                    "com/hortinis/r3/",
                    "com/hortinis/r4/",
                    "com/hortinis/r5/",
                    "com/hortinis/r6/",
                    "spring-security-webauthn",
                    "webauthn4j",
                    "jackson-dataformat-cbor",
                )
            require(
                configurations.runtimeClasspath
                    .get()
                    .files
                    .none { file -> retired.any { file.name.contains(it) } },
            )
            for (archive in archives) {
                ZipFile(
                    archive
                        .get()
                        .archiveFile
                        .get()
                        .asFile,
                ).use { zip ->
                    require(zip.entries().asSequence().none { entry -> retired.any { entry.name.contains(it) } }) {
                        "Production archive contains retired authentication or a synthetic fixture."
                    }
                }
            }
        }
    }
tasks.named("check") { dependsOn(verifyAuthenticationReplacementIsolation) }
