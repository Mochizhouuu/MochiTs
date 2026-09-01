# MochiTs

Skeleton project Android native (Kotlin & C++ NDK) multi-module, dibangun sesuai
`PRD_ComicType_Teknis_Kotlin_CICD.docx` — dokumen teknis pendamping yang
menggantikan pendekatan Flutter dengan Kotlin native.

## Struktur Module

| Module | Tipe | Tanggung Jawab |
|---|---|---|
| `:app` | Application | Entry point, navigasi, DI wiring, UI screen (Compose) |
| `:core-canvas` | Android Library | Rendering canvas, layer compositing, non-destruktif |
| `:core-imaging` | Android Library (Native NDK / C++) | Native OpenCV binding & C++ pixel engine (mask selection brush/lasso/rect, cv::inpaint Telea) |
| `:core-inpaint-ml` | Android Library | Inferensi model LaMa via TensorFlow Lite |
| `:core-text` | Android Library | Text engine kustom & efek teks |
| `:core-project` | Android Library | Model data project, `.ctproj`, Room DB |
| `:core-common` | Kotlin Library | Util bersama, tanpa dependency Android |

## Penggunaan & Build

1. **Build & Test** — Jalankan `./gradlew test` dan `./gradlew assembleDebug`. Wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) sudah disertakan di repository.
2. **Native NDK & OpenCV** — Modul `:core-imaging` menggunakan NDK LTS r26b (`26.1.10909125`) dan CMake `3.22.1`. OpenCV Android SDK versi native C++ (4.9.0) secara otomatis diunduh, diverifikasi via checksum SHA-256, dan dicache oleh workflow CI (`.github/workflows/android-build.yml`) sebelum kompilasi native.
3. **Model LaMa Manga (ONNX)** — Aplikasi secara otomatis mengunduh model inpainting LaMa Manga (`lama_manga.onnx`, khusus komik/manga finetuned pada ~300.000 gambar) dari GitHub Releases repo MochiTs ke folder storage aplikasi `models/` untuk dijalankan via ONNX Runtime Mobile.
4. **Keystore & GitHub Secrets** — Untuk build release bertanda tangan (signed release), tambahkan `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` ke GitHub Secrets repository. Jika secrets tidak tersedia (misal pada PR dari fork), CI akan otomatis melewati langkah signing dan tetap menjalankan test serta build debug.

## CI/CD

Workflow di `.github/workflows/android-build.yml` otomatis berjalan pada
push ke `main` (dan `pull_request`): mengunduh & memverifikasi OpenCV Android SDK Native, menjalankan unit test seluruh module via `./gradlew test`,
build APK Debug, lalu build & sign APK Release jika secrets keystore tersedia.

## Logo

Logo aplikasi (`MochiTs`) sudah dipasang sebagai app icon (`mipmap-*`)
dan disimpan mentah di `app/src/main/res/drawable-nodpi/logo_mochits.png`.
