import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release potpis: ključ je VAN projekta (%USERPROFILE%\.padsampler\keystore.properties + release.jks), bez lozinki u kodu.
// Ako fajla nema, release se ne potpisuje (debug radi normalno).
val keystoreProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".padsampler/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Verzija ista kao desktop (desktop-app/gradle.properties appVersion), npr. 2.0.0 -> versionCode 20000
val appVersion: String = Properties().apply {
    rootProject.file("desktop-app/gradle.properties").inputStream().use { load(it) }
}.getProperty("appVersion", "0.1.0")
val appVersionCode: Int = appVersion.split(".").map { it.toIntOrNull() ?: 0 }.let { v ->
    v.getOrElse(0) { 0 } * 10000 + v.getOrElse(1) { 0 } * 100 + v.getOrElse(2) { 0 }
}

// Ekran About: LICENSE, THIRD_PARTY_NOTICES.md, licenses/* i version.txt -> Java resursi /legal/ (data/Legal.kt)
val legalOut = layout.buildDirectory.dir("generated/legal")
val copyLegal by tasks.registering(Sync::class) {
    val v = appVersion
    inputs.property("appVersion", v)
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("licenses"))
    into(legalOut.map { it.dir("legal") })
    doLast { legalOut.get().file("legal/version.txt").asFile.writeText(v) }
}
tasks.named("preBuild") { dependsOn(copyLegal) }

android {
    namespace = "com.example.padsampler"
    compileSdk = 36

    defaultConfig {
        // ID na Google Playu (od domena nerd-instruments.net); NE mijenjati poslije objave. Paketi u kodu ostaju
        // com.example.padsampler (namespace, JNI imena u jni_bridge.cpp).
        applicationId = "net.nerdinstruments.padsampler"
        minSdk = 26
        targetSdk = 36   // Google Play: nove aplikacije i ažuriranja od 31. 8. 2026
        versionCode = appVersionCode
        versionName = appVersion

        ndk {
            // arm64-v8a = Redmi Pad; x86_64 = emulator
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Obavezno za Oboe prefab paket
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../shared/src/main/kotlin")
            resources.srcDir(legalOut)
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.3")   // 1.9.3: liboboe.so poravnat na 16 KB

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
