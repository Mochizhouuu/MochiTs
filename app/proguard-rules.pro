# Tambahkan rule ProGuard/R8 khusus proyek di sini.
# Rule default Android sudah mencakup kasus umum (lihat proguard-android-optimize.txt).

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }

# GPU delegate TFLite mereferensikan kelas opsional yang tidak selalu ada
# di classpath — abaikan warning missing class dari R8 untuk ini.
-dontwarn org.tensorflow.lite.gpu.**

# OpenCV
-keep class org.opencv.** { *; }
