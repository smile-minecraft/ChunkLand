import org.gradle.api.artifacts.ProjectDependency
import java.util.jar.JarFile

plugins {
    id("java-library")
    `maven-publish`
}

// JitPack publishes whatever lands in the local Maven repository, under coordinates
// it derives from the GitHub repository itself (`com.github.<owner>.<repo>` for a
// multi-module project, `com.github.<owner>:<repo>` for a single-module one). The
// declared group/artifact below are therefore the *local* identity used to publish,
// consume and inspect the module; JitPack remaps them on the server side. Keeping them
// explicit avoids inheriting `rootProject.name` ("ChunkLand"), whose capitalisation
// would leak into the published file names.
group = "com.github.smile-minecraft.ChunkLand"
version = libs.versions.project.get()

publishing {
    publications {
        create<MavenPublication>("chunklandApi") {
            groupId = "com.github.smile-minecraft.ChunkLand"
            artifactId = "chunkland-api"
            version = project.version.toString()
            from(components["java"])
            pom {
                name.set("ChunkLand API")
                description.set(
                    "Pure domain API for ChunkLand: land, mutation, permission, money and event contracts."
                )
                url.set("https://github.com/smile-minecraft/ChunkLand")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("smile")
                        name.set("Smile")
                    }
                }
                scm {
                    url.set("https://github.com/smile-minecraft/ChunkLand")
                    connection.set("scm:git:https://github.com/smile-minecraft/ChunkLand.git")
                    developerConnection.set("scm:git:https://github.com/smile-minecraft/ChunkLand.git")
                }
            }
        }
    }
}

// Source and javadoc archives travel with the binary artifact. JitPack serves
// `-sources.jar` and `-javadoc.jar` for consumers that need to read the contract
// without downloading a toolchain, so both are wired into the publication rather
// than relying on JitPack regenerating them.
java {
    withSourcesJar()
    withJavadocJar()
}

// Doclint stays on (the default): the published javadoc jar is part of the consumer
// contract, so malformed tags must fail the build instead of shipping. Only the
// JDK-standard @implSpec tag needs registering for it to be recognised.
tasks.withType<Javadoc>().configureEach {
    val javadocOptions = options as StandardJavadocDocletOptions
    javadocOptions.encoding = "UTF-8"
    javadocOptions.tags("implSpec:a:Implementation Specification:")
}

// Dependency guard: chunkland-api is a pure domain/API module and must
// stay free of any external production dependency. It must never pull SQLite,
// Bukkit/Paper, AceLib, or any other implementation dependency onto a production
// classpath — those belong in chunkland-plugin. This task fails the build at
// configuration/verification time (wired into `check`, hence `build`) when a
// forbidden dependency is declared, so the rule is enforced mechanically rather
// than by review or at runtime. It inspects declared dependencies on the production
// configurations only, so test-only dependencies are never treated as violations,
// and it requires no network or external state.
// `compileOnlyApi` is included because it is published: `from(components.java)`
// maps compileOnlyApi onto the `apiElements` variant, so anything declared there
// lands in consumers' compile classpath. Leaving it out would let a dependency
// reach the published POM without tripping this guard.
val forbiddenApiProductionConfigurations =
    listOf("implementation", "api", "runtimeOnly", "compileOnly", "compileOnlyApi")

val checkApiNoExternalProductionDependencies by tasks.registering {
    val configs = forbiddenApiProductionConfigurations
    doLast {
        val violations = mutableListOf<String>()
        for (name in configs) {
            val config = configurations.findByName(name) ?: continue
            for (dep in config.dependencies) {
                if (dep is ProjectDependency) continue
                val id = buildString {
                    if (!dep.group.isNullOrBlank()) append(dep.group).append(':')
                    append(dep.name ?: "<unknown>")
                    if (!dep.version.isNullOrBlank()) append(':').append(dep.version)
                }
                violations.add("configuration '$name' -> $id")
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "chunkland-api must not declare any external production dependency. " +
                    "Violations found on the production classpath:\n  " +
                    violations.joinToString("\n  ") +
                    "\nRemove these from chunkland-api; SQLite, Bukkit/Paper, AceLib and any " +
                    "other production dependency belong in chunkland-plugin."
            )
        }
    }
}

// Wire into both the verification lifecycle (`check` -> `build`) and the production
// artifact (`jar`). The latter ensures the guard also fires when chunkland-api's jar
// is produced transitively (e.g. while building chunkland-plugin), not only when
// chunkland-api:build/check is invoked directly.
tasks.named("check") { dependsOn(checkApiNoExternalProductionDependencies) }
tasks.named("jar") { dependsOn(checkApiNoExternalProductionDependencies) }

// Published-artifact guard. The dependency guard above inspects declared
// configurations; this one inspects what a consumer actually receives, because the
// two can diverge: a shaded/embedded dependency, a resource bundle that ships a
// vendor jar, or an accidental `from(configurations...)` all leave the declared
// configuration empty while still leaking classes into the jar. Both the binary jar
// and the generated POM are checked so the published contract itself is verified,
// not merely the build script.
//
// Class-name prefixes that must never appear in the api jar: plugin implementation
// classes (the api module owns only com.smile.chunkland.api), the Bukkit/Paper server
// API, and AceLib. The api's own package is excluded explicitly so the check stays
// about foreign content rather than about the module's own code.
val forbiddenApiJarEntryPrefixes = listOf(
    "com/smile/chunkland/gui/",
    "com/smile/chunkland/land/",
    "com/smile/chunkland/limit/",
    "com/smile/chunkland/event/",
    "com/smile/chunkland/config/",
    "com/smile/chunkland/permission/",
    "com/smile/chunkland/selection/",
    "com/smile/chunkland/capability/",
    "com/smile/acelib/",
    "org/bukkit/",
    "io/papermc/",
    "net/milkbowl/",
    "net/luckperms/",
    "org/sqlite/",
)

val checkApiPublishedArtifact by tasks.registering {
    group = "verification"
    description = "Verifies the published api jar and POM carry no plugin, server or SQLite content."
    dependsOn("jar", "sourcesJar", "javadocJar", "generatePomFileForChunklandApiPublication")

    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val sourcesJarFile = tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile }
    val javadocJarFile = tasks.named<Jar>("javadocJar").flatMap { it.archiveFile }
    // GenerateMavenPom.destination is a plain File (not a Provider), so it is
    // captured at configuration time; the POM path is stable for a given project dir.
    val pomFile = tasks.named<GenerateMavenPom>("generatePomFileForChunklandApiPublication")
        .get().destination
    val forbiddenPrefixes = forbiddenApiJarEntryPrefixes

    inputs.file(jarFile)
    inputs.file(sourcesJarFile)
    inputs.file(javadocJarFile)
    inputs.file(pomFile)
    // This task asserts an absence (no forbidden entries, no POM dependencies), which
    // Gradle cannot express as an output, so it must always re-run.
    outputs.upToDateWhen { false }

    doLast {
        val violations = mutableListOf<String>()

        JarFile(jarFile.get().asFile).use { archive ->
            for (entry in archive.entries().toList()) {
                val name = entry.name
                for (prefix in forbiddenPrefixes) {
                    if (name.startsWith(prefix)) {
                        violations.add("jar entry '$name' matches forbidden prefix '$prefix'")
                    }
                }
            }
        }

        val pomText = pomFile.readText()
        // A published POM must declare zero dependencies. The api is self-contained
        // by contract, so any <dependency> element — even a test-scoped one leaking
        // into the POM — is a violation.
        if (pomText.contains("<dependency>")) {
            violations.add("published POM declares dependencies; chunkland-api must publish with none")
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "chunkland-api published artifact must stay free of plugin, server and SQLite content. " +
                    "Violations:\n  " + violations.joinToString("\n  ")
            )
        }

        logger.lifecycle(
            "checkApiPublishedArtifact: binary/sources/javadoc jars present, no foreign entries, " +
                "POM declares no dependencies"
        )
    }
}

// Aggregate entry point used by CI and by scripts/verify-api-publication.sh: build
// the api alone (tests + dependency guard), then publish and verify the published
// artifact. Wired as `check`'s sibling rather than into `check` itself, because
// publishing writes to a Maven repository and must not happen on every local build.
val apiPublicationCheck by tasks.registering {
    group = "verification"
    description = "Builds chunkland-api and verifies the artifacts a consumer receives."
    dependsOn("build", checkApiPublishedArtifact)
}