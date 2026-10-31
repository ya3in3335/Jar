package com.jarvis.ai.di

import android.content.Context
import com.jarvis.ai.core.agent.ToolRegistry
import com.jarvis.ai.core.agent.defaultToolExecutors
import com.jarvis.ai.core.audio.AmplitudeMeter
import com.jarvis.ai.core.audio.VoiceEngine
import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.data.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun secureStore(@ApplicationContext context: Context): SecureStore = SecureStore(context)

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)

    @Provides
    @Singleton
    fun amplitudeMeter(@ApplicationContext context: Context): AmplitudeMeter =
        AmplitudeMeter(context)

    @Provides
    @Singleton
    fun voiceEngine(
        @ApplicationContext context: Context,
        http: OkHttpClient,
        secureStore: SecureStore,
    ): VoiceEngine = VoiceEngine(context, http, secureStore)

    @Provides
    @Singleton
    fun toolRegistry(@ApplicationContext context: Context): ToolRegistry =
        ToolRegistry(defaultToolExecutors(context))

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Streaming model responses can sit idle between tokens, so the read
        // timeout is generous while connect stays short.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
