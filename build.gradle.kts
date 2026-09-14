import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    base
}

require(JavaVersion.current() == JavaVersion.VERSION_25) {
    "Hortinis backend builds require Java 25; the current runtime is ${JavaVersion.current()}."
}

allprojects {
    group = "com.hortinis"
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Filters configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "$path must be run from the command line with the `--write-locks` flag" }
        }
        doLast {
            configurations.filter {
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