pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // ics-openvpn's build scripts declare repositories inside the module itself,
    // so we must not fail the build when a subproject adds its own.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LucentVPN"

include(":app")

// ics-openvpn's upstream :main project is a standalone Android application,
// which cannot be consumed as a library dependency. This small wrapper module
// compiles the pinned upstream core/resources/native sources as an Android
// library without modifying the submodule itself.
val icsOpenVpnModule = file("ics-openvpn/main")
if (!icsOpenVpnModule.resolve("build.gradle.kts").exists()) {
    throw GradleException(
        "The ics-openvpn submodule is missing. Run: git submodule update --init --recursive"
    )
}
include(":openvpn-engine")
