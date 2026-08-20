# Tambahkan rule ProGuard/R8 khusus proyek di sini.
# Rule default Android sudah mencakup kasus umum (lihat proguard-android-optimize.txt).

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }

# OpenCV
-keep class org.opencv.** { *; }
