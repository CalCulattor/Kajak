package pl.kajakapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RiverEntity::class,
        SectionEntity::class,
        ObstacleEntity::class,
        WaterCacheEntity::class,
        WeatherCacheEntity::class,
        TripEntity::class,
        ParticipantEntity::class,
        GearItemEntity::class,
        CheckInEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun riverDao(): RiverDao
    abstract fun obstacleDao(): ObstacleDao
    abstract fun cacheDao(): CacheDao
    abstract fun tripDao(): TripDao

    companion object {
        /** Dodaje pola synchronizacji z serwerem. Dane użytkownika zostają nienaruszone. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sections ADD COLUMN serverKey TEXT")
                db.execSQL("ALTER TABLE sections ADD COLUMN pendingSync INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE obstacles ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE obstacles ADD COLUMN pendingConfirms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE obstacles ADD COLUMN pendingRemovals INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE participants ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE gear_items ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE check_ins ADD COLUMN serverId INTEGER")
                // Dwa odcinki z danych startowych dostają ich stałe klucze serwerowe.
                db.execSQL(
                    "UPDATE sections SET serverKey = 'dunajec-sromowce-szczawnica' " +
                        "WHERE name = 'Sromowce – Szczawnica (Przełom Pieniński)'"
                )
                db.execSQL(
                    "UPDATE sections SET serverKey = 'krutynia-sorkwity-ukta' " +
                        "WHERE name = 'Sorkwity – Ukta'"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kajakapp.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
