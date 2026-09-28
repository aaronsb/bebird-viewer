// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The one place the app ID lives; the Kotlin sources sit under the same package root.
val appId = "com.bockelie.bebird"

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

    buildTypes {
        release {
            isMinifyEnabled = false
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
