import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    kotlin("android")
    id("secant.android-build-conventions")
}

// The LeekWallet SDK (host/leek-zcash-ffi in the main repo): libleek_zcash_ffi.so for
// arm64-v8a and x86_64, its UniFFI Kotlin bindings (io.leekwallet.zcash.sdk) and the R8 rules
// for them and JNA. tools/leekwallet/build-ffi.sh builds all three into sdk/ (gitignored).
val sdkOut = layout.projectDirectory.dir("sdk")

android {
    namespace = "io.leekwallet.zcash.sdk"

    defaultConfig {
        consumerProguardFiles(sdkOut.file("consumer-rules.pro"))
    }

    sourceSets["main"].jniLibs.srcDir(sdkOut.dir("jniLibs"))
    sourceSets["main"].kotlin.srcDir(sdkOut.dir("kotlin"))
}

// Generated code: its warnings are UniFFI's, not ours.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.allWarningsAsErrors.set(false)
}

val checkLeekSdk by tasks.registering {
    val bindings = sdkOut.file("kotlin/io/leekwallet/zcash/sdk/leek_zcash_ffi.kt").asFile
    val so = sdkOut.file("jniLibs/arm64-v8a/libleek_zcash_ffi.so").asFile
    doLast {
        check(bindings.isFile && so.isFile) {
            "The LeekWallet SDK is not built: run tools/leekwallet/build-ffi.sh (LEEK_HOST_DIR=<main repo>/host)"
        }
    }
}
tasks.named("preBuild") { dependsOn(checkLeekSdk) }

dependencies {
    // The bindings load the library through JNA; the AAR carries Android's libjnidispatch.so.
    api("net.java.dev.jna:jna:${project.property("JNA_VERSION")}@aar")
    implementation(libs.kotlin.stdlib)
}
