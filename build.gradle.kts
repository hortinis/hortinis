import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    base
    id("com.diffplug.spotless") version "8.10.2" apply false
}

require(JavaVersion.current() == JavaVersion.VERSION_25) {
    "Hortinis backend builds require Java 25; the current runtime is ${JavaVersion.current()}."
}

allprojects {
    group = "com.hortinis"

    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "pmd")
    apply(plugin = "checkstyle")

    extensions.configure<CheckstyleExtension> {
        toolVersion = "14.1.0"
    }

    pluginManager.withPlugin("com.diffplug.spotless") {
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            java {
                googleJavaFormat("1.30.0")
            }
            kotlinGradle {
                ktlint()
            }
        }
    }
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Filters configurations at execution time")
        doFirst {
            require(
                gradle.startParameter.isWriteDependencyLocks,
            ) { "$path must be run from the command line with the `--write-locks` flag" }
        }
        doLast {
            configurations
                .filter {
                    it.isCanBeResolved
                }.forEach { it.resolve() }
        }
    }
    pluginManager.withPlugin("java-base") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(25)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release = 25
        }
    }
}
