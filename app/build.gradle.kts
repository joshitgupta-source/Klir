plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.joshit.klir"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.joshit.klir"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign release builds with debug key locally until a production keystore is configured
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val buildRust by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compiles the klir_core Rust library for Android via cargo-ndk"

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val rustDir = file("../klir_core")
    val jniLibsDir = file("src/main/jniLibs")
    val cargoBinDir = file("${System.getProperty("user.home")}/.cargo/bin")
    val cargoExe = file("${cargoBinDir.absolutePath}/${if (isWindows) "cargo.exe" else "cargo"}")

    workingDir = rustDir

    inputs.dir(file("${rustDir.absolutePath}/src"))
    inputs.file(file("${rustDir.absolutePath}/Cargo.toml"))
    outputs.dir(jniLibsDir)

    val systemPath = System.getenv("PATH") ?: ""
    environment("PATH", "${cargoBinDir.absolutePath}${File.pathSeparator}$systemPath")

    // Uses the CI runner's NDK on GitHub Actions; falls back to local NDK on your PC
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
        ?: "C:\\Users\\ASUS\\AppData\\Local\\Android\\Sdk\\ndk\\30.0.16248370"
    environment("ANDROID_NDK_HOME", ndkHome)

    commandLine(
        if (cargoExe.exists()) cargoExe.absolutePath else "cargo",
        "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", jniLibsDir.absolutePath,
        "build",
        "--release"
    )
}

tasks.named("preBuild") {
    dependsOn(buildRust)
}