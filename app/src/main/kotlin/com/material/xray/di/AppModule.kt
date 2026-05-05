package com.material.xray.di

import com.material.xray.core.root.RootShell
import com.material.xray.data.parser.SubscriptionFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRootShell(): RootShell = RootShell()

    @Provides
    @Singleton
    fun provideSubscriptionFetcher(client: OkHttpClient): SubscriptionFetcher =
        SubscriptionFetcher(client)
}
