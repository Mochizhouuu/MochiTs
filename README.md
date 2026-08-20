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

## Yang masih perlu dilengkapi sebelum build pertama

1. **Gradle wrapper jar** — jalankan `gradle wrapper` sekali di mesin
   dengan Gradle terinstal untuk men-generate `gradle/wrapper/gradle-wrapper.jar`
   (tidak disertakan di sini karena file biner).
2. **Model LaMa** — taruh `lama_int8.tflite` di
   `core-inpaint-ml/src/main/assets/models/`.
3. **Keystore & GitHub Secrets** — ikuti PRD bagian 5 untuk generate
   keystore release dan menambahkan `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
   `KEY_ALIAS`, `KEY_PASSWORD` ke GitHub Secrets repo.
4. Sesuaikan versi dependency (OpenCV, TensorFlow Lite, Compose BOM, AGP)
   dengan rilis terbaru saat implementasi.

## CI/CD

Workflow di `.github/workflows/android-build.yml` otomatis berjalan pada
push ke `main` (dan `pull_request`): menjalankan unit test seluruh module,
build APK Debug, lalu build & sign APK Release memakai keystore dari
GitHub Secrets. Lihat PRD bagian 4–9 untuk detail lengkap & troubleshooting.

## Logo

Logo aplikasi (`MochiTs`) sudah dipasang sebagai app icon (`mipmap-*`)
dan disimpan mentah di `app/src/main/res/drawable-nodpi/logo_mochits.png`.
