-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn com.google.auto.value.**

# --- Gson model classes (reflection-based serialization) ---
# Tanpa ini R8 me-rename field dan deserialisasi proyek/history gagal
# diam-diam HANYA di build release.
-keep class com.mochits.app.editor.LayerJsonDto { *; }
-keep class com.mochits.app.editor.EditorViewModel$HistoryManifest { *; }
-keep class com.mochits.app.editor.EditorViewModel$HistoryStepEntry { *; }
-keep class com.mochits.app.model.** { *; }
-keep class com.mochits.app.font.CustomFontEntity { *; }
-keep class com.mochits.app.style.** { *; }

# Generic signature wajib untuk TypeToken/List<...> Gson.
-keepattributes Signature, Annotation, InnerClasses, EnclosingMethod

# Cegah stripping konstruktor default yang dipakai Gson.
-keepclassmembers class com.mochits.app.model.** {
    <init>(...);
}
