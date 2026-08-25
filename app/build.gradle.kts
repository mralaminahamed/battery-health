import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/**
 * Release signing credentials, from `keystore.properties` (gitignored) or, failing that, the
 * environment -- so CI can supply them without a file on disk.
 *
 * Null when nothing is configured, which is the normal state for a fresh clone and for anyone
 * who only ever builds debug. The release build type reacts to that by staying *unsigned*
 * rather than falling back to the debug key: a debug-signed release is rejected by Play at
 * upload, and worse, it is the kind of thing that gets noticed only after it ships somewhere.
 * Failing to produce an artifact is the safer failure.
 *
 * The keystore itself must never be committed. Losing it means losing the ability to update
 * the app under the same identity, so back it up somewhere outside this repository.
 */
val releaseSigning: Map<String, String>? = run {
    val fromFile = rootProject.file("keystore.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

    fun value(key: String, env: String): String? =
        fromFile?.getProperty(key)?.takeIf(String::isNotBlank)
            ?: System.getenv(env)?.takeIf(String::isNotBlank)

    val storePath = value("storeFile", "RELEASE_STORE_FILE") ?: return@run null
    val storePassword = value("storePassword", "RELEASE_STORE_PASSWORD") ?: return@run null
    val keyAlias = value("keyAlias", "RELEASE_KEY_ALIAS") ?: return@run null
    val keyPassword = value("keyPassword", "RELEASE_KEY_PASSWORD") ?: return@run null

    mapOf(
        "storeFile" to storePath,
        "storePassword" to storePassword,
        "keyAlias" to keyAlias,
        "keyPassword" to keyPassword,
    )
}

android {
    namespace = "com.alaminahamed.batteryhealth"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.alaminahamed.batteryhealth"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            isDefault = true
        }
        create("full") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        // Only declared when credentials were actually found. Declaring it unconditionally
        // would let a half-configured setup produce an artifact signed with blank or stale
        // values, which fails much later and much less clearly than not existing at all.
        releaseSigning?.let { creds ->
            create("release") {
                storeFile = rootProject.file(creds.getValue("storeFile"))
                storePassword = creds.getValue("storePassword")
                keyAlias = creds.getValue("keyAlias")
                keyPassword = creds.getValue("keyPassword")
                // Play's upload requirements: v1 is long dead, and Play App Signing
                // re-signs for distribution anyway, so what matters here is that the
                // upload artifact verifies under v2/v3.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Signed only when keystore.properties or the RELEASE_* environment variables
            // supplied every credential; otherwise this stays null and the build produces an
            // UNSIGNED release artifact. That is deliberate. The previous behaviour here was
            // to fall back to the debug keystore so the R8 build could be exercised locally,
            // and a debug-signed release is both rejected by Play at upload and exactly the
            // sort of thing that gets discovered only after it has shipped somewhere. An
            // artifact that cannot be installed is a far cheaper mistake than one that can.
            // See README "Release builds" for how to configure it.
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
                // keepRules.includeDefault defaults to true, which is the equivalent of
                // getDefaultProguardFile("proguard-android-optimize.txt") -- no manual
                // proguardFiles(...) call is needed for that baseline set under AGP 9's
                // DSL. Project-specific rules live in app/src/main/keepRules/, which AGP
                // discovers and combines automatically.
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.ext.compiler)
    ksp(libs.androidx.room.compiler)

    // Room's androidTest-only room-migration needs a newer kotlinx-serialization than
    // androidx.datastore contributes to the main classpath. AGP's test-consistent-resolution
    // mirrors the main classpath's resolved version onto androidTest as a strict constraint,
    // so the floor has to be raised here rather than on androidTestImplementation.
    constraints {
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
