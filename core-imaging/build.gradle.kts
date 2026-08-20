// core-imaging: binding OpenCV (seleksi magic wand, Telea inpainting,
// operasi bitmap dasar). JNI langsung, tanpa bridge platform channel.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mochits.imaging"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Uncomment bila ditambahkan native code custom di src/main/cpp
    // (di luar OpenCV Android SDK itu sendiri) dan CMakeLists.txt sudah dibuat:
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-common"))

    implementation("androidx.core:core-ktx:1.13.1")
    // OpenCV Android SDK — sesuaikan versi dengan rilis terbaru saat implementasi
    implementation("org.opencv:opencv:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
