import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

plugins {
    id("java-library")
}

dependencies {
    implementation(project(":chunkland-api"))
    implementation(libs.sqlite.jdbc)
    // ConfigService loads config.yml at runtime; snakeyaml is therefore a
    // production dependency (not test-only).
    implementation(libs.snakeyaml)
    // Compile-only: provided by the server at runtime (Paper / Folia).
    compileOnly(libs.paper.api)
    // Compile-only: AceLib is a server-provided plugin (depend: [AceLib] in plugin.yml).
    // Resolved from the locally built jar via the flatDir repo; never embedded.
    compileOnly("com.smile.acelib:AceLib:1.2.0")

    // Tests exercise the Bukkit lifecycle seams and the AceLib public API directly,
    // so both must be available on the test classpath. testImplementation does not
    // affect the plugin jar (only main sources are packaged).
    testImplementation(libs.paper.api)
    testImplementation("com.smile.acelib:AceLib:1.2.0")
}

// Self-contained plugin jar: Paper/Folia loads only this jar (no separate
// api/sqlite/snakeyaml jars in the server plugins directory), so the runtime
// dependencies must be embedded. runtimeClasspath carries exactly the
// implementation set (chunkland-api, sqlite-jdbc, snakeyaml + transitives);
// compileOnly (paper-api, AceLib) is server-provided and stays out.
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Expand plugin.yml placeholders (e.g. version) from the project model so the
// packaged descriptor carries the resolved version rather than a literal token.
tasks.named<ProcessResources>("processResources") {
    val version = project.version
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}
