# MochiTs

Skeleton project Android native (Kotlin) multi-module, dibangun sesuai
`PRD_ComicType_Teknis_Kotlin_CICD.docx` — dokumen teknis pendamping yang
menggantikan pendekatan Flutter dengan Kotlin native.

## Struktur Module

| Module | Tipe | Tanggung Jawab |
|---|---|---|
| `:app` | Application | Entry point, navigasi, DI wiring, UI screen |
| `:core-canvas` | Android Library | Rendering canvas, layer compositing, non-destruktif |
| `:core-imaging` | Android Library | Binding OpenCV (magic wand, Telea inpainting) |
| `:core-inpaint-ml` | Android Library | Inferensi model LaMa via TensorFlow Lite |
| `:core-text` | Android Library | Text engine kustom & efek teks |
| `:core-project` | Android Library | Model data project, `.ctproj`, Room DB |
| `:core-common` | Kotlin Library | Util bersama, tanpa dependency Android |

## Penggunaan & Build

1. **Build & Test** — Jalankan `./gradlew test` dan `./gradlew assembleDebug`. Wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) sudah disertakan di repository.
2. **Model LaMa** — Aplikasi akan mengunduh model LaMa TFLite otomatis dari GitHub Release saat dibutuhkan. Anda juga dapat menaruh `lama_int8.tflite` secara manual di folder storage aplikasi `models/` atau `core-inpaint-ml/src/main/assets/models/`.
3. **Keystore & GitHub Secrets** — Untuk build release bertanda tangan (signed release), tambahkan `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` ke GitHub Secrets repository. Jika secrets tidak tersedia (misal pada PR dari fork), CI akan otomatis melewati langkah signing dan tetap menjalankan test serta build debug.

## CI/CD

Workflow di `.github/workflows/android-build.yml` otomatis berjalan pada
push ke `main` (dan `pull_request`): menjalankan unit test seluruh module via `./gradlew test`,
build APK Debug, lalu build & sign APK Release jika secrets keystore tersedia.

## Logo

Logo aplikasi (`MochiTs`) sudah dipasang sebagai app icon (`mipmap-*`)
dan disimpan mentah di `app/src/main/res/drawable-nodpi/logo_mochits.png`.
