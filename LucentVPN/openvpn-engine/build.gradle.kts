import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

plugins {
    id("com.android.library")
}

val upstream = rootProject.file("ics-openvpn/main")

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 35
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "openvpn3", "true")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_PLATFORM=android-26"
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    sourceSets.getByName("main") {
        manifest.srcFile(upstream.resolve("src/main/AndroidManifest.xml"))
        java.setSrcDirs(listOf(upstream.resolve("src/main/java")))
        aidl.setSrcDirs(listOf(upstream.resolve("src/main/aidl")))
        res.setSrcDirs(listOf(upstream.resolve("src/main/res")))
        assets.setSrcDirs(listOf(upstream.resolve("build/ovpnassets")))
    }

    externalNativeBuild {
        cmake {
            path = upstream.resolve("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

abstract class GenerateOpenVpnSwig : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    val taskName = "generateOpenVpnSwig${variant.name.replaceFirstChar { it.uppercase() }}"
    val output = layout.buildDirectory.dir("generated/source/ovpn3swig/${variant.name}")
    val task = tasks.register<GenerateOpenVpnSwig>(taskName) {
        outputDir.set(output)
        val generated = output.get().asFile.resolve("net/openvpn/ovpn3")
        doFirst { generated.mkdirs() }
        workingDir(upstream)
        commandLine(
            "swig",
            "-outdir", generated.absolutePath,
            "-outcurrentdir",
            "-c++",
            "-java",
            "-package", "net.openvpn.ovpn3",
            "-Isrc/main/cpp/openvpn3/client",
            "-Isrc/main/cpp/openvpn3/",
            "-DOPENVPN_PLATFORM_ANDROID",
            "-o", generated.resolve("ovpncli_wrap.cxx").absolutePath,
            "-oh", generated.resolve("ovpncli_wrap.h").absolutePath,
            "src/main/cpp/openvpn3/client/ovpncli.i",
        )
        inputs.file(upstream.resolve("src/main/cpp/openvpn3/client/ovpncli.i"))
    }
    variant.sources.java?.addGeneratedSourceDirectory(task, GenerateOpenVpnSwig::outputDir)
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.15.0")
}
