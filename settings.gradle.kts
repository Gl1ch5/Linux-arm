pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "linux-arm"

include(":app")

// Embedded X server (Termux:X11, GPL-3.0), pinned as a git submodule.
include(":lorie")
project(":lorie").projectDir = file("third_party/termux-x11/lorie")
include(":shell-loader:stub")
// Only the stub (hidden framework API declarations) is needed; the parent project
// gets an empty directory so that the real shell-loader is never configured.
project(":shell-loader").projectDir = file("third_party/shell-loader-placeholder")
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")
