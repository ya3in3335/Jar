package com.jarvis.ai.di

import android.content.Context
import com.jarvis.ai.core.agent.ToolRegistry
import com.jarvis.ai.core.agent.WebSearchTool
import com.jarvis.ai.core.agent.defaultToolExecutors
import com.jarvis.ai.core.audio.AudioCapture
import com.jarvis.ai.core.audio.VoiceEngine
import com.jarvis.ai.core.net.JarvisBackend
import com.jarvis.ai.core.net.OpenRouterClient
import com.jarvis.ai.core.stt.AutoSttSessionProvider
import com.jarvis.ai.core.stt.BackendSttSessionProvider
import com.jarvis.ai.core.stt.DeepgramStt
import com.jarvis.ai.core.stt.DirectSttSessionProvider
import com.jarvis.ai.core.stt.SttEngine
import com.jarvis.ai.core.stt.SttSessionProvider
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
    fun audioCapture(@ApplicationContext context: Context): AudioCapture = AudioCapture(context)

    @Provides
    @Singleton
    fun jarvisBackend(http: OkHttpClient, secureStore: SecureStore): JarvisBackend =
        JarvisBackend(http, secureStore)

    @Provides
    @Singleton
    fun sttSessions(
        secureStore: SecureStore,
        backend: JarvisBackend,
    ): AutoSttSessionProvider = AutoSttSessionProvider(
        direct = DirectSttSessionProvider(secureStore),
        backend = BackendSttSessionProvider(backend),
        jarvisBackend = backend,
        secureStore = secureStore,
    )

    @Provides
    @Singleton
    fun sttEngine(
        http: OkHttpClient,
        sessions: AutoSttSessionProvider,
        capture: AudioCapture,
    ): SttEngine = DeepgramStt(http, sessions as SttSessionProvider, capture)

    @Provides
    @Singleton
    fun voiceEngine(
        @ApplicationContext context: Context,
        http: OkHttpClient,
        secureStore: SecureStore,
    ): VoiceEngine = VoiceEngine(context, http, secureStore)

    @Provides
    @Singleton
    fun toolRegistry(
        @ApplicationContext context: Context,
        openRouter: OpenRouterClient,
        secureStore: SecureStore,
    ): ToolRegistry = ToolRegistry(
        defaultToolExecutors(context) +
            // Research runs on the key the user already has: OpenRouter's
            // ":online" plugin means no second account for web search.
            WebSearchTool(openRouter, { secureStore.openRouterKey }),
    )

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
