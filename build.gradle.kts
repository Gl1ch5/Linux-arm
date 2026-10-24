plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

// Termux:X11 builds its X server for every ABI by default; this app is arm64-only.
subprojects {
    if (path == ":lorie") {
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                defaultConfig.ndk.abiFilters += "arm64-v8a"
                // CI caches compiled objects with ccache (the X server is large).
                if (System.getenv("USE_CCACHE") == "1") {
                    defaultConfig.externalNativeBuild.cmake.arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache",
                    )
                }
            }
        }
    }
}
