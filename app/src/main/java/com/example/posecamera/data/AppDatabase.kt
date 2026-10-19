package com.example.posecamera.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 (1 -> 2: local_sets.syncFailed). exportSchema=true writes app/schemas/<version>.json (see ksp arg in build.gradle.kts).
 * FUTURE MIGRATIONS: bump `version`, add a `Migration(from, to)` via `.addMigrations(...)` in [get], commit the
 * new schema JSON. Never use fallbackToDestructiveMigration: the outbox holds unsynced user data.
 * Never call from the camera/pose thread; all DAO access is suspend/Flow or on Dispatchers.IO.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_sets ADD COLUMN syncFailed INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        CatalogExercise::class, CatalogMachine::class, CatalogMuscle::class, CatalogFood::class,
        CatalogServing::class, CatalogPlan::class,
        LocalSet::class, LocalFoodLog::class, LocalWeight::class, LocalProfile::class,
        LocalUserPlan::class, LocalPlanCompletion::class,
        Outbox::class, SyncState::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sync(): SyncDao
    abstract fun catalog(): CatalogDao
    abstract fun user(): UserDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sahirep.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
