package com.material.xray.di

import android.content.Context
import androidx.room.Room
import com.material.xray.data.db.AppDatabase
import com.material.xray.data.db.DatabaseMigrations
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME,
    )
        .addMigrations(*DatabaseMigrations.all)
        .addCallback(AppDatabase.VALUE_VALIDATION_CALLBACK)
        // A downgrade cannot be migrated, so recreating the tables is the only way forward. An
        // upgrade that Room cannot satisfy is a bug in the migration chain, and destroying the
        // user's subscriptions and routing to paper over it is worse than failing loudly, so no
        // destructive fallback is registered for that direction.
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()

    @Provides
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideSubscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun provideAppBypassDao(db: AppDatabase): AppBypassDao = db.appBypassDao()
}
