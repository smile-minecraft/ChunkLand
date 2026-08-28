import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = libsCatalog.findVersion("java").get().requiredVersion.toInt()
val projectVersion = libsCatalog.findVersion("project").get().requiredVersion
val junitJupiter = libsCatalog.findLibrary("junit-jupiter").get()
val junitPlatformLauncher = libsCatalog.findLibrary("junit-platform-launcher").get()

plugins {
    base
}

allprojects {
    group = "com.smile.chunkland"
    version = projectVersion
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(javaVersion)
        }
        withSourcesJar()
    }

    dependencies {
        add("testImplementation", junitJupiter)
        add("testRuntimeOnly", junitPlatformLauncher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Reproducible archives: drop timestamps and fix entry order so repeated
    // builds on different machines (local vs CI) produce byte-identical jars.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
