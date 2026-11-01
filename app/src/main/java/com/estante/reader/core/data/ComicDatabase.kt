package com.estante.reader.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [ComicEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(ComicDataConverters::class)
abstract class ComicDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao

    companion object {
        @Volatile private var instance: ComicDatabase? = null

        fun getInstance(context: Context): ComicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ComicDatabase::class.java,
                "estante-library.db",
            ).build().also { instance = it }
        }
    }
}

class ComicDataConverters {
    @TypeConverter fun formatToStorage(value: ComicFormat): String = value.name
    @TypeConverter fun formatFromStorage(value: String): ComicFormat = ComicFormat.valueOf(value)
    @TypeConverter fun stateToStorage(value: ComicImportState): String = value.name
    @TypeConverter fun stateFromStorage(value: String): ComicImportState = ComicImportState.valueOf(value)
}
