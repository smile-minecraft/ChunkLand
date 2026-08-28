pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Paper / Folia API for compile-only plugin development (server provides it at runtime).
        maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
        // AceLib is a server-provided plugin: resolved compile-only from the locally
        // built jar (produced by scripts/build-acelib.sh), never embedded.
        val aceCache = System.getenv("ACE_OUTPUT_DIR")
            ?: "${System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache"}/chunkland-acelib"
        flatDir { dirs(file(aceCache)) }
    }
}

rootProject.name = "ChunkLand"

include(":chunkland-api")
include(":chunkland-plugin")
