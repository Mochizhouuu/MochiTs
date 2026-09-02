package com.mochits.app.font

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomFontDao {
    @Query("SELECT * FROM custom_fonts ORDER BY displayName ASC")
    fun getAllCustomFonts(): Flow<List<CustomFontEntity>>

    @Query("SELECT * FROM custom_fonts ORDER BY displayName ASC")
    suspend fun getAllCustomFontsList(): List<CustomFontEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomFont(font: CustomFontEntity)

    @Query("DELETE FROM custom_fonts WHERE id = :id")
    suspend fun deleteCustomFont(id: String)
}
