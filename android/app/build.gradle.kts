// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The one place the app ID lives; the Kotlin sources sit under the same package root.
val appId = "com.bockelie.bebird"

// Release signing comes only from the environment or Gradle properties (-P), never from a file in
// the repository. Without BEBIRD_KEYSTORE the release APK is built unsigned (CI on pull requests,
// contributors); it never falls back to the debug key.
fun signingValue(name: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull)?.takeIf { it.isNotEmpty() }
val releaseKeystore = signingValue("BEBIRD_KEYSTORE")

android {
    namespace = appId
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                fun required(name: String) = signingValue(name) ?: error("BEBIRD_KEYSTORE is set but $name is not")
                storeFile = file(releaseKeystore)
                storePassword = required("BEBIRD_KEYSTORE_PASSWORD")
                keyAlias = required("BEBIRD_KEY_ALIAS")
                keyPassword = required("BEBIRD_KEY_PASSWORD")
                // v1 (JAR signing) is only for Android 6 and older; v3 lets the key be rotated later.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // JVM tests run ScopeSession, which logs through android.util.Log: let the stubs no-op.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The build may rename or unpack assets (a ".gz" asset lands unpacked, without the extension),
// so tests reading src/main/assets can pass while the device can't find a file. After each
// assemble, check that the APK really contains every path in required-assets.txt.
val requiredAssets = file("required-assets.txt")
for (variant in listOf("debug", "release")) {
    val cap = variant.replaceFirstChar { it.uppercase() }
    val check = tasks.register("check${cap}ApkAssets") {
        val apkDir = layout.buildDirectory.dir("outputs/apk/$variant")
        inputs.file(requiredAssets)
        inputs.dir(apkDir)
        doLast {
            val wanted = requiredAssets.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
            check(apks.isNotEmpty()) { "no APK in ${apkDir.get().asFile}" }
            for (apk in apks) {
                ZipFile(apk).use { zip ->
                    val missing = wanted.filter { zip.getEntry("assets/$it") == null }
                    check(missing.isEmpty()) { "${apk.name} is missing assets: $missing" }
                }
            }
            logger.lifecycle("${apks.joinToString { it.name }}: all ${wanted.size} required assets present")
        }
    }
    tasks.matching { it.name == "assemble$cap" }.configureEach { finalizedBy(check) }
}

// ProtocolTest scans the main sources for the 66 3E fence: rerun it when they change at all,
// even in a way that leaves the bytecode alone.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/java"), fileTree("src/main/kotlin"))
        .withPropertyName("fencedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
