plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.mralaminahamed.batteryhealth"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mralaminahamed.batteryhealth"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

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

    buildTypes {
        release {
            // TEMPORARY, local-verification only: reuses the debug keystore so this build
            // type can actually be built, installed, and exercised on-device now that R8
            // runs against it for the first time. A real signing config MUST replace this
            // before any release build is distributed -- a debug-signed release must never
            // ship. There is no release-signing config in this project yet (see the task
            // report for why: no keystore is checked in on purpose).
            signingConfig = signingConfigs.getByName("debug")
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
