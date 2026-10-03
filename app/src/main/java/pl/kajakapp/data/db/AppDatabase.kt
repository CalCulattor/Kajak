package pl.kajakapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun riverDao(): RiverDao
    abstract fun obstacleDao(): ObstacleDao
    abstract fun cacheDao(): CacheDao
    abstract fun tripDao(): TripDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kajakapp.db").build()
    }
}
