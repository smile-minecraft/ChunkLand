import org.gradle.api.GradleException
import org.gradle.api.Task
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
    compileOnly("com.smile.acelib:AceLib:1.3.0")
    // Compile-only: Vault Legacy Economy boundary (net.milkbowl.vault.economy).
    // Provided by the Vault plugin at runtime (softdepend: [Vault]); never embedded.
    compileOnly(libs.vault.api)
    // Compile-only: LuckPerms optional limit metadata (net.luckperms.api).
    // Provided by the LuckPerms plugin at runtime (softdepend: [LuckPerms]);
    // never embedded. Only LuckPermsMetaLookup references it, and discovery
    // loads that class solely after confirming LuckPerms is present.
    compileOnly(libs.luckperms.api)
    // Tests exercise the Bukkit lifecycle seams and the AceLib public API directly,
    // so both must be available on the test classpath. testImplementation does not
    // affect the plugin jar (only main sources are packaged).
    testImplementation(libs.paper.api)
    testImplementation("com.smile.acelib:AceLib:1.3.0")
    testImplementation(libs.vault.api)
}

// Self-contained plugin jar: Paper/Folia loads only this jar (no separate
// api/sqlite/snakeyaml jars in the server plugins directory), so the runtime
// dependencies must be embedded. runtimeClasspath carries exactly the
// implementation set (chunkland-api, sqlite-jdbc, snakeyaml + transitives);
// compileOnly (paper-api, AceLib) is server-provided and stays out.
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // runtimeClasspath embeds the :chunkland-api artifact, so the tasks that
    // produce that configuration (notably :chunkland-api:jar) must run first.
    // Wiring the configuration itself keeps the ordering correct on clean/CI
    // builds, where an absent api jar would otherwise make zipTree fail or, worse,
    // let a stale api jar be bundled silently.
    dependsOn(configurations.runtimeClasspath)
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

// Regression guard for the self-contained jar contract: because the jar embeds the
// :chunkland-api artifact, it must declare :chunkland-api:jar as a task dependency.
// Without it a clean/CI build can run this jar before the api jar exists (zipTree
// fails) or silently bundle a stale api jar. Wired into `check`, so `build` fails
// fast if the dependency is ever dropped again. The dependency set is captured at
// configuration time as plain strings so the check stays configuration-cache safe.
val pluginJarTask = tasks.named<Jar>("jar")
val apiJarTask = project(":chunkland-api").tasks.named<Jar>("jar")
val checkPluginJarBundlesApiJar by tasks.registering {
    val apiJarPath = apiJarTask.get().path
    val declaredTaskPaths = pluginJarTask.get().taskDependencies
        .getDependencies(pluginJarTask.get())
        .filterIsInstance<Task>()
        .map { it.path }
        .toSortedSet()
    doLast {
        if (apiJarPath !in declaredTaskPaths) {
            throw GradleException(
                "chunkland-plugin:jar must depend on $apiJarPath so the api artifact it " +
                    "embeds is produced first; declared task dependencies were $declaredTaskPaths."
            )
        }
    }
}
tasks.named("check") { dependsOn(checkPluginJarBundlesApiJar) }
