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
        CheckInEntity::class,
        TrackEntity::class,
        TrackPointEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun riverDao(): RiverDao
    abstract fun obstacleDao(): ObstacleDao
    abstract fun cacheDao(): CacheDao
    abstract fun tripDao(): TripDao
    abstract fun trackDao(): TrackDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE participants ADD COLUMN isOrganizer INTEGER NOT NULL DEFAULT 0")
                // Dotychczasowy organizator spływu (osoba wpisana jako organizator) zostaje organizatorem.
                db.execSQL(
                    "UPDATE participants SET isOrganizer = 1 WHERE EXISTS (" +
                        "SELECT 1 FROM trips WHERE trips.id = participants.tripId " +
                        "AND lower(trips.organizer) = lower(participants.name))"
                )
            }
        }

        /** Dodaje nagrane trasy (historia spływów). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`ownerUsername` TEXT, " +
                        "`tripId` INTEGER, " +
                        "`tripTitle` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER, " +
                        "`distanceM` REAL NOT NULL, " +
                        "`elapsedMs` INTEGER NOT NULL, " +
                        "`movingMs` INTEGER NOT NULL, " +
                        "`maxSpeedKmh` REAL NOT NULL, " +
                        "`pointCount` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_startedAt` ON `tracks` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `track_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`trackId` INTEGER NOT NULL, " +
                        "`time` INTEGER NOT NULL, " +
                        "`lat` REAL NOT NULL, " +
                        "`lon` REAL NOT NULL, " +
                        "`accuracy` REAL, " +
                        "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_points_trackId` ON `track_points` (`trackId`)")
            }
        }

        /** Dodaje czas pauzy do nagranych tras. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `pausedMs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Dodaje opcjonalną godzinę startu spływu. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `startTime` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Wyposażenie spływu: wymagane/zalecane oraz potwierdzenia uczestników. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `gear_items` ADD COLUMN `requirement` TEXT NOT NULL DEFAULT 'recommended'")
                db.execSQL("ALTER TABLE `gear_items` ADD COLUMN `confirmedBy` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kajakapp.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
    }
}
