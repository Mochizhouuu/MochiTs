# JNI bridge (opsional)

Folder ini disiapkan untuk native code tambahan bila operasi OpenCV
Android SDK (Kotlin/Java binding) tidak cukup dan dibutuhkan native
code custom (mis. optimasi performa untuk operasi bitmap berat).

Jika dipakai:
1. Buat `CMakeLists.txt` di folder ini.
2. Un-comment blok `externalNativeBuild` di `core-imaging/build.gradle.kts`.

Selama belum dibutuhkan, seluruh binding OpenCV cukup lewat
`org.opencv:opencv` (Kotlin/Java API) di `core-imaging/src/main/java`.
