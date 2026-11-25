import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("org.json:json:20240303")
}

// Isti zajednički kod kao Android (shared/), plus logo iz Android resursa.
kotlin {
    sourceSets {
        getByName("main") {
            kotlin.srcDir("../shared/src/main/kotlin")
            resources.srcDir("../app/src/main/res/drawable")
            resources.srcDir(layout.buildDirectory.dir("generated/legal"))   // copyLegal (ispod)
        }
    }
}

// Ekran About: LICENSE, THIRD_PARTY_NOTICES.md, licenses/* i version.txt -> Java resursi /legal/ (data/Legal.kt)
val legalOut = layout.buildDirectory.dir("generated/legal")
val copyLegal by tasks.registering(Sync::class) {
    val v = (project.findProperty("appVersion") as String?) ?: "1.0.0"
    inputs.property("appVersion", v)
    from(file("../LICENSE"))
    from(file("../THIRD_PARTY_NOTICES.md"))
    from(file("../licenses"))
    into(legalOut.map { it.dir("legal") })
    doLast { legalOut.get().file("legal/version.txt").asFile.writeText(v) }
}
tasks.named("processResources") { dependsOn(copyLegal) }

// Motor (padsampler.dll, gradi ga CMake u desktop/) i fontovi idu u resurse aplikacije,
// odakle ih uzimaju i "gradlew run" i instalacijski paket.
val appRes = layout.projectDirectory.dir("resources")
// Windows: desktop/build/Release/padsampler.dll (Visual Studio); Linux: desktop/build/libpadsampler.so (Makefile/Ninja)
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val engineDll = if (isWindows) File(rootDir.parentFile, "desktop/build/Release/padsampler.dll")
                else File(rootDir.parentFile, "desktop/build/libpadsampler.so")

val copyEngine by tasks.registering(Copy::class) {
    from(engineDll)
    into(appRes.dir("common"))
    doFirst {
        if (!engineDll.isFile) throw GradleException("Missing $engineDll. Build the DLL first: cmake --build desktop/build --config Release")
    }
}
val copyFonts by tasks.registering(Copy::class) {
    from(File(rootDir.parentFile, "app/src/main/assets/fonts")) { include("*.ttf") }
    into(appRes.dir("common/fonts"))
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(copyEngine, copyFonts) }

// Instaler (msi/exe): slike PadSamplera umjesto WiX standardnih (crveni CD). main.wxs je kopija iz jpackage-a
// (JDK 21) sa dodatim WixVariable; putanja do slika se upiše ovdje (apsolutna). Slike: installer/*.bmp.
val installerRes = layout.buildDirectory.dir("installer-res")
val prepareInstallerResources by tasks.registering(Copy::class) {
    val resDir = installerRes.get().asFile.absolutePath.replace(File.separatorChar, '/')
    from("installer") {
        include("main.wxs.template")
        rename { "main.wxs" }
        filter { it.replace("@RES@", resDir) }
    }
    from("installer") { include("*.bmp") }
    into(installerRes)
}
// Compose (1.7.1) sam daje jpackage-u --resource-dir (zaštićeno polje jpackageResources) i taj folder isprazni
// prije pravljenja argumenata. Zato polje dobija provider koji pri svakom čitanju ponovo kopira naše fajlove:
// poslije pražnjenja ih čitanje za argumente vrati. Provjera: `--temp` u freeArgs, pa config/main.wxs ima WixVariable.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    if (name.contains("Exe") || name.contains("Msi")) {
        dependsOn(prepareInstallerResources)
        val src = installerRes.get().asFile
        val dst = layout.buildDirectory.dir("installer-res-$name").get().asFile
        val dir = layout.projectDirectory.dir(dst.absolutePath)
        val field = org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask::class.java
            .getDeclaredField("jpackageResources").apply { isAccessible = true }
        field.set(this, project.provider {
            dst.mkdirs()
            src.listFiles()?.forEach { it.copyTo(File(dst, it.name), overwrite = true) }
            dir
        })
        // -PjpTemp: jpackage ostavi radni folder (build/jp-temp-*) za provjeru
        if (project.hasProperty("jpTemp")) {
            val tmp = layout.buildDirectory.dir("jp-temp-$name").get().asFile
            freeArgs.addAll("--temp", tmp.absolutePath)
            doFirst { tmp.deleteRecursively() }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.example.padsampler.desktop.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            // Linux: createDistributable (folder sa bin/PadSampler, ide u USB image) ili packageDeb
            if (isWindows) targetFormats(TargetFormat.Msi, TargetFormat.Exe) else targetFormats(TargetFormat.Deb)
            packageName = "PadSampler"
            packageVersion = (project.findProperty("appVersion") as String?) ?: "1.0.0"
            description = "Live pad sampler"
            vendor = "PadSampler"
            includeAllModules = true
            appResourcesRootDir.set(appRes)
            // Instalacije idu u build/installer/main/{exe,msi,app} (ne u build/compose/binaries)
            outputBaseDir.set(layout.buildDirectory.dir("installer"))
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "PadSampler"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f0c2b8e-3a51-4c5e-9d0b-7a4e1f2c8d31"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                packageName = "padsampler"
                debMaintainer = "Nerd Instruments"
                menuGroup = "AudioVideo;Audio"
                appCategory = "sound"
            }
        }
    }
}
