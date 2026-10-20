# MochiTs

Aplikasi Android native (Kotlin + Jetpack Compose + C++ NDK) untuk mengedit
komik/manga: menghapus objek/teks dengan inpainting, typesetting teks, dan
komposit layer. Package `com.mochits.app` (minSdk 29, targetSdk 34).

## Fitur

- **Home & Proyek** — buat proyek kanvas kosong/transparan, impor gambar dari
  galeri (banyak sekaligus), daftar proyek dengan thumbnail, folder virtual,
  autosave + riwayat undo/redo yang bertahan antar sesi.
- **Impor ZIP** — gambar root menjadi folder bernama ZIP, subfolder menjadi
  folder masing-masing, bundel `.mts` di dalamnya tetap bisa diedit; ZIP dari
  file manager dibuka lewat dialog pratinjau. Natural sort + batas keamanan.
- **Script TL** — impor `.txt`/`.docx`, pemisah halaman regex, simbol
  normal/gabung/terhubung (`//-`, `//[]`), preset per simbol, antrean baris di
  Editor, muat ulang otomatis per proyek, dan ringkasan "Tes Script".
- **Erase / Seleksi objek** — Brush, Eraser, Lasso, dan Magic Wand (flood fill
  toleransi warna + pelebaran margin/expand), invert mask.
- **Inpainting** — Telea via OpenCV native (cepat, offline) dan LaMa AI via
  ONNX Runtime (`lama_manga.onnx` ±196 MB, diunduh saat pertama dipakai dari
  HuggingFace ke `models/`, dengan fallback otomatis ke Telea).
- **Teks** — layer teks BOX/OVAL, alignment, gradient, stroke, glow/shadow,
  huruf vertikal, reflow + hyphenation Indonesia/Inggris, jarak huruf dan jarak
  baris per layer, resize/rotate/stretch langsung di kanvas, edit ketuk-2-kali.
- **Font kustom** — impor `.ttf`/`.otf`, kelola & hapus dari Settings.
- **Layer gambar** — tambah gambar sebagai layer (tersimpan permanen di proyek).
- **Ekspor** — PNG/JPEG/WEBP + atur kualitas, ke folder pilihan (Storage
  Access Framework), sekaligus rename judul proyek.
- **Pengaturan** — tema (sistem/terang/gelap), unduh model LaMa, font manager,
  folder output default.

## Struktur Module

| Module | Tipe | Tanggung Jawab |
|---|---|---|
| `:app` | Application | Entry point, navigasi, DI (Hilt), seluruh UI Compose, ViewModel, Room DB, engine teks & LaMa |
| `:core-imaging` | Android Library (Native NDK / C++) | Pixel engine: brush/eraser/lasso/magic-wand/dilate mask, `cv::inpaint` Telea, JNI bridge + fallback JVM untuk unit test |

Paket penting di `:app`: `canvas` (state kanvas & mapping koordinat),
`editor` (layar editor, serializer layer, exporter), `text` (layout/render
teks), `imaging` (LaMa), `project` (Room + repository + impor ZIP/bundel),
`script` (parser & mapping naskah TL), `font`, `home`, `settings`, `model`,
`ui`.

## Penyimpanan Lokal

- Database Room `mochits.db` (proyek, folder, font kustom).
- `filesDir/projects/{id}/`: `base_image.png`, `layers/layer_{id}.png`,
  `history/` (bitmap undo/redo + `manifest.json`).
- `filesDir/models/lama_manga.onnx` (+`.tmp` saat mengunduh),
  `filesDir/fonts/`, `filesDir/custom_fonts/`.
- Preferensi script (`mochits_script_state`): file TL terakhir + posisi
  halaman/baris per proyek, serta toggle muat ulang otomatis.

## Dokumentasi

Panduan pengguna (Indonesia, statis): **https://mochits-docs.pages.dev/** — repo
[MochiTs-Docs](https://github.com/Mochizhouuu/MochiTs-Docs). Mencakup folder,
impor ZIP, teks/spasi, format script TL, tester parser di browser, dan ekspor.
Tautan juga tersedia dari **Pengaturan → Tema → Buka Panduan MochiTs**.

## Build & Test

- `./gradlew test` — unit test seluruh module (Robolectric).
- `./gradlew assembleDebug` — APK debug. Wrapper (`gradlew` +
  `gradle/wrapper/gradle-wrapper.jar`, Gradle 8.8) sudah disertakan; butuh
  JDK 17.
- **Native NDK & OpenCV** — `:core-imaging` memakai NDK r26b
  (`26.1.10909125`) dan CMake `3.22.1`. Tanpa OpenCV, kode native tetap
  terkompilasi lewat jalur fallback (`HAVE_OPENCV` off). CI mengunduh OpenCV
  Android SDK 4.9.0, memverifikasi checksum SHA-256, lalu meng-cache-nya
  sebelum build native.
- **Release bertanda tangan** — isi GitHub Secrets `KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Tanpa secrets (mis. PR
  dari fork), CI melewati signing dan tetap menjalankan test + build debug.

## CI/CD

Workflow `.github/workflows/android-build.yml` berjalan pada push ke `main`,
`pull_request`, dan manual: cache/unduh OpenCV → `./gradlew test` →
`assembleDebug` (upload artifact) → `assembleRelease` + sign bila secrets ada.

## Logo

App icon (`mipmap-*`), sumber mentah di
`app/src/main/res/drawable-nodpi/logo_mochits.png`.
