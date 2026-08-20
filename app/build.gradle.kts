// :app — Entry point, navigasi, DI wiring, UI screen
// (Home, Page Manager, Canvas Editor, Export).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mochits.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mochits.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing config release: parameter -P (project property) diberikan oleh
    // workflow CI/CD (lihat .github/workflows/android-build.yml), dibaca di sini
    // agar password/alias/keystore tidak pernah ditulis langsung di file Gradle.
    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("android.injected.signing.store.file") as String?
            val storePwd = project.findProperty("android.injected.signing.store.password") as String?
            val keyAliasProp = project.findProperty("android.injected.signing.key.alias") as String?
            val keyPwd = project.findProperty("android.injected.signing.key.password") as String?

            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = storePwd
                keyAlias = keyAliasProp
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            // menggunakan debug keystore bawaan Android SDK secara otomatis
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core-canvas"))
    implementation(project(":core-imaging"))
    implementation(project(":core-inpaint-ml"))
    implementation(project(":core-text"))
    implementation(project(":core-project"))
    implementation(project(":core-common"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")

    // Async/Concurrency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
