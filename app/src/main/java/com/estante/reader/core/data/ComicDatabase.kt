package com.estante.reader.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ComicEntity::class, BookHighlightEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(ComicDataConverters::class)
abstract class ComicDatabase : RoomDatabase() {
    abstract fun comicDao(): ComicDao
    abstract fun bookHighlightDao(): BookHighlightDao

    companion object {
        @Volatile private var instance: ComicDatabase? = null

        fun getInstance(context: Context): ComicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ComicDatabase::class.java,
                "estante-library.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `book_highlights` (
                        `id` TEXT NOT NULL,
                        `bookId` TEXT NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `chapterHref` TEXT NOT NULL,
                        `startOffset` INTEGER NOT NULL,
                        `endOffset` INTEGER NOT NULL,
                        `selectedText` TEXT NOT NULL,
                        `prefix` TEXT NOT NULL,
                        `suffix` TEXT NOT NULL,
                        `colorId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`bookId`) REFERENCES `comic_publications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_highlights_bookId_chapterIndex` ON `book_highlights` (`bookId`, `chapterIndex`)")
            }
        }
    }
}

class ComicDataConverters {
    @TypeConverter fun formatToStorage(value: ComicFormat): String = value.name
    @TypeConverter fun formatFromStorage(value: String): ComicFormat = ComicFormat.valueOf(value)
    @TypeConverter fun stateToStorage(value: ComicImportState): String = value.name
    @TypeConverter fun stateFromStorage(value: String): ComicImportState = ComicImportState.valueOf(value)
}
