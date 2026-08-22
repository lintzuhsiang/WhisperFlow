pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // JitPack – uncomment if you need any com.github.* artifacts
        // maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Mobile Live Translation"
// Single-module project – no include(":app") needed.
// The module build file is app/build.gradle.kts (this directory IS the project root).
