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

// ---------------------------------------------------------------------------
// OpenVPN engine.
//
// The tunnel itself is provided by the ics-openvpn native core (openvpn3 +
// OpenSSL, GPLv2). It is consumed as a git submodule so that the native code
// is compiled into *this* APK -- the finished app does not talk to, or require,
// any externally installed VPN application.
//
// Run this once after cloning (see README.md):
//   git submodule add https://github.com/schwabe/ics-openvpn.git ics-openvpn
//   git -C ics-openvpn submodule update --init --recursive
// ---------------------------------------------------------------------------
val icsOpenVpnModule = file("ics-openvpn/main")
if (icsOpenVpnModule.resolve("build.gradle.kts").exists() ||
    icsOpenVpnModule.resolve("build.gradle").exists()
) {
    include(":openvpn")
    project(":openvpn").projectDir = icsOpenVpnModule
} else {
    throw GradleException(
        """
        The ics-openvpn submodule is missing.

        LucentVPN embeds the ics-openvpn native core to establish real OpenVPN
        tunnels. Fetch it before building:

            git submodule update --init --recursive

        or, for a fresh checkout:

            git submodule add https://github.com/schwabe/ics-openvpn.git ics-openvpn
            git -C ics-openvpn submodule update --init --recursive

        See README.md -> "Building" for the full toolchain requirements
        (Android NDK, CMake and SWIG).
        """.trimIndent()
    )
}
