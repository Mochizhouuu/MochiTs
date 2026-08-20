package com.mochits.project

/**
 * Representasi file project (.ctproj) — dikemas via java.util.zip
 * berisi base image, mask, metadata layer teks, dan referensi font/style
 * ke dalam satu berkas. Room menyimpan metadata project & style preset;
 * aset gambar/font disimpan di filesystem lokal.
 */
data class ProjectFile(
    val id: String,
    val name: String,
    val baseImagePath: String,
    val maskPath: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)
