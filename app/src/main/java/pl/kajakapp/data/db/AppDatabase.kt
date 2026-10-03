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
    version = 3,
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

        /** Dodaje właściciela spływu (konto organizatora). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN ownerUsername TEXT")
                // Spływy udostępnione przed wprowadzeniem kont nie mają właściciela-konta;
                // stają się spływami lokalnymi i można je udostępnić ponownie po zalogowaniu.
                db.execSQL("UPDATE trips SET serverId = NULL")
                db.execSQL("UPDATE participants SET serverId = NULL")
                db.execSQL("UPDATE gear_items SET serverId = NULL")
                db.execSQL("UPDATE check_ins SET serverId = NULL, pendingSync = 1")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kajakapp.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
