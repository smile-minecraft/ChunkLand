import org.gradle.api.artifacts.ProjectDependency

plugins {
    id("java-library")
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
val forbiddenApiProductionConfigurations = listOf("implementation", "api", "runtimeOnly", "compileOnly")

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
