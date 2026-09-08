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
